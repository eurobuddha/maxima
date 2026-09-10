package com.eurobuddha.maxima.wake;

import org.junit.Test;
import static org.junit.Assert.*;

public class RateLimitTest {
    private static final long START = 10_000_000L;

    /** Fill through the real public admission path without exceeding its global rate. */
    private static long fill(RateLimit limit, int count) {
        long now = START;
        for (int i = 0; i < count; i++) {
            now = START + (i / 2999) * 60_001L;
            assertTrue("filler " + i, limit.allow("filler" + i, now));
        }
        return now;
    }

    @Test public void capacityPressureCannotResetAnExhaustedHourlyAllowance() {
        RateLimit limit = new RateLimit();
        long first = fill(limit, 199_999);
        for (int i = 0; i < 60; i++)
            assertTrue(limit.allow("protected", first + i * 10_001L));
        long now = first + 60 * 10_001L;
        assertTrue("expired filler may make room", limit.allow("new-key", now));
        assertFalse("capacity pressure must preserve the active hourly allowance", limit.allow("protected", now));
    }

    @Test public void anExpiredHourDoesNotEraseAStillActiveMinimumGap() {
        RateLimit limit = new RateLimit();
        long first = fill(limit, 199_999);
        assertTrue(limit.allow("protected", first));
        assertTrue(limit.allow("protected", first + 3_600_000 - 1));
        long now = first + 3_600_000 + 1;
        assertTrue(limit.allow("new-key", now));
        assertFalse("last accepted wake was only two milliseconds ago", limit.allow("protected", now));
        assertTrue(limit.allow("protected", first + 3_600_000 - 1 + 10_000));
    }

    @Test public void fullUnexpiredStateRefusesNewKeysAndRecoversAfterExpiry() {
        RateLimit limit = new RateLimit();
        long end = fill(limit, 200_000);
        // A wall-clock rollback means none of the retained windows is safely expired.
        assertFalse("full state is refused rather than cleared", limit.allow("new-key", START));
        assertTrue("expired state is reclaimable", limit.allow("new-key", end + 3_600_001));
        assertFalse("the new key retains its own minimum gap", limit.allow("new-key", end + 3_600_002));
    }

    @Test public void theGlobalCapStillAppliesAcrossDistinctKeysAndResetsOnItsWindow() {
        RateLimit limit = new RateLimit();
        for (int i = 0; i < RateLimit.GLOBAL_PER_MIN; i++) assertTrue(limit.allow("key" + i, START));
        for (int i = 0; i < 100; i++) assertFalse(limit.allow("overflow" + i, START + 60_000));
        assertTrue(limit.allow("next-minute", START + 60_001));
    }
}
