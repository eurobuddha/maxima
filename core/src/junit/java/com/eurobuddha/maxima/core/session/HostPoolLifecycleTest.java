package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.net.HostConnection;
import org.junit.Test;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real loopback handshakes using the existing discovery relay fixture. */
public class HostPoolLifecycleTest {
    @Test public void detachCancelsAPendingHandshakeWithoutPenalisingTheRelay() throws Exception {
        CountDownLatch greeted = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        HostPool pool = new HostPool(HostPoolShedTest.identity(65), PeerDiscoveryTest.PROTO, 1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            relay.beforeGreeting = () -> { greeted.countDown(); await(release); };
            Future<Boolean> attached = worker.submit(() -> pool.attachOne(relay.hostPort(), 5000));
            assertTrue(greeted.await(5, TimeUnit.SECONDS));
            pool.detach(relay.hostPort());
            assertFalse(attached.get(1, TimeUnit.SECONDS));
            assertEquals(0, pool.activeCount());
            relay.beforeGreeting = () -> { };
            release.countDown();
            assertTrue("deliberate detach must allow immediate retry", pool.attachOne(relay.hostPort(), 3000));
        } finally {
            release.countDown(); worker.shutdownNow(); worker.awaitTermination(6, TimeUnit.SECONDS); pool.closeAll();
        }
    }

    @Test public void closeCancelsAHandshakeBeforeTheRelayReplies() throws Exception {
        CountDownLatch greeted = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        HostPool pool = new HostPool(HostPoolShedTest.identity(61), PeerDiscoveryTest.PROTO, 1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            relay.beforeGreeting = () -> { greeted.countDown(); await(release); };
            Future<Boolean> attached = worker.submit(() -> pool.attachOne(relay.hostPort(), 5000));
            assertTrue(greeted.await(5, TimeUnit.SECONDS));
            pool.closeAll();
            assertFalse("shutdown releases the handshake without a greeting", attached.get(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(0, pool.activeCount());
            assertTrue(pool.contactAddresses().isEmpty());
        } finally {
            release.countDown(); worker.shutdownNow(); worker.awaitTermination(6, TimeUnit.SECONDS); pool.closeAll();
        }
    }

    @Test public void aClosedPoolNeverStartsAnotherHandshake() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(62), PeerDiscoveryTest.PROTO, 1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            pool.closeAll();
            assertFalse(pool.attachOne(relay.hostPort(), 1000));
            assertEquals(0, relay.greeted);
            assertEquals(0, pool.activeCount());
        } finally { pool.closeAll(); }
    }

    @Test public void concurrentAttachmentsCannotReplaceAndLeakTheSameHost() throws Exception {
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger greetings = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        HostPool pool = new HostPool(HostPoolShedTest.identity(63), PeerDiscoveryTest.PROTO, 1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            relay.beforeGreeting = () -> {
                if (greetings.incrementAndGet() == 1) { first.countDown(); await(release); }
            };
            Future<Boolean> attaching = worker.submit(() -> pool.attachOne(relay.hostPort(), 5000));
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertFalse("already connecting; do not start a second socket", pool.attachOne(relay.hostPort(), 1000));
            assertEquals(1, greetings.get());
            release.countDown();
            assertTrue(attaching.get(5, TimeUnit.SECONDS));
            assertEquals(1, pool.activeCount());
        } finally {
            release.countDown(); worker.shutdownNow(); worker.awaitTermination(6, TimeUnit.SECONDS); pool.closeAll();
        }
    }

    @Test public void closingAConnectionBeforeAttachCannotReopenItsSocket() throws Exception {
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            HostConnection connection = new HostConnection("127.0.0.1", relay.port,
                    HostPoolShedTest.identity(64).keyPair(), PeerDiscoveryTest.PROTO);
            try {
                connection.close();
                try { connection.attach(1000); fail("closed connection reopened"); }
                catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("closed")); }
                assertEquals(0, relay.greeted);
            } finally { connection.close(); }
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
