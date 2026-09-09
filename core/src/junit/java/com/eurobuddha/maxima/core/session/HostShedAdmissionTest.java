package com.eurobuddha.maxima.core.session;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostShedAdmissionTest {
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
    private static ThreadPoolExecutor executor(HostPool pool) throws Exception {
        return (ThreadPoolExecutor) field(pool, "mShedExec");
    }
    private static Runnable callback(HostPool pool, String host) throws Exception {
        return (Runnable) field(pool.connection(host), "mOnShed");
    }
    private static void hold(ThreadPoolExecutor executor, CountDownLatch release) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try { release.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
    }

    @Test public void repeatedAdvisoriesCoalesceWhileTheWorkerIsBusy() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(79), PeerDiscoveryTest.PROTO, 1);
        ThreadPoolExecutor executor = executor(pool);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            Runnable callback = callback(pool, relay.hostPort());
            hold(executor, release);
            for (int i = 0; i < 1000; i++) callback.run();
            assertEquals("one pending advisory per connection", 1, executor.getQueue().size());
        } finally { release.countDown(); pool.closeAll(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void aFullQueueIgnoresAdviceAndAllowsRetryAfterCapacityReturns() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(80), PeerDiscoveryTest.PROTO, 1);
        ThreadPoolExecutor executor = executor(pool);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList());
             FakeRelay alternate = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            pool.addCandidate(alternate.hostPort());
            Runnable callback = callback(pool, relay.hostPort());
            hold(executor, release);
            Future<?> drained = null;
            for (int i = 0; i < 32; i++) drained = executor.submit(() -> { });
            callback.run();
            assertEquals("overflow must not add another queued task", 32, executor.getQueue().size());
            assertEquals("overflow must not dial on the caller", 0, alternate.greeted);
            assertTrue(pool.connection(relay.hostPort()).isAttached());
            release.countDown();
            drained.get(3, TimeUnit.SECONDS);
            callback.run();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertNull("refusal must release admission for a later advisory", pool.connection(relay.hostPort()));
            assertTrue(pool.connection(alternate.hostPort()).isAttached());
        } finally { release.countDown(); pool.closeAll(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void recentlyHonouredAdviceDoesNotReenterTheQueue() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(81), PeerDiscoveryTest.PROTO, 1);
        ThreadPoolExecutor executor = executor(pool);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            assertFalse("no alternative, so keep the current relay", pool.shed(relay.hostPort(), 3000));
            Runnable callback = callback(pool, relay.hostPort());
            hold(executor, release);
            for (int i = 0; i < 1000; i++) callback.run();
            assertTrue("the existing time allowance also gates queue admission", executor.getQueue().isEmpty());
        } finally { release.countDown(); pool.closeAll(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void preferredAndRetiredConnectionsCannotQueueAdvice() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(82), PeerDiscoveryTest.PROTO, 1);
        ThreadPoolExecutor executor = executor(pool);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            Runnable callback = callback(pool, relay.hostPort());
            hold(executor, release);
            pool.setPreferred(relay.hostPort());
            callback.run();
            assertTrue("preferred relay advice is ignored before enqueue", executor.getQueue().isEmpty());
            pool.setPreferred("");
            pool.detach(relay.hostPort());
            callback.run();
            assertTrue("retired relay advice is ignored before enqueue", executor.getQueue().isEmpty());
        } finally { release.countDown(); pool.closeAll(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void separateConnectionsKeepIndependentPendingAdvisories() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(83), PeerDiscoveryTest.PROTO, 2);
        ThreadPoolExecutor executor = executor(pool);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeRelay first = new FakeRelay(Collections.emptyList());
             FakeRelay second = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(first.hostPort(), 3000));
            assertTrue(pool.attachOne(second.hostPort(), 3000));
            Runnable one = callback(pool, first.hostPort()), two = callback(pool, second.hostPort());
            hold(executor, release);
            for (int i = 0; i < 1000; i++) { one.run(); two.run(); }
            assertEquals("one relay's repeated advice must not suppress another's", 2, executor.getQueue().size());
        } finally { release.countDown(); pool.closeAll(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void closeDiscardsQueuedAdviceAndReleasesItsOwnership() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(84), PeerDiscoveryTest.PROTO, 1);
        ThreadPoolExecutor executor = executor(pool);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            Runnable callback = callback(pool, relay.hostPort());
            hold(executor, release);
            callback.run();
            assertEquals(1, executor.getQueue().size());
            pool.closeAll();
            assertTrue(executor.getQueue().isEmpty());
            assertTrue(((java.util.Set<?>) field(pool, "mQueuedSheds")).isEmpty());
            callback.run();
            assertTrue(executor.getQueue().isEmpty());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.closeAll(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void workerCreationFailureReleasesAdmissionForRetry() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(85), PeerDiscoveryTest.PROTO, 1);
        ThreadPoolExecutor executor = executor(pool);
        ThreadFactory original = executor.getThreadFactory();
        try (FakeRelay relay = new FakeRelay(Collections.emptyList());
             FakeRelay alternate = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            pool.addCandidate(alternate.hostPort());
            Runnable callback = callback(pool, relay.hostPort());
            executor.setThreadFactory(task -> { throw new IllegalStateException("synthetic worker failure"); });
            try { callback.run(); fail("worker creation should fail"); }
            catch (IllegalStateException expected) { assertEquals("synthetic worker failure", expected.getMessage()); }
            assertTrue("failed worker creation must not leave an owned advisory", ((java.util.Set<?>) field(pool, "mQueuedSheds")).isEmpty());
            executor.setThreadFactory(original);
            callback.run();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertNull(pool.connection(relay.hostPort()));
            assertTrue(pool.connection(alternate.hostPort()).isAttached());
        } finally { pool.closeAll(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }
}
