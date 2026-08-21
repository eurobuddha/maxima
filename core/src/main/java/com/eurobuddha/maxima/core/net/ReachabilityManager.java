package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.portmap.PortMapper;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Turn a lucky network into a genuine, PROVEN public address — platform-neutral.
 *
 *   OFF ──gates pass──▶ MAPPING ──got a port──▶ PROBING ──proven──▶ ADVERTISED
 *    ▲                                                                   │
 *    └──────── withdraw + onLost on loss / gate-off / renew fail ────────┘
 *
 * The cardinal rule, inherited from every layer below: advertise NOTHING that
 * has not just been proven reachable from OUTSIDE. Testing your own port is
 * worthless (hairpin NAT); proof is an existing relay dialling you back and a
 * greeting coming home ({@link Probe}). Publishing an address you merely hope
 * works is the exact classic-Maxima failure this tier exists to end — and the
 * desktop lesson from minimaCore's own code was identical: a router can accept a
 * port mapping and still not open it.
 *
 * This is the platform-neutral spine of the Android {@code DirectReachability}.
 * The two things that differ per platform are injected:
 *   - {@link Gates}: may we attempt / stay, and where is the gateway. Android
 *     gates on charging + unmetered Wi-Fi; a desktop relay always wants in.
 *   - {@link Listener}: what to do with a proven address. A phone sets its direct
 *     contact address; a relay sets its advertised public host.
 * The port to map+prove is an {@link IntSupplier} — a phone's DirectEndpoint
 * port, or a relay's own listen port.
 *
 * CONCURRENCY: every state operation runs on ONE dedicated thread, so there are
 * no locks. A network change bumps a generation counter; any in-flight attempt
 * whose generation is stale refuses to advertise — closing the window where we
 * might publish an address just "proven" on a network that has already gone.
 */
public final class ReachabilityManager {

    public enum State { OFF, MAPPING, PROBING, ADVERTISED }

    /** May we try, and where is the router? Platform supplies this. */
    public interface Gates {
        /** May we attempt (when OFF) or stay reachable (when ADVERTISED)? */
        boolean pass(State current);

        /** Human-readable reason for the current gate verdict. */
        String reason();

        /** The default-route gateway for NAT-PMP, or null to let PortMapper guess. */
        default InetAddress gatewayHint() {
            return null;
        }

        /** Always reachable, no gateway hint — a desktop relay that always wants a public port. */
        Gates ALWAYS = new Gates() {
            public boolean pass(State current) {
                return true;
            }

            public String reason() {
                return "ready";
            }
        };
    }

    /** What to do as reachability changes. Platform supplies this. */
    public interface Listener {
        /** Proven reachable at {@code ip:port} via {@code natpmp}/{@code upnp}. */
        default void onVerified(String ipPort, String via) {
        }

        /** No longer reachable (network change, renewal failure, gate closed). */
        default void onLost(String why) {
        }

        /** State/detail changed, for a status display. */
        default void onState(State state, String detail) {
        }
    }

    /** Re-prove from scratch at most this often, to catch a silently-changed WAN IP. */
    private static final long REPROVE_INTERVAL_MS = 20 * 60 * 1000;

    private final MaximaNode mProbeClient;
    private final IntSupplier mListenPort;
    private final Gates mGates;
    private final Listener mListener;

    private final ExecutorService mExec = Executors.newSingleThreadExecutor(daemon("reach-state"));

    private volatile State mState = State.OFF;
    private volatile String mPublicAddress = "";
    private volatile String mDetail = "not started";
    private volatile boolean mStopping;
    private volatile int mGen;

    private final PortMapper mMapper = new PortMapper();
    private PortMapper.Mapping mMapping;
    private long mMappedAtMs;
    private long mProvenAtMs;

    // Manual port-forward: when set, skip UPnP and prove this public IP + port.
    private volatile String mManualIp;
    private volatile int mManualPort;

    public ReachabilityManager(MaximaNode zProbeClient, IntSupplier zListenPort,
                               Gates zGates, Listener zListener) {
        mProbeClient = zProbeClient;
        mListenPort = zListenPort;
        mGates = zGates;
        mListener = zListener;
    }

    /** Enter manual port-forward mode: prove + advertise this public IP:port
     *  instead of asking the router for a mapping. Re-verified periodically. */
    public void setManual(String zPublicIp, int zPort) {
        mManualIp = zPublicIp == null ? "" : zPublicIp.trim();
        mManualPort = zPort;
        mGen++;
        submit(() -> { withdraw("switching to manual"); doTick(); });
    }

    /** Leave manual mode; the next tick returns to automatic (UPnP) mapping. */
    public void clearManual() {
        mManualIp = null;
        mManualPort = 0;
        mGen++;
        submit(() -> withdraw("manual forward off"));
    }

    public boolean isManual() {
        return mManualIp != null && !mManualIp.isEmpty();
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

    /** Heartbeat entry. Returns immediately; the work runs on the state thread. */
    public void tick() {
        submit(this::doTick);
    }

    /** Network changed or was lost: invalidate any in-flight attempt, then withdraw. */
    public void onNetworkChanged() {
        mGen++;
        submit(() -> withdraw("network changed"));
    }

    public void shutdown() {
        mStopping = true;
        mGen++;
        submit(() -> withdraw("stopping"));
        mExec.shutdown();
        try {
            mExec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------

    private void doTick() {
        if (mStopping) {
            return;
        }
        if (!mGates.pass(mState)) {
            if (mState != State.OFF) {
                withdraw("conditions no longer met");
            }
            setDetail(mGates.reason());
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
                // cleanly rather than re-entering half-initialised.
                withdraw("retrying");
                attempt();
        }
    }

    private void attempt() {
        int gen = mGen;

        if (mMapping != null) {
            mMapper.release(mMapping);
            mMapping = null;
        }

        int listenPort = mListenPort.getAsInt();
        if (listenPort <= 0) {
            setState(State.OFF, "listener not running");
            return;
        }

        // MANUAL PORT-FORWARD: the user forwarded a port on the router by hand and
        // gave us the public IP, so skip UPnP entirely and PROVE that port from
        // outside (a relay dials our source IP:port back) before advertising it —
        // the same never-advertise-on-hope rule as the automatic path.
        String manualIp = mManualIp;
        if (manualIp != null && !manualIp.isEmpty()) {
            int extPort = mManualPort > 0 ? mManualPort : listenPort;
            setState(State.PROBING,
                    "verifying " + manualIp + ":" + extPort + " (manual forward)…");
            boolean ok = proveReachable(extPort);
            if (mStopping || gen != mGen || !mGates.pass(mState)) {
                setState(State.OFF, "network changed during verification");
                return;
            }
            if (!ok) {
                setState(State.OFF, "manual forward: port " + extPort
                        + " is not open from outside — check the router rule");
                return;
            }
            mMapping = null;   // manual: no UPnP lease to hold or renew
            mPublicAddress = manualIp + ":" + extPort;
            mProvenAtMs = System.currentTimeMillis();
            mState = State.ADVERTISED;
            mDetail = "reachable at " + mPublicAddress + " (manual forward)";
            mListener.onState(mState, mDetail);
            mListener.onVerified(mPublicAddress, "manual");
            return;
        }

        // 1. ask the router for a public mapping of that port
        setState(State.MAPPING, "asking the router for a public port…");
        mMapper.setGatewayHint(mGates.gatewayHint());
        PortMapper.Mapping m = mMapper.map(listenPort);
        if (m == null) {
            setState(State.OFF, "this network has no forwardable public port");
            return;
        }

        // The network may have changed while map() blocked. If so, this mapping
        // belongs to a network that is gone — release it and do not advertise.
        if (mStopping || gen != mGen || !mGates.pass(mState)) {
            mMapper.release(m);
            setState(State.OFF, "network changed during mapping");
            return;
        }
        mMapping = m;
        mMappedAtMs = System.currentTimeMillis();

        // 2. PROVE it from outside before advertising a single thing
        setState(State.PROBING, "verifying " + m.externalIp + ":" + m.externalPort + " from outside…");
        boolean reachable = proveReachable(m.externalPort);

        if (mStopping || gen != mGen || !mGates.pass(mState)) {
            mMapper.release(m);
            mMapping = null;
            setState(State.OFF, "network changed during verification");
            return;
        }
        if (!reachable) {
            mMapper.release(m);
            mMapping = null;
            setState(State.OFF, "mapped a port but it was not reachable from outside");
            return;
        }

        // 3. advertise
        mPublicAddress = m.externalIp + ":" + m.externalPort;
        mProvenAtMs = System.currentTimeMillis();
        mState = State.ADVERTISED;
        mDetail = "reachable at " + mPublicAddress + " via " + m.via;
        mListener.onState(mState, mDetail);
        mListener.onVerified(mPublicAddress, m.via);
    }

    private void renewIfDue() {
        // Manual mode has no UPnP lease — just re-prove periodically so a changed
        // WAN IP or a dropped router rule stops us advertising a dead address.
        if (mManualIp != null && !mManualIp.isEmpty()) {
            if (System.currentTimeMillis() - mProvenAtMs > REPROVE_INTERVAL_MS) {
                withdraw("periodic re-verification");
                attempt();
            }
            return;
        }
        if (mMapping == null) {
            setState(State.OFF, "mapping lost");
            return;
        }
        // Periodically re-prove from scratch: a WAN IP can change under a stable
        // link with NO network-change event, and a renewed lease on a stale IP
        // would keep us advertising an address that is now wrong.
        if (System.currentTimeMillis() - mProvenAtMs > REPROVE_INTERVAL_MS) {
            withdraw("periodic re-verification");
            attempt();
            return;
        }

        long ageMs = System.currentTimeMillis() - mMappedAtMs;
        long halfLifeMs = mMapping.lifetimeSeconds * 1000L / 2;
        if (ageMs < halfLifeMs) {
            return;
        }
        if (mMapper.renew(mMapping)) {
            mMappedAtMs = System.currentTimeMillis();
            setDetail("lease renewed for " + mPublicAddress);
        } else {
            withdraw("lease renewal failed");
        }
    }

    /**
     * Ask a connected relay to dial us back. Reachable iff a relay's ack is OK.
     * A relay that cannot itself reach us tells us nothing, so try each until one
     * answers definitively.
     */
    private boolean proveReachable(int zExternalPort) {
        for (String hostPort : mProbeClient.pool().activeHosts()) {
            HostConnection c = mProbeClient.pool().connection(hostPort);
            if (c == null) {
                continue;
            }
            String relayAddr = c.getTheirMlsAddress();
            if (relayAddr == null) {
                continue;
            }
            try {
                MaximaSender.Result r = mProbeClient.sendRaw(relayAddr, Probe.APPLICATION,
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

    private void withdraw(String zWhy) {
        if (mMapping != null) {
            mMapper.release(mMapping);
            mMapping = null;
        }
        boolean wasAdvertised = !mPublicAddress.isEmpty();
        mPublicAddress = "";
        mState = State.OFF;
        mDetail = zWhy;
        mListener.onState(mState, mDetail);
        if (wasAdvertised) {
            mListener.onLost(zWhy);
        }
    }

    private void setState(State zState, String zDetail) {
        mState = zState;
        mDetail = zDetail;
        mListener.onState(zState, zDetail);
    }

    private void setDetail(String zDetail) {
        mDetail = zDetail;
        mListener.onState(mState, zDetail);
    }

    private void submit(Runnable zTask) {
        try {
            mExec.submit(zTask);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // After shutdown; nothing to do.
        }
    }

    private static ThreadFactory daemon(String zName) {
        return r -> {
            Thread t = new Thread(r, zName);
            t.setDaemon(true);
            return t;
        };
    }
}
