package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.net.HostConnection;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostShedLifecycleTest {
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(8, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test public void queuedShedCannotActOnAReattachedHost() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(74), PeerDiscoveryTest.PROTO, 1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService sheds = (ExecutorService) field(pool, "mShedExec");
        try (FakeRelay source = new FakeRelay(Collections.emptyList());
             FakeRelay alternate = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(source.hostPort(), 3000));
            Runnable callback = (Runnable) field(pool.connection(source.hostPort()), "mOnShed");
            sheds.submit(() -> { started.countDown(); await(release); });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            callback.run();
            pool.detach(source.hostPort());
            assertTrue(pool.attachOne(source.hostPort(), 3000));
            HostConnection replacement = pool.connection(source.hostPort());
            pool.addCandidate(alternate.hostPort());
            release.countDown();
            sheds.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertSame(replacement, pool.connection(source.hostPort()));
            assertTrue(replacement.isAttached());
            assertEquals("a retired callback must not start another dial", 0, alternate.greeted);
            // Ignoring an old advisory must not consume the current connection's allowance.
            ((Runnable) field(replacement, "mOnShed")).run();
            sheds.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertNull(pool.connection(source.hostPort()));
            assertTrue(pool.connection(alternate.hostPort()).isAttached());
        } finally { release.countDown(); pool.closeAll(); sheds.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void slowShedCannotDetachAReplacementAtTheAskingHost() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(75), PeerDiscoveryTest.PROTO, 1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch greeted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (FakeRelay source = new FakeRelay(Collections.emptyList());
             FakeRelay alternate = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(source.hostPort(), 3000));
            pool.addCandidate(alternate.hostPort());
            alternate.beforeGreeting = () -> { greeted.countDown(); await(release); };
            Future<Boolean> shed = worker.submit(() -> pool.shed(source.hostPort(), 5000));
            assertTrue(greeted.await(3, TimeUnit.SECONDS));
            pool.detach(source.hostPort());
            assertTrue(pool.attachOne(source.hostPort(), 3000));
            HostConnection replacement = pool.connection(source.hostPort());
            release.countDown();
            assertFalse(shed.get(5, TimeUnit.SECONDS));
            assertSame(replacement, pool.connection(source.hostPort()));
            assertTrue(replacement.isAttached());
        } finally { release.countDown(); pool.closeAll(); worker.shutdownNow(); worker.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void slowShedCannotCancelANewHandshakeAtTheAskingHost() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(76), PeerDiscoveryTest.PROTO, 1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch alternateSeen = new CountDownLatch(1), alternateRelease = new CountDownLatch(1);
        CountDownLatch sourceSeen = new CountDownLatch(1), sourceRelease = new CountDownLatch(1);
        try (FakeRelay source = new FakeRelay(Collections.emptyList());
             FakeRelay alternate = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(source.hostPort(), 3000));
            pool.addCandidate(alternate.hostPort());
            alternate.beforeGreeting = () -> { alternateSeen.countDown(); await(alternateRelease); };
            Future<Boolean> shed = workers.submit(() -> pool.shed(source.hostPort(), 5000));
            assertTrue(alternateSeen.await(3, TimeUnit.SECONDS));
            pool.detach(source.hostPort());
            source.beforeGreeting = () -> { sourceSeen.countDown(); await(sourceRelease); };
            Future<Boolean> attaching = workers.submit(() -> pool.attachOne(source.hostPort(), 5000));
            assertTrue(sourceSeen.await(3, TimeUnit.SECONDS));
            alternateRelease.countDown();
            assertFalse(shed.get(5, TimeUnit.SECONDS));
            sourceRelease.countDown();
            assertTrue(attaching.get(5, TimeUnit.SECONDS));
            assertTrue(pool.connection(source.hostPort()).isAttached());
        } finally {
            alternateRelease.countDown(); sourceRelease.countDown(); pool.closeAll();
            workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test public void sourceBecomingPreferredDuringAttachMustStay() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(77), PeerDiscoveryTest.PROTO, 1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch greeted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (FakeRelay source = new FakeRelay(Collections.emptyList());
             FakeRelay alternate = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(source.hostPort(), 3000));
            HostConnection original = pool.connection(source.hostPort());
            pool.addCandidate(alternate.hostPort());
            alternate.beforeGreeting = () -> { greeted.countDown(); await(release); };
            Future<Boolean> shed = worker.submit(() -> pool.shed(source.hostPort(), 5000));
            assertTrue(greeted.await(3, TimeUnit.SECONDS));
            pool.setPreferred(source.hostPort());
            release.countDown();
            assertFalse(shed.get(5, TimeUnit.SECONDS));
            assertSame(original, pool.connection(source.hostPort()));
            assertTrue(original.isAttached());
        } finally { release.countDown(); pool.closeAll(); worker.shutdownNow(); worker.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void replacementThatClosesBeforeDetachDoesNotCostTheSource() throws Exception {
        HostPool pool = new HostPool(HostPoolShedTest.identity(78), PeerDiscoveryTest.PROTO, 1);
        try (FakeRelay source = new FakeRelay(Collections.emptyList());
             FakeRelay alternate = new FakeRelay(Collections.emptyList())) {
            assertTrue(pool.attachOne(source.hostPort(), 3000));
            HostConnection original = pool.connection(source.hostPort());
            pool.addCandidate(alternate.hostPort());
            pool.setListener(new HostPool.Listener() {
                public void onAttached(String host, com.eurobuddha.maxima.core.msg.Greeting greeting) {
                    if (host.equals(alternate.hostPort())) pool.connection(host).close();
                }
                public void onNoConnect(String host) { }
            });
            assertFalse(pool.shed(source.hostPort(), 3000));
            assertSame(original, pool.connection(source.hostPort()));
            assertTrue(original.isAttached());
        } finally { pool.closeAll(); }
    }
}
