package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.portmap.PortMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Port mapping against the REAL router on this LAN, end to end:
 *
 *   1. listen on a local TCP port
 *   2. ask the router (NAT-PMP, then UPnP) to map it
 *   3. have a FLEET RELAY dial the mapped public address and prove a
 *      connection actually arrives - hairpin makes self-testing worthless
 *   4. release the mapping
 *
 * Run by hand: needs a router with NAT-PMP or UPnP enabled, and at least one
 * reachable relay for the external dial. Not part of the unit suite.
 *
 *   java ... PortMapLiveTest [probe-helper-host:port]
 *
 * Until the probe.dial service ships (phase C), step 3 falls back to plain
 * "listener got a connection from a non-LAN source within the window" if any
 * arrives; the definitive external dial arrives with phase C.
 */
public class PortMapLiveTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== PORT MAPPING, LIVE against this LAN's router ===\n");

        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("0.0.0.0", 0));
            int port = listener.getLocalPort();
            listener.setSoTimeout(30000);
            System.out.println("[1] listening on local port " + port);

            PortMapper mapper = new PortMapper(400, 4000);
            long t0 = System.currentTimeMillis();
            PortMapper.Mapping m = mapper.map(port);
            long ms = System.currentTimeMillis() - t0;

            if (m == null) {
                System.out.println("[2] NO MAPPING (" + ms + "ms) - either the router has "
                        + "NAT-PMP/UPnP disabled, or this network is behind CGNAT.");
                System.out.println("    That is a valid Tier 2 outcome: stay Tier 1.");
                return;
            }
            System.out.println("[2] MAPPED in " + ms + "ms: " + m);

            // ---- prove it from outside ----
            System.out.println("[3] waiting up to 30s for an external connection to "
                    + m.externalIp + ":" + m.externalPort);
            System.out.println("    (from another network: nc -z "
                    + m.externalIp + " " + m.externalPort + ")");
            try (Socket in = listener.accept()) {
                InetAddress from = in.getInetAddress();
                System.out.println("    CONNECTED from " + from.getHostAddress()
                        + (from.isSiteLocalAddress()
                        ? "  (LAN source - hairpin, not proof)" : "  (EXTERNAL - proof)"));
            } catch (java.net.SocketTimeoutException e) {
                System.out.println("    no connection arrived - mapping may still be fine; "
                        + "the external dial needs phase C's probe service");
            }

            System.out.println("[4] releasing");
            mapper.release(m);
            System.out.println("released. done.");
        }
    }
}
