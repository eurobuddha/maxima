package com.eurobuddha.maxima.core.reliability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending sends, with retry and backoff.
 *
 * Classic has no retry at all: {@code MaxMsgHandler} catches SocketTimeout and
 * ConnectException and does literally nothing, and its "poll" queue is CLEARED
 * WHOLESALE when it exceeds 256 entries. A send that fails is simply gone, and
 * the application is never told.
 *
 * Here a send is durable until it is acknowledged END TO END - by the recipient
 * after it has decrypted and dispatched, not by a relay that merely queued
 * bytes into a socket buffer.
 *
 * Bounded and oldest-dropped-first: a queue that grows without limit on a phone
 * is a crash, and one that silently empties itself is classic's bug.
 */
public final class Outbox {

    public static final int DEFAULT_MAX = 1000;
    public static final int DEFAULT_MAX_ATTEMPTS = 6;

    /** Exponential-ish, capped. Roughly 5s, 15s, 45s, 2m, 5m, 15m. */
    private static final long[] BACKOFF_MS = {
            5_000L, 15_000L, 45_000L, 120_000L, 300_000L, 900_000L
    };

    public static final class Item {
        public final String msgid;
        public final String peerKey;
        public final List<String> addresses;
        public final String application;
        public final byte[] payload;
        public final long created;

        public volatile int attempts;
        public volatile long nextAttempt;
        public volatile String lastError;
        /** Which address index to try next - rotates on failure when multi-homed. */
        public volatile int addressCursor;

        Item(String zMsgid, String zPeerKey, List<String> zAddresses,
             String zApplication, byte[] zPayload) {
            msgid = zMsgid;
            peerKey = zPeerKey;
            addresses = new ArrayList<>(zAddresses);
            application = zApplication;
            payload = zPayload;
            created = System.currentTimeMillis();
            nextAttempt = created;
        }

        public String currentAddress() {
            if (addresses.isEmpty()) {
                return null;
            }
            return addresses.get(addressCursor % addresses.size());
        }
    }

    private final Map<String, Item> mItems = new ConcurrentHashMap<>();
    private final int mMax;
    private final int mMaxAttempts;

    public Outbox() {
        this(DEFAULT_MAX, DEFAULT_MAX_ATTEMPTS);
    }

    public Outbox(int zMax, int zMaxAttempts) {
        mMax = zMax;
        mMaxAttempts = zMaxAttempts;
    }

    public int size() {
        return mItems.size();
    }

    /**
     * Queue a send.
     *
     * @return the item, or null if it was dropped because the box is full
     */
    public Item add(String zMsgid, String zPeerKey, List<String> zAddresses,
                    String zApplication, byte[] zPayload) {
        if (mItems.size() >= mMax) {
            // Drop the OLDEST, never the whole queue. Losing one stale message
            // beats classic's behaviour of discarding everything.
            mItems.values().stream()
                    .min(Comparator.comparingLong(i -> i.created))
                    .ifPresent(oldest -> mItems.remove(oldest.msgid));
        }
        Item it = new Item(zMsgid, zPeerKey, zAddresses, zApplication, zPayload);
        mItems.put(zMsgid, it);
        return it;
    }

    /** The end-to-end ack landed: stop retrying. */
    public boolean acknowledge(String zMsgid) {
        return mItems.remove(zMsgid) != null;
    }

    public boolean contains(String zMsgid) {
        return mItems.containsKey(zMsgid);
    }

    /** Everything due for a retry right now. */
    public List<Item> due() {
        long now = System.currentTimeMillis();
        List<Item> out = new ArrayList<>();
        for (Item i : mItems.values()) {
            if (i.nextAttempt <= now) {
                out.add(i);
            }
        }
        out.sort(Comparator.comparingLong(i -> i.nextAttempt));
        return out;
    }

    /** Record a failed attempt and schedule the next, or give up. */
    public boolean failed(Item zItem, String zError) {
        zItem.attempts++;
        zItem.lastError = zError;
        // Try a different relay next time, if the peer gave us more than one.
        zItem.addressCursor++;
        if (zItem.attempts >= mMaxAttempts) {
            mItems.remove(zItem.msgid);
            return false;
        }
        long delay = BACKOFF_MS[Math.min(zItem.attempts - 1, BACKOFF_MS.length - 1)];
        zItem.nextAttempt = System.currentTimeMillis() + delay;
        return true;
    }

    public List<Item> all() {
        return new ArrayList<>(mItems.values());
    }

    public void clear() {
        mItems.clear();
    }
}
