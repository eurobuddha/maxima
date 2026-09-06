package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.directory.MlsClient;
import com.eurobuddha.maxima.core.directory.MlsStore;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Directory REPLICATION: a signed entry a pool relay accepts is pushed to random pool peers, so
 * a resolve succeeds on a relay the publisher never touched even after the anchor is gone -
 * the per-user single point of failure removed. Every replica is re-verified; nothing a peer
 * pushes can plant an entry; a replica never travels a second hop; a local publish outranks it.
 */
public class MeshReplicateTest {

    static final String PROTO = "1.0.48";

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static String mls(MaximaIdentity relay, int port) {
        return relay.mxIdentity() + "@127.0.0.1:" + port;
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
    public void anAcceptedSetIsReplicatedAndResolvesWithTheAnchorDown() throws Exception {
        int pa = freePort(), pb = freePort(), pc = freePort(), pd = freePort();
        MaximaIdentity ia = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity ib = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity ic = MaximaIdentity.fromPhrase(Bip39.generate(24));
        MaximaIdentity id = MaximaIdentity.fromPhrase(Bip39.generate(24));
        RelayServer a = new RelayServer(ia, pa, PROTO, true);
        RelayServer b = new RelayServer(ib, pb, PROTO, true);
        RelayServer c = new RelayServer(ic, pc, PROTO, true);
        RelayServer d = new RelayServer(id, pd, PROTO, true);
        // A's mesh peers: B and C. B's mesh peer: D (so a second hop would reach D).
        a.setPeers(Arrays.asList("127.0.0.1:" + pb, "127.0.0.1:" + pc));
        b.setPeers(Collections.singletonList("127.0.0.1:" + pd));
        a.setReplicas(3);
        b.setReplicas(3);
        a.start(); b.start(); c.start(); d.start();
        try {
            MaximaIdentity publisher = MaximaIdentity.fromPhrase(Bip39.generate(24));
            MaximaIdentity resolver = MaximaIdentity.fromPhrase(Bip39.generate(24));
            String published = publisher.mxIdentity() + "@203.0.113.7:9501";
            assertTrue(new MlsClient(publisher).publish(mls(ia, pa),
                    Collections.singletonList(published), Collections.singletonList(resolver.publicKeyHex())));

            // B and C hold verified replicas within seconds
            String key = publisher.publicKeyHex();
            assertTrue("B got a replica", waitFor(() -> b.directory().peek(key) != null, 10));
            assertTrue("C got a replica", waitFor(() -> c.directory().peek(key) != null, 10));
            MlsStore.Entry rb = b.directory().peek(key);
            assertTrue(rb.replica);
            assertTrue(rb.hasProof());
            assertEquals(published, rb.primary());
            assertEquals(2, a.replicasSent());
            assertEquals(1, b.replicasStored());

            // strict 1-hop: D (B's peer, not A's) never receives it
            Thread.sleep(500);
            assertNull("a replica is never re-replicated", d.directory().peek(key));
            assertEquals(0, b.replicasSent());

            // the anchor goes down: a resolve against B answers from its replica, no fan-out
            a.stop();
            long forwardsBefore = b.forwardsStarted();
            MlsClient.Resolved r = new MlsClient(resolver).resolve(mls(ib, pb), key, 3000, 3000);
            assertTrue("resolved on B with A down: " + r.error, r.ok());
            assertEquals(published, r.address);
            assertEquals("served locally", forwardsBefore, b.forwardsStarted());
        } finally {
            a.stop(); b.stop(); c.stop(); d.stop();
        }
    }

    @Test
    public void aTamperedOrUnsignedPushIsRefusedAndANonPoolRelayStoresNothing() throws Exception {
        int pa = freePort(), pn = freePort();
        RelayServer pool = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pa, PROTO, true);
        RelayServer plain = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pn, PROTO, false);
        pool.start(); plain.start();
        try {
            // a real signed SET, captured by publishing to the pool relay, then tampered
            MaximaIdentity publisher = MaximaIdentity.fromPhrase(Bip39.generate(24));
            String published = publisher.mxIdentity() + "@203.0.113.9:9501";
            assertTrue(new MlsClient(publisher).publish(mls(pool.identityForTest(), pa),
                    Collections.singletonList(published), Collections.emptyList()));
            MlsStore.Entry e = pool.directory().peek(publisher.publicKeyHex());
            assertNotNull(e);
            byte[] badPayload = e.proofPayload.clone();
            badPayload[badPayload.length - 1] ^= 0x55;
            DirPublish tampered = new DirPublish(e.proofFrom, badPayload, e.proofSig);
            DirPublish good = new DirPublish(e.proofFrom, e.proofPayload, e.proofSig);

            // tampered → refused by a pool relay
            long before = pool.replicasStored();
            assertFalse(RelayQueryClient.publish("127.0.0.1", pa, tampered, 2000, 2000));
            assertEquals(before, pool.replicasStored());
            // a good replica → refused (UNKNOWN) by a NON-pool relay, nothing stored
            assertFalse(RelayQueryClient.publish("127.0.0.1", pn, good, 2000, 2000));
            assertNull(plain.directory().peek(publisher.publicKeyHex()));
        } finally {
            pool.stop(); plain.stop();
        }
    }

    @Test
    public void replicasNeverEvictTheRelaysOwnPublishesThroughTheCap() {
        MlsStore s = new MlsStore();
        s.setOpenResolve(true);
        s.setMaxEntries(4);   // replicas may hold at most 2
        byte[] pf = new byte[] {1}, pp = new byte[] {2}, ps = new byte[] {3};
        s.put("0xL1", Collections.singletonList("l1@h:1"), Collections.emptyList(), pf, pp, ps, 60_000);
        s.put("0xL2", Collections.singletonList("l2@h:1"), Collections.emptyList(), pf, pp, ps, 60_000);
        assertTrue(s.putReplica("0xR1", "r1@h:1", pf, pp, ps, 60_000));
        assertTrue(s.putReplica("0xR2", "r2@h:1", pf, pp, ps, 60_000));
        assertTrue(s.putReplica("0xR3", "r3@h:1", pf, pp, ps, 60_000));   // over the replica share
        assertEquals(4, s.size());
        assertNotNull("local publishes survive a replica flood", s.peek("0xL1"));
        assertNotNull(s.peek("0xL2"));
        assertNull("the least-recently-used replica made room", s.peek("0xR1"));
        assertNotNull(s.peek("0xR3"));
    }

    @Test
    public void aReplicaNeverOverwritesTheRelaysOwnLivePublish() throws Exception {
        MlsStore s = new MlsStore();
        s.setOpenResolve(true);
        byte[] pf = new byte[] {1}, pp = new byte[] {2}, ps = new byte[] {3};
        s.put("0xAA", Collections.singletonList("me@here:1"), Collections.emptyList(), pf, pp, ps, 60_000);
        assertFalse("live local publish outranks a replica",
                s.putReplica("0xAA", "me@elsewhere:1", pf, pp, ps, 60_000));
        assertEquals("me@here:1", s.peek("0xAA").primary());
        // a replica fills an absent slot and replaces an older replica
        assertTrue(s.putReplica("0xBB", "b@one:1", pf, pp, ps, 60_000));
        assertTrue(s.putReplica("0xBB", "b@two:1", pf, pp, ps, 60_000));
        assertEquals("b@two:1", s.peek("0xBB").primary());
        assertTrue(s.peek("0xBB").replica);
        // a local publish replaces a replica
        s.put("0xBB", Collections.singletonList("b@mine:1"), Collections.emptyList(), pf, pp, ps, 60_000);
        assertFalse(s.peek("0xBB").replica);
    }
}
