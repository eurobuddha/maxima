package com.eurobuddha.maxima.desktop.ui;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The desktop chat client's node — the same {@link MaximaNode} + {@link ChatEngine}
 * the phone runs in {@code MaximaService}, wired for a desktop process: persistent
 * stores under the data dir, a message pump, pool maintenance, relay discovery via
 * gossip, and a fan-out listener the Swing panels subscribe to.
 *
 * It shares the machine's Maxima identity (the seed under the data dir) so the
 * desktop is ONE identity whether it is relaying or chatting.
 */
public final class DesktopNode {

    private static final String PROTOCOL = "1.0.48";
    private static final int RELAY_TARGET = 4;
    /** The desktop's own inbound direct port, and its relay port when it relays. */
    public static final int DIRECT_PORT = 9536;
    public static final int RELAY_PORT = 9535;
    public static final int RELAY_RATE = 600;

    private final MaximaNode mNode;
    private final ChatEngine mChat;
    private final MediaService mMedia;
    private final RelayGossipClient mGossip;
    private final MaximaIdentity mIdentity;
    private final Path mDataDir;
    private final DesktopRelayStore mRelayStore;

    private Thread mPump;
    private volatile boolean mRunning = true;
    private ScheduledExecutorService mMaint;

    // Direct reachability (map → prove → advertise) via the shared :core manager.
    private ReachabilityManager mReach;
    private volatile ReachabilityManager.State mReachState = ReachabilityManager.State.OFF;
    private volatile String mReachAddr = "";
    private volatile String mReachDetail = "not started";

    // Optional in-process relay (desktop is always eligible).
    private RelayRuntime mRelay;
    private volatile boolean mRelayRunning;
    private volatile RelayRuntime.Stats mRelayStats;

    private final List<Runnable> mChangeListeners = new CopyOnWriteArrayList<>();
    private final List<ChatEngine.Listener> mChatListeners = new CopyOnWriteArrayList<>();

    public DesktopNode(MaximaIdentity zId, Path zDataDir, String zDisplayName) {
        mIdentity = zId;
        mDataDir = zDataDir;
        mNode = new MaximaNode(zId, PROTOCOL, RELAY_TARGET);
        mGossip = new RelayGossipClient(zId, PROTOCOL, 8);
        mRelayStore = new DesktopRelayStore(zDataDir.toFile());

        File base = zDataDir.toFile();
        mNode.setStore(new FileStore(new File(base, "node")));
        mNode.setName(zDisplayName);
        mNode.setNodeKind("core");   // an always-on desktop is a core node

        BlobStore blobs = new BlobStore(new File(base, "media"), 512L * 1024 * 1024);
        mMedia = new MediaService(mNode, blobs);
        mNode.setLocalBlobs(blobs);

        mChat = new ChatEngine(mNode);
        mChat.setStore(new FileStore(new File(base, "chat")));
        mChat.setMediaService(mMedia);
        mChat.setListener(new ChatEngine.Listener() {
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
        });

        // Route inbound chat traffic into the engine (the phone does the same).
        mNode.setMessageListener((msg, msgid) -> {
            try {
                mChat.onInbound(msg);
            } catch (Exception ignored) {
            }
        });
    }

    public MaximaNode node()       { return mNode; }
    public ChatEngine chat()       { return mChat; }
    public MediaService media()    { return mMedia; }
    public MaximaIdentity identity() { return mIdentity; }
    public DesktopRelayStore relayStore() { return mRelayStore; }
    public Path dataDir()          { return mDataDir; }

    // ---- direct reachability ----
    public ReachabilityManager.State reachState() { return mReachState; }
    public String reachAddress() { return mReachAddr; }
    public String reachDetail() { return mReachDetail; }

    /** User "make me reachable / check now": start (or re-run) the map→prove→advertise. */
    public void checkReachability() {
        if (mReach == null) {
            startReachability();
        }
        if (mReach != null) {
            new Thread(mReach::tick, "reach-tick").start();
        }
    }

    private synchronized void startReachability() {
        if (mReach != null) return;
        try { mNode.startDirect(DIRECT_PORT); } catch (Exception ignored) { }
        mReach = new ReachabilityManager(mNode,
                () -> { int p = mNode.directPort(); return p > 0 ? p : DIRECT_PORT; },
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
        // Heartbeat: re-prove / renew every 60s.
        if (mMaint != null) {
            mMaint.scheduleWithFixedDelay(() -> {
                try { mReach.tick(); } catch (Exception ignored) { }
            }, 2, 60, TimeUnit.SECONDS);
        }
    }

    // ---- relay ----
    public boolean relayRunning() { return mRelayRunning; }
    public RelayRuntime.Stats relayStats() { return mRelayStats; }
    public int relayPort() { return RELAY_PORT; }

    public synchronized void startRelay() {
        if (mRelay != null) return;
        try {
            mRelay = new RelayRuntime(mIdentity, RELAY_PORT, PROTOCOL, RELAY_RATE, "",
                    mDataDir.resolve("relay"));
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

    /** Subscribe to "something changed" — fired on any inbound/state/group event. */
    public void addChangeListener(Runnable r) { mChangeListeners.add(r); }
    public void removeChangeListener(Runnable r) { mChangeListeners.remove(r); }
    public void addChatListener(ChatEngine.Listener l) { mChatListeners.add(l); }

    private void fireChanged() {
        for (Runnable r : mChangeListeners) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    /** Attach to the configured + bootstrap relays and start pumping. */
    public int start() {
        java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>(mRelayStore.get());
        cands.addAll(Bootstrap.RELAYS);
        int attached = mNode.start(new java.util.ArrayList<>(cands), 30_000);
        startPump();
        return attached;
    }

    private void startPump() {
        // Receiving is PUSH now: :core runs a dedicated reader per attached host
        // (25s NAT keep-alive + instant inbound), and the ChatEngine listener
        // already fires the UI. No polling loop needed - only maintain below.

        mMaint = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "maxima-desktop-maint");
            t.setDaemon(true);
            return t;
        });
        mMaint.scheduleWithFixedDelay(() -> {
            try {
                mNode.maintain(20_000);
            } catch (Exception ignored) {
            }
            fireChanged();
        }, 20, 20, TimeUnit.SECONDS);
        mMaint.scheduleWithFixedDelay(() -> {
            try {
                mGossip.tick(mNode);
            } catch (Exception ignored) {
            }
        }, 15, 60, TimeUnit.SECONDS);
        // Resend anything that didn't get a delivery receipt.
        mMaint.scheduleWithFixedDelay(() -> {
            try {
                mChat.resendUndelivered();
            } catch (Exception ignored) {
            }
        }, 30, 45, TimeUnit.SECONDS);
    }

    public void setReadReceipts(boolean on) {
        mChat.setSendReadReceipts(on);
    }

    // ---- seed (identity = spendable wallet seed) ----

    /** The 24 words, read from the seed file. Empty if unavailable. */
    public String seedPhrase() {
        try {
            return new String(java.nio.file.Files.readAllBytes(mDataDir.resolve("seed.txt")),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Replace this machine's identity with the one the phrase derives: validate,
     * stop the node, wipe node/chat/media so the old identity can't mix in, and
     * write the new seed. The caller restarts the process to load it.
     */
    public void restoreSeed(String phrase) throws Exception {
        MaximaIdentity.fromPhrase(phrase.trim());   // throws if the phrase is invalid
        shutdown();
        wipe(mDataDir.resolve("node"));
        wipe(mDataDir.resolve("chat"));
        wipe(mDataDir.resolve("media"));
        java.nio.file.Files.write(mDataDir.resolve("seed.txt"),
                phrase.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        if (mPump != null) {
            mPump.interrupt();
        }
        if (mMaint != null) {
            mMaint.shutdownNow();
        }
        try { if (mReach != null) mReach.shutdown(); } catch (Exception ignored) { }
        try { stopRelay(); } catch (Exception ignored) { }
        try {
            mChat.close();
        } catch (Exception ignored) {
        }
        try {
            mNode.stop();
        } catch (Exception ignored) {
        }
    }
}
