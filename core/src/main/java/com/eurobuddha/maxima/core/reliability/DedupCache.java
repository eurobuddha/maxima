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
    /**
     * Freshness window, widened from 10 minutes to 6 hours.
     *
     * The msgid cache is the real replay defence; the timestamp window only
     * bounds how long a captured message stays replayable. Ten minutes was too
     * tight - it silently dropped a legitimately store-and-forwarded message,
     * and any message from a phone with more than ten minutes of clock skew
     * (common). Six hours keeps replay exposure short while not discarding
     * real, merely-delayed, mail. MaximaNode additionally admits recognised chat
     * history within the mailbox retention horizon via {@link #seenBefore};
     * commands, calls and mutable controls keep this default window.
     */
    public static final long DEFAULT_WINDOW_MS = 6L * 60 * 60 * 1000;

    /** Cap the cache so a flood cannot exhaust memory. */
    public static final int DEFAULT_MAX_ENTRIES = 20000;

    private final long mWindowMs;
    private final int mMaxEntries;
    private final Map<String, Long> mSeen;
    private final Map<String, java.util.concurrent.CompletableFuture<Void>> mCompletions = new java.util.HashMap<>();

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
                if (size() <= mMaxEntries) return false;
                mCompletions.remove(eldest.getKey());
                return true;
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
        // Subtract smaller from larger. A negative result means the distance exceeded
        // Long.MAX_VALUE; abs() would conceal overflow (and leaves Long.MIN_VALUE negative).
        long skew = now >= zTimeMilli ? now - zTimeMilli : zTimeMilli - now;
        if (skew < 0 || skew > mWindowMs) {
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

    /** Keep the processing outcome for exactly as long as its bounded dedup entry. */
    public synchronized void trackCompletion(String zMsgid, java.util.concurrent.CompletableFuture<Void> zDone) {
        if (mSeen.containsKey(zMsgid)) mCompletions.put(zMsgid, zDone);
    }

    public synchronized java.util.concurrent.CompletableFuture<Void> completion(String zMsgid) {
        return mCompletions.get(zMsgid);
    }

    /** A retryable application failure must not masquerade as an already-delivered message. */
    public synchronized void forget(String zMsgid) {
        mSeen.remove(zMsgid);
        mCompletions.remove(zMsgid);
    }

    public synchronized void clear() {
        mSeen.clear();
        mCompletions.clear();
    }
}
