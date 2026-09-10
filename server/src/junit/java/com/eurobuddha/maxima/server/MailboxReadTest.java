package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;

import static org.junit.Assert.*;

/** Real record read failures and signed ACKs, with frames captured on a synthetic transport. */
public class MailboxReadTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void anUnreadableFirstRecordCannotBeClearedByLaterMail() throws Exception { recover(0); }
    @Test public void anUnreadableMiddleRecordOnlyAllowsAcknowledgingTheDeliveredPrefix() throws Exception { recover(1); }

    @Test public void aDelayedSignedAcknowledgementCannotClearNewMail() throws Exception {
        MaximaIdentity owner = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity sender = MaximaIdentity.fromPhrase(Bip39.generate(24));
        String key = owner.publicKeyHex();
        RelayServer relay = new RelayServer(sender, 0, "1.0.48");
        relay.setStore(new FileStore(tmp.newFolder()));
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        try (Socket socket = new Socket() {
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public OutputStream getOutputStream() { return sent; }
        }) {
            Class<?> connType = Class.forName(RelayServer.class.getName() + "$Conn");
            Constructor<?> ctor = connType.getDeclaredConstructor(RelayServer.class, Socket.class);
            ctor.setAccessible(true); Object conn = ctor.newInstance(relay, socket);
            Method drain = RelayServer.class.getDeclaredMethod("drainMailbox", connType, String.class);
            Method ack = RelayServer.class.getDeclaredMethod("handleMailboxAck", connType, MaximaCTRLMessage.class);
            drain.setAccessible(true); ack.setAccessible(true);
            long old = 0;
            for (int i = 1; i <= 2; i++) {
                byte[] unit = Codec.serialise(MaximaSender.build(sender.publicKey(), sender.keyPair().getPrivate(),
                        owner.publicKey(), "sequence-test", new byte[]{(byte) i}, System.currentTimeMillis()).unit);
                assertEquals(Mailbox.Result.STORED, relay.mailbox().store(key, unit));
                if (old > 0) {
                    acknowledgeSequence(relay, conn, ack, owner, old);
                    assertEquals("an old signed ACK retains new mail", 1, relay.mailbox().count(key));
                }
                long sequence = relay.mailbox().highestSequence(key);
                assertTrue(sequence > old);
                sent.reset(); drain.invoke(relay, conn, key);
                List<byte[]> delivered = acknowledgeCaptured(relay, conn, ack, owner, sent, sequence);
                assertEquals(1, delivered.size()); assertArrayEquals(unit, delivered.get(0));
                assertEquals(0, relay.mailbox().count(key)); old = sequence;
            }
        } finally { relay.stop(); }
    }

    private void recover(int blockedIndex) throws Exception {
        MaximaIdentity owner = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity sender = MaximaIdentity.fromPhrase(Bip39.generate(24));
        byte[] route = owner.publicKey(); String routeKey = new MiniData(route).to0xString();
        Path dir = tmp.newFolder("relay").toPath();
        FileStore store = new FileStore(dir.toFile());
        RelayServer relay = new RelayServer(sender, 0, "1.0.48");
        relay.setStore(store);
        List<byte[]> units = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MaximaSender.Built built = MaximaSender.build(sender.publicKey(), sender.keyPair().getPrivate(),
                    route, "read-test", new byte[]{(byte) i}, System.currentTimeMillis());
            byte[] unit = Codec.serialise(built.unit); units.add(unit);
            assertEquals(Mailbox.Result.STORED, relay.mailbox().store(routeKey, unit));
        }
        String record = new ArrayList<>(store.listBytes("mailitems").keySet()).stream()
                .filter(k -> k.split("\\|", 4)[1].equals(Integer.toString(blockedIndex + 1)))
                .findFirst().get();
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(record.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder filename = new StringBuilder();
        for (byte b : hash) filename.append(String.format("%02x", b));
        Path target = dir.resolve("mailitems.d").resolve(filename.toString());
        Path saved = tmp.newFolder("saved").toPath().resolve("record");
        Files.move(target, saved); Files.createDirectory(target);

        // Same captured-frame technique as MailboxDurabilityTest, at the relay end.
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        try (Socket socket = new Socket() {
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public OutputStream getOutputStream() { return sent; }
        }) {
            Class<?> connType = Class.forName(RelayServer.class.getName() + "$Conn");
            Constructor<?> ctor = connType.getDeclaredConstructor(RelayServer.class, Socket.class);
            ctor.setAccessible(true); Object conn = ctor.newInstance(relay, socket);
            Method drain = RelayServer.class.getDeclaredMethod("drainMailbox", connType, String.class);
            Method ack = RelayServer.class.getDeclaredMethod("handleMailboxAck", connType, MaximaCTRLMessage.class);
            drain.setAccessible(true); ack.setAccessible(true);

            drain.invoke(relay, conn, routeKey);
            List<byte[]> delivered = acknowledgeCaptured(relay, conn, ack, owner, sent, blockedIndex);
            assertEquals("a valid ACK must retain the unread record and everything after it",
                    3 - blockedIndex, relay.mailbox().count(routeKey));
            assertEquals(blockedIndex, delivered.size());
            for (int i = 0; i < blockedIndex; i++) assertArrayEquals(units.get(i), delivered.get(i));

            Files.delete(target); Files.move(saved, target);
            sent.reset(); drain.invoke(relay, conn, routeKey);
            delivered = acknowledgeCaptured(relay, conn, ack, owner, sent, 3);
            assertEquals("recovery delivers every retained item", 3 - blockedIndex, delivered.size());
            for (int i = blockedIndex; i < 3; i++) assertArrayEquals(units.get(i), delivered.get(i - blockedIndex));
            assertEquals(0, relay.mailbox().count(routeKey));
            assertTrue(new FileStore(dir.toFile()).listBytes("mailitems").isEmpty());
        } finally { relay.stop(); }
    }

    private static List<byte[]> acknowledgeCaptured(RelayServer relay, Object conn, Method ack,
            MaximaIdentity owner, ByteArrayOutputStream sent, long expectedSeq) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(sent.toByteArray()));
        List<byte[]> delivered = new ArrayList<>(); long challengeSeq = 0; int challenges = 0;
        while (in.available() > 0) {
            byte[] frame = Frame.readOrSkip(in, 1 << 20); assertNotNull(frame);
            byte[] payload = Arrays.copyOfRange(frame, 1, frame.length);
            if (Frame.typeOf(frame) == Frame.MSG_MAXIMA_TXPOW) { delivered.add(payload); continue; }
            assertEquals(Frame.MSG_MAXIMA_CTRL, Frame.typeOf(frame));
            MaximaCTRLMessage challenge = MaximaCTRLMessage.fromBytes(payload);
            assertEquals(RelayServer.CTRL_MAILBOX_INFO, challenge.getType().getAsInt());
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(challenge.getData().getBytes()));
            MiniData key = MiniData.readFromStream(data); challengeSeq = MiniNumber.readFromStream(data).getAsLong();
            assertArrayEquals(owner.publicKey(), key.getBytes()); challenges++;
            // Answer what the relay actually asked, exactly like the production clients.
            acknowledgeSequence(relay, conn, ack, owner, challengeSeq);
        }
        assertEquals("only the contiguous delivered prefix may be challenged", expectedSeq, challengeSeq);
        assertEquals(expectedSeq == 0 ? 0 : 1, challenges);
        return delivered;
    }

    private static void acknowledgeSequence(RelayServer relay, Object conn, Method ack,
            MaximaIdentity owner, long sequence) throws Exception {
        MiniData key = new MiniData(owner.publicKey());
        byte[] signature = MaximaCrypto.sign(owner.keyPair().getPrivate(),
                RelayServer.mailboxAckCanonical(key.getBytes(), sequence));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        key.writeDataStream(out); new MiniNumber(sequence).writeDataStream(out);
        new MiniData(signature).writeDataStream(out); out.flush();
        MaximaCTRLMessage reply = new MaximaCTRLMessage(RelayServer.CTRL_MAILBOX_ACK);
        reply.setData(new MiniData(bytes.toByteArray())); ack.invoke(relay, conn, reply);
    }
}
