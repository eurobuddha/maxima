package com.eurobuddha.maxima.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/** A device picks its gateway at random ONCE and keeps it; the population still spreads. */
public class GatewayOrderTest {

    static PeerDiscovery.Gateway g(String url) {
        return new PeerDiscovery.Gateway("r", url, "key_0123456789");
    }

    static final List<PeerDiscovery.Gateway> FLOOR = Arrays.asList(
            g("https://floor-a.example/cmd"), g("https://floor-b.example/cmd"));

    @Test
    public void theRememberedGatewayLeadsWhileItIsStillOffered() {
        List<PeerDiscovery.Gateway> discovered = Arrays.asList(
                g("https://n1.example/cmd"), g("https://n2.example/cmd"), g("https://n3.example/cmd"));
        for (int seed = 0; seed < 20; seed++) {
            List<PeerDiscovery.Gateway> o = GatewayOrder.order(discovered, FLOOR, "https://n2.example/cmd", new Random(seed));
            assertEquals("https://n2.example/cmd", o.get(0).url);
            assertEquals(5, o.size());
        }
    }

    @Test
    public void withNothingRememberedTheLeadIsRandomSoAPopulationSpreads() {
        List<PeerDiscovery.Gateway> discovered = Arrays.asList(
                g("https://n1.example/cmd"), g("https://n2.example/cmd"), g("https://n3.example/cmd"));
        Set<String> leads = new HashSet<>();
        for (int seed = 0; seed < 40; seed++) {
            leads.add(GatewayOrder.order(discovered, FLOOR, "", new Random(seed)).get(0).url);
        }
        assertTrue("40 installs spread over " + leads.size() + " gateways", leads.size() >= 4);
    }

    @Test
    public void aRememberedGatewayThatVanishedIsReplacedNotKept() {
        List<PeerDiscovery.Gateway> o = GatewayOrder.order(new ArrayList<>(), FLOOR, "https://gone.example/cmd", new Random(1));
        assertEquals(2, o.size());
        assertTrue(o.get(0).url.startsWith("https://floor-"));
    }

    @Test
    public void discoveredWinsOverFloorForTheSameUrlAndBadEntriesAreDropped() {
        List<PeerDiscovery.Gateway> discovered = Arrays.asList(
                new PeerDiscovery.Gateway("r", "https://floor-a.example/cmd", "fresh_key_12345"),
                new PeerDiscovery.Gateway("r", "http://plain.example/cmd", "key_0123456789"),
                new PeerDiscovery.Gateway("r", "https://nokey.example/cmd", ""));
        List<PeerDiscovery.Gateway> o = GatewayOrder.order(discovered, FLOOR, "", new Random(2));
        assertEquals(2, o.size());
        for (PeerDiscovery.Gateway g : o) {
            if (g.url.equals("https://floor-a.example/cmd")) {
                assertEquals("fresh_key_12345", g.key);
            }
        }
    }
}
