package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.net.*;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MailboxDurabilityTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static MaximaIdentity identity() throws Exception { return MaximaIdentity.fromPhrase(Bip39.generate(24)); }

    private static MaximaCTRLMessage challenge(byte[] key, long seq) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        new MiniData(key).writeDataStream(out); new MiniNumber(seq).writeDataStream(out); out.flush();
        MaximaCTRLMessage ctrl = new MaximaCTRLMessage(40); ctrl.setData(new MiniData(bytes.toByteArray())); return ctrl;
    }
    private static void answer(HostConnection conn, MaximaCTRLMessage challenge) throws Exception {
        Method method = HostConnection.class.getDeclaredMethod("answerMailboxChallenge", MaximaCTRLMessage.class);
        method.setAccessible(true); method.invoke(conn, challenge);
        Field taskField = HostConnection.class.getDeclaredField("mMailboxAckTask"); taskField.setAccessible(true);
        Future<?> task = (Future<?>) taskField.get(conn);
        if (task != null) task.get(2, TimeUnit.SECONDS);
    }
    private static ByteArrayOutputStream capture(HostConnection conn) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Field out = HostConnection.class.getDeclaredField("mOut"); out.setAccessible(true); out.set(conn, new DataOutputStream(bytes));
        return bytes;
    }

    @Test public void aFailedFlushCannotWriteDeletionPermission() throws Exception {
        MaximaIdentity owner = identity();
        try (HostConnection conn = new HostConnection("127.0.0.1", 0, owner.keyPair(), "1.0.48")) {
            ByteArrayOutputStream sent = capture(conn);
            conn.setBeforeAck(() -> { throw new IllegalStateException("synthetic disk failure"); });
            answer(conn, challenge(conn.routingKey(), 1));
            assertEquals("no signed ACK frame", 0, sent.size());
        }
    }

    @Test public void possessionStillWorksWithoutGrantingDeletionWhenStorageIsUnavailable() throws Exception {
        MaximaIdentity owner = identity(); AtomicInteger flushes = new AtomicInteger();
        try (HostConnection conn = new HostConnection("127.0.0.1", 0, owner.keyPair(), "1.0.48")) {
            ByteArrayOutputStream sent = capture(conn);
            conn.setBeforeAck(() -> { flushes.incrementAndGet(); throw new IllegalStateException("disk unavailable"); });
            answer(conn, challenge(conn.routingKey(), 0));
            assertEquals("ownership proof needs no disk write", 0, flushes.get());
            byte[] frame = Frame.readOrSkip(new DataInputStream(new ByteArrayInputStream(sent.toByteArray())), 65536);
            assertEquals(Frame.MSG_MAXIMA_CTRL, Frame.typeOf(frame));
            MaximaCTRLMessage ack = Codec.deserialise(new MaximaCTRLMessage(), java.util.Arrays.copyOfRange(frame, 1, frame.length));
            assertEquals(41, ack.getType().getAsInt());
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(ack.getData().getBytes()));
            MiniData key = MiniData.readFromStream(in); long seq = MiniNumber.readFromStream(in).getAsLong(); MiniData signature = MiniData.readFromStream(in);
            assertEquals(0, seq);
            assertArrayEquals(conn.routingKey(), key.getBytes());
            assertTrue(MaximaCrypto.verify(key.getBytes(), RelayServer.mailboxAckCanonical(key.getBytes(), seq), signature.getBytes()));
        }
    }

    @Test public void wrongKeyAndNegativeSequenceCannotTriggerAFlushOrAck() throws Exception {
        MaximaIdentity owner = identity(); AtomicInteger flushes = new AtomicInteger();
        try (HostConnection conn = new HostConnection("127.0.0.1", 0, owner.keyPair(), "1.0.48")) {
            ByteArrayOutputStream sent = capture(conn); conn.setBeforeAck(flushes::incrementAndGet);
            answer(conn, challenge(new byte[]{1, 2, 3}, 1));
            answer(conn, challenge(conn.routingKey(), -1));
            assertEquals(0, flushes.get()); assertEquals(0, sent.size());
        }
    }

    @Test public void heldMailSurvivesAnApplicationStoreFailureAndDrainsAfterRecovery() throws Exception { recover(false, false); }
    @Test public void heldMailSurvivesANodeStoreFailureAndDrainsAfterRecovery() throws Exception { recover(true, false); }
    @Test public void heldMailSurvivesAnApplicationReadFailureAndDrainsAfterRecovery() throws Exception { recover(false, true); }

    @Test public void interruptionBeforeOrDuringTheFlushDoesNotGrantDeletion() throws Exception {
        MaximaIdentity owner = identity();
        try (HostConnection conn = new HostConnection("127.0.0.1", 0, owner.keyPair(), "1.0.48")) {
            ByteArrayOutputStream sent = capture(conn);
            try {
                Thread.currentThread().interrupt();
                answer(conn, challenge(conn.routingKey(), 1));
                assertEquals(0, sent.size()); assertTrue(Thread.currentThread().isInterrupted());
                Thread.interrupted();
                java.util.concurrent.atomic.AtomicBoolean workerInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
                conn.setBeforeAck(() -> { Thread.currentThread().interrupt(); workerInterrupted.set(Thread.currentThread().isInterrupted()); });
                answer(conn, challenge(conn.routingKey(), 1));
                assertEquals(0, sent.size()); assertTrue(workerInterrupted.get());
            } finally { Thread.interrupted(); }
        }
    }

    private void recover(boolean breakNodeStore, boolean breakRead) throws Exception {
        int port = AttachedSendTest.freePort(); String hp = "127.0.0.1:" + port;
        RelayServer relay = new RelayServer(identity(), port, "1.0.48");
        MaximaIdentity owner = identity(), sender = identity();
        MaximaNode node = new MaximaNode(owner, "1.0.48", 0);
        Path nodeDir = tmp.newFolder("node").toPath(), chatDir = tmp.newFolder("chat").toPath();
        FileStore nodeStore = new FileStore(nodeDir.toFile()), chatStore = new FileStore(chatDir.toFile());
        nodeStore.setWriteBehind(true); chatStore.setWriteBehind(true); node.setStore(nodeStore);
        nodeStore.put("settings", "test", "pending");
        // Flush-failure cases load valid initial state before the obstruction. The read
        // failure case deliberately leaves this collection cold until inbound delivery.
        if (!breakRead) chatStore.all("messages");
        CountDownLatch delivered = new CountDownLatch(1); AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch attempted = new CountDownLatch(1); AtomicInteger attempts = new AtomicInteger();
        node.setMessageListener((message, id) -> {
            attempts.incrementAndGet(); attempted.countDown();
            chatStore.put("messages", "test", new String(message.mData.getBytes(), StandardCharsets.UTF_8));
            deliveries.incrementAndGet(); delivered.countDown();
        });
        node.addFlushHook(chatStore::flush);
        Path blocked = Files.createDirectory((breakNodeStore ? nodeDir : chatDir).resolve(breakNodeStore ? "settings.tsv" : "messages.tsv"));
        Files.write(blocked.resolve("blocker"), new byte[]{1});
        byte[] route = owner.hostKey(hp).getPublic().getEncoded(); String routeKey = new MiniData(route).to0xString();
        MaximaSender.Built built = MaximaSender.build(sender.publicKey(), sender.keyPair().getPrivate(), route,
                "durability-test", "held message".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        assertEquals(Mailbox.Result.STORED, relay.mailbox().store(routeKey, Codec.serialise(built.unit)));
        relay.start();
        try {
            assertTrue(node.pool().attachOne(hp, 5000));
            if (breakRead) {
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                AttachedSendTest.waitFor(() -> node.pool().activeCount() == 0, 5000);
                assertEquals("failed read cannot become successful application delivery", 0, deliveries.get());
            } else {
                assertTrue("seq-zero possession still permits delivery", delivered.await(5, TimeUnit.SECONDS));
            }
            // The byte-capture tests pin the absent ACK directly; this checks the real relay's
            // retained item after the local connection has had time to process its challenge.
            Thread.sleep(250);
            assertEquals("disk failure retains the relay copy", 1, relay.mailbox().count(routeKey));
            Files.delete(blocked.resolve("blocker")); Files.delete(blocked);
            if (breakRead) assertTrue(node.pool().attachOne(hp, 5000));
            // lastDrain starts at zero: the first periodic drain is already due. Advancing
            // the clock could falsely reap a healthy write on the newly attached socket.
            relay.sweepConnections(System.currentTimeMillis(), Long.MAX_VALUE, Long.MAX_VALUE);
            AttachedSendTest.waitFor(() -> relay.mailbox().count(routeKey) == 0, 5000);
            assertEquals("held message", new FileStore(chatDir.toFile()).get("messages", "test"));
            assertEquals("pending", new FileStore(nodeDir.toFile()).get("settings", "test"));
            assertEquals("successful application delivery occurs once", 1, deliveries.get());
            assertEquals("only failed application delivery is retried", breakRead ? 2 : 1, attempts.get());
        } finally {
            if (Files.isDirectory(blocked)) { Files.deleteIfExists(blocked.resolve("blocker")); Files.delete(blocked); }
            node.stop(); relay.stop();
        }
    }
}
