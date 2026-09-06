package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.net.HostConnection;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Stage-3 item 2: a message to a relay we are attached to rides the attached connection
 * (one frame + one ack) instead of a fresh TCP connection and a fresh relay thread per
 * message. The relay's handling is unchanged - only the client's transport is.
 */
public class AttachedSendTest {

    static final String PROTO = "1.0.48";

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static final class Collector implements HostConnection.Sink {
        final List<byte[]> got = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger dead = new AtomicInteger();

        @Override
        public void onInbound(HostConnection.Inbound zIn) {
            got.add(zIn.message.mData.getBytes());
        }

        @Override
        public void onDead(String zHostPort) {
            dead.incrementAndGet();
        }
    }

    @Test
    public void aSendToAnAttachedRelayUsesTheAttachmentNotAFreshSocket() throws Exception {
        int port = freePort();
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.start();
        MaximaIdentity a = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity b = MaximaIdentity.fromPhrase(Bip39.generate(24));
        HostConnection ca = new HostConnection("127.0.0.1", port, a.keyPair(), PROTO);
        HostConnection cb = new HostConnection("127.0.0.1", port, b.keyPair(), PROTO);
        try {
            ca.attach(5000);
            cb.attach(5000);
            Collector sinkA = new Collector();
            ca.startReader(sinkA);
            cb.startReader(new Collector());
            waitFor(() -> relay.connectionCount() == 2, 5000);
            long freshBefore = MaximaSender.FRESH_SOCKETS.get();

            MaximaSender.Built built = MaximaSender.build(b.publicKey(), b.keyPair().getPrivate(),
                    ca.routingKey(), "chat", "over the attachment".getBytes(), System.currentTimeMillis());
            MaximaSender.Result r = MaximaSender.send("127.0.0.1", port, built.unit, built.msgid,
                    3000, 5000, (host, p, unit, msgid, readMs) -> cb.send(unit, msgid, readMs));

            assertNotNull(r);
            assertEquals("relay acked the send", Frame.RESPONSE_OK, r.status);
            waitFor(() -> !sinkA.got.isEmpty(), 5000);
            assertEquals("over the attachment", new String(sinkA.got.get(0)));
            assertEquals("no fresh socket was opened", freshBefore, MaximaSender.FRESH_SOCKETS.get());
            assertEquals(1, cb.attachedSends());
            assertEquals("still exactly the two attachments on the relay", 2, relay.connectionCount());
            assertTrue("the sending link is intact", cb.isAttached());
        } finally {
            ca.close();
            cb.close();
            relay.stop();
        }
    }

    @Test
    public void concurrentSendsOverOneAttachmentAreEachAckedAndAllDelivered() throws Exception {
        int port = freePort();
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.start();
        MaximaIdentity a = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity b = MaximaIdentity.fromPhrase(Bip39.generate(24));
        HostConnection ca = new HostConnection("127.0.0.1", port, a.keyPair(), PROTO);
        HostConnection cb = new HostConnection("127.0.0.1", port, b.keyPair(), PROTO);
        try {
            ca.attach(5000);
            cb.attach(5000);
            Collector sinkA = new Collector();
            ca.startReader(sinkA);
            cb.startReader(new Collector());
            waitFor(() -> relay.connectionCount() == 2, 5000);

            int n = 24;
            AtomicInteger ok = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final int k = i;
                new Thread(() -> {
                    try {
                        MaximaSender.Built built = MaximaSender.build(b.publicKey(),
                                b.keyPair().getPrivate(), ca.routingKey(), "chat",
                                ("msg " + k).getBytes(), System.currentTimeMillis());
                        MaximaSender.Result r = cb.send(built.unit, built.msgid, 5000);
                        if (r != null && r.status == Frame.RESPONSE_OK) {
                            ok.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            assertTrue(done.await(20, TimeUnit.SECONDS));
            assertEquals("every send got its own OK", n, ok.get());
            waitFor(() -> sinkA.got.size() == n, 5000);
            assertEquals(n, cb.attachedSends());
            assertTrue(cb.isAttached());
        } finally {
            ca.close();
            cb.close();
            relay.stop();
        }
    }

    /** A host that greets and registers but NEVER acks: the ledger cannot be trusted after a
     *  timeout, so the link is closed and the pool will re-attach with a clean one. */
    @Test
    public void anAckTimeoutClosesTheLinkInsteadOfDesyncingTheLedger() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                Frame.readOrSkip(in, 65536);
                Frame.write(out, Frame.body(Frame.MSG_GREETING, Greeting.commsOnly(PROTO, "", 0)));
                while (Frame.readOrSkip(in, 65536) != null) {
                    // swallow everything, ack nothing
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        MaximaIdentity a = MaximaIdentity.fromPhrase(Bip39.generate(24));
        HostConnection c = new HostConnection("127.0.0.1", ss.getLocalPort(), a.keyPair(), PROTO);
        try {
            c.attach(5000);
            Collector sink = new Collector();
            c.startReader(sink);
            MaximaSender.Built built = MaximaSender.build(a.publicKey(), a.keyPair().getPrivate(),
                    a.publicKey(), "chat", "x".getBytes(), System.currentTimeMillis());
            long t0 = System.currentTimeMillis();
            MaximaSender.Result r = c.send(built.unit, built.msgid, 700);
            assertNotNull(r);
            assertEquals(-1, r.status);
            assertTrue("gave up around the leash", System.currentTimeMillis() - t0 < 5000);
            assertFalse("the link was dropped", c.isAttached());
            waitFor(() -> sink.dead.get() == 1, 5000);
            assertEquals("a send on a closed link is refused, not queued", null, c.send(built.unit, built.msgid, 700));
        } finally {
            c.close();
            ss.close();
        }
    }

    static void waitFor(java.util.function.BooleanSupplier zCond, long zMs) throws Exception {
        long deadline = System.currentTimeMillis() + zMs;
        while (!zCond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + zMs + " ms");
            }
            Thread.sleep(25);
        }
    }
}
