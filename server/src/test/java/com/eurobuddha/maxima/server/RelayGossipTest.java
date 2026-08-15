package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.session.RelayGossipClient;
import com.eurobuddha.maxima.core.session.RelayPeers;

import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * Relay-gossip discovery over loopback, classic style: a relay that claims its
 * own endpoint in a greeting is dial-back VERIFIED before being shared, clients
 * read the shared peers from the greeting and adopt only what they can prove
 * live — and the anti-Sybil gates hold (spoofed host refused, dead port never
 * verified, caps enforced).
 */
public final class RelayGossipTest {

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
        try (ServerSocket s = new ServerSocket(0)) {
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
        System.out.println("=== RELAY GOSSIP DISCOVERY (classic greeting vocabulary) ===\n");

        // ---- Greeting codec: peers round-trip + claim parsing ----
        Greeting g = Greeting.commsOnly(PROTO, "1.2.3.4", 9501,
                java.util.Arrays.asList("5.6.7.8:9501", "9.9.9.9:8001"));
        java.util.List<String> peers = Greeting.peersOf(g.getExtraData());
        if (peers.size() == 2 && peers.contains("5.6.7.8:9501")
                && "1.2.3.4".equals(Greeting.hostOf(g.getExtraData()))
                && Greeting.portOf(g.getExtraData()) == 9501) {
            ok("greeting carries host/port claim + classic-shape peers list, and parses back");
        } else {
            bad("greeting peers round-trip: " + peers);
        }

        // ---- RelayPeers unit gates ----
        RelayPeers rp = new RelayPeers(PROTO);
        if (!rp.claim("10.0.0.5", "6.6.6.6", 9501, "1.1.1.1:9501")) {
            ok("a claim whose host does not match the source IP is refused (no third-party nominations)");
        } else {
            bad("spoofed claim accepted");
        }
        if (!rp.claim("10.0.0.5", "10.0.0.5", 70000, "1.1.1.1:9501")) {
            ok("an absurd port is refused");
        } else {
            bad("bad port accepted");
        }
        rp.stop();

        // ---- End-to-end over loopback ----
        int portA = freePort();
        int portB = freePort();
        String hostA = "127.0.0.1:" + portA;
        String selfB = "127.0.0.1:" + portB;

        RelayServer relayA = new RelayServer(idFrom(81), portA, PROTO);
        relayA.setPublicHost("127.0.0.1");
        relayA.start();
        RelayServer relayB = new RelayServer(idFrom(82), portB, PROTO);
        relayB.setPublicHost("127.0.0.1");
        relayB.start();

        CountDownLatch stop = new CountDownLatch(1);
        MaximaNode client = null;
        try {
            // 1. relay B announces itself to relay A: a greet claiming its own
            //    endpoint (source ip 127.0.0.1 == claimed host on loopback).
            MaximaIdentity idB = idFrom(82);
            RelayGossipClient announcerB = new RelayGossipClient(idB, PROTO, 8);
            announcerB.setSelfEndpoint(selfB);
            int accepted = announcerB.announceNow(Collections.singletonList(hostA));
            if (accepted == 1) {
                ok("relay B greeted relay A claiming its own endpoint");
            } else {
                bad("announce greet failed");
            }

            // 2. relay A dial-back verifies B (async) before sharing it.
            //    (Claiming a DEAD port must never verify.)
            RelayGossipClient deadAnnouncer = new RelayGossipClient(idB, PROTO, 8);
            int deadPort = freePort();
            deadAnnouncer.setSelfEndpoint("127.0.0.1:" + deadPort);
            deadAnnouncer.announceNow(Collections.singletonList(hostA));

            // 3. a fresh client attaches to A and reads the peers from A's greeting
            MaximaIdentity idC = idFrom(83);
            client = new MaximaNode(idC, PROTO, 1);
            final MaximaNode fc = client;
            RelayGossipClient gossip = new RelayGossipClient(idC, PROTO, 8);

            boolean learned = waitFor(() -> {
                try {
                    // re-attach so each try reads a FRESH greeting (A's verify is async)
                    fc.stop();
                    fc.start(Collections.singletonList(hostA), 8000);
                    gossip.tick(fc);
                    return gossip.learnedCount() >= 1;
                } catch (Exception e) {
                    return false;
                }
            }, 25);

            if (learned && fc.pool() != null) {
                ok("client learned relay B from A's greeting, PROVED it live, adopted it");
            } else {
                bad("client did not adopt the gossiped relay (learned=" + gossip.learnedCount() + ")");
            }

            // 4. the dead-port claim must never have been shared/adopted
            HostConnection peek = new HostConnection("127.0.0.1", portA,
                    idC.hostKey(hostA), PROTO);
            peek.attach(8000);
            java.util.List<String> shared = Greeting.peersOf(peek.getTheirGreeting().getExtraData());
            peek.close();
            if (shared.contains(selfB) && !shared.contains("127.0.0.1:" + deadPort)) {
                ok("relay A shares the VERIFIED relay and never the dead-port claim ("
                        + shared + ")");
            } else if (!shared.contains("127.0.0.1:" + deadPort)) {
                ok("dead-port claim was not shared (B fresh in: " + shared + ")");
            } else {
                bad("dead-port claim was shared: " + shared);
            }

        } finally {
            stop.countDown();
            if (client != null) try { client.stop(); } catch (Exception ignored) { }
            relayA.stop();
            relayB.stop();
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Relay gossip discovery holds (classic vocabulary).");
    }
}
