package com.eurobuddha.maxima.wake;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-key leaky buckets in memory: at most one wake per 10 s and 60 per hour per token hash,
 *  plus a global cap. Nothing persists; a restart forgets everything (that is the point). */
public final class RateLimit {

    static final long PER_TOKEN_MIN_GAP_MS = 10_000;
    static final int PER_TOKEN_PER_HOUR = 60;
    static final int GLOBAL_PER_MIN = 3000;
    static final int MAX_BUCKETS = 200_000;

    private static final class Bucket {
        long last;
        long hourStart;
        int inHour;
    }

    private final Map<String, Bucket> mBuckets = new ConcurrentHashMap<>();
    private long mMinuteStart;
    private int mInMinute;
    private long mLastPrunedMinute = Long.MIN_VALUE;

    public synchronized boolean allow(String zKey, long zNow) {
        if (zNow - mMinuteStart > 60_000) {
            mMinuteStart = zNow;
            mInMinute = 0;
        }
        if (mInMinute >= GLOBAL_PER_MIN) {
            return false;
        }
        mInMinute++;
        Bucket b = mBuckets.get(zKey);
        if (b == null) {
            // Like the account wake client, retire only expired state and refuse new
            // keys at capacity. Never clear another token's active rate window.
            if (mBuckets.size() >= MAX_BUCKETS && mLastPrunedMinute != mMinuteStart) {
                mLastPrunedMinute = mMinuteStart; // at most one full scan per global window
                mBuckets.entrySet().removeIf(e -> zNow - e.getValue().hourStart > 3_600_000
                        && zNow - e.getValue().last >= PER_TOKEN_MIN_GAP_MS);
            }
            if (mBuckets.size() >= MAX_BUCKETS) return false;
            b = new Bucket();
            mBuckets.put(zKey, b);
        }
        if (zNow - b.hourStart > 3_600_000) {
            b.hourStart = zNow;
            b.inHour = 0;
        }
        if (zNow - b.last < PER_TOKEN_MIN_GAP_MS || b.inHour >= PER_TOKEN_PER_HOUR) {
            return false;
        }
        b.last = zNow;
        b.inHour++;
        return true;
    }
}
