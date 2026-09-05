package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * The relay half of connection stickiness, in-process over loopback.
 *
 * A classic node drops any peer it has not READ from in 10 minutes. Our relay
 * used to send a quiet registered client nothing, so a stock node dropped US and
 * churned its Maxima address. And a NAT-dropped socket became a black hole the
 * relay kept pushing into. This proves the fix:
 *
 *   - the relay ANSWERS a SINGLE_PING reachability probe with a SINGLE_PONG
 *     greeting (the reference's fresh-socket check; unanswered = "unreachable");
 *   - the maintenance sweep sends a keep-alive to a quiet registered client
 *     (so the client keeps reading from us) WITHOUT dropping it;
 *   - the sweep REAPS a client that has answered nothing past the silence
 *     threshold (black-hole detection), freeing its route.
 */
public final class RelayKeepaliveTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static final String PROTO = "1.0.48";

    static MaximaIdentity idFrom(int salt) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * salt + salt);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }

    static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return c.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== RELAY KEEP-ALIVE, in-process over loopback ===\n");

        int port = freePort();
        String hostPort = "127.0.0.1:" + port;
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)),
                port, PROTO);
        relay.setPublicHost("127.0.0.1");
        relay.start();
        System.out.println("[*] relay up on " + hostPort + "\n");

        CountDownLatch stop = new CountDownLatch(1);
        MaximaNode client = new MaximaNode(idFrom(7), PROTO, 1);
        try {
            // ---- 1. reachability probe: bare SINGLE_PING -> SINGLE_PONG ----
            // Exactly what NIOManager.sendPingMessage does: fresh socket, no
            // greeting, one SINGLE_PING, expect a Greeting back.
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                s.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream());
                Frame.write(out, Frame.singlePing());
                byte[] resp = Frame.read(in);
                if (Frame.typeOf(resp) == Frame.MSG_SINGLE_PONG) {
                    byte[] pl = new byte[resp.length - 1];
                    System.arraycopy(resp, 1, pl, 0, pl.length);
                    Greeting g = Greeting.fromBytes(pl);
                    if (g.getExtraData().contains("\"welcome\":\"Maxima\"")) {
                        ok("relay answers a SINGLE_PING probe with a SINGLE_PONG greeting");
                    } else {
                        bad("SINGLE_PONG greeting malformed: " + g.getExtraData());
                    }
                } else {
                    bad("probe got frame type " + Frame.typeOf(resp) + ", expected SINGLE_PONG");
                }
            }

            // ---- attach a real client so a registered route exists ----
            client.setName("client");
            client.start(Collections.singletonList(hostPort), 15000);
            Thread pump = new Thread(() -> {
                while (stop.getCount() > 0) {
                    try { client.pump(hostPort, 1000); }
                    catch (Exception e) { if (stop.getCount() > 0) return; }
                }
            });
            pump.setDaemon(true);
            pump.start();

            if (!waitFor(() -> relay.routeCount() >= 1, 15)) {
                bad("client did not register a route");
                System.exit(1);
            }
            ok("client attached and registered a route");

            // ---- 2. keep-alive sweep writes a SINGLE_PING, does NOT drop ----
            long before = relay.keepalivesSent();
            int routesBefore = relay.routeCount();
            // keepalive threshold 0 => every registered conn is "overdue"; silence
            // threshold huge => nothing is a black hole. So: keep-alive, no reap.
            relay.sweepConnections(System.currentTimeMillis(), 0L, Long.MAX_VALUE);
            // keep-alives are written on the push pool now, not on the sweep thread
            if (waitFor(() -> relay.keepalivesSent() >= before + 1, 5)) {
                ok("sweep sent a keep-alive to the quiet registered client");
            } else {
                bad("sweep sent no keep-alive (" + before + " -> " + relay.keepalivesSent() + ")");
            }
            if (relay.routeCount() == routesBefore) {
                ok("keep-alive did NOT drop the connection");
            } else {
                bad("connection was dropped by a keep-alive sweep");
            }
            // the client must still be able to read that keep-alive without error
            if (waitFor(() -> relay.routeCount() >= 1, 10)) {
                ok("client survives the keep-alive and stays registered");
            } else {
                bad("client fell off after a keep-alive");
            }

            // ---- 2b. client-side predicates on the real attached connection ----
            // Assert with a sleep-bounded threshold rather than 0: the live pump
            // stamps mLastInbound/mLastWrite, so "> 0 ms elapsed" races it. After
            // a short quiet sleep, a small threshold is safely crossed while a
            // huge one is not - and that is exactly what reconcile() relies on.
            com.eurobuddha.maxima.core.net.HostConnection cc = client.pool().connection(hostPort);
            Thread.sleep(60);
            if (cc != null && cc.isAttached()
                    && cc.isStale(30L) && !cc.isStale(Long.MAX_VALUE)
                    && cc.needsKeepalive(30L) && !cc.needsKeepalive(Long.MAX_VALUE)) {
                ok("client isStale/needsKeepalive track elapsed time correctly");
            } else {
                bad("client predicates wrong on the attached connection");
            }

            // ---- 2c. check-connect: a self-addressed probe round-trips ----
            // maintain() runs auditHosts(), which sends a probe to our own
            // per-host key through the relay; the relay routes it back down our
            // pump connection, proving the host actually RELAYS (not just answers
            // keep-alives). The pump thread delivers it and marks the host.
            client.maintain(5000);
            if (waitFor(() -> client.isHostVerified(hostPort), 30)) {
                ok("check-connect: self-addressed probe relayed back, host verified");
            } else {
                bad("check-connect probe did not round-trip (host unverified)");
            }
            if (relay.routeCount() >= 1) {
                ok("a verified host is NOT dropped by the audit");
            } else {
                bad("verified host was dropped");
            }

            // ---- 3. silence sweep REAPS a black-hole route ----
            // silence threshold 0 => the client (which has sent nothing this
            // instant) is treated as a dead black hole and reaped.
            relay.sweepConnections(System.currentTimeMillis(), Long.MAX_VALUE, 0L);
            if (waitFor(() -> relay.routeCount() == 0, 10)) {
                ok("sweep reaps a silent (black-hole) client and frees its route");
            } else {
                bad("silent client was not reaped (routeCount=" + relay.routeCount() + ")");
            }

        } finally {
            stop.countDown();
            try { client.stop(); } catch (Exception ignored) { }
            relay.stop();
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Relay keep-alive + black-hole reap hold.");
    }
}
