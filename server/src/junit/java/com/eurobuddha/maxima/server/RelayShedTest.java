package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Over its soft client target a relay asks a few clients to move, once each per window,
 *  naming no destination. */
public class RelayShedTest {

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

    /** A registered client that counts the SHED frames it receives. */
    static final class Client implements AutoCloseable {
        final Socket s;
        final AtomicInteger sheds = new AtomicInteger();
        Client(int port, String hostPort) throws Exception {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Frame.write(out, Frame.body(Frame.MSG_GREETING, Greeting.commsOnly(PROTO, "", 0)));
            byte[] der = MaximaIdentity.fromPhrase(Bip39.generate(24)).hostKey(hostPort).getPublic().getEncoded();
            Frame.write(out, Frame.body(Frame.MSG_MAXIMA_CTRL, MaximaCTRLMessage.id(new MiniData(der))));
            Thread t = new Thread(() -> {
                try {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    while (true) {
                        byte[] f = Frame.readOrSkip(in, 1 << 20);
                        if (f == null || Frame.typeOf(f) != Frame.MSG_MAXIMA_CTRL) continue;
                        byte[] pl = new byte[f.length - 1];
                        System.arraycopy(f, 1, pl, 0, pl.length);
                        if (MaximaCTRLMessage.fromBytes(pl).getType().getAsInt() == RelayServer.CTRL_SHED) {
                            sheds.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            t.setDaemon(true);
            t.start();
        }
        @Override public void close() throws Exception { s.close(); }
    }

    @Test
    public void overTheTargetOneClientPerExcessIsAskedOnceAndNoDestinationIsNamed() throws Exception {
        int port = freePort();
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.setShedTarget(1);
        relay.start();
        try (Client a = new Client(port, "127.0.0.1:" + port); Client b = new Client(port, "127.0.0.1:" + port)) {
            assertTrue("both registered", waitFor(() -> relay.routeCount() == 2, 5));
            long now = System.currentTimeMillis();
            relay.shedIfOverloaded(now);          // 2 routes, target 1 -> one client asked
            assertTrue(waitFor(() -> relay.shedsSent() == 1, 5));
            assertTrue("one client received the shed", waitFor(() -> a.sheds.get() + b.sheds.get() == 1, 5));
            relay.shedIfOverloaded(now + 1000);   // still over: the OTHER client is asked
            assertTrue(waitFor(() -> relay.shedsSent() == 2, 5));
            assertTrue("each client asked exactly once",
                    waitFor(() -> a.sheds.get() == 1 && b.sheds.get() == 1, 5));
            relay.shedIfOverloaded(now + 2000);   // both asked within the window: nobody again
            Thread.sleep(300);
            assertEquals(2, relay.shedsSent());
            relay.setShedTarget(0);
            relay.shedIfOverloaded(now + 3000);   // disabled
            Thread.sleep(200);
            assertEquals(2, relay.shedsSent());
        } finally {
            relay.stop();
        }
    }
}
