package com.eurobuddha.maxima.core.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

/**
 * Which wallet gateway a device uses, and in what failover order — shared by the phone and
 * desktop wallet publishers so both behave the same.
 *
 * The fleet is the DISCOVERED gateways (every verified relay whose node advertises one, see
 * {@link PeerDiscovery#gateways()}) plus the compiled-in floor. The device spreads the
 * population by starting on a RANDOM one - but chosen ONCE per install and remembered, not per
 * wallet open: coins the wallet imports into a gateway's tracked set are spendable only through
 * that gateway, and re-drawing on every open re-ran that import (2+2N calls) against a fresh
 * node each time. The remembered choice moves only when failover moves it.
 */
public final class GatewayOrder {

    private GatewayOrder() {
    }

    /**
     * The failover list: the remembered gateway first when it is still offered, the rest
     * shuffled; when nothing is remembered (or it is gone), a random gateway leads and the
     * caller should remember it. Discovered entries win over a floor entry with the same URL.
     *
     * @param zDiscovered gateways learned from verified relays (may be empty)
     * @param zFloor      the compiled-in gateways, never empty
     * @param zSticky     the remembered URL, or null / ""
     */
    public static List<PeerDiscovery.Gateway> order(List<PeerDiscovery.Gateway> zDiscovered,
                                                    List<PeerDiscovery.Gateway> zFloor,
                                                    String zSticky, Random zRandom) {
        LinkedHashMap<String, PeerDiscovery.Gateway> all = new LinkedHashMap<>();
        for (PeerDiscovery.Gateway g : zDiscovered) {
            if (usable(g)) {
                all.put(g.url, g);
            }
        }
        for (PeerDiscovery.Gateway g : zFloor) {
            if (usable(g)) {
                all.putIfAbsent(g.url, g);
            }
        }
        List<PeerDiscovery.Gateway> out = new ArrayList<>(all.values());
        Collections.shuffle(out, zRandom);
        if (zSticky != null && !zSticky.isEmpty()) {
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).url.equals(zSticky)) {
                    PeerDiscovery.Gateway keep = out.remove(i);
                    out.add(0, keep);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean usable(PeerDiscovery.Gateway g) {
        return g != null && g.url != null && g.url.startsWith("https://")
                && g.key != null && !g.key.isEmpty();
    }
}
