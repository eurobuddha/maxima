package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.ChatPort;
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
import com.eurobuddha.maxima.desktop.jar.DesktopJarEngine;
import com.eurobuddha.maxima.desktop.jar.DesktopJarMigration;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * The desktop chat client's node. It runs ONE of two engines behind
 * {@link ChatPort}, exactly like the phone's {@code MaximaService}:
 *  - the built-in {@link MaximaNode} (fleet routing, the default), or
 *  - {@link DesktopJarEngine} — classic Maxima extracted whole ("Run as Maxima
 *    classic"), selected by the {@code engineJar} preference.
 * The {@link ChatEngine} rides whichever engine without knowing which. Flipping
 * carries identity + contacts across via {@link DesktopJarMigration}, mirroring
 * the phone's JarMigration one-for-one.
 */
public final class DesktopNode {

    private static final String PROTOCOL = "1.0.48";
    private static final int RELAY_TARGET = 2;
    public static final int DIRECT_PORT = 9536;
    public static final int RELAY_PORT = 9535;
    public static final int RELAY_RATE = 600;

    /** Preferences node + keys shared with SettingsPanel's engine toggle. */
    public static final String PREFS = "com/eurobuddha/maxima/desktop";
    public static final String KEY_ENGINE_JAR = "engineJar";
    public static final String KEY_FLIP_ANNOUNCE = "engineFlipAnnounce";

    public static boolean jarMode() {
        return Preferences.userRoot().node(PREFS).getBoolean(KEY_ENGINE_JAR, false);
    }

    private final boolean mJar;
    private MaximaNode mNode;                 // built-in engine (null in classic mode)
    private DesktopJarEngine mJarEngine;      // classic engine (null in built-in mode)
    private final ChatPort mPort;             // the active engine
    private final ChatEngine mChat;
    private final MediaService mMedia;
    private RelayGossipClient mGossip;        // built-in only
    private final MaximaIdentity mIdentity;
    private final Path mDataDir;
    private final DesktopRelayStore mRelayStore;

    private volatile boolean mRunning = true;
    private ScheduledExecutorService mMaint;

    // Direct reachability (built-in engine only).
    private ReachabilityManager mReach;
    private volatile ReachabilityManager.State mReachState = ReachabilityManager.State.OFF;
    private volatile String mReachAddr = "";
    private volatile String mReachDetail = "not started";

    private RelayRuntime mRelay;
    private volatile boolean mRelayRunning;
    private volatile RelayRuntime.Stats mRelayStats;

    private final List<Runnable> mChangeListeners = new CopyOnWriteArrayList<>();
    private final List<ChatEngine.Listener> mChatListeners = new CopyOnWriteArrayList<>();

    public DesktopNode(MaximaIdentity zId, Path zDataDir, String zDisplayName) {
        mIdentity = zId;
        mDataDir = zDataDir;
        mRelayStore = new DesktopRelayStore(zDataDir.toFile());
        File base = zDataDir.toFile();

        BlobStore blobs = new BlobStore(new File(base, "media"), 512L * 1024 * 1024);

        // Build the classic engine if requested — but NEVER let its failure brick
        // the app: on any boot error, archive the maybe-corrupt data and fall back
        // to the built-in engine, exactly like the phone's MaximaService.
        DesktopJarEngine jar = null;
        if (jarMode()) {
            try {
                java.util.List<String> hosts = new java.util.ArrayList<>();
                for (String h : mRelayStore.get()) {
                    hosts.add(h);
                    if (hosts.size() >= 2) break;
                }
                if (hosts.isEmpty()) {
                    for (String h : Bootstrap.RELAYS) {
                        hosts.add(h);
                        if (hosts.size() >= 2) break;
                    }
                }
                boolean migrate = DesktopJarMigration.needed(base);
                if (migrate) {
                    DesktopJarMigration.archiveOldJarData(base);
                }
                jar = new DesktopJarEngine(base, zDisplayName, hosts,
                        migrate ? () -> DesktopJarMigration.seedIdentity(base, zId) : null);
                if (migrate) {
                    DesktopJarMigration.markDone(base);
                }
                int merged = DesktopJarMigration.seedContacts(base, jar);
                if (merged > 0) {
                    DesktopEventLog.add("engine sync: merged " + merged
                            + " contact(s) from the built-in book");
                }
                Preferences pf = Preferences.userRoot().node(PREFS);
                if (pf.getBoolean(KEY_FLIP_ANNOUNCE, false)) {
                    pf.remove(KEY_FLIP_ANNOUNCE);
                    jar.announceContactsSoon();
                }
                if (DesktopJarMigration.inHealWindow(base)) {
                    jar.announceContactsSoon();
                }
            } catch (Throwable e) {
                DesktopEventLog.add("CLASSIC ENGINE BOOT FAILED, using built-in engine: " + e);
                try { DesktopJarMigration.archiveOldJarData(base); } catch (Exception ignored) { }
                jar = null;
            }
        }

        mJar = jar != null;
        if (mJar) {
            // ---- classic Maxima (jar) ----
            mJarEngine = jar;
            mPort = jar;
            mNode = null;
            mGossip = null;
            // Media LOCAL-ONLY (null node): inbound classic inline images are
            // stored/shown and our photos go out inline — same as the phone.
            mMedia = new MediaService(null, blobs);
            mChat = new ChatEngine(jar);
            mChat.setStore(new FileStore(new File(base, "chat")));
            mChat.setMediaService(mMedia);
            mChat.setListener(fanout());
            jar.setInbound((msg, msgid) -> {
                try { mChat.onInbound(msg, msgid == null ? "" : msgid); } catch (Exception ignored) { }
            });
            jar.setContactsChanged(() -> { DesktopEventLog.add("contacts changed (classic)"); fireChanged(); });
            DesktopEventLog.add("ENGINE: classic Maxima (jar)");
        } else {
            // ---- built-in engine (default) ----
            mNode = new MaximaNode(zId, PROTOCOL, RELAY_TARGET);
            mGossip = new RelayGossipClient(zId, PROTOCOL, 8);
            // The desktop wallet's gateway list follows relay discovery (see the phone).
            final MaximaNode gwNode = mNode;
            com.eurobuddha.maxima.desktop.wallet.DesktopWalletPublisher.setDiscoveredGateways(() -> {
                java.util.List<String[]> eps = new java.util.ArrayList<>();
                for (com.eurobuddha.maxima.core.session.PeerDiscovery.Gateway g : gwNode.discovery().gateways()) {
                    eps.add(new String[] {g.url, g.key});
                }
                return eps;
            });
            mNode.setStore(new FileStore(new File(base, "node")));
            mNode.setName(zDisplayName);
            mNode.setNodeKind("core");
            mMedia = new MediaService(mNode, blobs);
            mNode.setLocalBlobs(blobs);
            mPort = mNode;
            mChat = new ChatEngine(mNode);
            mChat.setStore(FileStore.coalescing(new File(base, "chat"), 2000));   // flushed before any mailbox ack
            mChat.setMediaService(mMedia);
            mChat.setListener(fanout());
            mNode.setLogListener(DesktopEventLog::add);
            mNode.setMessageListener((msg, msgid) -> {
                try { mChat.onInbound(msg, msgid == null ? "" : msgid.to0xString()); } catch (Exception ignored) { }
            });
            DesktopEventLog.add("ENGINE: built-in");
        }
        applySavedMls();   // re-pin a static MLS across restarts (phone parity)
    }

    private ChatEngine.Listener fanout() {
        return new ChatEngine.Listener() {
            public void onMessage(ChatEngine.Entry e) {
                for (ChatEngine.Listener l : mChatListeners) {
                    try { l.onMessage(e); } catch (Exception ignored) { }
                }
                fireChanged();
            }
            public void onStateChanged(ChatEngine.Entry e) {
                for (ChatEngine.Listener l : mChatListeners) {
                    try { l.onStateChanged(e); } catch (Exception ignored) { }
                }
                fireChanged();
            }
            public void onGroupChanged(Group g) {
                for (ChatEngine.Listener l : mChatListeners) {
                    try { l.onGroupChanged(g); } catch (Exception ignored) { }
                }
                fireChanged();
            }
        };
    }

    public MaximaNode node()       { return mNode; }          // null in classic mode
    public ChatPort port()         { return mPort; }          // the active engine (either)
    public boolean isJar()         { return mJar; }
    public DesktopJarEngine jarEngine() { return mJarEngine; }
    public ChatEngine chat()       { return mChat; }
    public MediaService media()    { return mMedia; }
    public MaximaIdentity identity() { return mIdentity; }
    public DesktopRelayStore relayStore() { return mRelayStore; }
    public Path dataDir()          { return mDataDir; }

    // ---- location service (MLS), engine-agnostic ----

    /** The current (rotating or pinned) location address, whichever engine. */
    public String mlsLocationAddress() {
        try {
            return mJar ? mJarEngine.mlsHost()
                    : (mNode != null ? mNode.mlsAddress() : "");
        } catch (Exception e) { return ""; }
    }

    /** The permanent MAX# address (empty until a static MLS is set). */
    public String permanentAddress() {
        try {
            return mJar ? mJarEngine.permanentAddress()
                    : (mNode != null ? mNode.permanentAddress() : "");
        } catch (Exception e) { return ""; }
    }

    public boolean isStaticMls() {
        try { return mJar ? mJarEngine.isStaticMls()
                : !Preferences.userRoot().node(PREFS).get("staticMls", "").isEmpty(); }
        catch (Exception e) { return false; }
    }

    /** Pin a static MLS (Mx…@host:port), persisted and applied to the engine. */
    public void setStaticMls(String m) {
        try {
            if (mJar) {
                mJarEngine.setStaticMls(m);
            } else if (mNode != null) {
                mNode.setStaticMls(m);
                new Thread(mNode::refreshContacts, "mls-refresh").start();
            }
            Preferences.userRoot().node(PREFS).put("staticMls", m == null ? "" : m);
        } catch (Exception ignored) { }
    }

    /** Register with a static-MLS pool (classic engine only). */
    public boolean registerWithPool(String apiBase, String serverId, String identity) {
        try { return mJar && mJarEngine.registerWithPool(apiBase, serverId, identity); }
        catch (Exception e) { return false; }
    }

    public boolean canRegisterPool() { return mJar; }

    /** Re-apply the pinned static MLS after a (re)boot, so it survives restarts. */
    private void applySavedMls() {
        try {
            String m = Preferences.userRoot().node(PREFS).get("staticMls", "");
            if (!m.isEmpty()) {
                if (mJar) mJarEngine.setStaticMls(m);
                else if (mNode != null) mNode.setStaticMls(m);
            }
        } catch (Exception ignored) { }
    }

    /** Connected host count, whichever engine is running. */
    public int connectedCount() {
        try {
            if (mJar) {
                return mJarEngine.connectedHosts().size();
            }
            return mNode != null && mNode.pool() != null ? mNode.pool().activeCount() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- direct reachability (built-in engine only) ----
    public ReachabilityManager.State reachState() { return mReachState; }
    public String reachAddress() { return mReachAddr; }
    public String reachDetail() { return mJar ? "classic engine manages its own routing" : mReachDetail; }

    public void checkReachability() {
        if (mJar || mNode == null) return;   // classic self-manages
        if (mReach == null) {
            startReachability();
        }
        if (mReach != null) {
            new Thread(mReach::tick, "reach-tick").start();
        }
    }

    private synchronized void startReachability() {
        if (mJar || mNode == null || mReach != null) return;
        // Bind the direct listener to the manually-forwarded port when manual mode
        // is on, so the router rule lines up; else the default direct port.
        int dp = DesktopManualForward.enabled() ? DesktopManualForward.port() : DIRECT_PORT;
        try { mNode.startDirect(dp); } catch (Exception ignored) { }
        mReach = new ReachabilityManager(mNode,
                () -> { int p = mNode.directPort(); return p > 0 ? p : dp; },
                ReachabilityManager.Gates.ALWAYS, new ReachabilityManager.Listener() {
            public void onVerified(String ipPort, String via) {
                mReachAddr = ipPort;
                mNode.setDirectAddress(ipPort);
                DesktopEventLog.add("DIRECT reachable at " + ipPort + " (via " + via + ")");
                fireChanged();
            }
            public void onLost(String why) {
                mReachAddr = "";
                mNode.setDirectAddress("");
                DesktopEventLog.add("DIRECT withdrawn: " + why);
                fireChanged();
            }
            public void onState(ReachabilityManager.State s, String detail) {
                mReachState = s;
                mReachDetail = detail;
                fireChanged();
            }
        });
        if (DesktopManualForward.enabled()) {
            mReach.setManual(DesktopManualForward.publicIp(), DesktopManualForward.port());
        }
        if (mMaint != null) {
            mMaint.scheduleWithFixedDelay(() -> {
                try { mReach.tick(); } catch (Exception ignored) { }
            }, 2, 60, TimeUnit.SECONDS);
        }
    }

    /** Apply the current manual port-forward settings (UI called after a change). */
    public void applyManualForward() {
        if (mJar || mNode == null) return;
        if (mReach == null) startReachability();
        if (mReach == null) return;
        if (DesktopManualForward.enabled()) {
            try { mNode.startDirect(DesktopManualForward.port()); } catch (Exception ignored) { }
            mReach.setManual(DesktopManualForward.publicIp(), DesktopManualForward.port());
        } else {
            try { mNode.startDirect(DIRECT_PORT); } catch (Exception ignored) { }
            mReach.clearManual();
        }
        new Thread(mReach::tick, "manual-fwd-tick").start();
    }

    // ---- relay (built-in engine only) ----
    public boolean relayRunning() { return mRelayRunning; }
    public RelayRuntime.Stats relayStats() { return mRelayStats; }
    public int relayPort() { return RELAY_PORT; }

    public synchronized void startRelay() {
        if (mRelay != null) return;
        try {
            mRelay = new RelayRuntime(mIdentity, RELAY_PORT, PROTOCOL, RELAY_RATE, "",
                    mDataDir.resolve("relay"));
            mRelay.setPool(false);   // in-process desktop relay: not a pool/permanent anchor until Phase-B mesh
            mRelay.setTickListener(s -> { mRelayStats = s; fireChanged(); });
            mRelay.start();
            mRelayRunning = true;
            DesktopEventLog.add("RELAY started on port " + RELAY_PORT);
            fireChanged();
        } catch (Exception e) {
            mRelay = null;
            DesktopEventLog.add("RELAY failed: " + e.getMessage());
        }
    }

    public synchronized void stopRelay() {
        if (mRelay == null) return;
        try { mRelay.stop(); } catch (Exception ignored) { }
        mRelay = null;
        mRelayRunning = false;
        mRelayStats = null;
        DesktopEventLog.add("RELAY stopped");
        fireChanged();
    }

    public void addChangeListener(Runnable r) { mChangeListeners.add(r); }
    public void removeChangeListener(Runnable r) { mChangeListeners.remove(r); }
    public void addChatListener(ChatEngine.Listener l) { mChatListeners.add(l); }

    public void removeChatListener(ChatEngine.Listener l) { mChatListeners.remove(l); }

    private void fireChanged() {
        for (Runnable r : mChangeListeners) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    /** Attach to relays (built-in) / confirm the classic engine is up, then pump. */
    public int start() {
        if (!mJar && mNode != null) {
            java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>(mRelayStore.get());
            cands.addAll(Bootstrap.RELAYS);
            mNode.start(new java.util.ArrayList<>(cands), 30_000);
        }
        // The classic engine connects to its hosts inside its own constructor.
        startPump();
        return connectedCount();
    }

    private void startPump() {
        mMaint = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "maxima-desktop-maint");
            t.setDaemon(true);
            return t;
        });
        if (!mJar && mNode != null) {
            mMaint.scheduleWithFixedDelay(() -> {
                try { mNode.maintain(20_000); } catch (Exception ignored) { }
                fireChanged();
            }, 20, 20, TimeUnit.SECONDS);
            mMaint.scheduleWithFixedDelay(() -> {
                try { mGossip.tick(mNode); } catch (Exception ignored) { }
            }, 15, 60, TimeUnit.SECONDS);
        } else {
            // Classic self-heals its hosts; still refresh the UI on a cadence.
            mMaint.scheduleWithFixedDelay(this::fireChanged, 10, 10, TimeUnit.SECONDS);
        }
        mMaint.scheduleWithFixedDelay(() -> {
            try { mChat.resendUndelivered(); } catch (Exception ignored) { }
        }, 30, 45, TimeUnit.SECONDS);
    }

    public void setReadReceipts(boolean on) {
        mChat.setSendReadReceipts(on);
    }

    // ---- seed (identity = spendable wallet seed) ----

    public String seedPhrase() {
        try {
            return new String(java.nio.file.Files.readAllBytes(mDataDir.resolve("seed.txt")),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public void restoreSeed(String phrase) throws Exception {
        MaximaIdentity.fromPhrase(phrase.trim());
        shutdown();
        wipe(mDataDir.resolve("node"));
        wipe(mDataDir.resolve("chat"));
        wipe(mDataDir.resolve("media"));
        wipe(mDataDir.resolve("maxjar"));
        // seed.txt is a wallet-grade secret — write it owner-only (0600) like the
        // create path, and atomically so a crash mid-write can't truncate the only
        // copy of the seed.
        java.nio.file.Path seedFile = mDataDir.resolve("seed.txt");
        java.nio.file.Path tmp = mDataDir.resolve("seed.txt.tmp");
        byte[] bytes = phrase.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(tmp, bytes);
        try {
            java.nio.file.Files.setPosixFilePermissions(tmp,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX FS (Windows) — ACLs apply instead.
        }
        try {
            java.nio.file.Files.move(tmp, seedFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp, seedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void wipe(Path dir) {
        try {
            File d = dir.toFile();
            if (!d.exists()) return;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) {
                if (f.isDirectory()) wipe(f.toPath()); else f.delete();
            }
            d.delete();
        } catch (Exception ignored) { }
    }

    public void shutdown() {
        mRunning = false;
        if (mMaint != null) {
            mMaint.shutdownNow();
        }
        try { if (mReach != null) mReach.shutdown(); } catch (Exception ignored) { }
        try { stopRelay(); } catch (Exception ignored) { }
        try { mChat.close(); } catch (Exception ignored) { }
        try {
            if (mJar && mJarEngine != null) {
                mJarEngine.shutdown();
            } else if (mNode != null) {
                mNode.stop();
            }
        } catch (Exception ignored) { }
    }
}
