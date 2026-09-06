package com.eurobuddha.maxima.core.mailbox;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.Hashes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STORE-AND-FORWARD - the single biggest functional gap in classic Maxima.
 *
 * In classic, a message to an offline peer is simply lost: the relay finds no
 * socket for the routing key, answers UNKNOWN, and drops it. Nothing is
 * buffered anywhere and the sender is never told the recipient never got it.
 *
 * Here a mailbox holds items for an offline peer and delivers them on
 * reconnect. Crucially it holds CIPHERTEXT addressed to a routing key: the
 * operator can see who has mail and roughly how much, but never its content.
 *
 * Quotas are mandatory, not optional. Relaying and storage are free, and
 * Maxima's nominal proof-of-work anti-spam is never verified by anyone, so
 * admission control has to be real.
 *
 * MEMORY: only METADATA lives in the heap (key, sequence, size, a content id); the
 * ciphertext itself is one binary record per item in the store and is read back only
 * when it is delivered. Before this, every held item sat in the heap three times over
 * (raw, hex in the store cache, and again in each whole-file rewrite), so a 96 MB relay
 * fell over at a few thousand held messages while its configured cap said 256 MB.
 */
public final class Mailbox {

    public static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    public static final int DEFAULT_MAX_PER_PEER = 200;
    public static final long DEFAULT_MAX_BYTES_PER_PEER = 8L * 1024 * 1024;

    /**
     * GLOBAL caps across ALL boxes. Per-peer quotas alone do not bound the
     * mailbox: the recipient key is attacker-chosen, so a flood to a million
     * distinct random keys allocates a million boxes and OOMs a small relay
     * long before any single box fills. These caps, plus LRU eviction of whole
     * boxes, make total memory bounded regardless of how many keys are used.
     */
    public static final int DEFAULT_MAX_BOXES = 10000;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    /** Items across all boxes: bytes bound the DISK, this bounds the HEAP (an item's
     *  metadata is a few hundred bytes; 50 000 of them is ~15 MB on a 96 MB relay). */
    public static final int DEFAULT_MAX_TOTAL_ITEMS = 50_000;

    public static final class Item {
        public final String id;
        public final String recipientKey;
        public final long storedAt;
        /** Monotonic within a recipient, so a client can resume from a cursor. */
        public final long sequence;
        /** Ciphertext length; the bytes themselves live in the store. */
        public final int size;
        private final Mailbox mOwner;
        /** In memory only until the item is persisted (memory-only stores keep it). */
        private volatile byte[] mCipher;

        Item(Mailbox zOwner, String zId, String zRecipient, long zSeq, long zStoredAt, int zSize,
             byte[] zCipher) {
            mOwner = zOwner;
            id = zId;
            recipientKey = zRecipient;
            sequence = zSeq;
            storedAt = zStoredAt;
            size = zSize;
            mCipher = zCipher;
        }

        /** The held ciphertext, read from the store on demand; null if it is gone. */
        public byte[] ciphertext() {
            byte[] c = mCipher;
            if (c != null) {
                return c;
            }
            return mOwner.mStore.getBytes(C_ITEMS, mOwner.recKey(this));
        }
    }

    public enum Result {
        STORED, QUOTA_COUNT, QUOTA_BYTES, DUPLICATE,
        /** The durable write failed (disk full, permissions): nothing is held. */
        IO_ERROR
    }

    private static final class Box {
        /** Committed items: written to the store (or memory-only), visible to fetch. */
        final List<Item> items = new ArrayList<>();
        /** Bytes of committed items. */
        long bytes;
        long nextSeq = 1;
        /** For LRU eviction under the global cap. */
        long lastActivity = System.currentTimeMillis();
        /** Items whose file is being written right now, outside the monitor. */
        int pending;
        long pendingBytes;
        final java.util.Set<String> pendingIds = new java.util.HashSet<>();
        /** Set when the box is evicted while a write is in flight: that write is undone. */
        boolean evicted;
    }

    private final Map<String, Box> mBoxes = new ConcurrentHashMap<>();

    /**
     * Optional durable backing.
     *
     * A relay runs under {@code Restart=always}; without this, every held
     * ciphertext is lost on the next restart, which is precisely the failure
     * this class exists to fix. One BINARY record per item under {@link #C_ITEMS},
     * keyed {@code recipient|seq|storedAt|id} so a reload learns every item's
     * metadata from the keys alone and never reads a ciphertext until delivery.
     * Deletes on acknowledge/expire/evict.
     */
    private com.eurobuddha.maxima.core.store.Store mStore =
            com.eurobuddha.maxima.core.store.Store.MEMORY_ONLY;
    /** Binary records, one per item. */
    static final String C_ITEMS = "mailitems";
    /** The pre-0.4.42 keyed collection (value = storedAt|hex): migrated on load. */
    private static final String C_LEGACY = "mailbox";

    private final long mTtlMs;
    private final int mMaxPerPeer;
    private final long mMaxBytesPerPeer;
    private final int mMaxBoxes;
    private final long mMaxTotalBytes;
    private final int mMaxTotalItems;
    private long mTotalBytes;
    private int mTotalItems;

    public Mailbox() {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_PER_PEER, DEFAULT_MAX_BYTES_PER_PEER);
    }

    public Mailbox(long zTtlMs, int zMaxPerPeer, long zMaxBytesPerPeer) {
        this(zTtlMs, zMaxPerPeer, zMaxBytesPerPeer,
                DEFAULT_MAX_BOXES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public Mailbox(long zTtlMs, int zMaxPerPeer, long zMaxBytesPerPeer,
                   int zMaxBoxes, long zMaxTotalBytes) {
        this(zTtlMs, zMaxPerPeer, zMaxBytesPerPeer, zMaxBoxes, zMaxTotalBytes,
                DEFAULT_MAX_TOTAL_ITEMS);
    }

    public Mailbox(long zTtlMs, int zMaxPerPeer, long zMaxBytesPerPeer,
                   int zMaxBoxes, long zMaxTotalBytes, int zMaxTotalItems) {
        mTtlMs = zTtlMs;
        mMaxPerPeer = zMaxPerPeer;
        mMaxBytesPerPeer = zMaxBytesPerPeer;
        mMaxBoxes = zMaxBoxes;
        mMaxTotalBytes = zMaxTotalBytes;
        mMaxTotalItems = zMaxTotalItems;
    }

    /** Attach durable storage and reload whatever is held. Call before use. */
    public synchronized void setStore(com.eurobuddha.maxima.core.store.Store zStore) {
        mStore = zStore == null
                ? com.eurobuddha.maxima.core.store.Store.MEMORY_ONLY : zStore;
        // Items stored before the store was attached stay in memory only; from here on a
        // stored item's bytes are the store's.
        migrateLegacy();
        load();
    }

    /** Persist any write-behind changes. Drive from a maintenance tick + shutdown. */
    public synchronized void flush() {
        mStore.flush();
    }

    /** Pre-0.4.42 records ({@code recipient|seq} -> {@code storedAt|hex}) become binary
     *  records once, then the old collection is emptied. */
    private void migrateLegacy() {
        Map<String, String> legacy = mStore.all(C_LEGACY);
        if (legacy.isEmpty()) {
            return;
        }
        int n = 0;
        for (Map.Entry<String, String> e : legacy.entrySet()) {
            try {
                String[] k = e.getKey().split("\\|", 2);
                String[] v = e.getValue().split("\\|", 2);
                if (k.length == 2 && v.length == 2) {
                    byte[] ct = new MiniData(v[1]).getBytes();
                    String id = new MiniData(Hashes.sha3(ct)).to0xString();
                    if (!mStore.putBytes(C_ITEMS,
                            recKey(norm(k[0]), Long.parseLong(k[1]), Long.parseLong(v[0]), id), ct)) {
                        // Could not be written (disk full, permissions): keep the legacy record
                        // for the next boot rather than lose held mail.
                        System.err.println("[mailbox] legacy record " + e.getKey() + " not migrated yet");
                        continue;
                    }
                    n++;
                }
            } catch (Exception ex) {
                System.err.println("[mailbox] legacy record " + e.getKey() + " skipped: " + ex);
            }
            mStore.remove(C_LEGACY, e.getKey());
        }
        mStore.flush();
        System.out.println("[mailbox] migrated " + n + " held item(s) to one-file-per-item storage");
    }

    private void load() {
        // Keys carry everything: no ciphertext is read here, however much is held.
        List<Map.Entry<String, Integer>> all = new ArrayList<>(mStore.listBytes(C_ITEMS).entrySet());
        // Oldest first, so the caps below keep the earliest mail and drop the newest overflow.
        all.sort(Comparator.comparingLong(e -> storedAtOf(e.getKey())));
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Integer> e : all) {
            String key = e.getKey();
            try {
                String[] k = key.split("\\|", 4);
                if (k.length != 4) {
                    continue;
                }
                String recipient = norm(k[0]);   // boxes are keyed normalised, always
                long seq = Long.parseLong(k[1]);
                long storedAt = Long.parseLong(k[2]);
                String id = k[3];
                int size = e.getValue();
                if (now - storedAt > mTtlMs) {
                    mStore.removeBytes(C_ITEMS, key);
                    continue;
                }
                // Re-enforce the global caps on reload: a persisted set that was
                // tampered with locally must not let us blow past them in memory.
                boolean newBox = !mBoxes.containsKey(recipient);
                if ((newBox && mBoxes.size() >= mMaxBoxes) || mTotalBytes + size > mMaxTotalBytes
                        || mTotalItems + 1 > mMaxTotalItems) {
                    mStore.removeBytes(C_ITEMS, key);
                    continue;
                }
                Box box = mBoxes.computeIfAbsent(recipient, x -> new Box());
                box.items.add(new Item(this, id, recipient, seq, storedAt, size, null));
                box.bytes += size;
                box.nextSeq = Math.max(box.nextSeq, seq + 1);
                mTotalBytes += size;
                mTotalItems++;
            } catch (Exception ex) {
                System.err.println("[mailbox] bad record " + key + ": " + ex);
            }
        }
        for (Box b : mBoxes.values()) {
            b.items.sort(Comparator.comparingLong(i -> i.sequence));
        }
    }

    private static long storedAtOf(String zKey) {
        String[] k = zKey.split("\\|", 4);
        try {
            return k.length == 4 ? Long.parseLong(k[2]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String recKey(String zRecipient, long zSeq, long zStoredAt, String zId) {
        return zRecipient + "|" + zSeq + "|" + zStoredAt + "|" + zId;
    }

    String recKey(Item zItem) {
        return recKey(zItem.recipientKey, zItem.sequence, zItem.storedAt, zItem.id);
    }

    /**
     * Hold a message for an offline recipient.
     *
     * The id is content-derived, so re-delivering the same message is idempotent
     * - a sender retrying does not fill the box with copies.
     */
    public Result store(String zRecipientKey, byte[] zCiphertext) {
        String key = norm(zRecipientKey);
        int len = zCiphertext.length;
        Box box;
        Item item;

        // PHASE 1 (monitor): quotas, dedup, a sequence number, and a RESERVATION of the
        // global and per-box budgets for this item.
        synchronized (this) {
            // Enforce the GLOBAL caps before allocating a new box. A brand-new key
            // that would push us over either global limit is refused rather than
            // evicting a real recipient's mail for a stranger's flood; an existing
            // box makes room by evicting the least-recently-used OTHER boxes.
            boolean isNew = !mBoxes.containsKey(key);
            if (isNew && mBoxes.size() >= mMaxBoxes) {
                if (!evictLruUnless(key)) {
                    return Result.QUOTA_COUNT;
                }
            }
            if (mTotalBytes + len > mMaxTotalBytes) {
                evictUntilFits(len, key);
                if (mTotalBytes + len > mMaxTotalBytes) {
                    return Result.QUOTA_BYTES;
                }
            }
            if (mTotalItems + 1 > mMaxTotalItems) {
                while (mTotalItems + 1 > mMaxTotalItems && evictLruUnless(key)) {
                    // make room by whole boxes, least recently active first
                }
                if (mTotalItems + 1 > mMaxTotalItems) {
                    return Result.QUOTA_COUNT;
                }
            }

            box = mBoxes.computeIfAbsent(key, k -> new Box());
            expire(box);
            box.lastActivity = System.currentTimeMillis();

            String id = new MiniData(Hashes.sha3(zCiphertext)).to0xString();
            for (Item i : box.items) {
                if (i.id.equals(id)) {
                    return Result.DUPLICATE;
                }
            }
            if (box.pendingIds.contains(id)) {
                return Result.DUPLICATE;   // the same message is being written right now
            }
            if (box.items.size() + box.pending >= mMaxPerPeer) {
                return Result.QUOTA_COUNT;
            }
            if (box.bytes + box.pendingBytes + len > mMaxBytesPerPeer) {
                return Result.QUOTA_BYTES;
            }
            long now = System.currentTimeMillis();
            boolean durable = mStore != com.eurobuddha.maxima.core.store.Store.MEMORY_ONLY;
            // A durable store owns the bytes from here (one file); a memory-only store keeps
            // them on the item so a test / the in-app mailbox needs no second copy.
            item = new Item(this, id, key, box.nextSeq++, now, len, durable ? null : zCiphertext);
            mTotalBytes += len;
            mTotalItems++;
            if (!durable) {
                box.items.add(item);
                box.bytes += len;
                return Result.STORED;
            }
            box.pending++;
            box.pendingBytes += len;
            box.pendingIds.add(id);
        }

        // PHASE 2 (no monitor): the durable write - a file plus an fsync, milliseconds on a
        // VPS disk. Holding the mailbox monitor across it serialised every store, fetch and
        // acknowledge on the relay behind one fsync at a time.
        boolean ok = mStore.putBytes(C_ITEMS, recKey(item), zCiphertext);

        // PHASE 3 (monitor): commit or undo the reservation.
        synchronized (this) {
            box.pending--;
            box.pendingBytes -= len;
            box.pendingIds.remove(item.id);
            if (ok && !box.evicted) {
                // The box may have been emptied and dropped by an acknowledge meanwhile, or
                // replaced by a fresh one for the same key: the item joins whichever box the
                // key has NOW (it is real, held mail either way).
                Box cur = mBoxes.get(key);
                if (cur == null) {
                    mBoxes.put(key, box);
                    cur = box;
                }
                cur.nextSeq = Math.max(cur.nextSeq, item.sequence + 1);
                int at = cur.items.size();
                while (at > 0 && cur.items.get(at - 1).sequence > item.sequence) {
                    at--;   // keep the list in sequence order for highestSequence()
                }
                cur.items.add(at, item);
                cur.bytes += len;
                return Result.STORED;
            }
            mTotalBytes -= len;
            mTotalItems--;
            if (ok) {
                // Evicted while being written: it must not survive on disk.
                mStore.removeBytes(C_ITEMS, recKey(item));
                return Result.QUOTA_COUNT;
            }
            return Result.IO_ERROR;
        }
    }

    /** Drop the single least-recently-used box other than zKeep. */
    private boolean evictLruUnless(String zKeep) {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Box> e : mBoxes.entrySet()) {
            if (e.getKey().equals(zKeep)) {
                continue;
            }
            if (e.getValue().lastActivity < oldest) {
                oldest = e.getValue().lastActivity;
                victim = e.getKey();
            }
        }
        if (victim == null) {
            return false;
        }
        Box b = mBoxes.remove(victim);
        if (b != null) {
            b.evicted = true;   // an in-flight write for this box undoes itself on commit
            mTotalBytes -= b.bytes;
            mTotalItems -= b.items.size();
            // Purge the durable copy too, or an evicted box reloads on restart
            // and re-inflates past the cap.
            for (Item i : b.items) {
                mStore.removeBytes(C_ITEMS, recKey(i));
            }
        }
        return true;
    }

    /** Evict LRU boxes until zNeed bytes will fit under the global cap. */
    private void evictUntilFits(int zNeed, String zKeep) {
        while (mTotalBytes + zNeed > mMaxTotalBytes && mBoxes.size() > 0) {
            if (!evictLruUnless(zKeep)) {
                return;
            }
        }
    }

    /**
     * Everything held for a recipient after a cursor.
     *
     * Cursor-based rather than delete-on-read: a client that crashes mid-pickup
     * must be able to resume, and at-least-once beats silently losing mail.
     */
    public synchronized List<Item> fetch(String zRecipientKey, long zAfterSequence, int zLimit) {
        Box box = mBoxes.get(norm(zRecipientKey));
        if (box == null) {
            return new ArrayList<>();
        }
        expire(box);
        List<Item> out = new ArrayList<>();
        for (Item i : box.items) {
            if (i.sequence > zAfterSequence) {
                out.add(i);
                if (out.size() >= zLimit) {
                    break;
                }
            }
        }
        out.sort(Comparator.comparingLong(i -> i.sequence));
        return out;
    }

    /** Explicit acknowledgement - only now is it safe to delete. */
    public synchronized int acknowledge(String zRecipientKey, long zUpToSequence) {
        Box box = mBoxes.get(norm(zRecipientKey));
        if (box == null) {
            return 0;
        }
        int before = box.items.size();
        box.items.removeIf(i -> {
            if (i.sequence <= zUpToSequence) {
                drop(box, i);
                return true;
            }
            return false;
        });
        // An emptied box is dropped so acknowledged recipients do not count
        // toward the global box cap forever (not while a write for it is in flight).
        if (box.items.isEmpty() && box.pending == 0) {
            mBoxes.remove(norm(zRecipientKey), box);
        }
        return before - box.items.size();
    }

    public synchronized int count(String zRecipientKey) {
        Box b = mBoxes.get(norm(zRecipientKey));
        if (b == null) {
            return 0;
        }
        expire(b);
        return b.items.size();
    }

    public synchronized long highestSequence(String zRecipientKey) {
        Box b = mBoxes.get(norm(zRecipientKey));
        if (b == null || b.items.isEmpty()) {
            return 0;
        }
        return b.items.get(b.items.size() - 1).sequence;
    }

    public synchronized int totalItems() {
        return mTotalItems;
    }

    /** Book-keeping for one item leaving its box (caller removes it from the list). */
    private void drop(Box zBox, Item zItem) {
        zBox.bytes -= zItem.size;
        mTotalBytes -= zItem.size;
        mTotalItems--;
        mStore.removeBytes(C_ITEMS, recKey(zItem));
    }

    private void expire(Box zBox) {
        long now = System.currentTimeMillis();
        zBox.items.removeIf(i -> {
            if (now - i.storedAt > mTtlMs) {
                drop(zBox, i);
                return true;
            }
            return false;
        });
    }

    /** Bytes held across every box. Bounded by the global cap. */
    public synchronized long totalBytes() {
        return mTotalBytes;
    }

    public synchronized int boxCount() {
        return mBoxes.size();
    }

    private static String norm(String zKey) {
        return zKey == null ? "" : zKey.trim().toUpperCase();
    }
}
