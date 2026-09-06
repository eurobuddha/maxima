package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.directory.MlsClient;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.identity.Bip39;

import org.junit.Test;

import java.net.ServerSocket;
import java.util.Collections;

/**
 * Phase-B B2, end to end: a client that reaches ONE pool relay resolves an identity that
 * only published to a DIFFERENT pool relay, because the first relay forwards the miss across
 * the mesh, verifies the signed answer, and returns a normal hit. Two real RelayServers over
 * loopback sockets.
 */
public class MeshForwardTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String mls(MaximaIdentity relay, int port) {
        return relay.mxIdentity() + "@127.0.0.1:" + port;
    }

    @Test
    public void aDeadPeerAheadInTheListDoesNotDelayTheAnswerPastTheClientLeash() throws Exception {
        String proto = "1.0.48";
        int portA = freePort();
        int portB = freePort();
        int dead = freePort();   // nothing listens here: connect is refused at once, but a
                                 // black-holed peer would cost the full 3 s - parallel fan-out
                                 // means the live peer answers regardless of list order
        MaximaIdentity relayA = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity relayB = MaximaIdentity.fromPhrase(Bip39.generate(24));
        RelayServer a = new RelayServer(relayA, portA, proto, true);
        RelayServer b = new RelayServer(relayB, portB, proto, true);
        a.setReplicas(0);
        b.setReplicas(0);
        a.setPeers(java.util.Arrays.asList("127.0.0.1:" + dead, "10.255.255.1:9501", "127.0.0.1:" + portB));
        a.start();
        b.start();
        try {
            MaximaIdentity publisher = MaximaIdentity.fromPhrase(Bip39.generate(24));
            MaximaIdentity resolver = MaximaIdentity.fromPhrase(Bip39.generate(24));
            String published = publisher.mxIdentity() + "@203.0.113.8:9501";
            assertTrue(new MlsClient(publisher).publish(mls(relayB, portB),
                    Collections.singletonList(published), Collections.singletonList(resolver.publicKeyHex())));
            long t0 = System.currentTimeMillis();
            MlsClient.Resolved r = new MlsClient(resolver).resolve(mls(relayA, portA), publisher.publicKeyHex(), 5000, 5000);
            long took = System.currentTimeMillis() - t0;
            assertTrue("resolved via the live peer: " + r.error, r.ok());
            assertEquals(published, r.address);
            assertTrue("answered inside the client's 5 s leash despite a black-holed peer first (" + took + " ms)", took < 5000);
        } finally {
            a.stop();
            b.stop();
        }
    }

    @Test
    public void resolveForwardsAcrossThePool() throws Exception {
        String proto = "1.0.48";
        int portA = freePort();
        int portB = freePort();

        MaximaIdentity relayA = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity relayB = MaximaIdentity.fromPhrase(Bip39.generate(24));

        // Both relays are pool hosts; A knows B as a bootstrap mesh peer, B knows nobody.
        RelayServer a = new RelayServer(relayA, portA, proto, true);
        RelayServer b = new RelayServer(relayB, portB, proto, true);
        a.setPeers(Collections.singletonList("127.0.0.1:" + portB));

        a.start();
        b.start();
        try {
            MaximaIdentity publisher = MaximaIdentity.fromPhrase(Bip39.generate(24));
            MaximaIdentity resolver = MaximaIdentity.fromPhrase(Bip39.generate(24));
            String published = publisher.mxIdentity() + "@203.0.113.7:9501";

            // The publisher registers ONLY with relay B (a signed SET → B stores it with proof).
            boolean pub = new MlsClient(publisher).publish(
                    mls(relayB, portB),
                    Collections.singletonList(published),
                    Collections.singletonList(resolver.publicKeyHex()));
            assertTrue("publish to relay B succeeded", pub);

            // Sanity: relay A does NOT have it locally.
            MlsClient.Resolved direct = new MlsClient(resolver)
                    .resolve(mls(relayA, portA), publisher.publicKeyHex());
            // (It only resolves because A forwards — so a hit here already proves the mesh; but
            //  assert the value is right regardless of whether the miss/forward is instant.)
            assertTrue("relay A resolved via the mesh: " + direct.error, direct.ok());
            assertEquals("mesh returned the address published only to B", published, direct.address);

            // A second resolve is served from A's short-TTL cache (still the right answer).
            MlsClient.Resolved cached = new MlsClient(resolver)
                    .resolve(mls(relayA, portA), publisher.publicKeyHex());
            assertTrue(cached.ok());
            assertEquals(published, cached.address);

            // A key nobody published stays unresolved (no false positives from the mesh).
            MaximaIdentity ghost = MaximaIdentity.fromPhrase(Bip39.generate(24));
            MlsClient.Resolved miss = new MlsClient(resolver)
                    .resolve(mls(relayA, portA), ghost.publicKeyHex());
            assertFalse("an unpublished key does not resolve", miss.ok());
        } finally {
            a.stop();
            b.stop();
        }
    }

    @Test
    public void nonPoolRelayDoesNotForward() throws Exception {
        String proto = "1.0.48";
        int portA = freePort();
        int portB = freePort();

        MaximaIdentity relayA = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity relayB = MaximaIdentity.fromPhrase(Bip39.generate(24));

        // A is NON-pool: it must not forward, and must not answer DIR_QUERYs either.
        RelayServer a = new RelayServer(relayA, portA, proto, false);
        RelayServer b = new RelayServer(relayB, portB, proto, true);
        a.setPeers(Collections.singletonList("127.0.0.1:" + portB));

        a.start();
        b.start();
        try {
            MaximaIdentity publisher = MaximaIdentity.fromPhrase(Bip39.generate(24));
            MaximaIdentity resolver = MaximaIdentity.fromPhrase(Bip39.generate(24));
            String published = publisher.mxIdentity() + "@203.0.113.7:9501";
            new MlsClient(publisher).publish(mls(relayB, portB),
                    Collections.singletonList(published),
                    Collections.singletonList(resolver.publicKeyHex()));

            MlsClient.Resolved r = new MlsClient(resolver)
                    .resolve(mls(relayA, portA), publisher.publicKeyHex());
            assertFalse("a non-pool relay does not forward the miss", r.ok());
        } finally {
            a.stop();
            b.stop();
        }
    }
}
