package com.eurobuddha.maxima.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;
import com.eurobuddha.maxima.core.store.FileStore;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Relay discovery must behave like classic Minima's peers checker — the proven way a node
 * finds peers out in the wild from nothing but a bootstrap list. Each test pins one classic
 * rule, using tiny loopback "relays" that greet like ours do.
 */
public class PeerDiscoveryTest {

    static final String PROTO = "1.0.48";

    /** A loopback relay: answers every connection with our greeting (peers included). */
    static final class FakeRelay implements AutoCloseable {
        final ServerSocket server;
        final List<String> peers;
        final int port;
        volatile int greeted;
        volatile boolean running = true;
        /** A wallet gateway to advertise, or null. */
        volatile String gateway;
        volatile String gatewayKey;

        FakeRelay(List<String> zPeers) throws Exception {
            server = new ServerSocket(0);
            port = server.getLocalPort();
            peers = zPeers;
            Thread t = new Thread(() -> {
                while (running) {
                    try {
                        Socket s = server.accept();
                        new Thread(() -> serve(s)).start();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        String hostPort() {
            return "127.0.0.1:" + port;
        }

        void serve(Socket s) {
            try {
                s.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                Frame.readOrSkip(in, 65536);   // their greeting
                Frame.write(out, Frame.body(Frame.MSG_GREETING,
                        Greeting.commsOnly(PROTO, "127.0.0.1", port, peers, 64, true, 3,
                                gateway, gatewayKey)));
                greeted++;
                // hold the socket like a relay would, until the peer goes
                while (running) {
                    byte[] f = Frame.readOrSkip(in, 65536);
                    if (f == null) {
                        continue;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { s.close(); } catch (Exception ignored) { }
            }
        }

        @Override
        public void close() throws Exception {
            running = false;
            server.close();
        }
    }

    static int deadPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** A discovery whose background checker never dials: tests drive {@link PeerDiscovery#check}
     *  themselves. (Left running, checkers from earlier tests kept dialling unroutable 10.x
     *  peers with 4 s timeouts for the rest of the JVM, which made later loopback tests flaky.) */
    static PeerDiscovery discovery() {
        PeerDiscovery d = new PeerDiscovery(PROTO);
        d.setAllowAllIp(true);   // classic -allowallip: loopback peers for the test
        d.setConnectedSupplier(() -> false);   // queued checks defer instead of dialling
        return d;
    }

    static MaximaIdentity identity(int salt) {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i + salt);
        }
        return MaximaIdentity.fromSeed(new MiniData(seed));
    }

    // ---- classic PEERS_ADDPEERS: filter, dedup, never yourself ----

    @Test
    public void privateAndIpv6AndSelfAreNeverAdopted() {
        PeerDiscovery d = new PeerDiscovery(PROTO);   // allowAllIp OFF, like production
        d.addSelf("5.6.7.8:9501");
        d.addPeer("192.168.1.9:9501");      // private
        d.addPeer("127.0.0.1:9501");        // loopback
        d.addPeer("[2a02::1]:9501");        // ipv6
        d.addPeer("5.6.7.8:9501");          // ourselves
        d.addPeer("not-a-port:x");
        assertEquals(0, d.unverifiedCount());
        assertEquals(0, d.verifiedCount());
    }

    // ---- classic PEERS_CHECKPEERS: verify before adopt; its greeting grows the list ----

    @Test
    public void aLivePeerIsVerifiedAndItsPeersAreConsidered() throws Exception {
        try (FakeRelay a = new FakeRelay(Arrays.asList("10.0.0.1:9501", "10.0.0.2:9501"))) {
            PeerDiscovery d = discovery();
            List<String> verified = new ArrayList<>();
            d.setListener(new PeerDiscovery.Listener() {
                @Override public void onVerified(String hp) { verified.add(hp); }
                @Override public void onRemoved(String hp) { }
            });
            d.check(a.hostPort(), true);
            assertEquals(1, d.verifiedCount());
            assertEquals(Arrays.asList(a.hostPort()), verified);
            // the two peers it listed are now owed a check (unverified), not adopted blind
            assertEquals(2, d.unverifiedCount());
            assertFalse(d.verified().contains("10.0.0.1:9501"));
        }
    }

    @Test
    public void aDeadUnverifiedPeerIsForgotten() throws Exception {
        PeerDiscovery d = discovery();
        String dead = "127.0.0.1:" + deadPort();
        d.check(dead, true);
        assertEquals(0, d.verifiedCount());
        assertEquals(0, d.unverifiedCount());
    }

    @Test
    public void aDeadVerifiedPeerGetsOneRecheckInThirtyMinutesThenGoes() throws Exception {
        PeerDiscovery d = discovery();
        FakeRelay a = new FakeRelay(new ArrayList<>());
        String hp = a.hostPort();
        d.check(hp, true);
        assertEquals(1, d.verifiedCount());
        a.close();
        List<String> removed = new ArrayList<>();
        d.setListener(new PeerDiscovery.Listener() {
            @Override public void onVerified(String h) { }
            @Override public void onRemoved(String h) { removed.add(h); }
        });
        d.check(hp, true);
        // classic: moved back to unverified, rechecked later — not dropped outright
        assertEquals(0, d.verifiedCount());
        assertEquals(1, d.unverifiedCount());
        assertEquals(Arrays.asList(hp), removed);
        d.check(hp, true);
        assertEquals(0, d.unverifiedCount());   // still dead: gone
    }

    @Test
    public void aFailedUnverifiedPeerIsNotDialledAgainForTheRecheckInterval() throws Exception {
        PeerDiscovery d = discovery();
        String dead = "127.0.0.1:" + deadPort();
        d.addPeer(dead);
        assertEquals(1, d.unverifiedCount());
        d.check(dead, true);
        assertEquals(0, d.unverifiedCount());
        // the same relay lists it again next heartbeat: ignored, no second dial queued
        d.addPeer(dead);
        assertEquals(0, d.unverifiedCount());
    }

    @Test
    public void aPeerDroppedByThreeStrikesIsNotReadoptedFromTheNextGreeting() throws Exception {
        try (FakeRelay a = new FakeRelay(new ArrayList<>())) {
            PeerDiscovery d = discovery();
            d.check(a.hostPort(), true);
            assertEquals(1, d.verifiedCount());
            d.noConnect(a.hostPort());          // greets, but three attaches failed
            assertEquals(0, d.verifiedCount());
            d.addPeer(a.hostPort());            // the next greeting lists it again
            assertEquals("not re-queued within the recheck interval", 0, d.unverifiedCount());
        }
    }

    @Test
    public void savedPeersAreNotDemotedWhileOffline() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "maxima-peers-offline-" + System.nanoTime());
        FileStore store = new FileStore(dir);
        store.put("peers", "10.2.0.1:9501", Long.toString(System.currentTimeMillis()));
        store.put("peers", "10.2.0.2:9501", Long.toString(System.currentTimeMillis()));
        PeerDiscovery d = discovery();
        d.setConnectedSupplier(() -> false);   // booted in airplane mode
        d.setStore(store);
        assertEquals(2, d.verifiedCount());
        // the startup checks are deferred, not forced: nothing is dialled, nothing demoted
        d.check("10.2.0.1:9501", false);
        d.check("10.2.0.2:9501", false);
        assertEquals(2, d.verifiedCount());
        assertEquals(0, d.unverifiedCount());
        d.stop();
    }

    // ---- a relay's node advertises its wallet gateway; discovery keeps it with the peer ----

    @Test
    public void aVerifiedRelaysGatewayIsRememberedPersistedAndDroppedWithIt() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "maxima-peers-gw-" + System.nanoTime());
        try (FakeRelay a = new FakeRelay(new ArrayList<>());
             FakeRelay b = new FakeRelay(new ArrayList<>())) {
            a.gateway = "https://node-a.example/parlons-node/cmd";
            a.gatewayKey = "0123456789abcdef0123456789abcdef";
            PeerDiscovery d = discovery();
            d.setStore(new FileStore(dir));
            d.check(a.hostPort(), true);
            d.check(b.hostPort(), true);   // b advertises none
            assertEquals(1, d.gateways().size());
            assertEquals(a.gateway, d.gateways().get(0).url);
            assertEquals(a.gatewayKey, d.gateways().get(0).key);
            assertEquals(a.hostPort(), d.gateways().get(0).relay);
            d.save();

            PeerDiscovery again = discovery();
            again.setStore(new FileStore(dir));
            assertEquals("gateway survives a restart with its relay", 1, again.gateways().size());
            assertEquals(a.gateway, again.gateways().get(0).url);

            again.noConnect(a.hostPort());
            assertTrue("gone with the relay", again.gateways().isEmpty());
            d.stop();
            again.stop();
        }
    }

    @Test
    public void onlyHttpsGatewaysWithAKeyAreAccepted() {
        assertEquals("", Greeting.gatewayOf("{\"gw\":\"http://plain.example/cmd\",\"gwkey\":\"abcdefghij\"}"));
        assertEquals("", Greeting.gatewayOf("{\"gw\":\"https://x.example/cmd with space\"}"));
        assertEquals("", Greeting.gatewayOf("{\"gw\":\"https://x.example/cmd\\\\evil\"}"));
        assertEquals("", Greeting.gatewayOf("{\"gw\":\"https://x.example/cmd?a=1\"}"));   // no query strings
        assertEquals("https://x.example/parlons-node/cmd",
                Greeting.gatewayOf("{\"gw\":\"https://x.example/parlons-node/cmd\",\"gwkey\":\"abcdefghij\"}"));
        assertEquals("", Greeting.gatewayKeyOf("{\"gwkey\":\"short\"}"));
        assertEquals("", Greeting.gatewayKeyOf("{\"gwkey\":\"has space in it\"}"));
        // an omitted gateway leaves both empty
        String g = Greeting.commsOnly(PROTO, "1.2.3.4", 9501, new ArrayList<>(), 8, true, 1, null, null).getExtraData();
        assertEquals("", Greeting.gatewayOf(g));
        String h = Greeting.commsOnly(PROTO, "1.2.3.4", 9501, new ArrayList<>(), 8, true, 1,
                "https://n.example/parlons-node/cmd", "key_0123456789").getExtraData();
        assertEquals("https://n.example/parlons-node/cmd", Greeting.gatewayOf(h));
        assertEquals("key_0123456789", Greeting.gatewayKeyOf(h));
    }

    // ---- classic full list: 10% admission, random eviction keeps the size ----

    @Test
    public void aFullListAdmitsOneNewcomerInTenAndStaysBounded() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "maxima-peers-test-" + System.nanoTime());
        FileStore store = new FileStore(dir);
        for (int i = 0; i < PeerDiscovery.MAX_VERIFIED_PEERS; i++) {
            store.put("peers", "10.1." + (i / 250) + "." + (i % 250) + ":9501",
                    Long.toString(System.currentTimeMillis()));
        }
        PeerDiscovery d = discovery();
        d.setStore(store);
        assertEquals(PeerDiscovery.MAX_VERIFIED_PEERS, d.verifiedCount());
        int tries = 400;
        for (int i = 0; i < tries; i++) {
            d.addPeer("10.9." + (i / 250) + "." + (i % 250) + ":9501");
        }
        int admitted = d.unverifiedCount();
        // 10% chance each: 400 tries -> ~40, allow a wide statistical band
        assertTrue("admitted " + admitted, admitted >= 15 && admitted <= 80);

        // a newcomer that VERIFIES evicts a random verified peer: the list never grows
        try (FakeRelay a = new FakeRelay(new ArrayList<>())) {
            d.check(a.hostPort(), true);
        }
        assertEquals(PeerDiscovery.MAX_VERIFIED_PEERS, d.verifiedCount());
        d.stop();
    }

    // ---- classic P2PDB: save/load, and the half-size guard ----

    @Test
    public void theListPersistsButAnEmptiedListNeverOverwritesAGoodOne() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "maxima-peers-save-" + System.nanoTime());
        PeerDiscovery d = discovery();
        d.setStore(new FileStore(dir));
        try (FakeRelay a = new FakeRelay(new ArrayList<>());
             FakeRelay b = new FakeRelay(new ArrayList<>())) {
            d.check(a.hostPort(), true);
            d.check(b.hostPort(), true);
            d.save();

            PeerDiscovery again = discovery();
            again.setStore(new FileStore(dir));
            assertEquals(2, again.verifiedCount());
            assertTrue(again.verified().contains(a.hostPort()));

            // an outage: both go away, list empties -> save() must keep the saved two
            again.noConnect(a.hostPort());
            again.noConnect(b.hostPort());
            again.save();
            PeerDiscovery third = discovery();
            third.setStore(new FileStore(dir));
            assertEquals(2, third.verifiedCount());
        }
    }

    // ---- classic P2P_NOCONNECT via the pool: three failed connects running ----

    @Test
    public void threeFailedConnectsRunningDropThePeer() throws Exception {
        HostPool pool = new HostPool(identity(1), PROTO, 1);
        List<String> gone = new ArrayList<>();
        pool.setListener(new HostPool.Listener() {
            @Override public void onAttached(String hp, Greeting g) { }
            @Override public void onNoConnect(String hp) { gone.add(hp); }
        });
        String dead = "127.0.0.1:" + deadPort();
        pool.addCandidate(dead);
        pool.attachOne(dead, 500);
        pool.attachOne(dead, 500);
        assertTrue(gone.isEmpty());
        pool.attachOne(dead, 500);
        assertEquals(Arrays.asList(dead), gone);
    }

    // ---- classic P2P_RANDOM_CONNECT: candidates are drawn at random, so a population spreads ----

    @Test
    public void fillDrawsCandidatesAtRandomSoClientsSpread() throws Exception {
        List<FakeRelay> relays = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            relays.add(new FakeRelay(new ArrayList<>()));
        }
        try {
            Set<String> chosen = new HashSet<>();
            for (int round = 0; round < 25; round++) {
                HostPool pool = new HostPool(identity(round + 3), PROTO, 1);
                for (FakeRelay r : relays) {
                    pool.addCandidate(r.hostPort());
                }
                pool.fill(10000);
                chosen.addAll(pool.activeHosts());
                pool.closeAll();
            }
            // 25 independent clients over 5 equal relays: merit order picked the same one
            // every time; a random draw uses (nearly) all of them.
            assertTrue("spread over " + chosen.size() + " relays: " + chosen, chosen.size() >= 3);
        } finally {
            for (FakeRelay r : relays) {
                r.close();
            }
        }
    }

    // ---- intake from an attached relay's greeting reaches discovery ----

    @Test
    public void anAttachedRelaysGreetingFeedsDiscovery() throws Exception {
        try (FakeRelay a = new FakeRelay(Arrays.asList("10.0.0.7:9501"))) {
            HostPool pool = new HostPool(identity(9), PROTO, 1);
            PeerDiscovery d = discovery();
            pool.setListener(new HostPool.Listener() {
                @Override public void onAttached(String hp, Greeting g) { d.onGreeting(hp, g); }
                @Override public void onNoConnect(String hp) { d.noConnect(hp); }
            });
            pool.addCandidate(a.hostPort());
            assertEquals(1, pool.fill(10000));
            // the relay itself (its host claim) and the peer it listed are both owed a check
            assertTrue(d.unverifiedCount() >= 1);
            pool.closeAll();
            d.stop();
        }
    }
}
