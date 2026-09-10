package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.*;
import com.eurobuddha.maxima.core.chat.*;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.net.*;
import com.eurobuddha.maxima.core.rpc.*;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class MailboxCompletionTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static MaximaIdentity identity() throws Exception { return MaximaIdentity.fromPhrase(Bip39.generate(24)); }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static ThreadPoolExecutor lane(MaximaNode node, String name) throws Exception {
        Field f = MaximaNode.class.getDeclaredField(name); f.setAccessible(true); return (ThreadPoolExecutor) f.get(node);
    }
    private static void flush(MaximaNode node) {
        try {
            Method m = MaximaNode.class.getDeclaredMethod("runFlushHooks"); m.setAccessible(true); m.invoke(node);
        } catch (InvocationTargetException e) { throw (RuntimeException) e.getCause(); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object field(Object target, Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true);
        synchronized (target == null ? type : target) { return f.get(target); }
    }

    @Test public void anApplicationFailureRetainsMailAndTheSameItemCanRecover() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean broken = new AtomicBoolean(true); AtomicInteger attempts = new AtomicInteger();
            f.node.setMessageListener((message, id) -> {
                attempts.incrementAndGet(); if (broken.get()) throw new IllegalStateException("synthetic pre-write failure");
                f.store.put("messages", "saved", "recovered");
            });
            f.queue("completion-test", new byte[]{1}); HostConnection first = f.connect();
            AttachedSendTest.waitFor(() -> !first.isAttached(), 5000);
            assertEquals(1, f.relay.mailbox().count(f.routeKey)); assertNull(new FileStore(f.dir).get("messages", "saved"));
            broken.set(false); f.connect(); f.cleared();
            assertEquals(2, attempts.get()); assertEquals("recovered", new FileStore(f.dir).get("messages", "saved"));
        }
    }

    @Test public void anRpcMustFinishAndFlushBeforeItsRelayCopyIsDeleted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture()) {
            try {
                f.node.services().register("completion-test", req -> { entered.countDown(); await(release); f.store.put("rpc", "saved", "done"); return new byte[0]; });
                f.queue(RpcEnvelope.APPLICATION, RpcEnvelope.request("rpc", "completion-test", Collections.emptyList(), new byte[0]).toBytes());
                f.connect(); assertTrue(entered.await(5, TimeUnit.SECONDS)); Thread.sleep(150);
                assertEquals("an unfinished RPC cannot authorize deletion", 1, f.relay.mailbox().count(f.routeKey));
                assertEquals(0, f.flushes.get());
                release.countDown(); f.cleared(); assertEquals("done", new FileStore(f.dir).get("rpc", "saved"));
            } finally { release.countDown(); }
        }
    }

    @Test public void aRealChatStoreFailureRetainsMailUntilItsDirtyWriteRecovers() throws Exception {
        try (Fixture f = new Fixture()) {
            File chatDir = tmp.newFolder(); FileStore store = new FileStore(chatDir);
            ChatEngine chat = new ChatEngine(f.node); chat.setStore(store);
            Path blocked = Files.createDirectory(chatDir.toPath().resolve("chat_messages.tsv"));
            Files.write(blocked.resolve("blocker"), new byte[]{1});
            try {
                f.node.setMessageListener((message, id) -> chat.onInbound(message, id.to0xString()));
                f.queue(ChatMessage.APPLICATION, ChatMessage.text("held", "persist me", System.currentTimeMillis()).encode().getBytes(StandardCharsets.UTF_8));
                HostConnection first = f.connect(); AttachedSendTest.waitFor(() -> !first.isAttached(), 5000);
                assertEquals(1, f.relay.mailbox().count(f.routeKey));
                Files.delete(blocked.resolve("blocker")); Files.delete(blocked);
                f.connect(); f.cleared();
                assertEquals(1, chat.allMessages().size());
                assertTrue(new FileStore(chatDir).get("chat_messages", "held").contains("persist me"));
            } finally {
                if (Files.isDirectory(blocked)) { Files.deleteIfExists(blocked.resolve("blocker")); Files.delete(blocked); }
                chat.close();
            }
        }
    }

    @Test public void aPendingMailboxDeliveryDoesNotBlockTheConnectionsSendAcks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture()) {
            try {
                f.node.services().register("completion-test", req -> { entered.countDown(); await(release); return new byte[0]; });
                f.queue(RpcEnvelope.APPLICATION, RpcEnvelope.request("waiting", "completion-test", Collections.emptyList(), new byte[0]).toBytes());
                HostConnection conn = f.connect(); assertTrue(entered.await(2, TimeUnit.SECONDS)); Thread.sleep(150);
                MaximaSender.Built outgoing = MaximaSender.build(f.sender.publicKey(), f.sender.keyPair().getPrivate(), f.route,
                        "live-while-waiting", new byte[]{2}, System.currentTimeMillis());
                MaximaSender.Result sent = conn.send(outgoing.unit, outgoing.msgid, 1000);
                assertNotNull(sent); assertTrue("the reader must keep collecting normal send ACKs", sent.isOk());
                assertEquals("RPC remains unfinished", 1, f.relay.mailbox().count(f.routeKey));
                release.countDown(); f.cleared();
            } finally { release.countDown(); }
        }
    }

    @Test public void aDuplicateOnAnotherConnectionWaitsForTheOriginalDelivery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture()) {
            try {
                AtomicInteger deliveries = new AtomicInteger();
                f.node.setMessageListener((message, id) -> { deliveries.incrementAndGet(); entered.countDown(); await(release); f.store.put("messages", "saved", "once"); });
                f.queue("completion-test", new byte[]{1}); HostConnection first = f.connect(); assertTrue(entered.await(5, TimeUnit.SECONDS));
                // An active delivery must remain owned even after ordinary dedup-cache churn.
                Field cacheField = MaximaNode.class.getDeclaredField("mDedup"); cacheField.setAccessible(true);
                com.eurobuddha.maxima.core.reliability.DedupCache cache = (com.eurobuddha.maxima.core.reliability.DedupCache) cacheField.get(f.node);
                for (int i = 0; i < 20_000; i++) cache.check("synthetic-" + i, System.currentTimeMillis());
                first.close(); AttachedSendTest.waitFor(() -> f.relay.connectionCount() == 0, 2000);
                CountDownLatch duplicate = new CountDownLatch(1);
                f.connect(in -> { f.node.handle(in); duplicate.countDown(); }); assertTrue(duplicate.await(2, TimeUnit.SECONDS));
                Thread.sleep(150); assertEquals(1, f.relay.mailbox().count(f.routeKey));
                release.countDown(); f.cleared(); assertEquals(1, deliveries.get());
                assertEquals("once", new FileStore(f.dir).get("messages", "saved"));
            } finally { release.countDown(); }
        }
    }

    @Test public void anInlineDeliveryIsStillPendingAfterTheWorkerQueueDrains() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1), workerRelease = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (Fixture f = new Fixture()) {
            try {
                ThreadPoolExecutor lane = lane(f.node, "mInboundExec");
                lane.execute(() -> { workerStarted.countDown(); await(workerRelease); }); assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
                while (lane.getQueue().remainingCapacity() > 0) lane.getQueue().add(() -> {});
                f.node.setMessageListener((message, id) -> { entered.countDown(); await(release); f.store.put("messages", "saved", "inline"); });
                MaximaSender.Built built = f.queue("completion-test", new byte[]{1});
                HostConnection.Inbound[] inbound = new HostConnection.Inbound[1];
                assertEquals(Frame.RESPONSE_OK, HostConnection.unwrap(built.unit, f.route, f.owner.hostKey(f.hp).getPrivate(), inbound));
                Future<?> delivery = reader.submit(() -> f.node.handle(inbound[0])); assertTrue(entered.await(2, TimeUnit.SECONDS));
                workerRelease.countDown(); AttachedSendTest.waitFor(() -> lane.getQueue().isEmpty(), 2000);
                CountDownLatch duplicate = new CountDownLatch(1);
                f.connect(in -> { f.node.handle(in); duplicate.countDown(); }); assertTrue(duplicate.await(2, TimeUnit.SECONDS));
                Thread.sleep(150);
                assertFalse(delivery.isDone()); assertEquals("duplicate must await inline original", 1, f.relay.mailbox().count(f.routeKey));
                assertEquals(0, f.flushes.get());
                release.countDown(); delivery.get(2, TimeUnit.SECONDS); f.cleared();
                assertEquals("inline", new FileStore(f.dir).get("messages", "saved"));
            } finally { release.countDown(); workerRelease.countDown(); }
        } finally { reader.shutdownNow(); reader.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void anUnexpectedRpcFailureDoesNotTransparentlyRepeatItsSideEffect() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger effects = new AtomicInteger();
            f.node.services().register("completion-test", req -> { effects.incrementAndGet(); return new byte[0]; });
            Field replies = RpcPeer.class.getDeclaredField("mReplyExec"); replies.setAccessible(true);
            ThreadPoolExecutor replyPool = (ThreadPoolExecutor) replies.get(f.node.rpc());
            replyPool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
            replyPool.shutdown(); // inject a reported failure AFTER dispatch, before reply scheduling
            f.queue(RpcEnvelope.APPLICATION, RpcEnvelope.request("uncertain", "completion-test", Collections.singletonList("unused-address"), new byte[0]).toBytes());
            HostConnection first = f.connect(); AttachedSendTest.waitFor(() -> !first.isAttached(), 5000);
            assertEquals(1, effects.get()); assertEquals(1, f.relay.mailbox().count(f.routeKey));
            HostConnection second = f.connect(); AttachedSendTest.waitFor(() -> !second.isAttached(), 5000);
            assertEquals("same cached request must not execute again", 1, effects.get());
            assertEquals("unknown outcome is not certified durable", 1, f.relay.mailbox().count(f.routeKey));
        }
    }

    @Test public void aThrowingSynchronousConsumerCannotDeleteHeldMail() throws Exception {
        try (Fixture f = new Fixture()) {
            f.queue("completion-test", new byte[]{1});
            HostConnection conn = f.connect(in -> { throw new IllegalStateException("synchronous failure"); });
            AttachedSendTest.waitFor(() -> !conn.isAttached(), 5000);
            assertEquals(1, f.relay.mailbox().count(f.routeKey)); assertEquals(0, f.flushes.get());
        }
    }

    @Test public void stoppingCancelsPendingDeliveryWithoutCertifyingIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try (Fixture f = new Fixture()) {
            try {
                f.node.setMessageListener((message, id) -> { entered.countDown(); await(release); });
                f.queue("completion-test", new byte[]{1}); HostConnection conn = f.connect();
                assertTrue(entered.await(2, TimeUnit.SECONDS)); Future<?> stop = stopper.submit(f.node::stop);
                AttachedSendTest.waitFor(() -> !conn.isAttached(), 3000);
                assertEquals(1, f.relay.mailbox().count(f.routeKey));
                release.countDown(); stop.get(3, TimeUnit.SECONDS);
                Field active = MaximaNode.class.getDeclaredField("mDeliveries"); active.setAccessible(true);
                assertTrue(((Map<?, ?>) active.get(f.node)).isEmpty());
            } finally { release.countDown(); }
        } finally { stopper.shutdownNow(); stopper.awaitTermination(3, TimeUnit.SECONDS); }
    }

    @Test public void aClosedLaneRejectsInsteadOfSilentlyStrandingADelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            lane(f.node, "mInboundExec").shutdown();
            f.queue("completion-test", new byte[]{1}); HostConnection conn = f.connect();
            AttachedSendTest.waitFor(() -> !conn.isAttached(), 3000);
            assertEquals(1, f.relay.mailbox().count(f.routeKey));
            Field active = MaximaNode.class.getDeclaredField("mDeliveries"); active.setAccessible(true);
            assertTrue(((Map<?, ?>) active.get(f.node)).isEmpty());
        }
    }

    @Test public void completionStateIsBoundedAndReleasedWhenTheConnectionCloses() throws Exception {
        try (Fixture f = new Fixture()) {
            HostConnection conn = f.connect();
            Method track = HostConnection.class.getDeclaredMethod("trackDelivery", CompletableFuture.class); track.setAccessible(true);
            Field pending = HostConnection.class.getDeclaredField("mPendingDeliveries"); pending.setAccessible(true);
            CompletableFuture<Void> repeated = new CompletableFuture<>();
            for (int i = 0; i < 5000; i++) track.invoke(conn, repeated);
            assertEquals("duplicate references do not grow state", 1, ((Set<?>) pending.get(conn)).size());
            for (int i = 0; i < 4096; i++) track.invoke(conn, new CompletableFuture<Void>());
            assertFalse("capacity closes without granting deletion", conn.isAttached());
            assertTrue(((Set<?>) pending.get(conn)).isEmpty());
        }
    }

    @Test public void repeatedChallengesCoalesceAndClosingCancelsOwnedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture()) {
            try {
                f.node.setMessageListener((message, id) -> { entered.countDown(); await(release); });
                f.queue("completion-test", new byte[]{1}); HostConnection conn = f.connect(); assertTrue(entered.await(2, TimeUnit.SECONDS));
                Thread.sleep(100);
                FutureTask<?> original = (FutureTask<?>) field(conn, HostConnection.class, "mMailboxAckTask"); assertNotNull(original);
                Method answer = HostConnection.class.getDeclaredMethod("answerMailboxChallenge", MaximaCTRLMessage.class); answer.setAccessible(true);
                for (int seq = 2; seq <= 100; seq++) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
                    new MiniData(f.route).writeDataStream(out); new MiniNumber(seq).writeDataStream(out);
                    MaximaCTRLMessage challenge = new MaximaCTRLMessage(40); challenge.setData(new MiniData(bytes.toByteArray())); answer.invoke(conn, challenge);
                }
                assertSame(original, field(conn, HostConnection.class, "mMailboxAckTask"));
                assertEquals(100L, field(conn, HostConnection.class, "mPendingMailboxSeq"));
                conn.close(); assertTrue(original.isCancelled());
                ThreadPoolExecutor pool = (ThreadPoolExecutor) field(null, HostConnection.class, "MAILBOX_ACK_EXEC");
                assertFalse(pool.getQueue().contains(original));
                assertTrue(((List<?>) field(conn, HostConnection.class, "mPendingMailboxDeliveries")).isEmpty());
                assertEquals(1, f.relay.mailbox().count(f.routeKey));
            } finally { release.countDown(); }
        }
    }

    @Test public void saturatedAckWorkersKeepTheCopyAndTheNextDrainCanRecover() throws Exception {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field(null, HostConnection.class, "MAILBOX_ACK_EXEC");
        CountDownLatch blocked = new CountDownLatch(8), release = new CountDownLatch(1);
        try (Fixture f = new Fixture()) {
            try {
                AttachedSendTest.waitFor(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty(), 3000);
                for (int i = 0; i < 264; i++) pool.execute(() -> { blocked.countDown(); await(release); });
                assertTrue(blocked.await(2, TimeUnit.SECONDS)); assertEquals(8, pool.getPoolSize()); assertEquals(256, pool.getQueue().size());
                CountDownLatch delivered = new CountDownLatch(1);
                f.node.setMessageListener((message, id) -> { f.store.put("messages", "saved", "saturated"); delivered.countDown(); });
                f.queue("completion-test", new byte[]{1}); HostConnection conn = f.connect(); assertTrue(delivered.await(2, TimeUnit.SECONDS));
                Thread.sleep(150); assertTrue(conn.isAttached()); assertEquals(1, f.relay.mailbox().count(f.routeKey)); assertEquals(0, f.flushes.get());
                assertNull(field(conn, HostConnection.class, "mMailboxAckTask"));
                release.countDown(); AttachedSendTest.waitFor(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty(), 3000);
                f.relay.sweepConnections(System.currentTimeMillis(), Long.MAX_VALUE, Long.MAX_VALUE);
                f.cleared(); assertEquals("saturated", new FileStore(f.dir).get("messages", "saved"));
            } finally { release.countDown(); }
        }
        AttachedSendTest.waitFor(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty(), 3000);
    }

    private final class Fixture implements AutoCloseable {
        final int port = AttachedSendTest.freePort(); final String hp = "127.0.0.1:" + port;
        final MaximaIdentity owner = identity(), sender = identity();
        final RelayServer relay = new RelayServer(identity(), port, "1.0.48");
        final MaximaNode node = new MaximaNode(owner, "1.0.48", 0);
        final File dir = tmp.newFolder(); final FileStore store = new FileStore(dir);
        final byte[] route = owner.hostKey(hp).getPublic().getEncoded(); final String routeKey = new MiniData(route).to0xString();
        final AtomicInteger flushes = new AtomicInteger(); final List<HostConnection> connections = new ArrayList<>();
        Fixture() throws Exception { store.setWriteBehind(true); node.setStore(store); node.addFlushHook(flushes::incrementAndGet); relay.start(); }
        MaximaSender.Built queue(String app, byte[] payload) throws Exception {
            MaximaSender.Built built = MaximaSender.build(sender.publicKey(), sender.keyPair().getPrivate(), route, app, payload, System.currentTimeMillis());
            assertEquals(Mailbox.Result.STORED, relay.mailbox().store(routeKey, Codec.serialise(built.unit))); return built;
        }
        HostConnection connect() throws Exception { return connect(node::handle); }
        HostConnection connect(java.util.function.Consumer<HostConnection.Inbound> receiver) throws Exception {
            HostConnection conn = new HostConnection("127.0.0.1", port, owner.hostKey(hp), "1.0.48"); connections.add(conn);
            conn.setBeforeAck(() -> flush(node)); conn.attach(5000);
            conn.startReader(new HostConnection.Sink() {
                public void onInbound(HostConnection.Inbound in) { receiver.accept(in); }
                public void onDead(String host) { }
            });
            return conn;
        }
        void cleared() throws Exception { AttachedSendTest.waitFor(() -> flushes.get() > 0 && relay.mailbox().count(routeKey) == 0, 5000); }
        @Override public void close() { for (HostConnection c : connections) c.close(); try { node.stop(); } finally { relay.stop(); } }
    }
}
