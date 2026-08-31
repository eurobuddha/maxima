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
    private final WatchWallet mWallet;
    private final ParlonsControl mControl;

    private ScheduledExecutorService mMaint;
    private ReachabilityManager mReach;
    private RelayRuntime mRelay;
    private volatile boolean mRunning;
    private volatile long mStartedAt;

    /** Headless runtime configuration (from CLI/env — no Preferences on a VPS). */
    public static final class Config {
        /** Explicit --name override; null = keep whatever name the account has stored. */
        public String displayName = null;
        /** Build version reported on the Node tab (set from Main.VERSION). */
        public String version = "";
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
        mNodeStore = new FileStore(new File(base, "node"));
        mNode.setStore(mNodeStore);
        // The account's name is the USER's (set from a device via parlons.identity.setname and
        // persisted by the node). An unconditional set here clobbered it on every restart —
        // config only wins when --name was EXPLICITLY given; the default applies on first boot.
        if (zConfig.displayName != null && !zConfig.displayName.isEmpty()) {
            mNode.setName(zConfig.displayName);
        } else if (mNode.name() == null || mNode.name().isEmpty()) {
            mNode.setName("Parlons Cloud");
        }
        mNode.setNodeKind("core");
        mMedia = new MediaService(mNode, blobs);
        mNode.setLocalBlobs(blobs);
        mChat = new ChatEngine(mNode);
        mChat.setStore(new FileStore(new File(base, "chat")));
        mChat.setMediaService(mMedia);
        // (listener wired AFTER mControl below — it fans events out through the control push)
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
        mWallet = new WatchWallet(zDataDir);
        mControl = new ParlonsControl(mNode, mChat, mPairing, mWallet);
        mControl.setStatusSource(new ParlonsControl.StatusSource() {
            public long uptimeMillis() { return mStartedAt == 0 ? 0 : System.currentTimeMillis() - mStartedAt; }
            public String version()    { return mCfg.version; }
            public int hosts()         { return connectedCount(); }
            public boolean relayOn()   { return relayRunning(); }
            public int meshPeers()     { return mCfg.meshPeers.size(); }
        });
        // Account settings — persisted across restarts (the engine's flags are volatile).
        mSettingsFile = new File(base, "cloud-settings.properties");
        loadSettings();
        mChat.setSendReadReceipts(readReceiptsSetting());
        mControl.setSettingsSink(new ParlonsControl.SettingsSink() {
            public boolean readReceipts() {
                return readReceiptsSetting();
            }
            public void setReadReceipts(boolean zSend) {
                mChat.setSendReadReceipts(zSend);
                mSettings.setProperty("readreceipts", String.valueOf(zSend));
                saveSettings();
            }
        });
        mControl.setPaySource(new ParlonsControl.PaySource() {
            public String myWalletAddress() {
                return mWalletMx;
            }
            public CloudPaymentSender sender() {
                return mPaymentSender;
            }
            public String walletError() {
                return mWalletError;
            }
        });
        mControl.setBackupSource(new ParlonsControl.BackupSource() {
            public String revealPhrase() throws Exception {
                return CloudBackupManager.readPhrase(mDataDir);
            }
            public byte[] exportBackup(char[] zPassword) throws Exception {
                return CloudBackupManager.export(mDataDir, mNodeStore, mNode.name(), zPassword);
            }
        });
        mControl.registerOn(mNode.services());
        mChat.setListener(loggingListener());
        // Inbound CALL signals for the account ring the paired devices (WebRTC terminates on the
        // device; we relay the opaque SDP/ICE). Without this, an offer was silently swallowed.
        mChat.setCallListener((from, cm) -> mControl.forwardCallSignal(from, cm));
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
        mStartedAt = System.currentTimeMillis();
        openAccountWallet();

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
            try { healStuckPeers(); } catch (Exception ignored) { }
            try { mChat.resendUndelivered(); } catch (Exception ignored) { }
        }, 30, 45, TimeUnit.SECONDS);
        mMaint.scheduleWithFixedDelay(() -> {
            try { mControl.maintenanceSweep(); } catch (Exception ignored) { }
            // Wallet upkeep does blocking gateway HTTP (2+2N calls) — its OWN thread, so a slow
            // gateway can't starve maintain()/gossip/resend/MLS on the shared maint executor.
            if (mWalletUpkeepRunning.compareAndSet(false, true)) {
                mWalletExec.execute(() -> {
                    try { walletUpkeep(); } catch (Exception ignored) { }
                    finally { mWalletUpkeepRunning.set(false); }
                });
            }
        }, 60, 60, TimeUnit.SECONDS);
        // A restart gives this node FRESH relay addresses; until contacts learn them, their
        // replies rot in the old addresses' mailboxes (core's first refresh is at +3min — a
        // long deaf window for an "always-on" account). Announce early, once, then core's
        // 20-min loop owns it.
        mMaint.schedule(() -> {
            try { mNode.refreshContacts(); } catch (Exception ignored) { }
        }, 15, TimeUnit.SECONDS);
        // Publish our MLS record proactively — an always-on ACCOUNT must be resolvable by its
        // permanent MAX# BEFORE it has any contacts, so a paired device can log in. maintain()
        // only republishes when the host set changes, so a stable contactless account would
        // otherwise never publish. Every 5 min keeps the 24h record fresh; first push at ~8s.
        mMaint.scheduleWithFixedDelay(() -> {
            try { mNode.publishToMls(); } catch (Exception ignored) { }
        }, 8, 300, TimeUnit.SECONDS);
    }

    /** The LIVE node store — backup export reads through it (a second FileStore over a
     *  running store risks stale reads). */
    private FileStore mNodeStore;

    // ---- the account's own wallet (the Parlons pattern: the seed IS the wallet) ----
    private volatile CloudWallet mAccountWallet;
    private volatile CloudPaymentSender mPaymentSender;
    private volatile String mWalletMx = "";
    private volatile String mWalletError = "";
    private volatile boolean mScriptTracked;
    /** Wallet upkeep runs on its OWN thread (blocking gateway HTTP), never the maint executor. */
    private final java.util.concurrent.ExecutorService mWalletExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "parlons-wallet-upkeep");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicBoolean mWalletUpkeepRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Open the account wallet off-thread (the first WOTS address derivation takes seconds),
     * publish the pay source, track our script on the gateway (so our coins carry proofs —
     * idempotent, can never move funds) and share our receive address with every contact,
     * exactly as the app does when a chat opens.
     */
    private void openAccountWallet() {
        Thread t = new Thread(() -> {
            try {
                CloudWallet w = CloudWallet.open(mIdentity, new File(mDataDir.toFile(), "wallet"));
                w.ensureAddress();
                mWalletMx = w.mxAddress();
                mAccountWallet = w;
                mPaymentSender = new CloudPaymentSender(w, mWallet);
                log("account wallet ready: " + mWalletMx
                        + " (key uses " + w.uses() + " / " + CloudWallet.MAX_USES + ")");
                // trackScript + address-share retry on the maintenance heartbeat: this first
                // attempt runs pre-attach and a swallowed failure here used to break payments
                // (no proofs) and receivability (address never shared) until a restart.
                walletUpkeep();
            } catch (Exception e) {
                mWalletError = e.getMessage() == null ? e.toString() : e.getMessage();
                log("account wallet failed to open: " + mWalletError);
            }
        }, "parlons-wallet-open");
        t.setDaemon(true);
        t.start();
    }

    /** Last share time per contact key — the address is re-shared hourly (cheap idempotent
     *  control record; the app re-shares on every chat open). */
    private final java.util.Map<String, Long> mAddrShared =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Wallet upkeep, retried on the heartbeat: gateway script tracking (payments need the
     *  proofs) and receive-address shares to contacts. Cheap, idempotent, network-bound. */
    private void walletUpkeep() {
        CloudWallet w = mAccountWallet;
        if (w == null) {
            return;
        }
        if (!mScriptTracked) {
            try {
                mWallet.trackScript(w.script());
                mScriptTracked = true;
                log("wallet script tracked on the gateway");
            } catch (Exception e) {
                log("wallet script tracking failed (will retry): " + e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        for (com.eurobuddha.maxima.core.contacts.Contact c : mNode.contacts()) {
            String key = c.publicKey == null ? "" : c.publicKey;
            Long last = mAddrShared.get(key);
            if (last != null && now - last < 3600_000L) {
                continue;
            }
            try {
                mChat.shareWalletAddress(c, mWalletMx);
                mAddrShared.put(key, now);
            } catch (Exception ignored) {
                // offline peer — retried next heartbeat round
            }
        }
        syncCoinsIfLagging(w, now);
    }

    private volatile long mLastCoinSync;

    /**
     * Make funded-before-tracked coins spendable — the app's exact backfill (WalletPage
     * syncCoins): a coin that arrived BEFORE the gateway tracked our script is confirmed
     * on-chain (balance shows it) but not in the gateway node's tracked set, so txnbasics
     * can't attach a proof and sendable stays 0. Fix: coinexport each of our coins from the
     * global MegaMMR and coinimport track:true it back. NEVER signs, burns no key use —
     * safe to auto-run. Rate-limited to one pass per 5 min.
     */
    private void syncCoinsIfLagging(CloudWallet w, long now) {
        if (now - mLastCoinSync < 5 * 60_000L) {
            return;
        }
        mLastCoinSync = now;
        try {
            org.minima.utils.json.JSONObject bal =
                    mWallet.cmd("balance megammr:true address:" + w.mxAddress());
            org.minima.utils.json.JSONArray arr =
                    (org.minima.utils.json.JSONArray) bal.get("response");
            boolean lagging = false;
            if (arr != null) {
                for (Object o : arr) {
                    org.minima.utils.json.JSONObject t = (org.minima.utils.json.JSONObject) o;
                    if ("0x00".equals(String.valueOf(t.get("tokenid")))
                            && !String.valueOf(t.get("confirmed"))
                                    .equals(String.valueOf(t.get("sendable")))) {
                        lagging = true;
                    }
                }
            }
            if (!lagging) {
                return;
            }
            org.minima.utils.json.JSONObject coinsResp = mWallet.coins(w.hexAddress());
            org.minima.utils.json.JSONArray coins =
                    (org.minima.utils.json.JSONArray) coinsResp.get("response");
            int imported = 0, failed = 0;
            if (coins != null) {
                for (Object o : coins) {
                    String coinid = String.valueOf(
                            ((org.minima.utils.json.JSONObject) o).get("coinid"));
                    if (coinid.isEmpty() || "null".equals(coinid)) {
                        continue;
                    }
                    try {
                        org.minima.utils.json.JSONObject ex = mWallet.coinExport(coinid);
                        org.minima.utils.json.JSONObject resp =
                                (org.minima.utils.json.JSONObject) ex.get("response");
                        String data = resp == null ? "" : String.valueOf(resp.get("data"));
                        if (data.isEmpty() || "null".equals(data)) {
                            failed++;
                            continue;
                        }
                        mWallet.coinImport(data);
                        imported++;
                    } catch (Exception coinErr) {
                        failed++;
                    }
                }
            }
            log("wallet coin sync: imported=" + imported + " failed=" + failed);
        } catch (Exception e) {
            log("wallet coin sync failed: " + e.getMessage());
        }
    }

    // ---- persisted account settings (read receipts etc.) ----
    private final java.util.Properties mSettings = new java.util.Properties();
    private File mSettingsFile;

    private void loadSettings() {
        try (java.io.FileInputStream in = new java.io.FileInputStream(mSettingsFile)) {
            mSettings.load(in);
        } catch (Exception ignored) {
            // first boot — defaults apply
        }
    }

    private synchronized void saveSettings() {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(mSettingsFile)) {
            mSettings.store(out, "parlons cloud account settings");
        } catch (Exception e) {
            log("could not save settings: " + e.getMessage());
        }
    }

    private boolean readReceiptsSetting() {
        return Boolean.parseBoolean(mSettings.getProperty("readreceipts", "true"));
    }

    /** Per-peer receipt-heal rate limit (peer key → last heal attempt millis). */
    private final java.util.Map<String, Long> mLastHeal = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The receipt heal — the staleness case the core self-heal can't see.
     *
     * Core's send path only re-resolves a contact when EVERY address fails at the socket
     * (MaximaNode.sendToContact step 3). But a STALE relay still ACCEPTS a store-and-forward
     * blob, so a send to a mailbox the peer no longer reads looks "delivered" at the socket and
     * the heal never fires — the message rots at one tick while the resend heartbeat re-posts
     * it into the same dead mailbox forever. And inbound traffic keeps the contact's lastSeen
     * fresh, so the 30-min checkStaleMls never considers them stale either.
     *
     * Fix, before every resend beat: any peer with an outbound entry stuck short of DELIVERED
     * (older than a settle window, younger than the resend cap) gets an mlsLookup — which
     * persists their CURRENT address at the front of the contact's set — so the very next
     * resend goes where they actually are. Rate-limited per peer; one directory GET is cheap.
     */
    private void healStuckPeers() {
        long now = System.currentTimeMillis();
        for (ChatEngine.Summary s : mChat.summaries()) {
            String peer = s.conversation;
            if (!s.lastMine) {
                continue;   // their message is newest — nothing of ours can be freshly stuck
            }
            com.eurobuddha.maxima.core.contacts.Contact c =
                    peer == null || peer.isEmpty() ? null : mNode.contact(peer);
            if (c == null) {
                continue;                                  // a group, or an unknown key
            }
            Long last = mLastHeal.get(peer);
            if (last != null && now - last < 240_000) {
                continue;                                  // healed recently — let it settle
            }
            boolean stuck = false;
            for (ChatEngine.Entry e : mChat.conversation(peer)) {
                if (!e.mine || e.state == null) {
                    continue;
                }
                if (!"sent".equals(e.state) && !"failed".equals(e.state)) {
                    continue;                              // delivered/read = receipted, fine
                }
                long age = now - e.time;
                if (age > 60_000 && age < 24L * 3600_000) {
                    stuck = true;
                    break;
                }
            }
            if (!stuck) {
                continue;
            }
            mLastHeal.put(peer, now);
            if (mNode.mlsLookup(c)) {
                log("receipt-heal " + c.name + ": directory checked, sending to their current address");
            }
        }
    }

    private ChatEngine.Listener loggingListener() {
        // Headless: no local UI — but paired DEVICES are the UI, so every event fans out to
        // them over the push channel (instant chat, live ticks, notifications on the portal).
        return new ChatEngine.Listener() {
            public void onMessage(ChatEngine.Entry e) {
                if (!e.mine) {
                    log("message from " + shortPeer(e) + ": " + preview(e.body));
                    mControl.pushMessage(e);
                }
            }
            public void onStateChanged(ChatEngine.Entry e) {
                mControl.pushState(e);
            }
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
