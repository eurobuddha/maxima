package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The two silent relay deaths found in the scalability survey, pinned:
 *  - a peer that stops READING (full kernel buffer, vanished NAT mapping) used to block the
 *    thread writing to it forever - when that was the maintain thread, keep-alives, sweeps
 *    and flushes stopped for everyone. The sweep now closes a socket whose write has stalled
 *    past {@link RelayServer#WRITE_STALL_MS}, which frees the writer;
 *  - admission is guarded so nothing thrown while spawning a connection thread can end the
 *    accept loop, and a few hundred idle attachments are simply held.
 */
public class RelayHardeningTest {

    static final String PROTO = "1.0.48";

    static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return c.getAsBoolean();
    }

    /**
     * The production stall: a client registers and proves its route, the relay drains several
     * MB of held mail to it on the push pool, and the client never reads (a phone that vanished
     * behind NAT). Loopback buffers on this platform auto-tune up to 4 MB, so the drain must be
     * larger than that - ~6 MB of held ciphertext, well under the 8 MB per-peer quota.
     */
    @Test
    public void aPeerThatStopsReadingIsReapedAndItsWriterFreed() throws Exception {
        int port = freePort();
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.setPublicHost("127.0.0.1");
        relay.start();
        MaximaIdentity client = MaximaIdentity.fromPhrase(Bip39.generate(24));
        String hostPort = "127.0.0.1:" + port;
        java.security.KeyPair route = client.hostKey(hostPort);
        byte[] routeDer = route.getPublic().getEncoded();
        String routeKey = new com.eurobuddha.maxima.core.codec.MiniData(routeDer).to0xString();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            s.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
            Frame.write(out, Frame.body(Frame.MSG_GREETING, Greeting.commsOnly(PROTO, "", 0)));
            // register the route (the relay now holds mail for this key), then hold 6 MB for it
            Frame.write(out, Frame.body(Frame.MSG_MAXIMA_CTRL,
                    com.eurobuddha.maxima.core.msg.MaximaCTRLMessage.id(
                            new com.eurobuddha.maxima.core.codec.MiniData(routeDer))));
            assertTrue("route registered", waitFor(() -> relay.routeCount() == 1, 5));
            byte[] big = new byte[250_000];
            for (int i = 0; i < 26; i++) {
                big[0] = (byte) i;   // distinct content -> distinct ids
                com.eurobuddha.maxima.core.msg.MaximaPackage pkg =
                        new com.eurobuddha.maxima.core.msg.MaximaPackage(
                                new com.eurobuddha.maxima.core.codec.MiniData(routeDer),
                                new com.eurobuddha.maxima.core.codec.MiniData(big));
                byte[] unit = com.eurobuddha.maxima.core.codec.Codec.serialise(
                        com.eurobuddha.maxima.core.msg.MaxTxPoW.create(pkg, System.currentTimeMillis()));
                assertEquals(com.eurobuddha.maxima.core.mailbox.Mailbox.Result.STORED,
                        relay.mailbox().store(routeKey, unit));
            }
            // read frames until the possession probe (MAILBOX_INFO seq 0), answer it signed -
            // that is the last thing this client ever reads.
            long deadline = System.currentTimeMillis() + 10000;
            boolean answered = false;
            while (!answered && System.currentTimeMillis() < deadline) {
                byte[] f = Frame.readOrSkip(in, 1024 * 1024);
                if (f == null || Frame.typeOf(f) != Frame.MSG_MAXIMA_CTRL) {
                    continue;
                }
                byte[] pl = new byte[f.length - 1];
                System.arraycopy(f, 1, pl, 0, pl.length);
                com.eurobuddha.maxima.core.msg.MaximaCTRLMessage ctrl =
                        com.eurobuddha.maxima.core.msg.MaximaCTRLMessage.fromBytes(pl);
                if (ctrl.getType().getAsInt() != RelayServer.CTRL_MAILBOX_INFO) {
                    continue;
                }
                byte[] sig = com.eurobuddha.maxima.core.crypto.MaximaCrypto.sign(
                        route.getPrivate(), RelayServer.mailboxAckCanonical(routeDer, 0));
                java.io.ByteArrayOutputStream ab = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream ad = new java.io.DataOutputStream(ab);
                new com.eurobuddha.maxima.core.codec.MiniData(routeDer).writeDataStream(ad);
                new com.eurobuddha.maxima.core.codec.MiniNumber(0).writeDataStream(ad);
                new com.eurobuddha.maxima.core.codec.MiniData(sig).writeDataStream(ad);
                ad.flush();
                com.eurobuddha.maxima.core.msg.MaximaCTRLMessage ack =
                        new com.eurobuddha.maxima.core.msg.MaximaCTRLMessage(RelayServer.CTRL_MAILBOX_ACK);
                ack.setData(new com.eurobuddha.maxima.core.codec.MiniData(ab.toByteArray()));
                Frame.write(out, Frame.body(Frame.MSG_MAXIMA_CTRL, ack));
                answered = true;
            }
            assertTrue("possession probe answered", answered);

            // The drain (on the push pool) now writes 6 MB to a socket nobody reads. Prove the
            // writer is REALLY blocked: one write's start time stays put for longer than any
            // loopback write could take.
            assertTrue("a write is in progress", waitFor(() -> relay.oldestWriteStartedAt() > 0, 10));
            long started = relay.oldestWriteStartedAt();
            Thread.sleep(2000);
            assertEquals("the same write is still blocked 2 s later", started, relay.oldestWriteStartedAt());
            assertEquals(0, relay.writeStalls());

            // Not yet stalled by the relay's clock: a sweep "now" must leave it alone...
            relay.sweepConnections(System.currentTimeMillis(), Long.MAX_VALUE, Long.MAX_VALUE);
            assertEquals(1, relay.connectionCount());
            // ...and one past the stall window reaps it, counts it, and frees the writer.
            relay.sweepConnections(started + RelayServer.WRITE_STALL_MS + 1, Long.MAX_VALUE, Long.MAX_VALUE);
            assertEquals(1, relay.writeStalls());
            assertTrue("stalled connection reaped", waitFor(() -> relay.connectionCount() == 0, 5));
            assertTrue("writer freed", waitFor(() -> relay.oldestWriteStartedAt() == 0, 5));
        } finally {
            relay.stop();
        }
    }

    @Test
    public void hundredsOfIdleAttachmentsAreHeldAndAcceptStaysAlive() throws Exception {
        int port = freePort();
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.setMaxConnections(600);
        relay.setMaxPerSource(600);
        relay.start();
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < 300; i++) {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                held.add(s);
            }
            assertTrue("300 held", waitFor(() -> relay.connectionCount() == 300, 10));
            assertTrue(relay.acceptAlive());
            assertEquals(0, relay.acceptFailures());
        } finally {
            for (Socket s : held) {
                try { s.close(); } catch (Exception ignored) { }
            }
            relay.stop();
        }
    }
}
