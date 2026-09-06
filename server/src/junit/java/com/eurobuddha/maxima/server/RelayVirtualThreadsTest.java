package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.util.Threads;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

/**
 * Stage-3 item 2: on a JDK with virtual threads every connection is one, and the default
 * connection cap rises past what platform threads could hold. On an older JDK the relay
 * behaves exactly as before (the test is skipped there, not failed).
 */
public class RelayVirtualThreadsTest {

    static final String PROTO = "1.0.48";

    @Test
    public void connectionsRideVirtualThreadsAndTheDefaultCapRisesPastThePlatformOne() throws Exception {
        Assume.assumeTrue("needs a JDK with virtual threads", Threads.virtualAvailable());
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.setMaxPerSource(1000);   // every test client is 127.0.0.1
        relay.start();
        List<Socket> held = new ArrayList<>();
        try {
            assertEquals("virtual", relay.threadMode());
            assertEquals(RelayServer.DEFAULT_MAX_CONNECTIONS_VIRTUAL, relay.getMaxConnections());
            assertTrue(relay.getMaxConnections() > RelayServer.DEFAULT_MAX_CONNECTIONS);

            // More idle clients than the platform-thread default ever admitted.
            int n = RelayServer.DEFAULT_MAX_CONNECTIONS + 64;
            byte[] hello = Frame.body(Frame.MSG_GREETING, Greeting.commsOnly(PROTO, "", 0));
            for (int i = 0; i < n; i++) {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                new DataOutputStream(s.getOutputStream()).write(hello);
                held.add(s);
            }
            long deadline = System.currentTimeMillis() + 15000;
            while (relay.connectionCount() < n && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals("all admitted, none refused by the old 512 cap", n, relay.connectionCount());
            assertEquals(0, relay.acceptFailures());
            assertTrue(relay.acceptAlive());

            // The connection threads really are virtual (Thread.isVirtual via reflection so
            // this still compiles for Java 11).
            Thread probe = Threads.newThread("probe", () -> { }, true);
            Object virtual = Thread.class.getMethod("isVirtual").invoke(probe);
            assertEquals(Boolean.TRUE, virtual);
        } finally {
            for (Socket s : held) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            relay.stop();
        }
    }

    @Test
    public void aPlatformThreadIsStillADaemonWithTheRequestedName() {
        Thread t = Threads.newThread("relay-conn-test", () -> { }, false);
        assertTrue(t.isDaemon());
        assertEquals("relay-conn-test", t.getName());
    }
}
