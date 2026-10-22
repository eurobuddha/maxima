package com.eurobuddha.maxima.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.Greeting;

import java.util.Collections;

import org.junit.Test;

/**
 * The {@code dex-relay} capability record travels the relay mesh in the greeting ({@code "dex"}),
 * exactly like the pool bit: two loopback relays that verify each other end up each listing the
 * other's DEX base URL next to their own - what {@code GET /dex/relays} serves.
 */
public class DexDiscoveryTest {

    @Test
    public void verifiedPeersExchangeDexRecords() throws Exception {
        int pa = RelaySelfAnnounceTest.freePort();
        int pb = RelaySelfAnnounceTest.freePort();
        RelayServer a = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pa, RelaySelfAnnounceTest.PROTO);
        RelayServer b = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pb, RelaySelfAnnounceTest.PROTO);
        a.setPublicHost("127.0.0.1");
        b.setPublicHost("127.0.0.1");
        a.peers().setAllowAllIp(true);
        b.peers().setAllowAllIp(true);
        a.setDex(19799, "https://dex-a.example.com/");    // behind a TLS front: override, slash trimmed
        b.setDex(19798, "");                               // bare port: derived from the public host
        assertEquals("https://dex-a.example.com", a.dexUrl());
        assertEquals("http://127.0.0.1:19798", b.dexUrl());
        try {
            b.start();
            a.start();
            a.setPeers(Collections.singletonList("127.0.0.1:" + pb));
            assertTrue("A learned B's dex record from its verification dial",
                    RelaySelfAnnounceTest.waitFor(() -> a.dexRelays().containsKey("http://127.0.0.1:19798"), 15));
            assertTrue("B learned A's dex record from the dial-back",
                    RelaySelfAnnounceTest.waitFor(() -> b.dexRelays().containsKey("https://dex-a.example.com"), 15));
            assertTrue("A lists itself", a.dexRelays().containsKey("https://dex-a.example.com"));
            assertTrue("seen is epoch seconds", a.dexRelays().get("http://127.0.0.1:19798") > 1_700_000_000L
                    && a.dexRelays().get("http://127.0.0.1:19798") < 10_000_000_000L);
        } finally {
            a.stop();
            b.stop();
        }
    }

    /** A peer verified BEFORE it turned its DEX relay on must still be listed once it does. */
    @Test
    public void aReClaimRefreshesWhatAPeerAdvertises() throws Exception {
        int pa = RelaySelfAnnounceTest.freePort();
        int pb = RelaySelfAnnounceTest.freePort();
        RelayServer a = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pa, RelaySelfAnnounceTest.PROTO);
        RelayServer b = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pb, RelaySelfAnnounceTest.PROTO);
        a.setPublicHost("127.0.0.1");
        b.setPublicHost("127.0.0.1");
        a.peers().setAllowAllIp(true);
        b.peers().setAllowAllIp(true);
        try {
            b.start();
            a.start();
            a.setPeers(Collections.singletonList("127.0.0.1:" + pb));
            assertTrue("A verified B (no dex yet)",
                    RelaySelfAnnounceTest.waitFor(() -> a.peers().share().contains("127.0.0.1:" + pb), 15));
            assertTrue("nothing to list yet", a.dexRelays().isEmpty());
            b.setDex(19797, "https://dex-b.example.com");      // B turns its relay on later
            a.peers().setRedialMs(0);
            a.peers().claim("127.0.0.1", "127.0.0.1", pb, "127.0.0.1:" + pa);   // B greets A again
            assertTrue("the re-claim re-read B's greeting and found its dex URL",
                    RelaySelfAnnounceTest.waitFor(() -> a.dexRelays().containsKey("https://dex-b.example.com"), 15));
        } finally {
            a.stop();
            b.stop();
        }
    }

    /** Without any re-claim from the peer, A re-dials its verified peers by itself every REDIAL_MS: discovery
     *  must not depend on which peers happen to dial in (live 2026-10-22: eurobuddha.com stuck at 3 of 7). */
    @Test
    public void verifiedPeersAreReDialledWithoutAReClaim() throws Exception {
        int pa = RelaySelfAnnounceTest.freePort();
        int pb = RelaySelfAnnounceTest.freePort();
        RelayServer a = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pa, RelaySelfAnnounceTest.PROTO);
        RelayServer b = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), pb, RelaySelfAnnounceTest.PROTO);
        a.setPublicHost("127.0.0.1");
        b.setPublicHost("127.0.0.1");
        a.peers().setAllowAllIp(true);
        b.peers().setAllowAllIp(true);
        a.peers().setRedialMs(500);   // set before the check thread's idle wait starts
        try {
            b.start();
            a.start();
            a.setPeers(Collections.singletonList("127.0.0.1:" + pb));
            assertTrue("A verified B (no dex yet)",
                    RelaySelfAnnounceTest.waitFor(() -> a.peers().share().contains("127.0.0.1:" + pb), 15));
            b.setDex(19796, "https://dex-b2.example.com");     // B turns its relay on later and never greets A again
            assertTrue("A re-dialled B on its own and found its dex URL",
                    RelaySelfAnnounceTest.waitFor(() -> a.dexRelays().containsKey("https://dex-b2.example.com"), 15));
        } finally {
            a.stop();
            b.stop();
        }
    }

    @Test
    public void noPublicHostMeansNoRecord() throws Exception {
        RelayServer r = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), 9501, RelaySelfAnnounceTest.PROTO);
        r.setDex(19798, "");
        assertEquals("", r.dexUrl());
        assertTrue(r.dexRelays().isEmpty());
        r.setDex(0, "https://dex.example.com");
        assertEquals("cape off: nothing announced even with an override", "", r.dexUrl());
    }

    @Test
    public void dexKeyShapeIsStrict() {
        Greeting g = Greeting.commsOnly("1.0.48", "1.2.3.4", 9501, Collections.emptyList(), 0, true, 0, null, null,
                "http://1.2.3.4:19798");
        assertEquals("http://1.2.3.4:19798", Greeting.dexOf(g.getExtraData()));
        assertTrue(g.getExtraData().contains("\"pool\":\"true\""));
        assertEquals("", Greeting.dexOf("{\"dex\":\"ftp://x\"}"));
        assertEquals("", Greeting.dexOf("{\"dex\":\"https://x.example/dex/\"}"));   // trailing slash
        assertEquals("", Greeting.dexOf("{\"dex\":\"https://x.example/?a=b\"}"));
        assertEquals("", Greeting.dexOf("{}"));
        assertTrue("a malformed URL is never announced", !Greeting.commsOnly("1.0.48", "1.2.3.4", 9501,
                Collections.emptyList(), 0, false, 0, null, null, "javascript:alert(1)").getExtraData().contains("dex"));
    }
}
