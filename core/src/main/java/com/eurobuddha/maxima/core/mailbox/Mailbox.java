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
    }

    private final Map<String, Box> mBoxes = new ConcurrentHashMap<>();
    private final long mTtlMs;
    private final int mMaxPerPeer;
    private final long mMaxBytesPerPeer;

    public Mailbox() {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_PER_PEER, DEFAULT_MAX_BYTES_PER_PEER);
    }

    public Mailbox(long zTtlMs, int zMaxPerPeer, long zMaxBytesPerPeer) {
        mTtlMs = zTtlMs;
        mMaxPerPeer = zMaxPerPeer;
        mMaxBytesPerPeer = zMaxBytesPerPeer;
    }

    /**
     * Hold a message for an offline recipient.
     *
     * The id is content-derived, so re-delivering the same message is idempotent
     * - a sender retrying does not fill the box with copies.
     */
    public synchronized Result store(String zRecipientKey, byte[] zCiphertext) {
        Box box = mBoxes.computeIfAbsent(norm(zRecipientKey), k -> new Box());
        expire(box);

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
        box.items.add(new Item(id, norm(zRecipientKey), zCiphertext, box.nextSeq++));
        box.bytes += zCiphertext.length;
        return Result.STORED;
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
                box.bytes -= i.ciphertext.length;
                return true;
            }
            return false;
        });
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
                return true;
            }
            return false;
        });
    }

    private static String norm(String zKey) {
        return zKey == null ? "" : zKey.trim().toUpperCase();
    }
}
