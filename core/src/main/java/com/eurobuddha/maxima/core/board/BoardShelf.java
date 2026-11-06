package com.eurobuddha.maxima.core.board;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.store.Store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pool relay's board shelf: per board, an ordered log of signed envelopes with a cursor.
 *
 * This is the "post once, read by many" store that lifts the 12-member group cap. Like the
 * {@link com.eurobuddha.maxima.core.mailbox.Mailbox} it holds only what it can verify, bounds
 * everything, and keeps the bytes on disk (one binary record per post) with metadata in the heap.
 * Unlike the mailbox there is no acknowledge: readers are many and anonymous, so posts expire by
 * age and by per-board quota (oldest first), and whole boards are evicted least-recently-READ when
 * the relay is full. Members keep their own copies and re-seed a relay that forgot, so a relay is
 * redundancy, never the record.
 *
 * Admission: the envelope must verify. Nothing about membership is known or enforced here - that
 * is the members' job, from the signed roster posts they decrypt.
 *
 * Sequence numbers are per board and clock-derived (max(last+1, now ms)), so they survive a
 * restart without a reservation record even when every post of a board was pruned meanwhile.
 * ponytail: a clock set BACK across a restart after a full prune could issue a cursor below one a
 * client holds; relays run NTP, and a client's reset handling re-syncs. Reservation if it bites.
 */
public final class BoardShelf {

    public static final int DEFAULT_MAX_POSTS_PER_BOARD = 20_000;
    public static final long DEFAULT_MAX_BYTES_PER_BOARD = 32L * 1024 * 1024;
    public static final int DEFAULT_MAX_BOARDS = 1000;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    /** Metadata per post is ~150 B; this bounds the heap. */
    public static final int DEFAULT_MAX_TOTAL_ITEMS = 200_000;
    public static final long DEFAULT_TTL_MS = 180L * 24 * 60 * 60 * 1000;

    /** Binary records, one per post, keyed board|seq|storedAt|id. */
    static final String C_POSTS = "boardposts";

    public enum Result { STORED, DUPLICATE, INVALID, TOOBIG, QUOTA, IO_ERROR }

    private static final class Item {
        final long seq;
        final String id;
        final long storedAt;
        final int size;

        Item(long zSeq, String zId, long zStoredAt, int zSize) {
            seq = zSeq;
            id = zId;
            storedAt = zStoredAt;
            size = zSize;
        }
    }

    private static final class Board {
        final List<Item> items = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        long bytes;
        long lastSeq;
        long lastRead = System.currentTimeMillis();
        /** Monotonic use stamp for LRU eviction - wall-clock ms ties within a burst. */
        long lru;
    }

    private final Map<String, Board> mBoards = new HashMap<>();
    private long mUseClock;
    private Store mStore = Store.MEMORY_ONLY;
    /** Memory-only stores keep the bytes here (tests, the in-app relay). */
    private final Map<String, byte[]> mMemory = new HashMap<>();

    private final int mMaxPerBoard;
    private final long mMaxBytesPerBoard;
    private final int mMaxBoards;
    private final long mMaxTotalBytes;
    private final int mMaxTotalItems;
    private final long mTtlMs;
    private long mTotalBytes;
    private int mTotalItems;

    public BoardShelf() {
        this(DEFAULT_MAX_POSTS_PER_BOARD, DEFAULT_MAX_BYTES_PER_BOARD, DEFAULT_MAX_BOARDS,
                DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_TOTAL_ITEMS, DEFAULT_TTL_MS);
    }

    public BoardShelf(int zMaxPerBoard, long zMaxBytesPerBoard, int zMaxBoards, long zMaxTotalBytes,
                      int zMaxTotalItems, long zTtlMs) {
        mMaxPerBoard = zMaxPerBoard;
        mMaxBytesPerBoard = zMaxBytesPerBoard;
        mMaxBoards = zMaxBoards;
        mMaxTotalBytes = zMaxTotalBytes;
        mMaxTotalItems = zMaxTotalItems;
        mTtlMs = zTtlMs;
    }

    /** Attach durable storage and reload whatever is held. Call before use. */
    public synchronized void setStore(Store zStore) {
        mStore = zStore == null ? Store.MEMORY_ONLY : zStore;
        mMemory.clear();
        mBoards.clear();
        mTotalBytes = 0;
        mTotalItems = 0;
        if (mStore != Store.MEMORY_ONLY) {
            load();
        }
    }

    public synchronized void flush() {
        mStore.flush();
    }

    private void load() {
        List<Map.Entry<String, Integer>> all = new ArrayList<>(mStore.listBytes(C_POSTS).entrySet());
        all.sort(Comparator.comparingLong(e -> seqOf(e.getKey())));
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Integer> e : all) {
            String key = e.getKey();
            String[] k = key.split("\\|", 4);
            if (k.length != 4) {
                continue;
            }
            try {
                long seq = Long.parseLong(k[1]);
                long storedAt = Long.parseLong(k[2]);
                int size = e.getValue();
                if (now - storedAt > mTtlMs || seq <= 0) {
                    mStore.removeBytes(C_POSTS, key);
                    continue;
                }
                Board b = mBoards.get(k[0]);
                boolean newBoard = b == null;
                if ((newBoard && mBoards.size() >= mMaxBoards) || mTotalBytes + size > mMaxTotalBytes
                        || mTotalItems + 1 > mMaxTotalItems
                        || (!newBoard && (b.items.size() >= mMaxPerBoard || b.bytes + size > mMaxBytesPerBoard))) {
                    mStore.removeBytes(C_POSTS, key);
                    continue;
                }
                if (newBoard) {
                    b = new Board();
                    mBoards.put(k[0], b);
                }
                b.items.add(new Item(seq, k[3], storedAt, size));
                b.ids.add(k[3]);
                b.bytes += size;
                b.lastSeq = Math.max(b.lastSeq, seq);
                mTotalBytes += size;
                mTotalItems++;
            } catch (NumberFormatException ex) {
                System.err.println("[boards] bad record " + key + ": " + ex);
            }
        }
    }

    private static long seqOf(String zKey) {
        String[] k = zKey.split("\\|", 4);
        try {
            return k.length == 4 ? Long.parseLong(k[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String recKey(String zBoard, Item zItem) {
        return zBoard + "|" + zItem.seq + "|" + zItem.storedAt + "|" + zItem.id;
    }

    /**
     * Admit one envelope. Anyone may introduce a correctly signed post - deliberately NOT only
     * its signer, because members re-seed each other's posts onto a relay that lost them. The
     * envelope's own signature is what stops forgery; the host's per-source rate limit is what
     * stops floods.
     */
    public Result put(byte[] zRaw) {
        if (zRaw == null || zRaw.length > BoardPost.MAX_BYTES) {
            return Result.TOOBIG;
        }
        BoardPost.Signed s;
        try {
            s = BoardPost.verify(zRaw);
        } catch (IllegalArgumentException e) {
            return Result.INVALID;
        }
        String boardHex = s.post.boardHex();
        String id = s.post.idHex();
        int len = zRaw.length;

        // ponytail: the durable write runs under the shelf monitor. Posts are rare (tens a
        // minute per relay) and a reader only touches memory; split into reserve/write/commit
        // like the Mailbox if a slow disk ever shows up in the relay's latency.
        synchronized (this) {
            Board b = mBoards.get(boardHex);
            if (b != null && b.ids.contains(id)) {
                return Result.DUPLICATE;
            }
            boolean isNew = b == null;
            if (isNew && mBoards.size() >= mMaxBoards && !evictLruUnless(boardHex)) {
                return Result.QUOTA;
            }
            // Relay full: boards SHRINK, they do not vanish. Shed the oldest posts of the
            // least-recently-read boards first; a board leaves only once it is empty.
            while (mTotalBytes + len > mMaxTotalBytes || mTotalItems + 1 > mMaxTotalItems) {
                if (!shedLruUnless(boardHex)) {
                    break;
                }
            }
            if (mTotalBytes + len > mMaxTotalBytes || mTotalItems + 1 > mMaxTotalItems) {
                // Only this board is left and the relay is full: a board must keep accepting
                // new posts, so it sheds its own oldest.
                if (b == null || !dropOldestUntil(boardHex, b, len)) {
                    return Result.QUOTA;
                }
            }
            if (b == null) {
                b = new Board();
                mBoards.put(boardHex, b);
            }
            expire(boardHex, b, System.currentTimeMillis());
            // Per-board quota: oldest out, never refuse - the board moves on.
            while (b.items.size() + 1 > mMaxPerBoard || b.bytes + len > mMaxBytesPerBoard) {
                if (b.items.isEmpty()) {
                    return Result.QUOTA;
                }
                drop(boardHex, b, b.items.remove(0));
            }
            long now = System.currentTimeMillis();
            long seq = Math.max(b.lastSeq + 1, now);
            Item item = new Item(seq, id, now, len);
            String key = recKey(boardHex, item);
            boolean ok;
            try {
                ok = mStore == Store.MEMORY_ONLY
                        ? mMemory.put(key, zRaw) == null
                        : mStore.putBytes(C_POSTS, key, zRaw);
            } catch (RuntimeException e) {
                System.err.println("[boards] post write failed: " + e);
                ok = false;
            }
            if (!ok) {
                if (b.items.isEmpty()) {
                    mBoards.remove(boardHex, b);
                }
                return Result.IO_ERROR;
            }
            b.items.add(item);
            b.ids.add(id);
            b.bytes += len;
            b.lastSeq = seq;
            b.lastRead = now;   // a poster is a reader too; keeps an active board off the LRU
            b.lru = ++mUseClock;
            mTotalBytes += len;
            mTotalItems++;
            return Result.STORED;
        }
    }

    /**
     * Everything after the cursor, up to a byte budget; {@code reset} when the cursor predates the
     * retained log (the client may have missed posts and should re-seed what it has).
     */
    public synchronized BoardWire.Slice since(byte[] zBoard, long zCursor, int zMaxBytes) {
        MiniData bd = new MiniData(zBoard);
        String boardHex = bd.to0xString();
        Board b = mBoards.get(boardHex);
        if (b == null) {
            return new BoardWire.Slice(bd, 0, true, new ArrayList<>());
        }
        long now = System.currentTimeMillis();
        expire(boardHex, b, now);
        b.lastRead = now;
        b.lru = ++mUseClock;
        long oldest = b.items.isEmpty() ? b.lastSeq : b.items.get(0).seq;
        // The next post after the cursor is retained, or the cursor is current: exact deltas.
        boolean reset = zCursor < oldest - 1 && !(zCursor >= b.lastSeq);
        List<byte[]> out = new ArrayList<>();
        long cursor = zCursor;
        int bytes = 0;
        for (Item i : b.items) {
            if (i.seq <= zCursor) {
                continue;
            }
            if (bytes + i.size > zMaxBytes && !out.isEmpty()) {
                break;   // page: the client continues from the cursor we return
            }
            byte[] raw = mStore == Store.MEMORY_ONLY
                    ? mMemory.get(recKey(boardHex, i))
                    : mStore.getBytes(C_POSTS, recKey(boardHex, i), BoardPost.MAX_BYTES);
            if (raw == null) {
                continue;   // damaged on disk: skip, a member will re-seed it
            }
            out.add(raw);
            bytes += i.size;
            cursor = i.seq;
        }
        if (out.isEmpty()) {
            cursor = Math.max(zCursor, b.lastSeq);
        }
        return new BoardWire.Slice(bd, cursor, reset, out);
    }

    /** Answer a whole request within one reply budget. */
    public BoardWire.SinceReply answer(BoardWire.SinceRequest zReq) {
        BoardWire.SinceReply reply = new BoardWire.SinceReply();
        int budget = BoardWire.MAX_REPLY_BYTES;
        for (int i = 0; i < zReq.size(); i++) {
            BoardWire.Slice s = since(zReq.boards.get(i).getBytes(), zReq.cursors.get(i), Math.max(budget, 0));
            for (byte[] r : s.raws) {
                budget -= r.length + 8;
            }
            reply.slices.add(s);
            if (budget <= 0) {
                // Later boards answer "nothing new" at their own cursor; the client asks again.
                for (int j = i + 1; j < zReq.size(); j++) {
                    reply.slices.add(new BoardWire.Slice(zReq.boards.get(j), zReq.cursors.get(j), false,
                            new ArrayList<>()));
                }
                break;
            }
        }
        return reply;
    }

    private boolean dropOldestUntil(String zBoard, Board zB, int zNeed) {
        while (!zB.items.isEmpty()
                && (mTotalBytes + zNeed > mMaxTotalBytes || mTotalItems + 1 > mMaxTotalItems)) {
            drop(zBoard, zB, zB.items.remove(0));
        }
        return mTotalBytes + zNeed <= mMaxTotalBytes && mTotalItems + 1 <= mMaxTotalItems;
    }

    /** Drop ONE oldest post from the least-recently-read board other than zKeep; a board that
     *  empties this way is forgotten. False when no other board holds anything. */
    private boolean shedLruUnless(String zKeep) {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Board> e : mBoards.entrySet()) {
            if (!e.getKey().equals(zKeep) && !e.getValue().items.isEmpty() && e.getValue().lru < oldest) {
                oldest = e.getValue().lru;
                victim = e.getKey();
            }
        }
        if (victim == null) {
            return false;
        }
        Board b = mBoards.get(victim);
        drop(victim, b, b.items.remove(0));
        if (b.items.isEmpty()) {
            mBoards.remove(victim);
        }
        return true;
    }

    /** Drop the least-recently-read board other than zKeep (only for the board-COUNT cap: a
     *  new board needs a whole slot, and a count cannot be shrunk). */
    private boolean evictLruUnless(String zKeep) {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Board> e : mBoards.entrySet()) {
            if (!e.getKey().equals(zKeep) && e.getValue().lru < oldest) {
                oldest = e.getValue().lru;
                victim = e.getKey();
            }
        }
        if (victim == null) {
            return false;
        }
        Board b = mBoards.remove(victim);
        for (Item i : b.items) {
            drop(victim, b, i);
        }
        return true;
    }

    /** Book-keeping for one post leaving (caller removes it from the list). */
    private void drop(String zBoard, Board zB, Item zItem) {
        zB.bytes -= zItem.size;
        zB.ids.remove(zItem.id);
        mTotalBytes -= zItem.size;
        mTotalItems--;
        String key = recKey(zBoard, zItem);
        if (mStore == Store.MEMORY_ONLY) {
            mMemory.remove(key);
        } else {
            mStore.removeBytes(C_POSTS, key);
        }
    }

    private void expire(String zBoard, Board zB, long zNow) {
        while (!zB.items.isEmpty() && zNow - zB.items.get(0).storedAt > mTtlMs) {
            drop(zBoard, zB, zB.items.remove(0));
        }
    }

    /** Periodic: expire by age, forget boards that emptied. */
    public synchronized void maintain() {
        long now = System.currentTimeMillis();
        for (java.util.Iterator<Map.Entry<String, Board>> it = mBoards.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Board> e = it.next();
            expire(e.getKey(), e.getValue(), now);
            if (e.getValue().items.isEmpty() && now - e.getValue().lastRead > mTtlMs) {
                it.remove();
            }
        }
    }

    /** Every board held here, as 32-byte ids (what a peer relay is asked about when mirroring). */
    public synchronized List<byte[]> boards() {
        List<byte[]> out = new ArrayList<>();
        for (String hex : mBoards.keySet()) {
            out.add(new MiniData(hex).getBytes());
        }
        return out;
    }

    public synchronized int boardCount() {
        return mBoards.size();
    }

    public synchronized int totalItems() {
        return mTotalItems;
    }

    public synchronized long totalBytes() {
        return mTotalBytes;
    }

    public synchronized int count(byte[] zBoard) {
        Board b = mBoards.get(new MiniData(zBoard).to0xString());
        return b == null ? 0 : b.items.size();
    }
}
