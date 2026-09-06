package com.eurobuddha.maxima.wake;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-key leaky buckets in memory: at most one wake per 10 s and 60 per hour per token hash,
 *  plus a global cap. Nothing persists; a restart forgets everything (that is the point). */
public final class RateLimit {

    static final long PER_TOKEN_MIN_GAP_MS = 10_000;
    static final int PER_TOKEN_PER_HOUR = 60;
    static final int GLOBAL_PER_MIN = 3000;

    private static final class Bucket {
        long last;
        long hourStart;
        int inHour;
    }

    private final Map<String, Bucket> mBuckets = new ConcurrentHashMap<>();
    private long mMinuteStart;
    private int mInMinute;

    public synchronized boolean allow(String zKey, long zNow) {
        if (zNow - mMinuteStart > 60_000) {
            mMinuteStart = zNow;
            mInMinute = 0;
        }
        if (++mInMinute > GLOBAL_PER_MIN) {
            return false;
        }
        Bucket b = mBuckets.computeIfAbsent(zKey, k -> new Bucket());
        if (zNow - b.hourStart > 3_600_000) {
            b.hourStart = zNow;
            b.inHour = 0;
        }
        if (zNow - b.last < PER_TOKEN_MIN_GAP_MS || b.inHour >= PER_TOKEN_PER_HOUR) {
            return false;
        }
        b.last = zNow;
        b.inHour++;
        if (mBuckets.size() > 200_000) {
            mBuckets.clear();   // a flood of distinct keys: forget rather than grow
        }
        return true;
    }
}
