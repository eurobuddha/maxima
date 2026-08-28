package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.media.MediaService;
import com.eurobuddha.maxima.core.net.ReachabilityManager;
import com.eurobuddha.maxima.core.session.Bootstrap;
import com.eurobuddha.maxima.core.session.RelayGossipClient;
import com.eurobuddha.maxima.core.store.BlobStore;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Parlons Cloud — the headless always-on node.
 *
 * This is the {@link com.eurobuddha.maxima.core.MaximaNode}-backed chat engine of
 * {@code DesktopNode}, lifted out from under Swing/Preferences and the classic-jar
 * option, and given a VPS personality: it holds ONE identity (the sole holder of its
 * routing key on the fleet — devices will drive it, never re-hold it), runs the chat
 * engine headless, and — because it is always-on and publicly reachable — ALSO runs an
 * in-process pool relay so that running your own account IS a contribution to the network.
 *
 * Phase 1: identity + chat + pool relay + fleet attach + direct reachability. Remote
 * control (device pairing, the owner control channel) and the watch-only wallet arrive in
 * later phases; the seams here (setMessageListener / the ChatEngine.Listener fan-out) are
 * where that control channel will bind.
 */
public final class ParlonsCore {

    private static final String PROTOCOL = "1.0.48";
    /** How many fleet relays the client half attaches to (sole-holder of its key on each). */
    private static final int RELAY_TARGET = 2;
    private static final int RELAY_RATE = 600;

    private final MaximaIdentity mIdentity;
    private final Path mDataDir;
    private final Config mCfg;

    private final MaximaNode mNode;
    private final RelayGossipClient mGossip;
    private final MediaService mMedia;
    private final ChatEngine mChat;
    private final DevicePairing mPairing;
    private final ParlonsControl mControl;

    private ScheduledExecutorService mMaint;
    private ReachabilityManager mReach;
    private RelayRuntime mRelay;
    private volatile boolean mRunning;

    /** Headless runtime configuration (from CLI/env — no Preferences on a VPS). */
    public static final class Config {
        public String displayName = "Parlons Cloud";
        /** Pool-relay listener port (0 = no relay). Public; peers dial it. */
        public int relayPort = 9501;
        /** The node's own direct listener port (Tier-2 reachability). 0 = off. */
        public int directPort = 9536;
        /** Public host to advertise for the relay (blank = say nothing). */
        public String publicHost = "";
        /** Extra fleet relays to attach to, on top of {@link Bootstrap#RELAYS}. */
        public List<String> extraRelays = new ArrayList<>();
        /** Mesh peers the pool relay forwards resolve-misses to (host:port). */
        public List<String> meshPeers = new ArrayList<>();
        /** Media blob-shelf cap for the relay (MB, 0 = off). */
        public int relayBlobMb = 1024;
    }

    public ParlonsCore(MaximaIdentity zIdentity, Path zDataDir, Config zConfig) {
        mIdentity = zIdentity;
        mDataDir = zDataDir;
        mCfg = zConfig;
        File base = zDataDir.toFile();

        BlobStore blobs = new BlobStore(new File(base, "media"), 512L * 1024 * 1024);

        // ---- built-in engine (the only engine on the cloud — no classic jar) ----
        mNode = new MaximaNode(zIdentity, PROTOCOL, RELAY_TARGET);
        mGossip = new RelayGossipClient(zIdentity, PROTOCOL, 8);
        mNode.setStore(new FileStore(new File(base, "node")));
        mNode.setName(zConfig.displayName);
        mNode.setNodeKind("core");
        mMedia = new MediaService(mNode, blobs);
        mNode.setLocalBlobs(blobs);
        mChat = new ChatEngine(mNode);
        mChat.setStore(new FileStore(new File(base, "chat")));
        mChat.setMediaService(mMedia);
        mChat.setListener(loggingListener());
        mNode.setLogListener(s -> log("node: " + s));
        mNode.setMessageListener((msg, msgid) -> {
            try {
                mChat.onInbound(msg, msgid == null ? "" : msgid.to0xString());
            } catch (Exception ignored) {
            }
        });

        // The owner control channel: paired devices drive the account over the encrypted
        // Maxima RPC substrate (no public port). RPC is dispatched before the chat engine
        // (MaximaNode routes RpcEnvelope.APPLICATION first), so control never hits chat.
        mPairing = new DevicePairing(zDataDir);
        mControl = new ParlonsControl(mNode, mChat, mPairing);
        mControl.registerOn(mNode.services());
    }

    public MaximaNode node() { return mNode; }
    public ChatEngine chat() { return mChat; }
    public MediaService media() { return mMedia; }
    public MaximaIdentity identity() { return mIdentity; }
    public boolean relayRunning() { return mRelay != null; }
    public RelayRuntime.Stats relayStats() { return mRelay == null ? null : null; }

    public int connectedCount() {
        try {
            return mNode.pool() != null ? mNode.pool().activeCount() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Stand everything up: attach to the fleet, start the pump, the pool relay, and
     *  Tier-2 direct reachability. Returns the connected-host count after attach. */
    public int start() {
        mRunning = true;

        // 1. Attach the client half to the fleet (bootstrap + configured extras).
        LinkedHashSet<String> relays = new LinkedHashSet<>(Bootstrap.RELAYS);
        relays.addAll(mCfg.extraRelays);
        mNode.start(new ArrayList<>(relays), 30_000);

        // 2. The maintenance pump — relay reconcile / MLS / outbox, gossip, resend.
        startPump();

        // 3. The in-process POOL relay: always-on + public => a real federation host.
        if (mCfg.relayPort > 0) {
            startRelay();
        }

        // 4. Tier-2 direct reachability: a VPS has a routable IP, so prove the port and
        //    advertise a zero-hop direct address for our contacts.
        if (mCfg.directPort > 0) {
            startReachability();
        }

        // 5. Pairing: if no device is paired yet, mint a one-time bootstrap code the operator
        //    reads over ssh (never logged — it gates account access).
        mPairing.ensureBootstrapCode();

        int hosts = connectedCount();
        log("started: identity " + mIdentity.mxIdentity());   // RULE 1: full identity, never truncated
        log("attached to " + hosts + " relay(s); permanent address " + safePermanent());
        if (mPairing.authorizedCount() == 0) {
            log("NO devices paired yet. To pair your first device, read the one-time code:");
            log("    cat " + mPairing.codeFile());
            log("  then enter it in the app pointed at this account's address above.");
        } else {
            log(mPairing.authorizedCount() + " device(s) paired.");
        }
        return hosts;
    }

    public DevicePairing pairing() {
        return mPairing;
    }

    private void startRelay() {
        try {
            mRelay = new RelayRuntime(mIdentity, mCfg.relayPort, PROTOCOL, RELAY_RATE,
                    mCfg.publicHost, mDataDir.resolve("relay"));
            mRelay.setPool(true);   // a cloud node is always-on + public => a pool/permanent-anchor host
            if (mCfg.relayBlobMb > 0) {
                mRelay.setBlobBytes((long) mCfg.relayBlobMb * 1024L * 1024L);
            }
            if (!mCfg.meshPeers.isEmpty()) {
                mRelay.setPeers(mCfg.meshPeers);
            }
            mRelay.setTickListener(s -> { /* Phase 2+: surface to the control channel */ });
            mRelay.start();
            log("pool relay up on port " + mCfg.relayPort
                    + (mCfg.meshPeers.isEmpty() ? "" : " (mesh: " + mCfg.meshPeers.size() + " peers)"));
        } catch (Exception e) {
            mRelay = null;
            log("pool relay could NOT start on port " + mCfg.relayPort + ": " + e.getMessage());
        }
    }

    private void startReachability() {
        try {
            mNode.startDirect(mCfg.directPort);
        } catch (Exception ignored) {
        }
        mReach = new ReachabilityManager(mNode,
                () -> {
                    int p = mNode.directPort();
                    return p > 0 ? p : mCfg.directPort;
                },
                ReachabilityManager.Gates.ALWAYS, new ReachabilityManager.Listener() {
            public void onVerified(String ipPort, String via) {
                mNode.setDirectAddress(ipPort);
                log("DIRECT reachable at " + ipPort + " (via " + via + ")");
            }
            public void onLost(String why) {
                mNode.setDirectAddress("");
                log("DIRECT withdrawn: " + why);
            }
            public void onState(ReachabilityManager.State s, String detail) {
                log("reachability: " + s + " — " + detail);
            }
        });
        if (mMaint != null) {
            mMaint.scheduleWithFixedDelay(() -> {
                try { mReach.tick(); } catch (Exception ignored) { }
            }, 2, 60, TimeUnit.SECONDS);
        }
    }

    private void startPump() {
        mMaint = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "parlons-cloud-maint");
            t.setDaemon(true);
            return t;
        });
        mMaint.scheduleWithFixedDelay(() -> {
            try { mNode.maintain(20_000); } catch (Exception ignored) { }
        }, 20, 20, TimeUnit.SECONDS);
        mMaint.scheduleWithFixedDelay(() -> {
            try { mGossip.tick(mNode); } catch (Exception ignored) { }
        }, 15, 60, TimeUnit.SECONDS);
        mMaint.scheduleWithFixedDelay(() -> {
            try { mChat.resendUndelivered(); } catch (Exception ignored) { }
        }, 30, 45, TimeUnit.SECONDS);
        // Publish our MLS record proactively — an always-on ACCOUNT must be resolvable by its
        // permanent MAX# BEFORE it has any contacts, so a paired device can log in. maintain()
        // only republishes when the host set changes, so a stable contactless account would
        // otherwise never publish. Every 5 min keeps the 24h record fresh; first push at ~8s.
        mMaint.scheduleWithFixedDelay(() -> {
            try { mNode.publishToMls(); } catch (Exception ignored) { }
        }, 8, 300, TimeUnit.SECONDS);
    }

    private ChatEngine.Listener loggingListener() {
        // Headless: no UI to update. Log inbound so a running node is observable; later
        // phases replace/augment this with the owner control-channel fan-out.
        return new ChatEngine.Listener() {
            public void onMessage(ChatEngine.Entry e) {
                if (!e.mine) {
                    log("message from " + shortPeer(e) + ": " + preview(e.body));
                }
            }
            public void onStateChanged(ChatEngine.Entry e) { }
            public void onGroupChanged(Group g) { }
        };
    }

    private String safePermanent() {
        try {
            String p = mNode.permanentAddress();
            return p == null || p.isEmpty() ? "(rotating — no static MLS pinned yet)" : p;
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static String shortPeer(ChatEngine.Entry e) {
        return e.sender == null ? (e.peer == null ? "?" : e.peer) : e.sender;
    }

    private static String preview(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    public void shutdown() {
        mRunning = false;
        if (mMaint != null) {
            mMaint.shutdownNow();
        }
        try { if (mReach != null) mReach.shutdown(); } catch (Exception ignored) { }
        try { if (mRelay != null) mRelay.stop(); } catch (Exception ignored) { }
        try { mChat.close(); } catch (Exception ignored) { }
        try { mNode.stop(); } catch (Exception ignored) { }
    }

    private static void log(String s) {
        System.out.println("[parlons-cloud] " + s);
    }
}
