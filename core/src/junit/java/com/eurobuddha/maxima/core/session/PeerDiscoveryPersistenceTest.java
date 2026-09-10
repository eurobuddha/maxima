package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.maxima.core.store.Store;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Discovery state must survive save overlap, failed flushes and endpoint changes. */
public class PeerDiscoveryPersistenceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final String PEER = "10.2.0.1:9501", OTHER = "10.2.0.2:9501";
    private static final String KEY = "key_0123456789";

    private FileStore disk(Path dir) {
        FileStore disk = new FileStore(dir.toFile());
        disk.put("peers", PEER, "123456789"); disk.put("peers", OTHER, "123456789");
        disk.setWriteBehind(true);
        return disk;
    }

    private void gateway(PeerDiscovery d, String name) {
        d.onGreeting(PEER, Greeting.commsOnly(PeerDiscoveryTest.PROTO, "", 0,
                Collections.emptyList(), 8, true, 1, "https://" + name + ".example/cmd", KEY));
    }

    @Test public void aGatewayChangeDuringFlushRemainsPendingForShutdown() throws Exception { overlap(false); }
    @Test public void anOlderSaveCannotOverwriteANewerSave() throws Exception { overlap(true); }

    private void overlap(boolean secondSave) throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore disk = disk(dir);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), updating = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        Store gated = (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[]{Store.class},
                (proxy, method, args) -> {
                    boolean gate = secondSave ? method.getName().equals("put") && PEER.equals(args[1])
                            : method.getName().equals("flush");
                    if (gate && armed.compareAndSet(true, false)) {
                        entered.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("save was not released");
                    }
                    try { return method.invoke(disk, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        PeerDiscovery d = PeerDiscoveryTest.discovery(); d.setStore(gated); gateway(d, "old");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(d::save); assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> next = workers.submit(() -> { updating.countDown(); gateway(d, "new"); if (secondSave) d.save(); });
            assertTrue(updating.await(5, TimeUnit.SECONDS));
            // Both an implementation that serializes mutations and one that snapshots safely
            // may pass: the required outcome is the newest gateway on disk after shutdown.
            try { next.get(200, TimeUnit.MILLISECONDS); } catch (TimeoutException expectedOverlap) { }
            release.countDown(); first.get(5, TimeUnit.SECONDS); next.get(5, TimeUnit.SECONDS);
            d.stop();
            assertTrue(new FileStore(dir.toFile()).get("peers", PEER).endsWith("|https://new.example/cmd|" + KEY));
        } finally {
            release.countDown(); workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); d.stop();
        }
    }

    @Test public void aReentrantStoreCallbackLeavesItsNewGatewayPending() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore disk = disk(dir);
        PeerDiscovery d = PeerDiscoveryTest.discovery(); AtomicBoolean armed = new AtomicBoolean(true);
        Store callback = (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[]{Store.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("flush") && armed.compareAndSet(true, false)) gateway(d, "new");
                    try { return method.invoke(disk, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        d.setStore(callback); gateway(d, "old");
        try {
            d.save(); d.stop();
            assertTrue(new FileStore(dir.toFile()).get("peers", PEER).endsWith("|https://new.example/cmd|" + KEY));
        } finally { d.stop(); }
    }

    @Test public void aFailedExplicitSaveIsRetriedByShutdownEvenWithoutANewPeer() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore disk = disk(dir);
        PeerDiscovery d = PeerDiscoveryTest.discovery(); d.setStore(disk);
        Path target = dir.resolve("peers.tsv"), saved = dir.resolve("saved-peers");
        Files.move(target, saved); Files.createDirectory(target); Files.write(target.resolve("blocker"), new byte[]{1});
        try {
            assertThrows(UncheckedIOException.class, d::save);
        } finally {
            Files.delete(target.resolve("blocker")); Files.delete(target); Files.move(saved, target);
            d.stop();
        }
        assertEquals("coarsened peer snapshot was retried", "122400000", new FileStore(dir.toFile()).get("peers", PEER));
    }

    @Test public void learningOurOwnEndpointPersistsItsRemovalWithOtherPeersKept() throws Exception {
        Path dir = tmp.newFolder().toPath(); PeerDiscovery d = PeerDiscoveryTest.discovery(); d.setStore(disk(dir));
        try {
            d.save(); d.addSelf(PEER); d.stop();
            assertEquals(Collections.singleton(OTHER), new FileStore(dir.toFile()).all("peers").keySet());
        } finally { d.stop(); }
    }
}
