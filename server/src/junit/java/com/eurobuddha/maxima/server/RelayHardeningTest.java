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
import java.util.Collections;
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

    @Test
    public void aPeerThatStopsReadingIsReapedAndItsWriterFreed() throws Exception {
        int port = freePort();
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.setPublicHost("127.0.0.1");
        relay.start();
        try (Socket s = new Socket()) {
            s.setReceiveBufferSize(1024);   // tiny window: the relay's replies back up fast
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            // Greet, then fire probes and NEVER read the pongs. Each pong is a greeting with
            // a peer list, so a few thousand fill the relay's send buffer and its serve thread
            // blocks inside Conn.write.
            Frame.write(out, Frame.body(Frame.MSG_GREETING, Greeting.commsOnly(PROTO, "", 0)));
            List<String> peers = new ArrayList<>();
            for (int i = 0; i < 40; i++) peers.add("10.0." + i + ".1:9501");
            for (int i = 0; i < 6000; i++) {
                Frame.write(out, Frame.singlePing());
            }
            assertTrue("relay accepted the connection", waitFor(() -> relay.connectionCount() == 1, 5));

            // The sweep, "61 seconds later": the writer has been blocked longer than the
            // stall window, so the connection is reaped and the stall counted.
            long later = System.currentTimeMillis() + RelayServer.WRITE_STALL_MS + 1000;
            assertTrue("writer stalled", waitFor(() -> {
                relay.sweepConnections(later, Long.MAX_VALUE, Long.MAX_VALUE);
                return relay.writeStalls() >= 1;
            }, 10));
            assertTrue("stalled connection reaped", waitFor(() -> relay.connectionCount() == 0, 5));
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
