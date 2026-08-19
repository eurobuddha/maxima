package com.eurobuddha.maxima.app.direct;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;

import com.eurobuddha.maxima.app.AndroidContribution;
import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.ManualForward;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.net.Probe;
import com.eurobuddha.maxima.core.portmap.PortMapper;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier 2 on the phone: turn a lucky network into a genuine public address.
 *
 *   OFF ──gates pass──▶ MAPPING ──got a port──▶ PROBING ──proven──▶ ADVERTISED
 *    ▲                                                                   │
 *    └──────── withdraw + refreshContacts on loss / gate-off / renew fail ┘
 *
 * The cardinal rule, inherited from every layer below: advertise NOTHING that
 * has not just been proven reachable from OUTSIDE. A phone testing its own port
 * is worthless (hairpin NAT), so proof is a relay dialling us back (phase C).
 * Publishing an address we merely hope works is the exact classic-Maxima
 * failure this whole tier exists to end.
 *
 * CONCURRENCY. Everything that touches state runs on ONE dedicated thread, so
 * there are no locks and nothing to contend for. A blocking map/probe therefore
 * cannot stall an "immediate" withdraw held behind a monitor - the withdraw is
 * simply the next task on the same thread. And because a network change can
 * arrive WHILE a probe is in flight on the old network, every attempt captures
 * a generation number; a change bumps it, and the attempt refuses to advertise
 * if its generation is stale. That closes the window where we might otherwise
 * publish an address just "proven" on a network that has already gone.
 *
 * All of this is OPPORTUNISTIC. The common outcome on cellular or a CGNAT home
 * line is "no public port", and staying Tier 1 is a success - so nothing here
 * logs an error for the ordinary case.
 */
public final class DirectReachability {

    public enum State { OFF, MAPPING, PROBING, ADVERTISED }

    /** Why direct is not currently advertised — drives a plain-language suggestion. */
    public enum Blocker { NONE, CONTRIB_OFF, NEEDS_WIFI, NEEDS_CHARGING, ROUTER_NO_PORT, NEEDS_PUBLIC_IP }

    /** Re-prove from scratch at most this often, to catch a silently-changed WAN IP. */
    private static final long REPROVE_INTERVAL_MS = 20 * 60 * 1000;

    private final Context mCtx;
    private final MaximaNode mNode;
    private final AndroidContribution mPolicy;

    /** One thread runs every state operation, in submission order. */
    private final ExecutorService mExec = Executors.newSingleThreadExecutor(daemon("direct-state"));
    /** Contact refreshes on their own single thread, so they never queue behind a probe. */
    private final ExecutorService mRefresh = Executors.newSingleThreadExecutor(daemon("direct-refresh"));

    private volatile State mState = State.OFF;
    private volatile Blocker mBlocker = Blocker.NONE;
    private volatile String mPublicAddress = "";
    private volatile String mDetail = "not started";
    private volatile boolean mStopping;
    /** Bumped by any event that invalidates an in-flight attempt. */
    // Generation guard: bumped (atomically, from any thread) whenever the
    // network changes or the user re-checks, so an in-flight attempt refuses to
    // advertise a stale mapping. A plain int++ loses increments under races.
    private final AtomicInteger mGen = new AtomicInteger();

    private final PortMapper mMapper = new PortMapper();
    private PortMapper.Mapping mMapping;
    private long mMappedAtMs;
    private long mProvenAtMs;
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

    /** Why direct is not advertised right now (NONE when advertised). */
    public Blocker blocker() {
        return mBlocker;
    }

    /** The internal port a manual router forward should target. */
    public int suggestedPort() {
        return mListenPort;
    }

    /** The mechanism that opened the port ("natpmp"/"upnp"), or "" if not mapped. */
    public String via() {
        PortMapper.Mapping m = mMapping;
        return m == null ? "" : m.via;
    }

    /** The public external port, or -1 if not mapped. */
    public int externalPort() {
        PortMapper.Mapping m = mMapping;
        return m == null ? -1 : m.externalPort;
    }

    /** Heartbeat entry. Returns immediately; the work runs on the state thread. */
    public void tick() {
        submit(this::doTick);
    }

    /**
     * User-initiated "try now": run map → prove → advertise immediately, bypassing
     * the charging-to-start gate (still needs Wi-Fi + contribution). Safe to call
     * from any thread; the work runs on the state thread.
     */
    public void reprobe() {
        mGen.incrementAndGet();
        submit(() -> {
            if (mStopping) {
                return;
            }
            if (!AndroidContribution.isEnabled(mCtx)) {
                mBlocker = Blocker.CONTRIB_OFF;
                mDetail = gateReason();
                return;
            }
            if (!mPolicy.isUnmetered()) {
                mBlocker = Blocker.NEEDS_WIFI;
                mDetail = gateReason();
                return;
            }
            withdraw("manual re-check");
            attempt();
        });
    }

    /**
     * Network changed or was lost. Bumps the generation so any in-flight attempt
     * refuses to advertise, then queues a withdraw - neither step waits on the
     * blocking work, which was the whole problem with a shared monitor.
     */
    public void onNetworkChanged() {
        mGen.incrementAndGet();
        submit(() -> withdraw("network changed"));
    }

    public void shutdown() {
        mStopping = true;
        mGen.incrementAndGet();
        submit(() -> withdraw("stopping"));
        mExec.shutdown();
        try {
            // Give the queued withdraw a moment to release the router mapping.
            // Best-effort: the lease expires on its own regardless.
            mExec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        mRefresh.shutdown();
    }

    // ---------------------------------------------------------------

    private void doTick() {
        if (mStopping) {
            return;
        }
        if (!gatesPass()) {
            if (mState != State.OFF) {
                withdraw("conditions no longer met");
            }
            mBlocker = gateBlocker();
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
                // Parked in a transient state (an attempt threw part way). Reset
                // cleanly and try again rather than re-entering half-initialised.
                withdraw("retrying");
                attempt();
        }
    }

    private void attempt() {
        int gen = mGen.get();

        // Never leak a previous mapping if we are re-entering.
        if (mMapping != null) {
            mMapper.release(mMapping);
            mMapping = null;
        }

        // 1. the direct listener is owned by the service (it also serves LAN
        //    peers, so it runs on Wi-Fi regardless of the public-mapping gate).
        //    We only map a public port for it - we never start or stop it.
        mListenPort = mNode.directPort();
        if (mListenPort <= 0) {
            mState = State.OFF;
            mDetail = "listener not running";
            return;
        }

        // Manual port-forward mode: the user opened the port by hand and told us
        // their public IP; skip the router negotiation and just prove + advertise.
        if (ManualForward.enabled(mCtx)) {
            attemptManual(gen);
            return;
        }

        // 2. ask the router for a public mapping of that port
        mState = State.MAPPING;
        mDetail = "asking the router for a public port…";
        mMapper.setGatewayHint(gatewayHint());
        PortMapper.Mapping m = mMapper.map(mListenPort);
        if (m == null) {
            mState = State.OFF;
            mBlocker = Blocker.ROUTER_NO_PORT;
            mDetail = "this network has no forwardable public port (staying Tier 1)";
            return;
        }

        // The network may have changed while map() blocked. If so, this mapping
        // belongs to a network that is gone - release it and do not advertise.
        if (mStopping || gen != mGen.get() || !gatesPass()) {
            mMapper.release(m);
            mState = State.OFF;
            mDetail = "network changed during mapping (staying Tier 1)";
            return;
        }
        mMapping = m;
        mMappedAtMs = System.currentTimeMillis();

        // 3. PROVE it from outside before advertising a single thing
        mState = State.PROBING;
        mDetail = "verifying " + m.externalIp + ":" + m.externalPort + " from outside…";
        boolean reachable = proveReachable(m.externalPort, m.externalIp);

        // Re-check AFTER the probe too - it also blocks, and a change during it
        // is exactly the dead-address window we must not advertise into.
        if (mStopping || gen != mGen.get() || !gatesPass()) {
            mMapper.release(m);
            mMapping = null;
            mState = State.OFF;
            mDetail = "network changed during verification (staying Tier 1)";
            return;
        }
        if (!reachable) {
            mMapper.release(m);
            mMapping = null;
            mState = State.OFF;
            mBlocker = Blocker.ROUTER_NO_PORT;
            mDetail = "mapped a port but it was not reachable (staying Tier 1)";
            return;
        }

        // 4. advertise, and tell contacts
        mPublicAddress = m.externalIp + ":" + m.externalPort;
        mNode.setDirectAddress(mPublicAddress);
        mProvenAtMs = System.currentTimeMillis();
        mBlocker = Blocker.NONE;
        mState = State.ADVERTISED;
        mDetail = "reachable at " + mPublicAddress + " via " + m.via;
        EventLog.add("DIRECT reachable at " + mPublicAddress + " (" + m.via + ")");
        refreshContacts();
    }

    /** Manual mode: prove the user's forwarded public IP:port, then advertise it. */
    private void attemptManual(int gen) {
        int extPort = ManualForward.port(mCtx);
        // ADVERTISE ONLY THE EXTERNALLY-PROVEN ADDRESS. We use the phone's CURRENT public
        // egress IP, fetched fresh, NOT a manually-typed value: an external relay dials that
        // egress back, and we advertise exactly it only if the dial succeeds. This kills the
        // old bug where the app proved the real egress but advertised a stale hand-entered IP
        // (a phone that roamed networks, or is behind CGNAT, kept advertising a dead address).
        String ip = currentPublicIp();
        if (ip.isEmpty()) {
            mState = State.OFF;
            mBlocker = Blocker.NEEDS_PUBLIC_IP;
            mDetail = "can't determine your public IP (need internet)";
            return;
        }
        mState = State.PROBING;
        mDetail = "verifying " + ip + ":" + extPort + " from outside…";
        boolean reachable = proveReachable(extPort, ip);
        if (mStopping || gen != mGen.get() || !gatesPass()) {
            mState = State.OFF;
            mDetail = "network changed during verification (staying Tier 1)";
            return;
        }
        if (!reachable) {
            mState = State.OFF;
            mBlocker = Blocker.ROUTER_NO_PORT;
            mDetail = ip + ":" + extPort + " not reachable from outside — behind CGNAT, or the "
                    + "forward rule must point at Parlons port " + extPort;
            return;
        }
        mPublicAddress = ip + ":" + extPort;
        mNode.setDirectAddress(mPublicAddress);
        mProvenAtMs = System.currentTimeMillis();
        mBlocker = Blocker.NONE;
        mState = State.ADVERTISED;
        mDetail = "reachable at " + mPublicAddress + " (manual forward)";
        EventLog.add("DIRECT reachable at " + mPublicAddress + " (manual)");
        refreshContacts();
    }

    private void renewIfDue() {
        // Manual mode has no router lease — just re-prove periodically.
        if (ManualForward.enabled(mCtx)) {
            if (System.currentTimeMillis() - mProvenAtMs > REPROVE_INTERVAL_MS) {
                withdraw("periodic re-verification");
                attempt();
            }
            return;
        }
        if (mMapping == null) {
            mState = State.OFF;
            return;
        }
        // Periodically re-prove from scratch: a WAN IP can change under a stable
        // Wi-Fi link with NO network-change event, and a renewed lease on a
        // stale IP would keep us advertising an address that is now wrong.
        if (System.currentTimeMillis() - mProvenAtMs > REPROVE_INTERVAL_MS) {
            String was = mPublicAddress;
            withdraw("periodic re-verification");
            attempt();
            if (mState != State.ADVERTISED) {
                EventLog.add("DIRECT " + was + " no longer reachable on re-check");
            }
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
     * Ask a connected relay to dial us back. Reachable iff a relay's ack is OK.
     * A relay that cannot itself reach us tells us nothing, so we try each until
     * one answers definitively.
     */
    private boolean proveReachable(int zExternalPort, String zMyPublicIp) {
        // Only a GENUINELY EXTERNAL relay can prove external reachability. A relay on our
        // OWN network shares our public IP, so its dial-back to us HAIRPINS through the
        // router and falsely confirms reachability that no true off-net peer has - which is
        // exactly what made a CGNAT'd / double-NAT'd phone advertise a dead port-forward.
        // Skip any relay whose IP equals our own public IP; if none is left to ask, we are
        // NOT provably reachable and must not advertise.
        for (String hostPort : mNode.pool().activeHosts()) {
            if (isOwnNetwork(hostPort, zMyPublicIp)) {
                continue;   // hairpin risk - proves nothing about external reachability
            }
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
                if (r.isOk()) {
                    return true;
                }
            } catch (Exception ignored) {
                // try the next relay
            }
        }
        return false;
    }

    /** True if this relay sits on our OWN NAT (its IP == our public IP), so a dial-back from
     *  it would hairpin and cannot prove genuine external reachability. */
    private static boolean isOwnNetwork(String zHostPort, String zMyPublicIp) {
        if (zMyPublicIp == null || zMyPublicIp.isEmpty()) {
            return false;
        }
        int colon = zHostPort.lastIndexOf(':');
        String host = colon < 0 ? zHostPort : zHostPort.substring(0, colon);
        if (host.equals(zMyPublicIp)) {
            return true;
        }
        try {
            return java.net.InetAddress.getByName(host).getHostAddress().equals(zMyPublicIp);
        } catch (Exception e) {
            return false;
        }
    }

    /** The phone's CURRENT public egress IP, fetched fresh (ipify → icanhazip). This is the
     *  address an external relay actually dials back, so it is what we advertise - never a
     *  stale hand-entered value. Empty (→ not advertised) if it can't be determined or isn't
     *  a routable public address. Blocking; runs on the state thread only. */
    private String currentPublicIp() {
        String ip = httpGet("https://api.ipify.org");
        if (ip.isEmpty()) {
            ip = httpGet("https://icanhazip.com");
        }
        return PortMapper.isPublic(ip) ? ip : "";
    }

    private static String httpGet(String zUrl) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(zUrl).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line = r.readLine();
                return line == null ? "" : line.trim();
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void withdraw(String zWhy) {
        if (mMapping != null) {
            mMapper.release(mMapping);
            mMapping = null;
        }
        boolean wasAdvertised = !mPublicAddress.isEmpty();
        if (wasAdvertised) {
            EventLog.add("DIRECT withdrawn (" + zWhy + ")");
        }
        mPublicAddress = "";
        mNode.setDirectAddress("");
        // Deliberately NOT stopDirect(): the listener is the service's to own,
        // and LAN discovery still needs it. We only retract the PUBLIC address.
        mState = State.OFF;
        mDetail = zWhy;
        // Only bother contacts if we actually had an address to retract.
        if (wasAdvertised) {
            refreshContacts();
        }
    }

    private void refreshContacts() {
        try {
            mRefresh.submit(mNode::refreshContacts);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutting down; contacts will reconcile on next start.
        }
    }

    private void submit(Runnable zTask) {
        try {
            mExec.submit(zTask);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // After shutdown; nothing to do.
        }
    }

    // ---- gates ----

    /**
     * The heaviest opportunistic duty gets the strictest gate: contribution on,
     * unmetered (Wi-Fi), and charging for the initial attempt. Cellular never
     * tries - carrier CGNAT makes it pointless and the mapper's public-IP check
     * would refuse the result anyway. Once ADVERTISED we keep it while on Wi-Fi
     * even off charge, since the expensive map+probe is already paid.
     */
    private boolean gatesPass() {
        if (!AndroidContribution.isEnabled(mCtx)) {
            return false;
        }
        if (!mPolicy.isUnmetered()) {
            return false;
        }
        // Manual mode is cheap (no router negotiation), so it doesn't need charging.
        if (!ManualForward.enabled(mCtx) && mState == State.OFF && !mPolicy.isCharging()) {
            return false;
        }
        return true;
    }

    private Blocker gateBlocker() {
        if (!AndroidContribution.isEnabled(mCtx)) {
            return Blocker.CONTRIB_OFF;
        }
        if (!mPolicy.isUnmetered()) {
            return Blocker.NEEDS_WIFI;
        }
        if (!ManualForward.enabled(mCtx) && mState == State.OFF && !mPolicy.isCharging()) {
            return Blocker.NEEDS_CHARGING;
        }
        return Blocker.NONE;
    }

    private String gateReason() {
        if (!AndroidContribution.isEnabled(mCtx)) {
            return "off (contribution disabled)";
        }
        if (!mPolicy.isUnmetered()) {
            return "off (needs Wi-Fi - CGNAT makes cellular pointless)";
        }
        if (mState == State.OFF && !mPolicy.isCharging()) {
            return "off (needs charging to start)";
        }
        return "ready";
    }

    /** The default-route gateway, where the platform will tell us. NAT-PMP needs it. */
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

    private static ThreadFactory daemon(String zName) {
        return r -> {
            Thread t = new Thread(r, zName);
            t.setDaemon(true);
            return t;
        };
    }
}
