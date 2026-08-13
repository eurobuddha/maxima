package com.eurobuddha.maxima.core.reliability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Message-id dedup with a freshness window.
 *
 * Classic has NEITHER. A captured MaxTxPoW can be replayed forever and will be
 * decrypted and re-delivered to the application every time - {@code timeMilli}
 * is never checked and no id is ever remembered.
 *
 * Both fixes are purely local: the wire format does not change, and a classic
 * peer cannot tell the difference.
 *
 * msgid = SHA3-256 of the serialised MaximaMessage, which both sides already
 * compute identically, so it is a free and exact dedup key.
 */
public final class DedupCache {

    /** Reject anything older or more future-dated than this. */
    public static final long DEFAULT_WINDOW_MS = 10L * 60 * 1000;

    /** Cap the cache so a flood cannot exhaust memory. */
    public static final int DEFAULT_MAX_ENTRIES = 20000;

    private final long mWindowMs;
    private final int mMaxEntries;
    private final Map<String, Long> mSeen;

    public DedupCache() {
        this(DEFAULT_WINDOW_MS, DEFAULT_MAX_ENTRIES);
    }

    public DedupCache(long zWindowMs, int zMaxEntries) {
        mWindowMs = zWindowMs;
        mMaxEntries = zMaxEntries;
        // Access-ordered LRU so eviction drops the least recently seen.
        mSeen = new LinkedHashMap<String, Long>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > mMaxEntries;
            }
        };
    }

    public enum Verdict {
        /** New, in-window: deliver it. */
        ACCEPT,
        /** Already seen: drop silently. */
        DUPLICATE,
        /** Timestamp outside the window: drop as a replay. */
        STALE
    }

    /**
     * @param zMsgid     uppercase 0x hex
     * @param zTimeMilli the message's claimed send time
     */
    public synchronized Verdict check(String zMsgid, long zTimeMilli) {
        long now = System.currentTimeMillis();
        long skew = Math.abs(now - zTimeMilli);
        if (skew > mWindowMs) {
            return Verdict.STALE;
        }
        if (mSeen.containsKey(zMsgid)) {
            return Verdict.DUPLICATE;
        }
        mSeen.put(zMsgid, now);
        return Verdict.ACCEPT;
    }

    /** Dedup only, ignoring freshness - for stored-and-forwarded messages. */
    public synchronized boolean seenBefore(String zMsgid) {
        return mSeen.put(zMsgid, System.currentTimeMillis()) != null;
    }

    public synchronized int size() {
        return mSeen.size();
    }

    public synchronized void clear() {
        mSeen.clear();
    }
}
