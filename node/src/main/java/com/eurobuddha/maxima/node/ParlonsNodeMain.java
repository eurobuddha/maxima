package com.eurobuddha.maxima.node;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.minima.system.Main;
import org.minima.system.commands.CommandRunner;
import org.minima.system.params.GeneralParams;
import org.minima.system.params.GlobalParams;
import org.minima.utils.json.JSONObject;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.server.RelayRuntime;

/**
 * Parlons Node — the merged VPS binary. A FULL Minima node ({@code new Main()}) and the clean-room
 * {@code :core}/{@code :server} Parlons Maxima relay cohabit in ONE JVM, driven from ONE seed: the
 * node's BIP39 seed is also the Maxima comms identity. "minimaCore wearing the Parlons cape."
 *
 * <p>M1 deliverable (verifiable locally): both halves boot in-process with no duplicate-class
 * collision, the node answers its in-process command API, and the Maxima identity is DERIVED from
 * the node's own seed (one seed drives both). Chain-climb (P2P sync) needs a reachable Minima peer —
 * set {@code -Dparlons.node.rootnode=host:port}; on a peered VPS the node syncs from there.
 *
 * <p>Guardrail #1: we do NOT call {@code org.minima.Minima.main()} — it reads stdin, calls
 * {@code System.exit}, {@code resetDefaults()}, and installs a JVM-global uncaught-exception handler.
 * We replicate only the essential setup and drive the node via {@link Main} + {@link CommandRunner}.
 *
 * <p>Guardrail #2: this module depends ONLY on the full node's {@code org.minima} (bundled jar) plus
 * the clean-room {@code com.eurobuddha.*} :core/:server. It must NEVER pull the vendored org.minima
 * (:minima-common/:maxjar/com.eurobuddha.wallet) — those would duplicate the node's classes.
 */
public final class ParlonsNodeMain {

    /**
     * Parlons Node release. Bumped on EVERY code change (house rule: one change = one version), and
     * printed at boot + stamped into the dist jar name so a running box is always attributable.
     */
    public static final String  NODE_VERSION = "0.2.21";

    /** Parlons Maxima relay port. 9501 fleet-wide; free where the node's 9001/8001 are taken. */
    private static final int    RELAY_PORT = Integer.getInteger("parlons.relay.port", 9501);
    private static final String PROTOCOL   = "1.0.48";
    private static final int    RELAY_RATE = 600;

    public static void main(String[] zArgs) throws Exception {
        // --- configure the embedded node's global params (mirrors Minima.main, minus the CLI bits) ---
        GeneralParams.resetDefaults();

        // 1. Minima's OWN startup flags first (-Dparlons.node.args / PARLONS_NODE_ARGS), through
        //    Minima's own parser, minus a short exclusion list — see MinimaFlags. Minima owns what it
        //    parses (-port, -data, -host, -megammr, -archive, the P2P role, …).
        boolean flags = MinimaFlags.apply();

        // 2. The node's own knobs, applied ONLY when set explicitly, so they win on conflict and
        //    nothing else. Data dir: -Dparlons.node.data > Minima -data > ~/.parlons-node. The
        //    Parlons state (relay/, gateway-token.txt, the account) lives BESIDE the node's
        //    <version>/ folder, i.e. in the data dir itself.
        String dataProp = System.getProperty("parlons.node.data");
        File dataFolder;
        if (dataProp != null && !dataProp.trim().isEmpty()) {
            dataFolder = new File(dataProp.trim());
        } else if (flags && MinimaFlags.has("data")) {
            dataFolder = new File(GeneralParams.DATA_FOLDER).getParentFile();
        } else {
            dataFolder = new File(System.getProperty("user.home"), ".parlons-node");
        }
        sDataFolder = dataFolder;
        File minimaFolder = new File(dataFolder, GlobalParams.MINIMA_BASE_VERSION);
        GeneralParams.DATA_FOLDER     = minimaFolder.getAbsolutePath();
        // Layer-1 P2P port: -Dparlons.node.port > Minima -port > 9001. Every big fleet box already
        // runs a stock node on 9001, so a second node needs its own. The derived ports follow it.
        String portProp = System.getProperty("parlons.node.port");
        if (portProp != null && !portProp.trim().isEmpty()) {
            GeneralParams.MINIMA_PORT = Integer.parseInt(portProp.trim());
        }
        GeneralParams.MDSFILE_PORT    = GeneralParams.MINIMA_PORT + 2;
        GeneralParams.MDSCOMMAND_PORT = GeneralParams.MINIMA_PORT + 3;
        GeneralParams.RPC_PORT        = GeneralParams.MINIMA_PORT + 4;
        // Operator admin channel. The gateway is deliberately read+relay only, so without this the
        // running node has NO way to run `vault` (seed backup), `megammr action:import` (seed the
        // MegaMMR — an IBD does NOT carry it) or any other admin command. Off by default. It is OUR
        // loopback-bound AdminRpc on p2p+4, NOT Minima's -rpcenable: the stock RPC binds every
        // interface with no bind option and was internet-reachable on a firewall-less box (0.1.1).
        boolean rpc = Boolean.parseBoolean(System.getProperty("parlons.node.rpc", "false"));
        GeneralParams.RPC_ENABLED     = false;   // never the stock RPC — see AdminRpc (and MinimaFlags)
        minimaFolder.mkdirs();

        // Sync peer(s): this node fork ships an EMPTY DEFAULT_NODE_LIST, so give it a rootnode to
        // P2P-discover from (host:port), or a fixed CONNECT_LIST. Empty => boots but won't sync
        // (fine locally; a VPS sets this). Minima's own -connect / -nop2p pass through as well.
        String rootnode = System.getProperty("parlons.node.rootnode", "").trim();
        if (!rootnode.isEmpty()) {
            GeneralParams.P2P_ROOTNODE = rootnode;
            System.out.println("[parlons-node] P2P rootnode: " + rootnode);
        }
        String connect = System.getProperty("parlons.node.connect", "").trim();
        if (!connect.isEmpty()) {
            GeneralParams.CONNECT_LIST = connect;
            GeneralParams.P2P_ENABLED  = false;   // explicit peer list => static, no discovery
            System.out.println("[parlons-node] static connect list: " + connect);
        }

        // MegaMMR: keep the full MegaMMR so `coins/balance megammr:true address:<any>` proves coins
        // for ANY address — this is what makes the node a wallet gateway for phones (M3). Node
        // default ON (Minima's default is off): -Dparlons.node.megammr > Minima -megammr > true.
        String mmProp = System.getProperty("parlons.node.megammr");
        if (mmProp != null && !mmProp.trim().isEmpty()) {
            GeneralParams.IS_MEGAMMR = Boolean.parseBoolean(mmProp.trim());
        } else if (!(flags && MinimaFlags.has("megammr"))) {
            GeneralParams.IS_MEGAMMR = true;
        }
        boolean megammr = GeneralParams.IS_MEGAMMR;
        if (flags) {
            System.out.println("[parlons-node] minima flags: " + MinimaFlags.applied);
        }
        // Fixed default 9585 to match ops/deploy-parlons-node.sh + cloud/NODE-SETUP.md (one value
        // everywhere beats a node-port-relative offset that the docs would then contradict).
        int gatewayPort = Integer.getInteger("parlons.gateway.port", 9585);

        // JDBC drivers the node's SqlDB layer needs (same registration Minima.main does).
        try { new org.h2.Driver(); } catch (Exception ignored) {}
        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception ignored) {}

        System.out.println("[parlons-node] Parlons Node " + NODE_VERSION + " — booting embedded Minima node "
                + GlobalParams.getFullMicroVersion() + " at " + GeneralParams.DATA_FOLDER
                + " (p2p " + GeneralParams.MINIMA_PORT + ", megammr " + megammr + ", admin rpc "
                + (rpc ? "127.0.0.1:" + GeneralParams.RPC_PORT + " (loopback-bound)" : "off") + ")");

        // --- boot the full node in-process (Main is a MessageProcessor; spawns its own threads) ---
        final Main main = new Main();

        // --- operator admin RPC: loopback-bound by construction (see AdminRpc) ---
        if (rpc) {
            AdminRpc admin = AdminRpc.start(GeneralParams.RPC_PORT);
            System.out.println("[parlons-node] admin rpc up on 127.0.0.1:" + admin.port()
                    + " (loopback only; every node command; never proxy this)");
        }

        // --- the phone-facing wallet gateway (M3): hardened read+relay /cmd proxy over the node ---
        final AtomicReference<NodeGateway> gatewayHolder = new AtomicReference<>();

        // --- co-boot the Parlons Maxima relay + wallet gateway on the node's OWN seed, once ready ---
        final AtomicReference<RelayRuntime> relayHolder = new AtomicReference<>();
        // --- M5: the Parlons ACCOUNT (pairing, control RPC, chat, push) riding the same seed ---
        final AtomicReference<com.eurobuddha.maxima.cloud.ParlonsCore> accountHolder = new AtomicReference<>();
        Thread capeThread = new Thread(() -> {
            try {
                // deriveMaximaIdentityFromNode() only returns once the node wallet is initialised
                // (vault succeeded) — so past this line the wallet is provably up.
                MaximaIdentity identity = deriveMaximaIdentityFromNode();
                Path relayDir = new File(dataFolder, "relay").toPath();
                // The cape's public host: -Dparlons.relay.host, else what the Minima node detected
                // for itself (its peers see our NAT'd address too - the Pi has no public interface
                // but IS reachable on its forwarded port). A cape that knows its address names
                // itself in the peer list it shares and never forwards a resolve miss to itself.
                String capeHost = System.getProperty("parlons.relay.host", "").trim();
                if (capeHost.isEmpty()) {
                    String det = detectedPublicHost();
                    if (isPublicHost(det)) {
                        capeHost = det;
                    }
                }
                RelayRuntime relay = new RelayRuntime(identity, RELAY_PORT, PROTOCOL, RELAY_RATE,
                        capeHost, relayDir);
                relay.setPool(true);   // a VPS node is always-on + public => a permanent-anchor host
                // Fleet parity with maxima-server.jar: the Phase-B mesh bootstrap list (--peers) and
                // the media blob shelf (--blobstore MB). Without peers a resolve MISS on this relay
                // is unanswerable fleet-wide; without the shelf store users' photos have nowhere to go.
                String peers = System.getProperty("parlons.relay.peers", "").trim();
                java.util.List<String> list = new java.util.ArrayList<>();
                for (String p : peers.split(",")) if (!p.trim().isEmpty()) list.add(p.trim());
                if (list.isEmpty()) {
                    // No --peers given: join the mesh through the same bootstrap list every
                    // client starts from. A node that stood outside the mesh could not answer
                    // a resolve miss fleet-wide and was never gossiped to anyone.
                    list.addAll(com.eurobuddha.maxima.core.session.Bootstrap.RELAYS);
                }
                relay.setPeers(list);
                System.out.println("[parlons-node] mesh: " + list.size() + " bootstrap peer(s)"
                        + (peers.isEmpty() ? " (default fleet list)" : ""));
                long blobMb = Long.getLong("parlons.relay.blob", 0L);
                if (blobMb > 0) {
                    relay.setBlobBytes(blobMb * 1024L * 1024L);
                    System.out.println("[parlons-node] blob shelf: " + blobMb + " MB");
                }
                relay.start();
                relayHolder.set(relay);
                System.out.println("[parlons-node] Maxima cape up on port " + RELAY_PORT
                        + " — identity " + identity.mxIdentity()
                        + " (derived from the node seed; one seed drives both)");

                // The account wallet IS the node's own wallet (M2).
                NodeWallet.Address acct = NodeWallet.defaultAddress();
                System.out.println("[parlons-node] account wallet = node wallet: "
                        + acct.hex + " (" + acct.mini + ") — " + NodeWallet.balance());
                maybeSelfTestSend();

                // Wallet is live => open the phone-facing gateway (M3).
                try {
                    NodeGateway gw = NodeGateway.create(dataFolder.toPath(), gatewayPort);
                    // NFT art hosting: files the node's tokens point at, public at <public>/nft/…
                    String publicBase = System.getProperty("parlons.node.public", "").trim();
                    sNft = new NftStore(dataFolder, publicBase);
                    gw.setNftStore(sNft);
                    System.out.println("[parlons-node] nft hosting: " + dataFolder + "/nft served at "
                            + (publicBase.isEmpty() ? "(no public base - set -Dparlons.node.public=https://host/parlons-node)"
                               : publicBase + "/nft/<file>"));
                    gw.start();
                    gatewayHolder.set(gw);
                    // Advertise the gateway in the cape's greeting so phones that discover this
                    // relay discover its wallet gateway too. Only a MegaMMR node can serve the
                    // wallet's megammr:true reads, and only a public TLS front is reachable.
                    if (GeneralParams.IS_MEGAMMR && !publicBase.isEmpty() && relay.server() != null) {
                        relay.server().setGateway(publicBase + "/cmd", gw.token());
                        System.out.println("[parlons-node] wallet gateway advertised to phones: "
                                + publicBase + "/cmd");
                    }
                    System.out.println("[parlons-node] wallet gateway up on " + gw.bindHost() + ":"
                            + gw.port() + "/cmd (megammr=" + GeneralParams.IS_MEGAMMR
                            + ", bearer token in " + dataFolder + "/gateway-token.txt)");
                } catch (Throwable gt) {
                    System.out.println("[parlons-node] wallet gateway FAILED: " + gt);
                }

                // The ACCOUNT (M5): the same ParlonsCore parlons-cloud runs, on the node's identity,
                // with the node's own wallet behind AccountWallet and the cape as its relay. This is
                // what makes a Parlons Node pairable from the Parlons Cloud app — "one binary IS
                // the account". -Dparlons.account=false runs a relay/gateway-only node.
                if (Boolean.parseBoolean(System.getProperty("parlons.account", "true"))) {
                    try {
                        accountHolder.set(startAccount(identity, dataFolder, relay));
                    } catch (Throwable at) {
                        System.out.println("[parlons-node] account layer FAILED to start: " + at);
                        at.printStackTrace();
                    }
                }
            } catch (Throwable t) {
                System.out.println("[parlons-node] Maxima cape FAILED to start: " + t);
                t.printStackTrace();
            }
        }, "parlons-node-cape");
        capeThread.start();

        // --- single shutdown hook stops ALL halves cleanly ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            NodeGateway gw = gatewayHolder.get();
            RelayRuntime rl = relayHolder.get();
            com.eurobuddha.maxima.cloud.ParlonsCore acct = accountHolder.get();
            try { if (acct != null) acct.shutdown(); } catch (Throwable ignored) {}   // leaves the cape alone
            try { if (gw != null) gw.stop(); } catch (Throwable ignored) {}
            try { if (rl != null) rl.stop(); } catch (Throwable ignored) {}
            try { main.shutdown(); } catch (Throwable ignored) {}
        }));

        // --- heartbeat: report chain height + subsystem liveness via the in-process command API ---
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30_000);
                    JSONObject res = CommandRunner.getRunner().runSingleCommand("status");
                    Object chain = res.get("response") instanceof JSONObject
                            ? ((JSONObject) res.get("response")).get("chain") : null;
                    Object length = (chain instanceof JSONObject) ? ((JSONObject) chain).get("length") : "?";
                    Object block  = (chain instanceof JSONObject) ? ((JSONObject) chain).get("block")  : "?";
                    System.out.println("[parlons-node] heartbeat: node block=" + block
                            + " chainlen=" + length
                            + " | cape=" + (relayHolder.get()   == null ? "down" : "up:" + RELAY_PORT)
                            + " | gateway=" + (gatewayHolder.get() == null ? "down" : "up:" + gatewayPort)
                            + " | account=" + (accountHolder.get() == null ? "off"
                                    : "up:" + accountHolder.get().connectedCount() + " hosts, "
                                      + accountHolder.get().pairing().authorizedCount() + " devices"));
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    System.out.println("[parlons-node] heartbeat: node not ready: " + t);
                }
            }
        }, "parlons-node-heartbeat").start();

        // Keep the JVM alive — both halves run on their own threads.
        Object lock = new Object();
        synchronized (lock) { lock.wait(); }
    }

    /**
     * M5 — stand up the Parlons ACCOUNT on this node: the very same {@code ParlonsCore} that
     * {@code parlons-cloud.jar} runs, given (a) the node's identity, (b) the node's own wallet
     * behind {@code AccountWallet} (no gateway, no key-#1000 signer — the node IS the chain),
     * (c) the phrase straight from the node's vault for backups, and (d) the cape as its relay
     * (the account never starts a second one). Same data-dir layout as the cloud
     * ({@code node/}, {@code chat/}, {@code media/}, {@code devices.json}, {@code pair-code.txt},
     * {@code cloud-settings.properties}) so a cloud account's files migrate by plain copy.
     *
     * <p>Knobs: {@code -Dparlons.account.name} (display name, first boot only),
     * {@code -Dparlons.account.relays} (extra fleet relays to attach to, csv),
     * {@code -Dparlons.account.direct} (Tier-2 direct listener port, 0 = off).
     */
    private static com.eurobuddha.maxima.cloud.ParlonsCore startAccount(MaximaIdentity zIdentity,
                                                                         File zDataFolder,
                                                                         RelayRuntime zRelay) throws Exception {
        com.eurobuddha.maxima.cloud.ParlonsCore.Config cfg = new com.eurobuddha.maxima.cloud.ParlonsCore.Config();
        cfg.version = NODE_VERSION;
        cfg.logTag = "parlons-node";
        cfg.relayPort = 0;                                   // the cape is the relay
        cfg.directPort = Integer.getInteger("parlons.account.direct", 0);
        cfg.publicHost = System.getProperty("parlons.relay.host", "");
        // The node's OWN cape is its public door: attach to it first, advertise it first, anchor
        // the permanent address on it. Public host = -Dparlons.relay.host, else what the Minima
        // node detected for itself; a private/loopback host is useless to contacts, so skip.
        String ownHost = cfg.publicHost.isEmpty() ? detectedPublicHost() : cfg.publicHost;
        if (isPublicHost(ownHost)) {
            cfg.ownRelay = ownHost + ":" + RELAY_PORT;
            System.out.println("[parlons-node] own relay: " + cfg.ownRelay + " (preferred + advertised first)");
        } else {
            System.out.println("[parlons-node] own relay NOT advertised: public host unknown or private ("
                    + ownHost + ") - set -Dparlons.relay.host=<public ip>");
        }
        String name = System.getProperty("parlons.account.name", "").trim();
        cfg.displayName = name.isEmpty() ? null : name;
        String peers = System.getProperty("parlons.relay.peers", "").trim();
        if (!peers.isEmpty()) {
            for (String p : peers.split(",")) if (!p.trim().isEmpty()) cfg.meshPeers.add(p.trim());
        }
        String relays = System.getProperty("parlons.account.relays", "").trim();
        if (!relays.isEmpty()) {
            for (String r : relays.split(",")) if (!r.trim().isEmpty()) cfg.extraRelays.add(r.trim());
        }
        com.eurobuddha.maxima.cloud.AccountBackup.Source backup = new com.eurobuddha.maxima.cloud.AccountBackup.Source() {
            public String phrase() throws Exception { return identityPhrase(); }
            public java.util.Map<String, Integer> keyUses() { return new java.util.LinkedHashMap<>(); }   // node-owned
        };
        com.eurobuddha.maxima.cloud.ParlonsCore core = new com.eurobuddha.maxima.cloud.ParlonsCore(
                zIdentity, zDataFolder.toPath(), cfg, new NodeAccountWallet(zDataFolder), backup);
        core.useExternalRelay(zRelay);
        // The Terminal IDE on a paired device: any node command, run on the console lane.
        core.control().setNodeConsole(NodeWallet::run);
        // NFT hosting from the wallet on a paired device (upload over the paired channel).
        final NftStore nft = sNft;
        if (nft != null) {
            core.control().setNftHost(new com.eurobuddha.maxima.cloud.ParlonsControl.NftHost() {
                public org.minima.utils.json.JSONObject put(String uid, String ext, long size, String sha256,
                        long off, byte[] chunk, String collection, int index) throws Exception {
                    return nft.put(uid, ext, size, sha256, off, chunk, collection, index);
                }
                public org.minima.utils.json.JSONObject newCollection() throws Exception { return nft.newCollection(); }
                public org.minima.utils.json.JSONObject list() throws Exception { return nft.list(); }
                public boolean delete(String path) throws Exception { return nft.delete(path); }
                public String publicBase() { return nft.publicBase(); }
            });
        }
        int hosts = core.start();
        System.out.println("[parlons-node] account up: attached to " + hosts + " relay(s), "
                + core.pairing().authorizedCount() + " paired device(s)"
                + (core.pairing().authorizedCount() == 0
                    ? " — pair the first one with the code in " + core.pairing().codeFile() : ""));
        return core;
    }

    /** The host the embedded Minima node detected for itself ({@code status} → network.host). */
    private static String detectedPublicHost() {
        try {
            org.minima.utils.json.JSONObject st = NodeWallet.response(NodeWallet.run("status"));
            Object net = st.get("network");
            if (net instanceof org.minima.utils.json.JSONObject) {
                return String.valueOf(((org.minima.utils.json.JSONObject) net).getOrDefault("host", "")).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * True for a host contacts can reach: a DNS name, or an IP literal (v4 or v6) that is not
     * loopback, link-local, site-local (RFC 1918 / fc00::/7), any-local, or carrier-grade NAT
     * (100.64.0.0/10). A literal is judged without a DNS lookup; a name is trusted as given.
     */
    static boolean isPublicHost(String zHost) {
        if (zHost == null || zHost.isEmpty() || "null".equals(zHost) || "localhost".equalsIgnoreCase(zHost)) {
            return false;
        }
        boolean literal = zHost.matches("[0-9.]+") || zHost.contains(":");
        if (!literal) {
            return true;   // a hostname the operator chose
        }
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(zHost);   // literal: no lookup
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                    || a.isAnyLocalAddress() || a.isMulticastAddress()) {
                return false;
            }
            byte[] b = a.getAddress();
            if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) {
                return false;   // 100.64.0.0/10 carrier-grade NAT
            }
            if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
                return false;   // fc00::/7 unique-local (isSiteLocalAddress only covers fec0::/10)
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The ACCOUNT identity phrase: identity.txt if pinned, else the vault. Never logged. */
    private static String identityPhrase() throws Exception {
        File pin = new File(sDataFolder, "identity.txt");
        if (pin.isFile()) {
            String p = new String(java.nio.file.Files.readAllBytes(pin.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!p.isEmpty()) return p;
        }
        return readVaultPhrase();
    }

    /** The node's WALLET phrase, read through {@code vault} (never logged, never cached). */
    private static String readVaultPhrase() throws Exception {
        JSONObject res = CommandRunner.getRunner().runSingleCommand("vault");
        Object resp = res.get("response");
        if (resp instanceof JSONObject) {
            Object phrase = ((JSONObject) resp).get("phrase");
            Object locked = ((JSONObject) resp).get("locked");
            if (phrase instanceof String && !((String) phrase).isEmpty() && !Boolean.TRUE.equals(locked)) {
                return ((String) phrase).trim();
            }
        }
        throw new IllegalStateException("node vault is locked or not ready");
    }

    /**
     * Derive the Maxima comms identity from the node's own BIP39 seed. The node owns the seed
     * (wallet-grade); we read its 24-word phrase via the in-process {@code vault} command and feed
     * it to {@link MaximaIdentity#fromPhrase}. Blocks until the node's wallet is initialised.
     *
     * <p>A password-LOCKED node keeps the seed encrypted, so the cape/wallet/gateway can't come up
     * until it is unlocked. Supply the passphrase out-of-band and this unlocks once: env
     * {@code PARLONS_NODE_PASSPHRASE} or a file via {@code -Dparlons.node.passphrase.file} (both keep
     * the secret out of argv/ps — a systemd {@code EnvironmentFile} mode 600 is the intended path).
     */
    private static MaximaIdentity deriveMaximaIdentityFromNode() throws Exception {
        // PINNED identity (0.2.4+): <data>/identity.txt holds the phrase the Maxima identity is
        // derived from, written ONCE from the vault on first boot. From then on the vault (the
        // wallet) can be resynced to a new phrase without changing the MAX#, paired devices or
        // contacts — the phone's "resync wallet, keep identity" model. Delete the file to re-pin.
        File pin = new File(sDataFolder, "identity.txt");
        if (pin.isFile()) {
            String phrase = new String(java.nio.file.Files.readAllBytes(pin.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!phrase.isEmpty()) {
                System.out.println("[parlons-node] identity: pinned (identity.txt)");
                return MaximaIdentity.fromPhrase(Arrays.asList(phrase.split("\\s+")));
            }
        }
        String vaultPhrase = readVaultPhraseWhenReady();
        MaximaIdentity id = MaximaIdentity.fromPhrase(Arrays.asList(vaultPhrase.split("\\s+")));
        try {
            java.nio.file.Path pp = pin.toPath();
            try {
                java.nio.file.Files.createFile(pp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            } catch (Exception nonPosix) { }
            java.nio.file.Files.write(pp, vaultPhrase.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("[parlons-node] identity: pinned from the vault into identity.txt "
                    + "(wallet resyncs no longer change the identity)");
        } catch (Exception e) {
            System.out.println("[parlons-node] could not pin identity.txt: " + e);
        }
        return id;
    }

    private static File sDataFolder;
    private static NftStore sNft;

    /** The vault phrase once the node's wallet is up (waits up to 120 s; unlocks a
     *  password-locked node once). Returned to the caller, held nowhere else. */
    private static String readVaultPhraseWhenReady() throws Exception {
        boolean unlockTried = false;
        for (int i = 0; i < 60; i++) {
            try {
                JSONObject res = CommandRunner.getRunner().runSingleCommand("vault");
                Object resp = res.get("response");
                if (resp instanceof JSONObject) {
                    Object phrase = ((JSONObject) resp).get("phrase");
                    Object locked = ((JSONObject) resp).get("locked");
                    if (Boolean.TRUE.equals(locked) && !unlockTried) {
                        unlockTried = true;               // one attempt — a wrong pass shouldn't loop
                        tryUnlockNode();
                        continue;                         // re-read the (now hopefully unlocked) vault
                    }
                    if (phrase instanceof String && !((String) phrase).isEmpty()
                            && !Boolean.TRUE.equals(locked)) {
                        return ((String) phrase).trim();
                    }
                }
            } catch (Throwable ignored) {
                // node/wallet not up yet — retry
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("node seed (vault) not available after 120s — cannot derive "
                + "Maxima identity (a password-locked node needs PARLONS_NODE_PASSPHRASE or "
                + "-Dparlons.node.passphrase.file — see cloud/NODE-SETUP.md)");
    }

    /** Unlock a password-locked node once, using an out-of-band passphrase (env or file). */
    private static void tryUnlockNode() {
        String pass = readPassphrase();
        if (pass == null || pass.isEmpty()) {
            System.out.println("[parlons-node] node is password-locked but no passphrase provided "
                    + "(PARLONS_NODE_PASSPHRASE / -Dparlons.node.passphrase.file) — cape/gateway will wait");
            return;
        }
        // Operator-supplied, but still refuse a value that could break out of the command string.
        if (pass.matches(".*[\";\\s].*")) {
            System.out.println("[parlons-node] passphrase contains whitespace/quote/';' — unsupported "
                    + "by the vault command; not attempting unlock");
            return;
        }
        try {
            JSONObject r = CommandRunner.getRunner()
                    .runSingleCommand("vault action:passwordunlock password:" + pass);
            System.out.println("[parlons-node] node unlock attempted — status="
                    + (r == null ? "?" : r.get("status")));
        } catch (Throwable t) {
            System.out.println("[parlons-node] node unlock failed: " + t);
        }
    }

    /** Passphrase from a file ({@code -Dparlons.node.passphrase.file}) or env {@code PARLONS_NODE_PASSPHRASE}. */
    private static String readPassphrase() {
        String file = System.getProperty("parlons.node.passphrase.file", "").trim();
        if (!file.isEmpty()) {
            try {
                return new String(java.nio.file.Files.readAllBytes(new File(file).toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Throwable t) {
                System.out.println("[parlons-node] could not read passphrase file " + file + ": " + t);
                return null;
            }
        }
        String env = System.getenv("PARLONS_NODE_PASSPHRASE");
        return env == null ? null : env.trim();
    }

    /**
     * M2 send-path self-test, gated by {@code -Dparlons.node.selftest.send=<address>[,<amount>]}.
     * Exercises the node-signed broadcast end-to-end: on a funded+synced node it posts a real txn;
     * on a fresh local node it returns a clean {@code insufficient funds} node error — either way it
     * proves the wallet routes through the embedded node's own sign+broadcast, not a gateway.
     */
    private static void maybeSelfTestSend() {
        String spec = System.getProperty("parlons.node.selftest.send", "").trim();
        if (spec.isEmpty()) return;
        String[] parts = spec.split(",");
        String to = parts[0].trim();
        String amount = parts.length > 1 ? parts[1].trim() : "0.001";
        try {
            NodeWallet.SendResult r = NodeWallet.send(to, amount);
            System.out.println("[parlons-node] SELFTEST send OK — node-signed txpowid=" + r.txid
                    + " istransaction=" + r.isTransaction);
        } catch (NodeWallet.WalletException e) {
            System.out.println("[parlons-node] SELFTEST send returned a clean NODE error (wiring proven): "
                    + e.getMessage());
        } catch (Throwable t) {
            System.out.println("[parlons-node] SELFTEST send FAILED at the wiring level: " + t);
        }
    }

    private ParlonsNodeMain() {}
}
