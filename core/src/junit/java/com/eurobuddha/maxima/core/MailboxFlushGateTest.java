package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.UncheckedIOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class MailboxFlushGateTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private MaximaNode node() throws Exception {
        return new MaximaNode(MaximaIdentity.fromPhrase(Bip39.generate(24)), "1.0.48", 0);
    }
    private static ThreadPoolExecutor lane(MaximaNode node) throws Exception {
        Field f = MaximaNode.class.getDeclaredField("mInboundExec"); f.setAccessible(true);
        return (ThreadPoolExecutor) f.get(node);
    }
    private static boolean drain(MaximaNode node, long ms) throws Exception {
        Method m = MaximaNode.class.getDeclaredMethod("drainInbound", long.class); m.setAccessible(true);
        Object result = m.invoke(node, ms);
        // The prior void method's normal return was unconditionally treated as success by its caller.
        return result == null || Boolean.TRUE.equals(result);
    }
    private static void flush(MaximaNode node) throws Exception {
        Method m = MaximaNode.class.getDeclaredMethod("runFlushHooks"); m.setAccessible(true);
        try { m.invoke(node); } catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test public void timedOutDeliveryDoesNotPassTheFlushGate() throws Exception {
        MaximaNode node = node(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            lane(node).execute(() -> { started.countDown(); await(release); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(drain(node, 20));
            assertTrue("expired barriers are removed", lane(node).getQueue().isEmpty());
            release.countDown();
            assertTrue("a subsequent completed barrier succeeds", drain(node, 2000));
        } finally { release.countDown(); node.stop(); }
    }

    @Test public void fullQueueCannotRunASuccessfulBarrierOnTheCallingThread() throws Exception {
        MaximaNode node = node(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            ThreadPoolExecutor lane = lane(node);
            lane.execute(() -> { started.countDown(); await(release); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            while (lane.getQueue().remainingCapacity() > 0) lane.getQueue().add(() -> { });
            assertFalse("CallerRuns is not an ordered drain", drain(node, 100));
        } finally { lane(node).getQueue().clear(); release.countDown(); node.stop(); }
    }

    @Test public void interruptionIsPreservedAndDoesNotAuthorizeDeletion() throws Exception {
        MaximaNode node = node(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            lane(node).execute(() -> { started.countDown(); await(release); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            assertFalse(drain(node, 2000));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); release.countDown(); node.stop(); }
    }

    @Test public void shutdownAndTheCurrentDeliveryCannotCertifyCompletion() throws Exception {
        MaximaNode node = node();
        try {
            assertFalse(lane(node).submit(() -> drain(node, 100)).get(2, TimeUnit.SECONDS));
            lane(node).shutdown();
            assertFalse(drain(node, 20));
        } finally { node.stop(); }
    }

    @Test public void aFailedApplicationFlushPropagatesAndLaterRecoverySucceeds() throws Exception {
        MaximaNode node = node(); java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            node.addFlushHook(() -> { if (broken.get()) throw new IllegalStateException("synthetic failure"); });
            assertThrows(IllegalStateException.class, () -> flush(node));
            broken.set(false); flush(node);
        } finally { node.stop(); }
    }

    @Test public void aFailedNodeStoreFlushPropagatesAndRemainsRetryable() throws Exception {
        MaximaNode node = node(); Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile()); store.setWriteBehind(true); node.setStore(store);
        store.put("settings", "test", "pending");
        Path target = Files.createDirectory(dir.resolve("settings.tsv")); Files.write(target.resolve("blocker"), new byte[]{1});
        try {
            assertThrows(UncheckedIOException.class, () -> flush(node));
        } finally { Files.delete(target.resolve("blocker")); Files.delete(target); node.stop(); }
        assertEquals("pending", new FileStore(dir.toFile()).get("settings", "test"));
    }
}
