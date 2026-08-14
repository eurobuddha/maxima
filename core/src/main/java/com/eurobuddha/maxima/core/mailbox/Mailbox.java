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

    public static final class Item {
        public final String id;
        public final String recipientKey;
        public final byte[] ciphertext;
        public final long storedAt;
        /** Monotonic within a recipient, so a client can resume from a cursor. */
        public final long sequence;

        Item(String zId, String zRecipient, byte[] zCiphertext, long zSeq) {
            id = zId;
            recipientKey = zRecipient;
            ciphertext = zCiphertext;
            storedAt = System.currentTimeMillis();
            sequence = zSeq;
        }
    }

    public enum Result {
        STORED, QUOTA_COUNT, QUOTA_BYTES, DUPLICATE
    }

    private static final class Box {
        final List<Item> items = new ArrayList<>();
        long bytes;
        long nextSeq = 1;
        /** For LRU eviction under the global cap. */
        long lastActivity = System.currentTimeMillis();
    }

    private final Map<String, Box> mBoxes = new ConcurrentHashMap<>();

    /**
     * Optional durable backing.
     *
     * A relay runs under {@code Restart=always}; without this, every held
     * ciphertext is lost on the next restart, which is precisely the failure
     * this class exists to fix. One record per item, keyed recipient|sequence,
     * value = storedAt|hex(ciphertext). Deletes on acknowledge/expire.
     */
    private com.eurobuddha.maxima.core.store.Store mStore =
            com.eurobuddha.maxima.core.store.Store.MEMORY_ONLY;
    private static final String C_MAIL = "mailbox";

    private final long mTtlMs;
    private final int mMaxPerPeer;
    private final long mMaxBytesPerPeer;
    private final int mMaxBoxes;
    private final long mMaxTotalBytes;
    private long mTotalBytes;

    public Mailbox() {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_PER_PEER, DEFAULT_MAX_BYTES_PER_PEER);
    }

    public Mailbox(long zTtlMs, int zMaxPerPeer, long zMaxBytesPerPeer) {
        this(zTtlMs, zMaxPerPeer, zMaxBytesPerPeer,
                DEFAULT_MAX_BOXES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public Mailbox(long zTtlMs, int zMaxPerPeer, long zMaxBytesPerPeer,
                   int zMaxBoxes, long zMaxTotalBytes) {
        mTtlMs = zTtlMs;
        mMaxPerPeer = zMaxPerPeer;
        mMaxBytesPerPeer = zMaxBytesPerPeer;
        mMaxBoxes = zMaxBoxes;
        mMaxTotalBytes = zMaxTotalBytes;
    }

    /** Attach durable storage and reload whatever is held. Call before use. */
    public synchronized void setStore(com.eurobuddha.maxima.core.store.Store zStore) {
        mStore = zStore == null
                ? com.eurobuddha.maxima.core.store.Store.MEMORY_ONLY : zStore;
        load();
    }

    /** Persist any write-behind changes. Drive from a maintenance tick + shutdown. */
    public synchronized void flush() {
        mStore.flush();
    }

    private void load() {
        for (Map.Entry<String, String> e : mStore.all(C_MAIL).entrySet()) {
            try {
                // key = RECIPIENT|SEQ ; value = storedAt|hex(ciphertext)
                String[] k = e.getKey().split("\\|", 2);
                String[] v = e.getValue().split("\\|", 2);
                if (k.length != 2 || v.length != 2) {
                    continue;
                }
                String recipient = k[0];
                long seq = Long.parseLong(k[1]);
                long storedAt = Long.parseLong(v[0]);
                if (System.currentTimeMillis() - storedAt > mTtlMs) {
                    mStore.remove(C_MAIL, e.getKey());
                    continue;
                }
                byte[] ct = new com.eurobuddha.maxima.core.codec.MiniData(v[1]).getBytes();
                // Re-enforce the global caps on reload: a persisted set that was
                // tampered with locally must not let us blow past them in memory.
                if (mBoxes.size() >= mMaxBoxes || mTotalBytes + ct.length > mMaxTotalBytes) {
                    mStore.remove(C_MAIL, e.getKey());
                    continue;
                }
                Box box = mBoxes.computeIfAbsent(recipient, x -> new Box());
                box.items.add(new Item(
                        new MiniData(Hashes.sha3(ct)).to0xString(), recipient, ct, seq));
                box.bytes += ct.length;
                box.nextSeq = Math.max(box.nextSeq, seq + 1);
                mTotalBytes += ct.length;
            } catch (Exception ex) {
                System.err.println("[mailbox] bad record " + e.getKey() + ": " + ex);
            }
        }
    }

    private String recKey(String zRecipient, long zSeq) {
        return zRecipient + "|" + zSeq;
    }

    /**
     * Hold a message for an offline recipient.
     *
     * The id is content-derived, so re-delivering the same message is idempotent
     * - a sender retrying does not fill the box with copies.
     */
    public synchronized Result store(String zRecipientKey, byte[] zCiphertext) {
        String key = norm(zRecipientKey);

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
        if (mTotalBytes + zCiphertext.length > mMaxTotalBytes) {
            evictUntilFits(zCiphertext.length, key);
            if (mTotalBytes + zCiphertext.length > mMaxTotalBytes) {
                return Result.QUOTA_BYTES;
            }
        }

        Box box = mBoxes.computeIfAbsent(key, k -> new Box());
        expire(box);
        box.lastActivity = System.currentTimeMillis();

        String id = new MiniData(Hashes.sha3(zCiphertext)).to0xString();
        for (Item i : box.items) {
            if (i.id.equals(id)) {
                return Result.DUPLICATE;
            }
        }
        if (box.items.size() >= mMaxPerPeer) {
            return Result.QUOTA_COUNT;
        }
        if (box.bytes + zCiphertext.length > mMaxBytesPerPeer) {
            return Result.QUOTA_BYTES;
        }
        Item item = new Item(id, key, zCiphertext, box.nextSeq++);
        box.items.add(item);
        box.bytes += zCiphertext.length;
        mTotalBytes += zCiphertext.length;
        mStore.put(C_MAIL, recKey(key, item.sequence),
                item.storedAt + "|" + new MiniData(zCiphertext).to0xString());
        return Result.STORED;
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
            mTotalBytes -= b.bytes;
            // Purge the durable copy too, or an evicted box reloads on restart
            // and re-inflates past the cap.
            for (Item i : b.items) {
                mStore.remove(C_MAIL, recKey(victim, i.sequence));
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
        String rk = norm(zRecipientKey);
        box.items.removeIf(i -> {
            if (i.sequence <= zUpToSequence) {
                box.bytes -= i.ciphertext.length;
                mTotalBytes -= i.ciphertext.length;
                mStore.remove(C_MAIL, recKey(rk, i.sequence));
                return true;
            }
            return false;
        });
        // An emptied box is dropped so acknowledged recipients do not count
        // toward the global box cap forever.
        if (box.items.isEmpty()) {
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
        int n = 0;
        for (Box b : mBoxes.values()) {
            n += b.items.size();
        }
        return n;
    }

    private void expire(Box zBox) {
        long now = System.currentTimeMillis();
        zBox.items.removeIf(i -> {
            if (now - i.storedAt > mTtlMs) {
                zBox.bytes -= i.ciphertext.length;
                mTotalBytes -= i.ciphertext.length;
                mStore.remove(C_MAIL, recKey(i.recipientKey, i.sequence));
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
