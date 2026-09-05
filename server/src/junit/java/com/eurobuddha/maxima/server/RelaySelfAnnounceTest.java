package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.util.Collections;

import org.junit.Test;

/**
 * A relay with no account of its own must still become known to the fleet: when it
 * dial-verifies a mesh peer it CLAIMS its own endpoint in that greeting, the peer checks the
 * claim against the source IP and dials back, and from then on lists it. Two loopback relays:
 * A is given B as its only mesh peer; B must end up sharing A without anyone else's help.
 */
public class RelaySelfAnnounceTest {

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
            Thread.sleep(100);
        }
        return c.getAsBoolean();
    }

    @Test
    public void aRelayThatVerifiesAPeerBecomesKnownToThatPeer() throws Exception {
        int pa = freePort();
        int pb = freePort();
        RelayServer a = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pa, PROTO);
        RelayServer b = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pb, PROTO);
        a.setPublicHost("127.0.0.1");
        b.setPublicHost("127.0.0.1");
        a.peers().setAllowAllIp(true);   // loopback peers, like classic -allowallip
        b.peers().setAllowAllIp(true);
        try {
            b.start();
            a.start();
            a.setPeers(Collections.singletonList("127.0.0.1:" + pb));   // A's mesh list = B
            // A dials B to verify it (claiming 127.0.0.1:pa); B dials A back; B now lists A.
            assertTrue("B learned A from A's own verification dial",
                    waitFor(() -> b.peers().share().contains("127.0.0.1:" + pa), 15));
            // ...and A lists B, the peer it verified.
            assertTrue("A lists B", waitFor(() -> a.peers().share().contains("127.0.0.1:" + pb), 15));
        } finally {
            a.stop();
            b.stop();
        }
    }
}
