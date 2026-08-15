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

    private static final String PROTOCOL = "1.0.48";
    private static final int RATE = 600;
    private static final int DEFAULT_PORT = 9501;
    private static final int TRAY_SIZE = 20;

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

    public static void main(String[] args) throws Exception {
        new DesktopMain().run();
    }

    private void run() throws Exception {
        Path dataDir = resolveDataDir();
        Files.createDirectories(dataDir);

        if (!acquireSingleInstanceLock(dataDir)) {
            note("Maxima Node is already running.");
            return;
        }

        int port = resolvePort();

        RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(dataDir);
        MaximaIdentity id = MaximaIdentity.fromPhrase(seed.phrase);

        // 1. the relay itself, in-process
        mRuntime = new RelayRuntime(id, port, PROTOCOL, RATE, "", dataDir);
        mRuntime.setTickListener(s -> {
            mAttachedClients = s.routes;
            mRelayed = s.relayed;
            refreshTray();
        });
        mRuntime.start();
        log("relay up on port " + port + ", data " + dataDir + ", identity "
                + id.mxIdentity().substring(0, 20) + "…");

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

    // ---- pump loop for the reachability/gossip client ----

    private void startPump(MaximaNode node) {
        Thread t = new Thread(() -> {
            while (true) {
                boolean any = false;
                for (String hp : node.pool().activeHosts()) {
                    try {
                        any |= node.pump(hp, 1500);
                    } catch (Exception ignored) {
                    }
                }
                if (!any) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "maxima-probe-pump");
        t.setDaemon(true);
        t.start();

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
