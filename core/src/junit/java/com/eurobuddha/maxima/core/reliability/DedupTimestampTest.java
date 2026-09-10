package com.eurobuddha.maxima.core.reliability;

import org.junit.Test;
import static org.junit.Assert.*;

public class DedupTimestampTest {
    @Test public void minimumTimestampExceedsEvenTheLargestSignedWindow() {
        DedupCache cache = new DedupCache(Long.MAX_VALUE, 10);
        assertEquals(DedupCache.Verdict.STALE, cache.check("extreme", Long.MIN_VALUE));
        assertEquals(0, cache.size());
    }

    @Test public void wrappedAbsoluteValueCannotBypassTheDefaultWindow() {
        DedupCache cache = new DedupCache();
        // Sampling in the same millisecond exposes abs(Long.MIN_VALUE). Final behavior
        // must reject every sample, regardless of clock advancement between these calls.
        for (int i = 0; i < 10_000; i++) {
            long timestamp = System.currentTimeMillis() + Long.MIN_VALUE;
            assertEquals(DedupCache.Verdict.STALE, cache.check("wrapped-" + i, timestamp));
        }
        assertEquals(0, cache.size());
    }

    @Test public void ordinaryFreshnessAndReplayRulesRemainIntact() {
        DedupCache cache = new DedupCache(); long now = System.currentTimeMillis();
        assertEquals(DedupCache.Verdict.ACCEPT, cache.check("past", now - 60_000));
        assertEquals(DedupCache.Verdict.DUPLICATE, cache.check("past", now - 60_000));
        assertEquals(DedupCache.Verdict.ACCEPT, cache.check("future", now + 60_000));
        assertEquals(DedupCache.Verdict.STALE, cache.check("old", now - 7 * 60 * 60_000L));
        assertEquals(DedupCache.Verdict.STALE, cache.check("ahead", now + 7 * 60 * 60_000L));
        assertEquals(DedupCache.Verdict.STALE, cache.check("maximum", Long.MAX_VALUE));
        assertEquals(2, cache.size());
    }
}
