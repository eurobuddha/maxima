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
    public static final String  NODE_VERSION = "0.2.82";

    /** Parlons Maxima relay port. 9501 fleet-wide; free where the node's 9001/8001 are taken. */
    /** -Dparlons.relay.port: a port (own listener), 0 (no relay), or "shared" (the relay rides the
     *  Minima P2P port: one public port, as classic Maxima does). Resolved once the P2P port is known. */
    private static final String RELAY_PORT_PROP = System.getProperty("parlons.relay.port", "9501").trim();
    private static final boolean RELAY_SHARED = "shared".equalsIgnoreCase(RELAY_PORT_PROP);
    private static int RELAY_PORT = RELAY_SHARED ? 0 : parseIntOr(RELAY_PORT_PROP, 9501);

    private static int parseIntOr(String zText, int zDefault) {
        try { return Integer.parseInt(zText); } catch (Exception e) { return zDefault; }
    }
    private static final String PROTOCOL   = "1.0.48";
    private static final int    RELAY_RATE = 600;

    /** What a person needs to run this jar by hand. The supported install is the deploy script. */
    static void help(java.io.PrintStream out) {
        out.println("Parlons Node " + NODE_VERSION + " - a full Minima node + Maxima relay + wallet gateway + your always-on");
        out.println("Parlons account, in one process.");
        out.println();
        out.println("This jar takes NO command-line options. Everything is a -D property before -jar:");
        out.println();
        out.println("  java -Xmx3g -Dparlons.node.data=/var/lib/parlons-node -jar parlons-node.jar");
        out.println();
        out.println("  -Dparlons.node.data=<dir>        data dir (default ~/.parlons-node); identity.txt, pair-code.txt,");
        out.println("                                    account.txt, invite.txt, devices.json live here");
        out.println("  -Dparlons.node.port=9001         Minima P2P port (public); RPC is loopback on port+4 when enabled");
        out.println("  -Dparlons.node.rootnode=host:port  a peer to discover the chain from (first run)");
        out.println("  -Dparlons.node.megammr=true      keep the MegaMMR (wallet gateway needs it; 3 GB heap)");
        out.println("  -Dparlons.node.rpc=false         loopback admin RPC (needed for wallet resync / the vault)");
        out.println("  -Dparlons.node.args=\"…\"         Minima's own flags, e.g. \"-host 1.2.3.4 -archive\"");
        out.println("  -Dparlons.node.conf=<file>       key=value lines of Minima flags read before the args (a 0600 file is");
        out.println("                                    where a secret such as mdspassword belongs - never on argv)");
        out.println("  -Dparlons.mds=true               serve MDS (MiniHUB + MiniDapps) on https://127.0.0.1:<p2p+2>/ - the password");
        out.println("                                    comes from the conf file (mdspassword=…) or is generated; `mds` prints it");
        out.println("  -Dparlons.mds.bind=127.0.0.1     the interface MDS listens on; 0.0.0.0 exposes MiniHUB to the network -");
        out.println("                                    only ever behind your own TLS + firewall");
        out.println("  -Dparlons.relay.port=9501        the Maxima relay (public); 0 = no relay; shared = the relay rides the");
        out.println("                                    Minima P2P port (ONE public port, as classic Maxima); -Dparlons.relay.peers=h:p,… mesh");
        out.println("  -Dparlons.relay.blob=0           media shelf in MB;  -Dparlons.relay.maxconn=0 (0 = default)");
        out.println("  -Dparlons.gateway.port=9585      wallet gateway /cmd on 127.0.0.1 (put TLS in front for phones)");
        out.println("  -Dparlons.node.public=https://…  public base URL, advertises the gateway + NFT hosting");
        out.println("  -Dparlons.account=true           run the Parlons account layer;  -Dparlons.account.name=<name>");
        out.println("  -Dparlons.panel.port=9587        the account's web panel + API on 127.0.0.1 (0 = off); open it with");
        out.println("                                    the one-time link in <data>/panel-ticket.txt (ssh tunnel on a server)");
        out.println("  -Dparlons.node.passphrase.file=<f>  unlock a password-locked node (or PARLONS_NODE_PASSPHRASE)");
        out.println("  -Dparlons.restore=<bundle.pbk>   restore a portable account bundle into a FRESH data dir, then exit");
        out.println();
        out.println("First run: the node syncs the chain (watch the 'heartbeat' log line until block= reaches the tip),");
        out.println("pins its identity into <data>/identity.txt, attaches to the relays and prints");
        out.println("'permanent address MAX#…'. Pair your first device with <data>/invite.txt (address + one-time code).");
        out.println();
        out.println("The supported install on a server is ONE command from the repository:");
        out.println("  ops/deploy-parlons-node.sh root@your.box --rootnode 65.109.31.226:9001");
        out.println("(Java, user, hardened systemd unit, firewall, MegaMMR seed - see cloud/NODE-SETUP.md).");
        out.println("Just want an always-on account without the blockchain? Use parlons-cloud.jar instead (1 GB RAM).");
    }

    public static void main(String[] zArgs) throws Exception {
        // The jar takes no command-line flags: every knob is a -D property (see help()). Anything
        // on the command line is therefore a misunderstanding - answer it with the help text
        // instead of silently ignoring it, which is what happened until 0.2.43.
        if (zArgs != null && zArgs.length > 0) {
            if ("-v".equals(zArgs[0]) || "--version".equals(zArgs[0])) {
                System.out.println("parlons-node " + NODE_VERSION);
                return;
            }
            boolean help = "-h".equals(zArgs[0]) || "--help".equals(zArgs[0]) || "help".equals(zArgs[0]);
            help(help ? System.out : System.err);
            System.exit(help ? 0 : 2);
            return;
        }
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
        // -Dparlons.restore=<file.pbk>: bring a PORTABLE ACCOUNT here (a fresh data dir). Writes
        // the paired devices, settings, contacts, chat and the identity (identity.txt) and
        // exits; the next normal start boots the node pinned to that identity - the same MAX#,
        // so paired devices reconnect without re-pairing. The node's WALLET stays its own
        // vault (resync it to the old phrase from a paired device if the funds should follow).
        String restore = System.getProperty("parlons.restore", "").trim();
        if (!restore.isEmpty()) {
            restoreAccount(dataFolder, restore);
            System.exit(0);
            return;
        }
        File minimaFolder = new File(dataFolder, GlobalParams.MINIMA_BASE_VERSION);
        GeneralParams.DATA_FOLDER     = minimaFolder.getAbsolutePath();
        // Layer-1 P2P port: -Dparlons.node.port > Minima -port > 9001. Every big fleet box already
        // runs a stock node on 9001, so a second node needs its own. The derived ports follow it.
        String portProp = System.getProperty("parlons.node.port");
        if (portProp != null && !portProp.trim().isEmpty()) {
            GeneralParams.MINIMA_PORT = Integer.parseInt(portProp.trim());
        }
        if (RELAY_SHARED) {
            RELAY_PORT = GeneralParams.MINIMA_PORT;   // the relay names itself by the P2P port
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
        // MDS - the MiniDAPP System (fork re-import, node 0.2.59): -Dparlons.mds=true > Minima -mdsenable.
        // Loopback-bound unless -Dparlons.mds.bind says otherwise: MiniHUB is a full wallet UI behind one
        // password, so a VPS must never have it on a public interface (the same rule as AdminRpc).
        String mdsProp = System.getProperty("parlons.mds", "").trim();
        if (!mdsProp.isEmpty()) {
            GeneralParams.MDS_ENABLED = Boolean.parseBoolean(mdsProp);
        }
        // Precedence (0.2.61): -Dparlons.mds.bind > Minima's own -mdsbind (in the args / conf file) > 127.0.0.1.
        String bindProp = System.getProperty("parlons.mds.bind");
        if (bindProp != null && !bindProp.trim().isEmpty()) {
            GeneralParams.MDS_BIND_HOST = bindProp.trim();
        } else if (!(flags && MinimaFlags.has("mdsbind"))) {
            GeneralParams.MDS_BIND_HOST = "127.0.0.1";
        }
        if (GeneralParams.MDS_BIND_HOST.equals("*") || GeneralParams.MDS_BIND_HOST.equals("0.0.0.0")) {
            GeneralParams.MDS_BIND_HOST = "";
        }
        boolean mds = GeneralParams.MDS_ENABLED;
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
        if (mds) {
            String host = GeneralParams.MDS_BIND_HOST.isEmpty() ? "0.0.0.0 (EVERY interface - put TLS + a firewall in front)" : GeneralParams.MDS_BIND_HOST;
            System.out.println("[parlons-node] MDS on " + (GeneralParams.MDS_NOSSL ? "http" : "https") + "://" + host + ":"
                    + GeneralParams.MDSFILE_PORT + "/ (password " + (GeneralParams.MDS_PASSWORD.isEmpty() ? "generated - run `mds` to read it" : "from the conf file") + ")");
        }

        // --- boot the full node in-process (Main is a MessageProcessor; spawns its own threads) ---
        final Main main = new Main();

        // --- operator admin RPC: loopback-bound by construction (see AdminRpc) ---
        if (rpc) {
            AdminRpc admin;
            try {
                admin = AdminRpc.start(GeneralParams.RPC_PORT);
            } catch (Throwable e) {
                // Before 0.2.59 this killed the main thread only: the Minima node kept running with no
                // admin RPC, no cape and no account - an orphan the host app could neither reach nor
                // stop cleanly. Throwable, not Exception (0.2.60): a JRE jlinked without jdk.httpserver
                // throws NoClassDefFoundError here, and that must be just as fatal as a port clash.
                System.err.println("[parlons-node] REFUSING to run: admin rpc on 127.0.0.1:" + GeneralParams.RPC_PORT
                        + " cannot start (" + e + ") - another node on this port, or a JRE without the jdk.httpserver module? stopping the embedded node");
                try { main.shutdown(); } catch (Throwable ignored) { }
                System.exit(2);
                return;
            }
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
                RelayRuntime relay = null;
                if (RELAY_PORT > 0) {
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
                relay = new RelayRuntime(identity, RELAY_PORT, PROTOCOL, RELAY_RATE,
                        capeHost, relayDir);
                relay.setPool(true);   // a VPS node is always-on + public => a permanent-anchor host
                if (RELAY_SHARED) {
                    // One public port: the embedded node's P2P listener hands us every connection
                    // that greets as a Parlons client (org.minima…NIOHandoff), greeting included.
                    relay.setShared(true);
                    final RelayRuntime shared = relay;
                    org.minima.system.network.minima.NIOHandoff.HANDLER = (ch, greeting, leftover, ip) -> {
                        com.eurobuddha.maxima.server.RelayServer srv = shared.server();
                        if (srv == null) { try { ch.close(); } catch (Exception ignored) { } return; }
                        srv.admit(ch.socket(), greeting, leftover);
                    };
                    System.out.println("[parlons-node] Maxima cape rides the P2P port " + RELAY_PORT
                            + " (one public port): Parlons clients are handed over by their greeting");
                }
                // Capacity knobs, same names as maxima-server.jar's flags: -Dparlons.relay.maxconn
                // (connections held; default 512) and -Dmaxima.relay.shed (soft client target).
                int maxConn = Integer.getInteger("parlons.relay.maxconn", 0);
                if (maxConn > 0) {
                    relay.setMaxConnections(maxConn);
                    System.out.println("[parlons-node] cape holds up to " + maxConn + " connections");
                }
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
                } else {
                    // A desktop that is not contributing: no public relay, the account attaches to
                    // the fleet like parlons-cloud does. -Dparlons.relay.port=<n> turns the cape on.
                    System.out.println("[parlons-node] Maxima cape OFF (-Dparlons.relay.port=0) — identity "
                            + identity.mxIdentity() + "; the account rides the fleet's relays");
                }

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
                    if (GeneralParams.IS_MEGAMMR && !publicBase.isEmpty() && relay != null && relay.server() != null) {
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
                        final boolean relayOn = relay != null;
                        accountHolder.get().setNetworkInfo(new com.eurobuddha.maxima.cloud.ParlonsCore.NetworkInfo() {
                            public String publicIp() { String h = detectedPublicHost(); return isPublicHost(h) ? h : ""; }
                            public int relayPort() { return relayOn ? RELAY_PORT : 0; }
                            public Boolean portOpen() {
                                if (!relayOn) { return null; }
                                // Shared port: the node's own inbound public peers are the proof. Own port: the
                                // pool's dial-back verdict (verified / unreachable), unknown while attaching.
                                if (RELAY_SHARED) { return portReachedFromOutside() ? Boolean.TRUE : null; }
                                String st = accountHolder.get().ownRelayState();
                                return "verified".equals(st) ? Boolean.TRUE : "unreachable".equals(st) ? Boolean.FALSE : null;
                            }
                        });
                    } catch (Throwable at) {
                        System.out.println("[parlons-node] account layer FAILED to start: " + at);
                        at.printStackTrace();
                    }
                }
                // A desktop learns its public address from its peers MINUTES after boot, so at cape
                // start the detected host is empty or private and the cape stays nameless with the
                // account anchored on a fleet relay - forever, until 0.2.49. Keep asking the node;
                // the moment the host is public, name the cape and hand it to the account.
                final RelayRuntime capeRef = relay;
                if (capeRef != null && System.getProperty("parlons.relay.host", "").trim().isEmpty()) {
                    Thread learn = new Thread(() -> {
                        String learned = "";
                        for (int i = 0; ; i++) {
                            // every 30 s until the first public address, then every 10 min - a home
                            // connection's address CHANGES (dynamic IP); the cape must follow it
                            try { Thread.sleep(learned.isEmpty() && i < 60 ? 30_000 : 600_000); } catch (InterruptedException ie) { return; }
                            String det = detectedPublicHost();
                            if (!isPublicHost(det) || det.equals(learned)) continue;
                            learned = det;
                            String own = det + ":" + RELAY_PORT;
                            try { if (capeRef.server() != null) capeRef.server().setPublicHost(det); } catch (Throwable ignored) { }
                            com.eurobuddha.maxima.cloud.ParlonsCore account = accountHolder.get();
                            System.out.println("[parlons-node] public address learned from peers: " + det
                                    + " - the cape now names itself " + own + (account == null ? "" : "; the account adopts it"));
                            if (account != null) {
                                if (RELAY_SHARED) {
                                    // In-process relay: reach it over loopback (no hairpin needed); the world
                                    // reaches it on the P2P port - and the node KNOWS when it does.
                                    account.adoptOwnRelay(own, "127.0.0.1:" + RELAY_PORT, ParlonsNodeMain::portReachedFromOutside);
                                } else {
                                    account.adoptOwnRelay(own);
                                }
                            }
                        }
                    }, "parlons-node-learn-host");
                    learn.setDaemon(true);
                    learn.start();
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

        // --- the embedded node shut ITSELF down (megammrsync / restore / reset end with Minima's own
        // shutdown + "please restart"): Minima.main would have exited the JVM; this jar never runs it,
        // so the cape, gateway and account lingered on a dead chain, spraying NullPointerExceptions
        // (seen live 2026-09-08 after a wallet resync from a host app). Exit like `quit` does, through
        // the shutdown hook, so the host restarts a whole node. (0.2.62) ---
        Thread selfStop = new Thread(() -> {
            boolean seen = false;
            while (true) {
                try { Thread.sleep(2_000); } catch (InterruptedException ie) { return; }
                if (Main.getInstance() != null) { seen = true; continue; }
                if (!seen) continue;   // still booting
                System.out.println("[parlons-node] the embedded node shut itself down (resync / restore / reset) - exiting so the host can restart it");
                System.exit(0);
            }
        }, "parlons-node-selfstop");
        selfStop.setDaemon(true);
        selfStop.start();

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
        cfg.relayPort = 0;                                   // the cape is the relay (or there is none)
        cfg.directPort = Integer.getInteger("parlons.account.direct", 0);
        cfg.panelPort = Integer.getInteger("parlons.panel.port", com.eurobuddha.maxima.cloud.ParlonsLocal.DEFAULT_PORT);
        cfg.publicHost = System.getProperty("parlons.relay.host", "");
        // The node's OWN cape is its public door: attach to it first, advertise it first, anchor
        // the permanent address on it. Public host = -Dparlons.relay.host, else what the Minima
        // node detected for itself; a private/loopback host is useless to contacts, so skip.
        String ownHost = cfg.publicHost.isEmpty() ? detectedPublicHost() : cfg.publicHost;
        if (zRelay == null) {
            System.out.println("[parlons-node] own relay: none (cape off) - the account attaches to the fleet");
        } else if (isPublicHost(ownHost)) {
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
        // -Dparlons.account.builtin=false: the compiled-in relay list is not used as a seed
        // (the account then starts from parlons.account.relays + relays it remembers).
        if ("false".equalsIgnoreCase(System.getProperty("parlons.account.builtin", "true"))) {
            cfg.builtInRelays = false;
        }
        com.eurobuddha.maxima.cloud.AccountBackup.Source backup = new com.eurobuddha.maxima.cloud.AccountBackup.Source() {
            public String phrase() throws Exception { return identityPhrase(); }
            public java.util.Map<String, Integer> keyUses() { return new java.util.LinkedHashMap<>(); }   // node-owned
        };
        com.eurobuddha.maxima.cloud.ParlonsCore core = new com.eurobuddha.maxima.cloud.ParlonsCore(
                zIdentity, zDataFolder.toPath(), cfg, new NodeAccountWallet(zDataFolder), backup);
        if (zRelay != null) {
            core.useExternalRelay(zRelay);
        }
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
        // account.txt + invite.txt beside the account's files: what a person reads to pair a device.
        com.eurobuddha.maxima.cloud.AccountFiles.startRefresher(core.pairing().codeFile().getParent(),
                () -> core.node().permanentAddress(), 3_000);
        System.out.println("[parlons-node] account up: attached to " + hosts + " relay(s), "
                + core.pairing().remoteCount() + " paired device(s)"
                + (core.pairing().remoteCount() == 0
                    ? " — pair the first one with the code in " + core.pairing().codeFile() : ""));
        return core;
    }

    /** Has the internet reached the node's P2P port? Minima's own verdict: inbound peers on it
     *  from PUBLIC addresses (self-dials excluded - a node that reaches itself only hairpins;
     *  private/LAN peers excluded - they say nothing about the router). */
    static boolean portReachedFromOutside() {
        try {
            org.minima.utils.json.JSONObject net = NodeWallet.response(NodeWallet.run("network"));
            Object details = net.get("details");
            Object p2p = details instanceof org.minima.utils.json.JSONObject
                    ? ((org.minima.utils.json.JSONObject) details).get("p2p") : null;
            if (!(p2p instanceof org.minima.utils.json.JSONObject)) return false;
            org.minima.utils.json.JSONObject j = (org.minima.utils.json.JSONObject) p2p;
            String self = String.valueOf(j.getOrDefault("address", ""));
            String selfIp = self.contains(":") ? self.substring(0, self.lastIndexOf(':')) : self;
            int inbound = 0;
            Object conns = net.get("connections");
            if (conns instanceof org.minima.utils.json.JSONArray) {
                for (Object o : (org.minima.utils.json.JSONArray) conns) {
                    if (!(o instanceof org.minima.utils.json.JSONObject)) continue;
                    org.minima.utils.json.JSONObject c = (org.minima.utils.json.JSONObject) o;
                    String host = String.valueOf(c.get("host"));
                    // Only a PUBLIC peer proves the internet reaches this port: a LAN node, a
                    // container or a VPN peer at 10.x reaches it without any router forwarding.
                    if (Boolean.TRUE.equals(c.get("incoming")) && !selfIp.equals(host) && isPublicHost(host)) inbound++;
                }
            }
            return inbound > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The host the embedded Minima node detected for itself ({@code status} → network.host). */
    private static String detectedPublicHost() {
        // The address the node LEARNED FROM ITS PEERS (network → details.p2p.address, "ip:port") is
        // the public one; `status` → network.host stays the interface address, which on a desktop
        // behind NAT is the LAN address (seen live: host 192.168.1.247 while p2p.address was
        // 31.125.188.214:12101). A VPS reports the same in both, which is why this went unnoticed.
        try {
            org.minima.utils.json.JSONObject net = NodeWallet.response(NodeWallet.run("network"));
            Object details = net.get("details");
            Object p2p = details instanceof org.minima.utils.json.JSONObject
                    ? ((org.minima.utils.json.JSONObject) details).get("p2p") : null;
            if (p2p instanceof org.minima.utils.json.JSONObject) {
                String addr = String.valueOf(((org.minima.utils.json.JSONObject) p2p).getOrDefault("address", "")).trim();
                int colon = addr.lastIndexOf(':');
                String host = colon > 0 && !addr.contains("]") ? addr.substring(0, colon) : addr;
                if (isPublicHost(host)) {
                    return host;
                }
            }
        } catch (Exception ignored) {
        }
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

    /** Offline restore of a portable account bundle into this node's data dir (see main). */
    private static void restoreAccount(File zDataFolder, String zPbkPath) throws Exception {
        java.io.Console console = System.console();
        if (console == null) {
            System.err.println("ERROR: -Dparlons.restore needs an interactive console (passphrase).");
            System.exit(1);
            return;
        }
        byte[] blob = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(zPbkPath));
        char[] pw = console.readPassword("Backup passphrase: ");
        com.eurobuddha.maxima.cloud.BackupBundle b;
        try {
            b = com.eurobuddha.maxima.cloud.AccountBackup.read(blob, pw);
        } catch (javax.crypto.AEADBadTagException bad) {
            System.err.println("ERROR: wrong passphrase (or a damaged backup file).");
            System.exit(1);
            return;
        } finally {
            java.util.Arrays.fill(pw, '\0');
        }
        // The node owns its key-use counters (its vault): nothing to import there.
        com.eurobuddha.maxima.cloud.AccountBackup.applyRestore(zDataFolder.toPath(), b, null, "identity.txt");
        System.out.println("Restored into " + zDataFolder + ": "
                + (b.displayName.isEmpty() ? "(unnamed)" : b.displayName) + " · "
                + b.contacts.size() + " contact(s)"
                + (b.hasAccount() ? " · paired devices, settings and chat (portable account bundle)"
                        : " · (an older backup: contacts and identity only)"));
        System.out.println();
        System.out.println("  !! The host this backup came from must be STOPPED for good - one");
        System.out.println("     identity, one live account.");
        System.out.println("  Start the node normally: it boots pinned to this identity (same MAX#),");
        System.out.println("  paired devices reconnect on their own. The wallet is this node's own");
        System.out.println("  vault - resync it to the old phrase from a paired device if the funds");
        System.out.println("  should follow.");
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
            String secret = new String(java.nio.file.Files.readAllBytes(pin.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!secret.isEmpty()) {
                System.out.println("[parlons-node] identity: pinned (identity.txt"
                        + (MaximaIdentity.isSeedHex(secret) ? ", seed hex" : "") + ")");
                return MaximaIdentity.fromNodeSecret(secret);
            }
        }
        // The vault's OWN seed is authoritative (a node with a custom -seed phrase hashes the phrase
        // verbatim, not as BIP39 - see Bip39.toNodeSeed). Pin the phrase when our rule reproduces
        // that seed (so backups show words), else pin the seed hex itself.
        JSONObject vault = readVaultWhenReady();
        String vaultPhrase = String.valueOf(vault.get("phrase")).trim();
        String vaultSeed = vault.get("seed") == null ? "" : String.valueOf(vault.get("seed")).trim();
        MaximaIdentity id = MaximaIdentity.isSeedHex(vaultSeed)
                ? MaximaIdentity.fromSeed(new com.eurobuddha.maxima.core.codec.MiniData(vaultSeed))
                : MaximaIdentity.fromNodePhrase(vaultPhrase);
        boolean phraseReproduces = MaximaIdentity.fromNodePhrase(vaultPhrase).seed().equals(id.seed());
        String pinned = phraseReproduces ? vaultPhrase : id.seed().to0xString();
        if (!phraseReproduces) {
            System.out.println("[parlons-node] identity: the node's phrase is a custom one that our phrase rule"
                    + " cannot reproduce - pinning the vault's seed hex instead (backups show the hex)");
        }
        try {
            java.nio.file.Path pp = pin.toPath();
            try {
                java.nio.file.Files.createFile(pp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            } catch (Exception nonPosix) { }
            java.nio.file.Files.write(pp, pinned.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("[parlons-node] identity: pinned from the vault into identity.txt "
                    + "(wallet resyncs no longer change the identity)");
        } catch (Exception e) {
            System.out.println("[parlons-node] could not pin identity.txt: " + e);
        }
        return id;
    }

    private static File sDataFolder;
    private static NftStore sNft;

    /** The vault (phrase + seed) once the node's wallet is up (waits up to 120 s; unlocks a
     *  password-locked node once). Returned to the caller, held nowhere else. */
    private static JSONObject readVaultWhenReady() throws Exception {
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
                        return (JSONObject) resp;
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
