package com.eurobuddha.maxima.core.session;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback probes, held at the existing FakeRelay greeting boundary. */
public class PeerDiscoveryLifecycleTest {
    @Test public void stopClosesTheProbeAndClearsQueuedWork() throws Exception {
        PeerDiscovery d = discovery();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger verified = new AtomicInteger();
        d.setListener(listener(h -> verified.incrementAndGet()));
        try (FakeRelay first = new FakeRelay(Collections.emptyList());
             FakeRelay queued = new FakeRelay(Collections.emptyList())) {
            first.beforeGreeting = () -> { entered.countDown(); await(release); };
            d.addPeer(first.hostPort());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            d.addPeer(queued.hostPort());
            Thread worker = (Thread) field(d, "mChecker");
            d.stop();
            worker.join(1000);
            assertFalse("stop must close the read, not wait for the greeting timeout", worker.isAlive());
            assertTrue("queued probes are discarded", queue(d).isEmpty());
            release.countDown();
            assertEquals(0, verified.get());
            assertEquals(0, d.verifiedCount());
            assertEquals(0, queued.greeted);
        } finally { release.countDown(); stopAndJoin(d); }
    }

    @Test public void stoppedDiscoveryCannotAdmitOrRestartWork() throws Exception {
        PeerDiscovery d = discovery();
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            d.stop();
            d.addPeer(relay.hostPort());
            d.check(relay.hostPort(), true);
            d.tick();
            assertEquals(0, d.unverifiedCount());
            assertEquals(0, d.verifiedCount());
            assertEquals(0, relay.greeted);
            assertNull(field(d, "mChecker"));
        } finally { stopAndJoin(d); }
    }

    @Test public void noConnectRetiresAnInFlightResult() throws Exception { retiredResult(false); }
    @Test public void aNewSelfAddressRetiresAnInFlightResult() throws Exception { retiredResult(true); }

    private static void retiredResult(boolean self) throws Exception {
        PeerDiscovery d = discovery();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch nextVerified = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        try (FakeRelay first = new FakeRelay(Collections.emptyList());
             FakeRelay next = new FakeRelay(Collections.emptyList())) {
            first.beforeGreeting = () -> { entered.countDown(); await(release); };
            d.setListener(listener(h -> {
                if (h.equals(first.hostPort())) stale.incrementAndGet();
                if (h.equals(next.hostPort())) nextVerified.countDown();
            }));
            d.addPeer(first.hostPort());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (self) d.addSelf(first.hostPort()); else d.noConnect(first.hostPort());
            d.addPeer(next.hostPort());
            release.countDown();
            assertTrue(nextVerified.await(5, TimeUnit.SECONDS));
            assertEquals("a retired check must not readopt its peer", 0, stale.get());
            assertEquals(Collections.singletonList(next.hostPort()), d.verified());
            assertEquals(0, d.unverifiedCount());
        } finally { release.countDown(); stopAndJoin(d); }
    }

    @Test public void removingAQueuedPeerPreventsItsDial() throws Exception {
        PeerDiscovery d = discovery();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch nextVerified = new CountDownLatch(1);
        try (FakeRelay first = new FakeRelay(Collections.emptyList());
             FakeRelay removed = new FakeRelay(Collections.emptyList());
             FakeRelay next = new FakeRelay(Collections.emptyList())) {
            first.beforeGreeting = () -> { entered.countDown(); await(release); };
            d.setListener(listener(h -> { if (h.equals(next.hostPort())) nextVerified.countDown(); }));
            d.addPeer(first.hostPort());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            d.addPeer(removed.hostPort());
            d.noConnect(removed.hostPort());
            d.addPeer(next.hostPort());
            release.countDown();
            assertTrue(nextVerified.await(5, TimeUnit.SECONDS));
            assertEquals(0, removed.greeted);
            assertFalse(d.verified().contains(removed.hostPort()));
        } finally { release.countDown(); stopAndJoin(d); }
    }

    @Test public void aFullQueueKeepsTheNewPeerEligibleForRetry() throws Exception {
        PeerDiscovery d = discovery();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch nextVerified = new CountDownLatch(1);
        try (FakeRelay first = new FakeRelay(Collections.emptyList());
             FakeRelay next = new FakeRelay(Collections.emptyList())) {
            first.beforeGreeting = () -> { entered.countDown(); await(release); };
            d.setListener(listener(h -> { if (h.equals(next.hostPort())) nextVerified.countDown(); }));
            d.addPeer(first.hostPort());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            BlockingQueue<Object> queue = queue(d);
            Constructor<?> constructor = Class.forName(PeerDiscovery.class.getName() + "$Check")
                    .getDeclaredConstructor(String.class, boolean.class);
            constructor.setAccessible(true);
            while (queue.remainingCapacity() > 0) {
                queue.add(constructor.newInstance("192.0.2.1:9501", false));
            }
            d.addPeer(next.hostPort());
            Map<String, Long> due = due(d);
            assertTrue("a refused queue offer must retain a retry", due.containsKey(next.hostPort()));
            queue.clear(); // release only the synthetic saturation fixture
            due.put(next.hostPort(), 0L); // advance the deferred retry without sleeping a minute
            d.tick();
            release.countDown();
            assertTrue(nextVerified.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); stopAndJoin(d); }
    }

    @Test public void repeatedRechecksCoalesceBehindTheOwnedProbe() throws Exception {
        PeerDiscovery d = discovery();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch nextVerified = new CountDownLatch(1);
        try (FakeRelay first = new FakeRelay(Collections.emptyList());
             FakeRelay next = new FakeRelay(Collections.emptyList())) {
            d.check(first.hostPort(), true);
            first.beforeGreeting = () -> { entered.countDown(); await(release); };
            d.setListener(listener(h -> { if (h.equals(next.hostPort())) nextVerified.countDown(); }));
            for (int i = 0; i < 20; i++) {
                setField(d, "mLastFullRecheck", 0L);
                d.tick();
                if (i == 0) assertTrue(entered.await(5, TimeUnit.SECONDS));
            }
            assertTrue("rechecks do not pile up behind a probe of the same peer", queue(d).isEmpty());
            d.addPeer(next.hostPort());
            release.countDown();
            assertTrue(nextVerified.await(5, TimeUnit.SECONDS));
            assertEquals("initial verification and one recheck", 2, first.greeted);
        } finally { release.countDown(); stopAndJoin(d); }
    }

    @Test public void aListenerCanStopDuringEvictionWithoutAdoptingTheNewPeer() throws Exception {
        PeerDiscovery d = discovery();
        AtomicInteger verified = new AtomicInteger();
        // Populate the existing verified-list boundary without starting unrelated network work.
        @SuppressWarnings("unchecked") Map<String, Long> peers = (Map<String, Long>) field(d, "mVerified");
        for (int i = 0; i < PeerDiscovery.MAX_VERIFIED_PEERS; i++) peers.put("10.0.0." + i + ":9501", 1L);
        d.setListener(new PeerDiscovery.Listener() {
            @Override public void onVerified(String h) { verified.incrementAndGet(); }
            @Override public void onRemoved(String h) { d.stop(); }
        });
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            d.check(relay.hostPort(), true);
            assertFalse("stop in a callback retires the rest of that result", d.verified().contains(relay.hostPort()));
            assertEquals(0, verified.get());
        } finally { stopAndJoin(d); }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }

    private static PeerDiscovery discovery() {
        PeerDiscovery d = new PeerDiscovery(PeerDiscoveryTest.PROTO);
        d.setAllowAllIp(true);
        return d;
    }
    private static PeerDiscovery.Listener listener(java.util.function.Consumer<String> verified) {
        return new PeerDiscovery.Listener() {
            @Override public void onVerified(String h) { verified.accept(h); }
            @Override public void onRemoved(String h) { }
        };
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    @SuppressWarnings("unchecked") private static BlockingQueue<Object> queue(PeerDiscovery d) throws Exception {
        return (BlockingQueue<Object>) field(d, "mQueue");
    }
    @SuppressWarnings("unchecked") private static Map<String, Long> due(PeerDiscovery d) throws Exception {
        return (Map<String, Long>) field(d, "mDue");
    }
    private static void stopAndJoin(PeerDiscovery d) throws Exception {
        d.stop(); Thread t = (Thread) field(d, "mChecker"); if (t != null) t.join(5000);
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
