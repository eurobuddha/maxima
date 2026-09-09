package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.net.HostConnection;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HostReapingTest {
    private static HostConnection.Sink sink(HostPool pool) throws Exception {
        Field field = HostPool.class.getDeclaredField("mSink");
        field.setAccessible(true);
        return (HostConnection.Sink) field.get(pool);
    }

    @Test public void lateDeathCallbackCannotRemoveAHealthyReplacement() throws Exception {
        MaximaNode node = new MaximaNode(HostPoolShedTest.identity(71), PeerDiscoveryTest.PROTO, 1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            HostPool pool = node.pool();
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            HostConnection original = pool.connection(relay.hostPort());
            pool.detach(relay.hostPort());
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            HostConnection replacement = pool.connection(relay.hostPort());
            assertNotSame(original, replacement);
            // The first reader was delayed between detecting death and notifying its sink.
            sink(pool).onDead(relay.hostPort());
            assertSame(replacement, pool.connection(relay.hostPort()));
            assertTrue(replacement.isAttached());
        } finally { node.stop(); }
    }

    @Test public void lateDeathCallbackCannotCancelANewHandshake() throws Exception {
        MaximaNode node = new MaximaNode(HostPoolShedTest.identity(72), PeerDiscoveryTest.PROTO, 1);
        CountDownLatch seen = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            HostPool pool = node.pool();
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            pool.detach(relay.hostPort());
            relay.beforeGreeting = () -> {
                seen.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            Future<Boolean> attaching = worker.submit(() -> pool.attachOne(relay.hostPort(), 3000));
            assertTrue(seen.await(3, TimeUnit.SECONDS));
            sink(pool).onDead(relay.hostPort());
            release.countDown();
            assertTrue(attaching.get(3, TimeUnit.SECONDS));
            assertEquals(1, pool.activeCount());
        } finally { release.countDown(); worker.shutdownNow(); node.stop(); worker.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void deathCallbackStillReapsTheCurrentClosedConnection() throws Exception {
        MaximaNode node = new MaximaNode(HostPoolShedTest.identity(73), PeerDiscoveryTest.PROTO, 1);
        try (FakeRelay relay = new FakeRelay(Collections.emptyList())) {
            HostPool pool = node.pool();
            assertTrue(pool.attachOne(relay.hostPort(), 3000));
            pool.connection(relay.hostPort()).close();
            sink(pool).onDead(relay.hostPort());
            assertEquals(0, pool.activeCount());
        } finally { node.stop(); }
    }
}
