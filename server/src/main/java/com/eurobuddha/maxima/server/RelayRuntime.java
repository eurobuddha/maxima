package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.identity.Bip39;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.Consumer;

/**
 * The relay, as a reusable object rather than a main() loop.
 *
 * {@link Main} (the headless CLI) and the desktop tray app both need the exact
 * same bring-up: load or mint the seed 0600, stand up a {@link RelayServer} with
 * a durable mailbox, and run a 30s maintain/flush loop. That logic used to live
 * inside {@code Main.run()}; pulling it here means the two front-ends can never
 * drift apart, and the desktop app gets a clean handle to the running relay so a
 * reachability manager can set the advertised host once it proves the port open.
 *
 * This class holds NO opinion on how a freshly-minted seed is shown to the user
 * (a terminal prints it; a GUI shows a back-it-up dialog; systemd must never log
 * it) — {@link #loadOrCreateSeed} returns the facts and the caller decides.
 */
public final class RelayRuntime {

    /** The result of resolving the identity seed on disk. */
    public static final class Seed {
        /** The 24-word phrase. Wallet-grade material; treat like money. */
        public final String phrase;
        /** Where it lives on disk (mode 600). */
        public final Path file;
        /** True if this run minted it; false if it was already there. */
        public final boolean created;

        Seed(String phrase, Path file, boolean created) {
            this.phrase = phrase;
            this.file = file;
            this.created = created;
        }
    }

    /** A snapshot of the relay's counters, emitted on every maintain tick. */
    public static final class Stats {
        public final int connections;
        public final int routes;
        public final long relayed;
        public final long stored;
        public final long dropped;
        public final int mail;
        public final int directory;

        Stats(int connections, int routes, long relayed, long stored, long dropped,
              int mail, int directory) {
            this.connections = connections;
            this.routes = routes;
            this.relayed = relayed;
            this.stored = stored;
            this.dropped = dropped;
            this.mail = mail;
            this.directory = directory;
        }
    }

    private static final long MAINTAIN_INTERVAL_MS = 30_000;

    private final MaximaIdentity mIdentity;
    private final int mPort;
    private final String mProtocol;
    private final int mRate;
    private final String mHost;
    private final Path mDataDir;

    private RelayServer mRelay;
    private Thread mMaintain;
    private volatile boolean mRunning;
    private volatile Consumer<Stats> mTickListener;
    private volatile long mBlobBytes;
    private volatile int mMaxConnections;
    /** Whether this relay is a public open-resolve staticMLS pool host. Defaults to
     *  the standalone-jar policy; the in-app phone relay and desktop in-process relay
     *  call {@link #setPool(boolean) setPool(false)} so a churny phone is never a
     *  permanent-address anchor (it still relays + mailboxes, just is not a pool
     *  directory). Set before {@link #start()}. */
    private volatile boolean mPool = RelayServer.poolDefault();
    /** Bootstrap mesh peers (host:port) forwarded to on a resolve miss. */
    private volatile java.util.List<String> mPeers = java.util.Collections.emptyList();

    /** Enable the media blob shelf at this byte cap (0 = off). Set before start(). */
    public void setBlobBytes(long zBytes) {
        mBlobBytes = zBytes;
    }

    /**
     * Cap concurrent hosted peers AND the capacity we advertise (0 = leave the
     * server default). A phone-as-host sets a small number so it advertises an
     * honest, modest capacity; a VPS leaves the large default. Same code, merit
     * -weighted by the number. Set before {@link #start()}.
     */
    public void setMaxConnections(int zMax) {
        mMaxConnections = zMax;
    }

    /**
     * Opt this relay into or out of the public open-resolve staticMLS pool. The
     * standalone jar leaves the default (on, unless the operator opts out); the phone
     * and desktop in-process relays pass {@code false} so strangers never anchor a
     * permanent MAX# to a high-churn host. Set before {@link #start()}.
     */
    public void setPool(boolean zPool) {
        mPool = zPool;
    }

    /**
     * Bootstrap the Phase-B mesh with fleet peer host:ports this relay forwards resolve
     * misses to (like the client's RelayStore.DEFAULTS — a starting set, not a single point
     * of failure; trust is the publisher's signed proof). Applies whether set before or after
     * {@link #start()}.
     */
    public void setPeers(java.util.List<String> zHostPorts) {
        mPeers = zHostPorts == null ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(zHostPorts);
        RelayServer r = mRelay;
        if (r != null) {
            r.setPeers(mPeers);
        }
    }

    public RelayRuntime(MaximaIdentity zIdentity, int zPort, String zProtocol,
                        int zRate, String zHost, Path zDataDir) {
        mIdentity = zIdentity;
        mPort = zPort;
        mProtocol = zProtocol;
        mRate = zRate;
        mHost = zHost == null ? "" : zHost;
        mDataDir = zDataDir;
    }

    /**
     * Resolve the identity seed under {@code dataDir}: read it if present, else
     * mint a checksummed 24-word phrase and persist it.
     *
     * The seed file is created 0600 ATOMICALLY (permissions baked into the
     * creation call) and only then written. The old order — write, then chmod —
     * left a spendable wallet seed world/group-readable for a window, and kept
     * it that way silently if the chmod failed. A seed we cannot protect is not
     * a seed we keep: this fails loudly instead.
     */
    public static Seed loadOrCreateSeed(Path zDataDir) throws Exception {
        Files.createDirectories(zDataDir);
        Path seedFile = zDataDir.resolve("seed.txt");
        if (Files.exists(seedFile)) {
            String phrase = new String(Files.readAllBytes(seedFile),
                    StandardCharsets.UTF_8).trim();
            return new Seed(phrase, seedFile, false);
        }
        List<String> words = Bip39.generate(24);
        String phrase = String.join(" ", words);
        try {
            Files.createFile(seedFile, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.write(seedFile, phrase.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.WRITE);
        } catch (UnsupportedOperationException nonPosix) {
            // Non-POSIX filesystem (Windows). Best effort — the OS user profile
            // ACL is the protection there.
            Files.write(seedFile, phrase.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            try {
                Files.deleteIfExists(seedFile);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException(
                    "refusing to store the seed without owner-only permissions", e);
        }
        return new Seed(phrase, seedFile, true);
    }

    /** Observe maintain ticks (counters). Set before {@link #start()}. */
    public void setTickListener(Consumer<Stats> zListener) {
        mTickListener = zListener;
    }

    /**
     * Stand up the relay and start the maintain loop. Does NOT install a JVM
     * shutdown hook — the caller owns lifecycle (the CLI adds one; the tray app
     * stops it on quit). Throws {@link java.net.BindException} if the port is
     * taken, which the CLI turns into a friendly message.
     */
    public void start() throws Exception {
        RelayServer relay = new RelayServer(mIdentity, mPort, mProtocol, mPool);
        relay.setRateLimit(mRate);
        if (mMaxConnections > 0) {
            relay.setMaxConnections(mMaxConnections);
        }
        // Our public address: the operator's --host, else the box's own global IPv4 (a VPS has
        // it on its interface). A relay that knows its address names ITSELF in the peer list
        // it hands out (classic P2PGreeting), so a client that reached us through another
        // relay's list learns us; a NAT'd box (the Pi) stays nameless here and is still shared
        // by whoever dial-back-verified it.
        String host = mHost.isEmpty() ? detectPublicIPv4() : mHost;
        relay.setPublicHost(host);
        relay.setPeers(mPeers);   // bootstrap the Phase-B mesh forwarding targets
        // Durable mailbox under <data>/relaystore, so a restart does not lose the
        // ciphertext we hold for offline peers.
        relay.setStore(new com.eurobuddha.maxima.core.store.FileStore(
                mDataDir.resolve("relaystore").toFile()));
        // The media shelf: self-hosted media parked here survives a phone
        // sleeping. Content-addressed, byte-capped, LRU — lives under <data>/blobs.
        if (mBlobBytes > 0) {
            relay.setBlobStore(new com.eurobuddha.maxima.core.store.BlobStore(
                    mDataDir.resolve("blobs").toFile(), mBlobBytes));
        }
        relay.start();
        mRelay = relay;
        mRunning = true;

        mMaintain = new Thread(this::maintainLoop, "maxima-relay-maintain");
        mMaintain.setDaemon(true);
        mMaintain.start();
    }

    private void maintainLoop() {
        while (mRunning) {
            try {
                Thread.sleep(MAINTAIN_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!mRunning) {
                return;
            }
            // Expire directory entries, sweep the rate maps, and flush
            // write-behind mail (one rewrite per dirty collection, not per item).
            mRelay.maintain();
            mRelay.flush();
            Consumer<Stats> l = mTickListener;
            if (l != null) {
                try {
                    l.accept(stats());
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** The first globally-routable IPv4 on a local interface, or "" (behind NAT / none). */
    public static String detectPublicIPv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                java.net.NetworkInterface nif = nets.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address
                            && com.eurobuddha.maxima.core.portmap.PortMapper.isPublic(a.getHostAddress())) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** A current counters snapshot (also emitted on every tick). */
    public Stats stats() {
        RelayServer r = mRelay;
        if (r == null) {
            return new Stats(0, 0, 0, 0, 0, 0, 0);
        }
        return new Stats(r.connectionCount(), r.routeCount(), r.relayedCount(),
                r.storedCount(), r.droppedCount(), r.mailbox().totalItems(),
                r.directory().size());
    }

    /** Stop the maintain loop, flush write-behind mail, and stop the relay. Idempotent. */
    public void stop() {
        mRunning = false;
        if (mMaintain != null) {
            mMaintain.interrupt();
        }
        RelayServer r = mRelay;
        if (r != null) {
            r.flush();
            r.stop();
        }
    }

    public MaximaIdentity identity() {
        return mIdentity;
    }

    /** The running relay, or null before {@link #start()}. Lets a reachability
     *  manager call {@code setPublicHost} once it proves the port open. */
    public RelayServer server() {
        return mRelay;
    }

    public int port() {
        return mPort;
    }

    public Path dataDir() {
        return mDataDir;
    }
}
