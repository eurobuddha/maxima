package com.eurobuddha.maxima.core.portmap;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The one call the rest of the system makes: "get me a public TCP port, or
 * tell me honestly that this network cannot".
 *
 * Order: NAT-PMP first (one UDP round trip, answers in milliseconds when the
 * router speaks it), then UPnP SSDP (seconds, but self-discovering). Either
 * way the result goes through the same honesty gate: a mapping whose external
 * address is private or CGNAT space is NOT a public address - we are behind a
 * second NAT and advertising it would publish a dead address, which is the
 * classic-Maxima failure this whole layer exists to avoid.
 */
public final class PortMapper {

    /** Lease we ask for. Renewal happens at half-life on the heartbeat. */
    public static final int LEASE_SECONDS = 3600;

    public static final class Mapping {
        public final String externalIp;
        public final int externalPort;
        public final int internalPort;
        public final long lifetimeSeconds;
        /** "natpmp" or "upnp" - shown in diagnostics, drives renew/release. */
        public final String via;

        Mapping(String zIp, int zExt, int zInt, long zLife, String zVia) {
            externalIp = zIp;
            externalPort = zExt;
            internalPort = zInt;
            lifetimeSeconds = zLife;
            via = zVia;
        }

        @Override
        public String toString() {
            return externalIp + ":" + externalPort + " via " + via
                    + " lease " + lifetimeSeconds + "s";
        }
    }

    private final int mNatPmpTimeoutMs;
    private final int mUpnpTimeoutMs;
    /** Injected by :app from ConnectivityManager when it knows better. */
    private volatile InetAddress mGatewayHint;

    private volatile UpnpIgd mIgd;
    private volatile NatPmp mPmp;

    public PortMapper() {
        this(250, 2500);
    }

    public PortMapper(int zNatPmpTimeoutMs, int zUpnpTimeoutMs) {
        mNatPmpTimeoutMs = zNatPmpTimeoutMs;
        mUpnpTimeoutMs = zUpnpTimeoutMs;
    }

    public void setGatewayHint(InetAddress zGateway) {
        mGatewayHint = zGateway;
    }

    /**
     * Try to map zInternalPort. Blocking, several seconds worst case - never
     * call from a main thread.
     *
     * @return a verified-shape mapping (public-looking external IP), or null
     */
    public Mapping map(int zInternalPort) {
        // ---- NAT-PMP against candidate gateways ----
        for (InetAddress gw : gatewayCandidates()) {
            NatPmp pmp = new NatPmp(gw, mNatPmpTimeoutMs);
            InetAddress ext = pmp.externalAddress();
            if (ext == null) {
                continue;
            }
            if (!isPublic(ext.getHostAddress())) {
                // The router answered and its WAN side is still private:
                // double NAT / CGNAT. UPnP would report the same thing
                // through a slower path - stop here.
                return null;
            }
            NatPmp.Result r = pmp.mapTcp(zInternalPort, zInternalPort, LEASE_SECONDS);
            if (r != null) {
                mPmp = pmp;
                return new Mapping(ext.getHostAddress(), r.externalPort,
                        zInternalPort, r.lifetimeSeconds, "natpmp");
            }
        }

        // ---- UPnP ----
        // Multicast first; then unicast at each gateway candidate, because a
        // chained home network (mesh router in front of the ISP hub) hides the
        // outer IGD from multicast but answers a M-SEARCH aimed straight at it.
        UpnpIgd igd = new UpnpIgd(mUpnpTimeoutMs);
        boolean found = igd.discover();
        if (!found) {
            for (java.net.InetAddress gw : gatewayCandidates()) {
                if (igd.discoverUnicast(gw.getHostAddress())) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            return null;
        }
        String ext = igd.externalIp();
        if (ext == null || !isPublic(ext)) {
            return null;
        }
        String local = localAddress();
        if (local == null) {
            return null;
        }
        // UPnP takes the port or errors; walk a few candidates on conflict.
        for (int i = 0; i < 5; i++) {
            int port = zInternalPort + i;
            if (igd.addTcpMapping(local, zInternalPort, port,
                    LEASE_SECONDS, "maxima")) {
                mIgd = igd;
                return new Mapping(ext, port, zInternalPort, LEASE_SECONDS, "upnp");
            }
        }
        return null;
    }

    /** Re-request the same mapping - both protocols treat this as renewal. */
    public boolean renew(Mapping zMapping) {
        if (zMapping == null) {
            return false;
        }
        if ("natpmp".equals(zMapping.via) && mPmp != null) {
            return mPmp.mapTcp(zMapping.internalPort, zMapping.externalPort,
                    LEASE_SECONDS) != null;
        }
        if ("upnp".equals(zMapping.via) && mIgd != null) {
            String local = localAddress();
            return local != null && mIgd.addTcpMapping(local,
                    zMapping.internalPort, zMapping.externalPort,
                    LEASE_SECONDS, "maxima");
        }
        return false;
    }

    /**
     * Withdraw. Best effort - a router reboot loses mappings anyway, which is
     * why the lease is short rather than the release load-bearing.
     */
    public void release(Mapping zMapping) {
        if (zMapping == null) {
            return;
        }
        try {
            if ("natpmp".equals(zMapping.via) && mPmp != null) {
                mPmp.mapTcp(zMapping.internalPort, zMapping.externalPort, 0);
            } else if ("upnp".equals(zMapping.via) && mIgd != null) {
                mIgd.deleteTcpMapping(zMapping.externalPort);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------

    /**
     * Is this address usable as a PUBLIC contact address?
     *
     * Excludes RFC1918, loopback, link-local, and - the one everybody forgets -
     * RFC 6598 CGNAT space (100.64.0.0/10), which is exactly what a phone
     * behind carrier NAT sees as its "external" address.
     */
    public static boolean isPublic(String zIp) {
        if (zIp == null || zIp.isEmpty() || zIp.contains(":")) {
            // IPv6 is out of scope entirely: classic parses an address on the
            // first ':' and cannot carry v6 literals.
            return false;
        }
        String[] p = zIp.split("\\.");
        if (p.length != 4) {
            return false;
        }
        int a, b;
        try {
            a = Integer.parseInt(p[0]);
            b = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (a == 0 || a == 10 || a == 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;   // CGNAT
        return a < 224;                                       // no multicast+
    }

    /** Our LAN address, via the UDP-connect trick (no packet is sent). */
    static String localAddress() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(new InetSocketAddress("8.8.8.8", 53));
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Likely gateways: an explicit hint from the host platform first, then the
     * .1 and .254 conventions on our own /24.
     */
    private List<InetAddress> gatewayCandidates() {
        List<InetAddress> out = new ArrayList<>();
        InetAddress hint = mGatewayHint;
        if (hint != null) {
            out.add(hint);
        }
        String local = localAddress();
        if (local != null && local.indexOf('.') > 0) {
            String prefix = local.substring(0, local.lastIndexOf('.'));
            for (String last : new String[]{".1", ".254"}) {
                try {
                    InetAddress a = InetAddress.getByName(prefix + last);
                    if (!out.contains(a)) {
                        out.add(a);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }
}
