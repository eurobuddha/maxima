package com.eurobuddha.maxima.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** Same key = in order, one at a time; different keys = in parallel; one dead lane blocks
 *  nobody else. */
public class SerialLanesTest {

    @Test
    public void sameKeyRunsInOrderDifferentKeysRunInParallel() throws Exception {
        SerialLanes lanes = new SerialLanes("test-lanes", 4);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        // an offline peer: its lane blocks
        lanes.execute("peer-dead", () -> {
            slowStarted.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            order.add("dead-1");
            done.countDown();
        });
        lanes.execute("peer-dead", () -> { order.add("dead-2"); done.countDown(); });
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
        // other work is not behind it
        lanes.execute("wallet", () -> { order.add("wallet"); done.countDown(); });
        lanes.execute("peer-live", () -> { order.add("live"); done.countDown(); });
        long until = System.currentTimeMillis() + 5000;
        while (order.size() < 2 && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertTrue("wallet and live ran while the dead lane was blocked: " + order,
                order.contains("wallet") && order.contains("live"));
        assertTrue(!order.contains("dead-2"));
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("dead-1", order.get(order.indexOf("dead-2") - 1));   // per-key order kept
        lanes.shutdownNow();
    }

    @Test
    public void aThrowingTaskDoesNotEndItsLane() throws Exception {
        SerialLanes lanes = new SerialLanes("test-lanes-2", 2);
        CountDownLatch done = new CountDownLatch(1);
        lanes.execute("k", () -> { throw new RuntimeException("boom"); });
        lanes.execute("k", done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        lanes.shutdownNow();
    }
}
