package com.eurobuddha.maxima.node;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.Map;
import static org.junit.Assert.*;

/** Synthetic IP labels only; no node, wallet, sockets or real identity. */
public class NodeGatewayRateLimitTest {
    @SuppressWarnings("unchecked")
    private static Map<String, NodeGateway.Bucket> buckets(NodeGateway.RateLimiter limiter) throws Exception {
        Field f = NodeGateway.RateLimiter.class.getDeclaredField("mPerIp");
        f.setAccessible(true);
        return (Map<String, NodeGateway.Bucket>) f.get(limiter);
    }

    @Test public void capacityDoesNotRestoreAnExhaustedIp() throws Exception {
        NodeGateway.RateLimiter limiter = new NodeGateway.RateLimiter(0, 0.000001);
        assertTrue(limiter.allow("protected"));
        for (int i = 1; i < 8192; i++) assertTrue(limiter.allow("ip" + i));
        limiter.allow("overflow");
        assertFalse("table pressure must not refill the protected bucket", limiter.allow("protected"));
        assertEquals(8192, buckets(limiter).size());
    }

    @Test public void fullActiveTableRefusesNewIps() throws Exception {
        NodeGateway.RateLimiter limiter = new NodeGateway.RateLimiter(0, 0.000001);
        for (int i = 0; i < 8192; i++) assertTrue(limiter.allow("ip" + i));
        for (int i = 0; i < 100; i++) assertFalse(limiter.allow("overflow" + i));
        assertEquals(8192, buckets(limiter).size());
    }

    @Test public void fullyRefilledBucketsCanMakeRoom() throws Exception {
        NodeGateway.RateLimiter limiter = new NodeGateway.RateLimiter(0, 1);
        for (int i = 0; i < 8192; i++) assertTrue(limiter.allow("ip" + i));
        Field last = NodeGateway.Bucket.class.getDeclaredField("mLastNanos");
        last.setAccessible(true);
        for (NodeGateway.Bucket b : buckets(limiter).values()) last.setLong(b, System.nanoTime() - 3_000_000_000L);
        assertTrue(limiter.allow("new-ip"));
        assertTrue(buckets(limiter).size() <= 8192);
    }

    @Test public void globalBudgetAndDisabledLimitsKeepTheirBehavior() {
        NodeGateway.RateLimiter global = new NodeGateway.RateLimiter(0.000001, 0);
        assertTrue(global.allow("one"));
        assertFalse(global.allow("two"));
        NodeGateway.RateLimiter disabled = new NodeGateway.RateLimiter(0, 0);
        for (int i = 0; i < 9000; i++) assertTrue(disabled.allow("ip" + i));
    }
    @Test public void cleanupKeepsAPartiallyRefilledBucket() throws Exception {
        NodeGateway.RateLimiter limiter = new NodeGateway.RateLimiter(0, 0.000001);
        for (int i = 0; i < 8192; i++) assertTrue(limiter.allow("ip" + i));
        Field last = NodeGateway.Bucket.class.getDeclaredField("mLastNanos");
        last.setAccessible(true);
        // Capacity is one token at this rate: 1,000,000 seconds for a full refill.
        for (NodeGateway.Bucket b : buckets(limiter).values())
            last.setLong(b, System.nanoTime() - 2_000_000_000_000_000L);
        last.setLong(buckets(limiter).get("ip0"), System.nanoTime() - 500_000_000_000_000L);
        assertTrue(limiter.allow("new-ip"));
        assertFalse("half a token cannot become a fresh full bucket", limiter.allow("ip0"));
    }

    @Test public void concurrentNewIpsCannotOverfillTheLastSlot() throws Exception {
        NodeGateway.RateLimiter limiter = new NodeGateway.RateLimiter(0, 0.000001);
        for (int i = 0; i < 8191; i++) assertTrue(limiter.allow("ip" + i));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> calls = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                String key = "new" + i;
                calls.add(pool.submit(() -> { start.await(); return limiter.allow(key); }));
            }
            start.countDown();
            int accepted = 0;
            for (java.util.concurrent.Future<Boolean> call : calls)
                if (call.get(10, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(8192, buckets(limiter).size());
        } finally { start.countDown(); pool.shutdownNow(); }
    }

}
