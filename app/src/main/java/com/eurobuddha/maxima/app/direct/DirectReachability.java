package com.eurobuddha.maxima.app.direct;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;

import com.eurobuddha.maxima.app.AndroidContribution;
import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.net.Probe;
import com.eurobuddha.maxima.core.portmap.PortMapper;

import java.net.InetAddress;

/**
 * Tier 2 on the phone: turn a lucky network into a genuine public address.
 *
 * The state machine, driven from the service's existing 60s heartbeat so it
 * costs no extra wakeups:
 *
 *   OFF ──gates pass──▶ MAPPING ──got a port──▶ PROBING ──proven──▶ ADVERTISED
 *    ▲                                                                   │
 *    └──────── withdraw + refreshContacts on loss / gate-off / renew fail ┘
 *
 * The cardinal rule, inherited from every layer below: advertise NOTHING that
 * has not just been proven reachable from OUTSIDE. A phone testing its own port
 * is worthless (hairpin NAT), so proof is a relay dialling us back (phase C).
 * The alternative - publishing an address we merely hope works - is the exact
 * classic-Maxima failure this whole tier exists to end.
 *
 * All of this is best-effort and OPPORTUNISTIC. The common outcome on cellular
 * or a CGNAT home line is "no public port," and staying Tier 1 is a success,
 * not a failure - so nothing here ever logs an error for the ordinary case.
 */
public final class DirectReachability {

    public enum State { OFF, MAPPING, PROBING, ADVERTISED }

    private final Context mCtx;
    private final MaximaNode mNode;
    private final AndroidContribution mPolicy;

    private volatile State mState = State.OFF;
    private volatile String mPublicAddress = "";
    private volatile String mDetail = "not started";

    private final PortMapper mMapper = new PortMapper();
    private PortMapper.Mapping mMapping;
    private long mMappedAtMs;
    private int mListenPort = -1;

    public DirectReachability(Context zCtx, MaximaNode zNode, AndroidContribution zPolicy) {
        mCtx = zCtx;
        mNode = zNode;
        mPolicy = zPolicy;
    }

    public State state() {
        return mState;
    }

    public String publicAddress() {
        return mPublicAddress;
    }

    public String detail() {
        return mDetail;
    }

    /**
     * Called on the heartbeat. Everything here can block (port mapping, a probe
     * round-trip), so it MUST run off the main thread - the service heartbeat
     * already does.
     */
    public synchronized void tick() {
        if (!gatesPass()) {
            if (mState != State.OFF) {
                withdraw("conditions no longer met");
            }
            mDetail = gateReason();
            return;
        }

        switch (mState) {
            case OFF:
                attempt();
                break;
            case ADVERTISED:
                renewIfDue();
                break;
            default:
                // MAPPING/PROBING are transient inside one attempt(); if we are
                // parked in them across ticks, something stalled - re-attempt.
                attempt();
        }
    }

    /** Network changed or was lost - drop any advertised address immediately. */
    public synchronized void onNetworkChanged() {
        if (mState != State.OFF) {
            withdraw("network changed");
        }
    }

    public synchronized void shutdown() {
        withdraw("stopping");
    }

    // ---------------------------------------------------------------

    private void attempt() {
        // 1. a local listener (idempotent - startDirect returns the same port)
        mListenPort = mNode.startDirect(0);
        if (mListenPort <= 0) {
            mState = State.OFF;
            mDetail = "could not open a local port";
            return;
        }

        // 2. ask the router for a public mapping of that port
        mState = State.MAPPING;
        mDetail = "asking the router for a public port…";
        mMapper.setGatewayHint(gatewayHint());
        PortMapper.Mapping m = mMapper.map(mListenPort);
        if (m == null) {
            // The ordinary case on most networks. Quietly stay Tier 1.
            mState = State.OFF;
            mDetail = "this network has no forwardable public port (staying Tier 1)";
            return;
        }
        mMapping = m;
        mMappedAtMs = System.currentTimeMillis();

        // 3. PROVE it from outside before advertising a single thing
        mState = State.PROBING;
        mDetail = "verifying " + m.externalIp + ":" + m.externalPort + " from outside…";
        if (!proveReachable(m.externalPort)) {
            // Mapped but not reachable (double NAT, ISP filtering). Release and
            // stay Tier 1 - never advertise an unproven address.
            mMapper.release(m);
            mMapping = null;
            mState = State.OFF;
            mDetail = "mapped a port but it was not reachable (staying Tier 1)";
            return;
        }

        // 4. advertise, and tell contacts
        mPublicAddress = m.externalIp + ":" + m.externalPort;
        mNode.setDirectAddress(mPublicAddress);
        mState = State.ADVERTISED;
        mDetail = "reachable at " + mPublicAddress + " via " + m.via;
        EventLog.add("DIRECT reachable at " + mPublicAddress + " (" + m.via + ")");
        refreshContacts();
    }

    private void renewIfDue() {
        if (mMapping == null) {
            mState = State.OFF;
            return;
        }
        long ageMs = System.currentTimeMillis() - mMappedAtMs;
        long halfLifeMs = mMapping.lifetimeSeconds * 1000L / 2;
        if (ageMs < halfLifeMs) {
            return;
        }
        if (mMapper.renew(mMapping)) {
            mMappedAtMs = System.currentTimeMillis();
            mDetail = "lease renewed for " + mPublicAddress;
        } else {
            withdraw("lease renewal failed");
        }
    }

    /**
     * Ask a connected relay to dial us back. Reachable iff the relay's ack is
     * OK. We try each attached relay until one answers definitively; a relay
     * that is itself unreachable to us tells us nothing.
     */
    private boolean proveReachable(int zExternalPort) {
        for (String hostPort : mNode.pool().activeHosts()) {
            com.eurobuddha.maxima.core.net.HostConnection c = mNode.pool().connection(hostPort);
            if (c == null) {
                continue;
            }
            String relayAddr = c.getTheirMlsAddress();
            if (relayAddr == null) {
                continue;
            }
            try {
                com.eurobuddha.maxima.core.MaximaSender.Result r =
                        mNode.sendRaw(relayAddr, Probe.APPLICATION,
                                Probe.request(zExternalPort));
                // OK => the relay dialled our external port and an endpoint (us)
                // answered. FAIL is inconclusive from one relay, so keep trying.
                if (r.isOk()) {
                    return true;
                }
            } catch (Exception ignored) {
                // try the next relay
            }
        }
        return false;
    }

    private void withdraw(String zWhy) {
        if (mMapping != null) {
            mMapper.release(mMapping);
            mMapping = null;
        }
        if (!mPublicAddress.isEmpty()) {
            EventLog.add("DIRECT withdrawn (" + zWhy + ")");
        }
        mPublicAddress = "";
        mNode.setDirectAddress("");
        mNode.stopDirect();
        mState = State.OFF;
        mDetail = zWhy;
        // Contacts must be told our direct address is gone, so they stop trying
        // a now-dead route and fall back to the relays.
        refreshContacts();
    }

    private void refreshContacts() {
        new Thread(mNode::refreshContacts, "direct-refresh").start();
    }

    // ---- gates ----

    /**
     * Direct reachability is the heaviest opportunistic duty, so it takes the
     * strictest gate: contribution on, unmetered (Wi-Fi), and charging for the
     * initial attempt. Cellular never tries - carrier CGNAT makes it pointless
     * and the port-mapper's public-IP check would refuse the result anyway.
     */
    private boolean gatesPass() {
        if (!AndroidContribution.isEnabled(mCtx)) {
            return false;
        }
        if (!mPolicy.isUnmetered()) {
            return false;
        }
        // Charging is required to START; once advertised we keep it while on
        // Wi-Fi even off charge, since the expensive part (map+probe) is done.
        if (mState == State.OFF && !mPolicy.isCharging()) {
            return false;
        }
        return true;
    }

    private String gateReason() {
        if (!AndroidContribution.isEnabled(mCtx)) {
            return "off (contribution disabled)";
        }
        if (!mPolicy.isUnmetered()) {
            return "off (needs Wi-Fi - CGNAT makes cellular pointless)";
        }
        if (!mPolicy.isCharging()) {
            return "off (needs charging to start)";
        }
        return "ready";
    }

    /**
     * The default gateway address, read from the active network where the
     * platform will tell us. NAT-PMP needs it; SSDP finds its own, so a null
     * here only costs the NAT-PMP path.
     */
    private InetAddress gatewayHint() {
        try {
            ConnectivityManager cm = mCtx.getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return null;
            }
            Network active = cm.getActiveNetwork();
            LinkProperties lp = cm.getLinkProperties(active);
            if (lp == null) {
                return null;
            }
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() != null) {
                    return r.getGateway();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
