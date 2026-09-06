package com.eurobuddha.maxima.desktop;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.ReachabilityManager;
import com.eurobuddha.maxima.core.session.Bootstrap;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The set-and-forget Maxima desktop node.
 *
 * One self-contained Java app that IS a full relay: it runs {@link RelayRuntime}
 * in-process, opens its inbound port on the home router automatically
 * ({@link ReachabilityManager}: NAT-PMP/UPnP + a third-party dial-back proof),
 * and shows a menu-bar / system-tray status. No Minima node, no config, no
 * terminal — install it and it quietly grows the relay fleet.
 *
 * The reachability client is a second, outbound {@link MaximaNode} attached to
 * the bootstrap relays: it is what asks an existing relay to dial us back and
 * prove our port is genuinely open (a router can accept a mapping and still not
 * open it — the whole reason we never advertise on a mapping alone).
 */
public final class DesktopMain {

    /** User-facing desktop app version (independent of the relay protocol). */
    public static final String APP_VERSION = "1.5.55";

    private static final String PROTOCOL = "1.0.48";
    private static final int RATE = 600;
    private static final int DEFAULT_PORT = 9501;
    private static final int TRAY_SIZE = 20;

    private String[] mArgs = new String[0];

    // Live status, read by the tray refresh.
    private volatile ReachabilityManager.State mReach = ReachabilityManager.State.OFF;
    private volatile String mReachDetail = "starting…";
    private volatile String mPublicAddress = "";
    private volatile int mAttachedClients = 0;
    private volatile long mRelayed = 0;

    private RelayRuntime mRuntime;
    private MaximaNode mProbeClient;
    private ReachabilityManager mReachMgr;
    private TrayIcon mTray;
    private MenuItem mStatusItem;
    private MenuItem mAddressItem;
    private MenuItem mStatsItem;
    private FileLock mLock;
    private RandomAccessFile mLockFile;

    // Live windowed-client state, so a Settings engine flip can rebuild in place.
    private Path mDataDir;
    private volatile com.eurobuddha.maxima.desktop.ui.DesktopNode mDnode;
    private volatile com.eurobuddha.maxima.desktop.ui.MaximaWindow mWindow;

    public static void main(String[] args) throws Exception {
        DesktopMain m = new DesktopMain();
        m.mArgs = args == null ? new String[0] : args;
        m.run();
    }

    private void run() throws Exception {
        Path dataDir = resolveDataDir();
        Files.createDirectories(dataDir);

        if (!acquireSingleInstanceLock(dataDir)) {
            note("Maxima is already running.");
            return;
        }

        // Two personalities from one binary, one identity seed:
        //   - windowed  (default on a machine with a display): the full chat
        //     client — Chats/Contacts/Wallet/Network/Settings, parity with the phone.
        //   - relay     (headless boxes, or --relay / MAXIMA_RELAY=1): the
        //     set-and-forget forwarding relay + tray, exactly as before.
        boolean relayOnly = java.awt.GraphicsEnvironment.isHeadless()
                || "1".equals(System.getenv("MAXIMA_RELAY"))
                || java.util.Arrays.asList(mArgs).contains("--relay");
        if (!relayOnly) {
            launchWindow(dataDir);
            return;
        }

        int port = resolvePort();

        RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(dataDir);
        MaximaIdentity id = MaximaIdentity.fromPhrase(seed.phrase);

        // 1. the relay itself, in-process
        mRuntime = new RelayRuntime(id, port, PROTOCOL, RATE, "", dataDir);
        mRuntime.setPool(false);   // in-process desktop relay: not a pool/permanent anchor until Phase-B mesh
        mRuntime.setTickListener(s -> {
            mAttachedClients = s.routes;
            mRelayed = s.relayed;
            refreshTray();
        });
        mRuntime.start();
        log("relay up on port " + port + ", data " + dataDir + ", identity "
                + id.mxIdentity());   // RULE 1: full identity, never truncated

        // 2. the outbound client that proves our reachability (and, later, gossips)
        mProbeClient = new MaximaNode(id, PROTOCOL, 1);
        int attached = mProbeClient.start(Bootstrap.RELAYS, 30_000);
        log("probe client attached to " + attached + " bootstrap relay(s)");
        startPump(mProbeClient);

        // 3a. gossip: once proven reachable we announce ourselves to the fleet,
        // and we adopt (probe-first, capped) relays the fleet tells us about.
        final com.eurobuddha.maxima.core.session.RelayGossipClient gossip =
                new com.eurobuddha.maxima.core.session.RelayGossipClient(id, PROTOCOL, 8);

        // 3b. router magic: map our port, prove it open from outside, then advertise
        mReachMgr = new ReachabilityManager(mProbeClient, () -> port,
                ReachabilityManager.Gates.ALWAYS, new ReachabilityManager.Listener() {
            public void onVerified(String ipPort, String via) {
                mPublicAddress = ipPort;
                log("VERIFIED public relay address " + ipPort + " (via " + via + ")");
                // Advertise the proven host in the relay's greeting so clients
                // keep dialling the right place.
                int c = ipPort.lastIndexOf(':');
                String ip = c > 0 ? ipPort.substring(0, c) : ipPort;
                if (mRuntime.server() != null) {
                    mRuntime.server().setPublicHost(ip);
                }
                // Announce to the fleet: future attaches claim the endpoint, and
                // an immediate greet-round tells the bootstrap relays right now.
                mProbeClient.pool().setAdvertisedEndpoint(ipPort);
                gossip.setSelfEndpoint(ipPort);
                Thread announce = new Thread(() -> {
                    int n = gossip.announceNow(Bootstrap.RELAYS);
                    log("announced this relay to " + n + " bootstrap relay(s)");
                }, "maxima-announce");
                announce.setDaemon(true);
                announce.start();
                refreshTray();
            }

            public void onLost(String why) {
                mPublicAddress = "";
                if (mRuntime.server() != null) {
                    mRuntime.server().setPublicHost("");
                }
                mProbeClient.pool().setAdvertisedEndpoint(null);
                gossip.setSelfEndpoint(null);
                refreshTray();
            }

            public void onState(ReachabilityManager.State state, String detail) {
                mReach = state;
                mReachDetail = detail;
                log("reachability: " + state + " — " + detail);
                refreshTray();
            }
        });

        // heartbeat: drive reachability (map/prove/renew) every 30s; the first
        // tick fires almost immediately so the port opens without waiting.
        ScheduledExecutorService beat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "maxima-reach-beat");
            t.setDaemon(true);
            return t;
        });
        beat.scheduleWithFixedDelay(() -> mReachMgr.tick(), 2, 30, TimeUnit.SECONDS);
        // discover relays the fleet gossips about (probe-first, capped)
        beat.scheduleWithFixedDelay(() -> {
            try {
                gossip.tick(mProbeClient);
            } catch (Exception ignored) {
            }
        }, 20, 60, TimeUnit.SECONDS);

        // 4. tray UI (or headless if no display / server box)
        if (SystemTray.isSupported()) {
            installTray(dataDir);
        } else {
            System.out.println("No system tray; running headless. Relay on port " + port
                    + ", data " + dataDir);
        }

        // 5. first run: show the wallet-grade backup once
        if (seed.created) {
            showSeedBackup(seed);
        }

        // 6. set-and-forget: register a login item (packaged installs only)
        AutoStart.installOnce();

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "maxima-desktop-shutdown"));

        // keep alive
        Thread.currentThread().join();
    }

    // ---- windowed chat client ----

    private void launchWindow(Path dataDir) throws Exception {
        RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(dataDir);
        MaximaIdentity id = MaximaIdentity.fromPhrase(seed.phrase);

        java.util.prefs.Preferences prefs =
                java.util.prefs.Preferences.userRoot().node("com/eurobuddha/maxima/desktop");
        String name = prefs.get("name", "Parlons! Desktop");

        com.eurobuddha.maxima.desktop.ui.DesktopNode dnode =
                new com.eurobuddha.maxima.desktop.ui.DesktopNode(id, dataDir, name);
        mDataDir = dataDir;
        mDnode = dnode;

        // Attach + pump on a background thread so the UI paints immediately.
        Thread starter = new Thread(() -> {
            try {
                int attached = dnode.start();
                log("chat client attached to " + attached + " relay(s)");
            } catch (Exception e) {
                log("chat client start failed: " + e.getMessage());
            }
        }, "maxima-window-start");
        starter.setDaemon(true);
        starter.start();

        // Read the CURRENT engine at exit (mDnode is swapped by restartEngine),
        // not the one captured here — otherwise after an engine flip the new
        // engine is never cleanly shut and classic H2 (MinimaDB) is left open.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                com.eurobuddha.maxima.desktop.ui.DesktopNode cur = mDnode;
                if (cur != null) cur.shutdown();
            } catch (Exception ignored) {
            }
            releaseLock();
        }, "maxima-window-shutdown"));

        // First run: show the wallet-grade seed backup once.
        if (seed.created) {
            showSeedBackup(seed);
        }

        final com.eurobuddha.maxima.desktop.ui.Theme.Mode mode =
                "dark".equals(prefs.get("appearance", ""))
                        ? com.eurobuddha.maxima.desktop.ui.Theme.Mode.DARK
                        : "light".equals(prefs.get("appearance", ""))
                                ? com.eurobuddha.maxima.desktop.ui.Theme.Mode.LIGHT
                                : com.eurobuddha.maxima.desktop.ui.Theme.detectMode();
        javax.swing.SwingUtilities.invokeLater(() -> {
            com.eurobuddha.maxima.desktop.ui.Theme theme =
                    new com.eurobuddha.maxima.desktop.ui.Theme(mode);
            com.eurobuddha.maxima.desktop.ui.MaximaWindow w =
                    new com.eurobuddha.maxima.desktop.ui.MaximaWindow(dnode, theme);
            w.setEngineRestart(this::restartEngine);
            w.frame().setTitle("Parlons! — " + name);
            w.show();
            mWindow = w;
        });
    }

    private static com.eurobuddha.maxima.desktop.ui.Theme.Mode currentMode(
            java.util.prefs.Preferences prefs) {
        String a = prefs.get("appearance", "");
        return "dark".equals(a) ? com.eurobuddha.maxima.desktop.ui.Theme.Mode.DARK
                : "light".equals(a) ? com.eurobuddha.maxima.desktop.ui.Theme.Mode.LIGHT
                        : com.eurobuddha.maxima.desktop.ui.Theme.detectMode();
    }

    /**
     * Swap the routing engine in place (built-in ↔ classic Maxima). The engine
     * is chosen when DesktopNode is built, so we build a fresh node (which reads
     * the flipped {@code engineJar} pref), start it off-EDT, then rebuild the
     * window and dispose the old one. Identity + contacts carry over via
     * DesktopJarMigration, exactly like the phone's process restart.
     */
    private final java.util.concurrent.atomic.AtomicBoolean mFlipping =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public void restartEngine() {
        // One flip at a time. Two overlapping flips could double-init the classic
        // MinimaDB global singleton and brick the app.
        if (!mFlipping.compareAndSet(false, true)) return;
        final com.eurobuddha.maxima.desktop.ui.MaximaWindow oldWin = mWindow;
        final com.eurobuddha.maxima.desktop.ui.DesktopNode oldNode = mDnode;
        new Thread(() -> {
            try {
                // Tear the OLD engine down FIRST. Classic Maxima's MinimaDB is a
                // global singleton, so a new jar engine must not init while the
                // old one is still alive — shut down, then build.
                try { if (oldNode != null) oldNode.shutdown(); } catch (Throwable ignored) { }
                RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(mDataDir);
                MaximaIdentity id = MaximaIdentity.fromPhrase(seed.phrase);
                java.util.prefs.Preferences prefs =
                        java.util.prefs.Preferences.userRoot().node("com/eurobuddha/maxima/desktop");
                String name = prefs.get("name", "Parlons! Desktop");
                // The constructor internally falls back to built-in if a jar boot
                // fails, so this rarely throws; a jar-boot failure never bricks.
                com.eurobuddha.maxima.desktop.ui.DesktopNode nn =
                        new com.eurobuddha.maxima.desktop.ui.DesktopNode(id, mDataDir, name);
                // start() is a network attach — non-fatal, exactly as launchWindow
                // treats it. A start failure must NOT abandon a dead window.
                try { nn.start(); } catch (Exception se) { log("engine restart attach failed: " + se.getMessage()); }
                mDnode = nn;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    try {
                        com.eurobuddha.maxima.desktop.ui.MaximaWindow w =
                                new com.eurobuddha.maxima.desktop.ui.MaximaWindow(nn,
                                        new com.eurobuddha.maxima.desktop.ui.Theme(currentMode(prefs)));
                        w.setEngineRestart(this::restartEngine);
                        if (oldWin != null) {
                            w.frame().setBounds(oldWin.frame().getBounds());
                            w.frame().setExtendedState(oldWin.frame().getExtendedState());
                        }
                        w.frame().setTitle("Parlons! — " + name);
                        w.show();
                        mWindow = w;
                        if (oldWin != null) oldWin.frame().dispose();
                        // NOTE: do NOT shut oldNode again here — it was torn down
                        // above; a second shutdown could clear the NEW engine's
                        // MinimaDB singleton in the jar→built-in-less-common case.
                    } finally {
                        mFlipping.set(false);
                    }
                });
            } catch (Throwable e) {
                // Total construction failure (rare). Don't leave a zombie: keep the
                // guard released so the user can retry, and log honestly.
                log("engine restart failed: " + e);
                mFlipping.set(false);
            }
        }, "engine-restart").start();
    }

    // ---- pump loop for the reachability/gossip client ----

    private void startPump(MaximaNode node) {
        // Receiving is PUSH now: :core runs a dedicated reader per attached host
        // (25s NAT keep-alive + instant inbound). Only the maintain sweep below
        // is still needed.

        // keep the pool healthy (re-attach dropped relays)
        ScheduledExecutorService maint = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread mt = new Thread(r, "maxima-probe-maint");
            mt.setDaemon(true);
            return mt;
        });
        maint.scheduleWithFixedDelay(() -> {
            try {
                node.maintain(20_000);
            } catch (Exception ignored) {
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    // ---- tray ----

    private void installTray(Path dataDir) throws Exception {
        SystemTray tray = SystemTray.getSystemTray();
        PopupMenu menu = new PopupMenu();

        mStatusItem = new MenuItem("Maxima relay — starting…");
        mStatusItem.setEnabled(false);
        menu.add(mStatusItem);

        mAddressItem = new MenuItem("Finding a public address…");
        mAddressItem.setEnabled(false);
        menu.add(mAddressItem);

        mStatsItem = new MenuItem("");
        mStatsItem.setEnabled(false);
        menu.add(mStatsItem);

        menu.addSeparator();

        MenuItem copy = new MenuItem("Copy my public address");
        copy.addActionListener(e -> {
            if (!mPublicAddress.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(mPublicAddress), null);
            }
        });
        menu.add(copy);

        MenuItem recheck = new MenuItem("Re-check reachability");
        recheck.addActionListener(e -> mReachMgr.tick());
        menu.add(recheck);

        MenuItem open = new MenuItem("Open data folder");
        open.addActionListener(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dataDir.toFile());
                }
            } catch (Exception ignored) {
            }
        });
        menu.add(open);

        menu.addSeparator();

        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> {
            shutdown();
            System.exit(0);
        });
        menu.add(quit);

        mTray = new TrayIcon(TrayIcons.working(TRAY_SIZE), "Maxima Node", menu);
        mTray.setImageAutoSize(true);
        tray.add(mTray);
        refreshTray();
    }

    private void refreshTray() {
        if (mTray == null) {
            return;
        }
        try {
            switch (mReach) {
                case ADVERTISED:
                    mTray.setImage(TrayIcons.advertised(TRAY_SIZE));
                    break;
                case OFF:
                    mTray.setImage(TrayIcons.stopped(TRAY_SIZE));
                    break;
                default:
                    mTray.setImage(TrayIcons.working(TRAY_SIZE));
            }
            if (mStatusItem != null) {
                mStatusItem.setLabel("Maxima relay — " + mReachDetail);
            }
            if (mAddressItem != null) {
                mAddressItem.setLabel(mPublicAddress.isEmpty()
                        ? "Not publicly reachable yet"
                        : "Public: " + mPublicAddress);
            }
            if (mStatsItem != null) {
                mStatsItem.setLabel("Clients attached: " + mAttachedClients
                        + "   ·   relayed: " + mRelayed);
            }
            mTray.setToolTip(mPublicAddress.isEmpty()
                    ? "Maxima Node — " + mReachDetail
                    : "Maxima Node — reachable at " + mPublicAddress);
        } catch (Exception ignored) {
        }
    }

    // ---- first-run seed backup ----

    private void showSeedBackup(RelayRuntime.Seed seed) {
        // Wallet-grade: this phrase is also a spendable Minima seed. Show it once,
        // on a Swing dialog off the AWT thread. On a headless/server install there
        // is no screen — never print a spendable seed to a log; point at the file.
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            log("NEW identity created. Your seed is at " + seed.file
                    + " (owner-only). Back it up — it is also a spendable Minima wallet seed.");
            return;
        }
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                javax.swing.JTextArea area = new javax.swing.JTextArea(seed.phrase);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setEditable(false);
                area.setColumns(32);
                area.setRows(3);
                Object[] msg = {
                    "Your Maxima node created a new identity.",
                    "These 24 words ARE your identity AND a spendable Minima wallet seed.",
                    "Write them down and keep them safe — anyone with them is you.",
                    area,
                    "(Also saved, owner-only, at " + seed.file + ")"
                };
                javax.swing.JOptionPane.showMessageDialog(null, msg,
                        "Back up your Maxima seed", javax.swing.JOptionPane.WARNING_MESSAGE);
            });
        } catch (Exception ignored) {
        }
    }

    // ---- lifecycle ----

    private void shutdown() {
        try {
            if (mReachMgr != null) {
                mReachMgr.shutdown();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mProbeClient != null) {
                mProbeClient.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mRuntime != null) {
                mRuntime.stop();
            }
        } catch (Exception ignored) {
        }
        releaseLock();
    }

    // ---- helpers ----

    private static Path resolveDataDir() {
        String prop = System.getProperty("maxima.data");
        if (prop != null && !prop.isEmpty()) {
            return Paths.get(prop);
        }
        return Paths.get(System.getProperty("user.home"), ".maxima");
    }

    private static int resolvePort() {
        String p = System.getProperty("maxima.port");
        if (p == null || p.isEmpty()) {
            p = System.getenv("MAXIMA_PORT");
        }
        if (p != null && !p.isEmpty()) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v > 0 && v < 65536) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_PORT;
    }

    /** One node per data dir: a shared seed run twice would split mailbox state. */
    private boolean acquireSingleInstanceLock(Path dataDir) {
        try {
            mLockFile = new RandomAccessFile(dataDir.resolve("desktop.lock").toFile(), "rw");
            mLock = mLockFile.getChannel().tryLock();
            return mLock != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void releaseLock() {
        try {
            if (mLock != null) {
                mLock.release();
            }
            if (mLockFile != null) {
                mLockFile.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Timestamped line to stdout — captured to a log file by the packaged app,
     *  and the only status surface on a headless/server install. */
    private static void log(String message) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] " + message);
    }

    private static void note(String message) {
        if (SystemTray.isSupported() || !java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                javax.swing.JOptionPane.showMessageDialog(null, message, "Maxima Node",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return;
            } catch (Exception ignored) {
            }
        }
        System.out.println(message);
    }
}
