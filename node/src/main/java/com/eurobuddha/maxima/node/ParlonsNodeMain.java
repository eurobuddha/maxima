package com.eurobuddha.maxima.node;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /** Parlons Maxima relay port. 9501 fleet-wide; free where the node's 9001/8001 are taken. */
    private static final int    RELAY_PORT = Integer.getInteger("parlons.relay.port", 9501);
    private static final String PROTOCOL   = "1.0.48";
    private static final int    RELAY_RATE = 600;

    public static void main(String[] zArgs) throws Exception {
        // --- configure the embedded node's global params (mirrors Minima.main, minus the CLI bits) ---
        GeneralParams.resetDefaults();
        File dataFolder = new File(System.getProperty("parlons.node.data",
                new File(System.getProperty("user.home"), ".parlons-node").getAbsolutePath()));
        File minimaFolder = new File(dataFolder, GlobalParams.MINIMA_BASE_VERSION);
        GeneralParams.DATA_FOLDER     = minimaFolder.getAbsolutePath();
        GeneralParams.MDSFILE_PORT    = GeneralParams.MINIMA_PORT + 2;
        GeneralParams.MDSCOMMAND_PORT = GeneralParams.MINIMA_PORT + 3;
        GeneralParams.RPC_PORT        = GeneralParams.MINIMA_PORT + 4;
        minimaFolder.mkdirs();

        // Sync peer(s): this node fork ships an EMPTY DEFAULT_NODE_LIST, so give it a rootnode to
        // P2P-discover from (host:port), or a fixed CONNECT_LIST. Empty => boots but won't sync
        // (fine locally; a VPS sets this).
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
        // for ANY address — this is what makes the node a wallet gateway for phones (M3). Default on
        // for the gateway role; -Dparlons.node.megammr=false to run a plain full node.
        boolean megammr = Boolean.parseBoolean(System.getProperty("parlons.node.megammr", "true"));
        GeneralParams.IS_MEGAMMR = megammr;
        int gatewayPort = Integer.getInteger("parlons.gateway.port", GeneralParams.MINIMA_PORT + 5);

        // JDBC drivers the node's SqlDB layer needs (same registration Minima.main does).
        try { new org.h2.Driver(); } catch (Exception ignored) {}
        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception ignored) {}

        System.out.println("[parlons-node] booting embedded Minima node "
                + GlobalParams.getFullMicroVersion() + " at " + GeneralParams.DATA_FOLDER);

        // --- boot the full node in-process (Main is a MessageProcessor; spawns its own threads) ---
        final Main main = new Main();

        // --- the phone-facing wallet gateway (M3): hardened read+relay /cmd proxy over the node ---
        final NodeGateway[] gatewayHolder = new NodeGateway[1];

        // --- co-boot the Parlons Maxima relay + wallet gateway on the node's OWN seed, once ready ---
        final RelayRuntime[] relayHolder = new RelayRuntime[1];
        Thread capeThread = new Thread(() -> {
            try {
                // deriveMaximaIdentityFromNode() only returns once the node wallet is initialised
                // (vault succeeded) — so past this line the wallet is provably up.
                MaximaIdentity identity = deriveMaximaIdentityFromNode();
                Path relayDir = new File(dataFolder, "relay").toPath();
                RelayRuntime relay = new RelayRuntime(identity, RELAY_PORT, PROTOCOL, RELAY_RATE,
                        System.getProperty("parlons.relay.host", ""), relayDir);
                relay.setPool(true);   // a VPS node is always-on + public => a permanent-anchor host
                relay.start();
                relayHolder[0] = relay;
                System.out.println("[parlons-node] Maxima cape up on port " + RELAY_PORT
                        + " — identity " + identity.mxIdentity()
                        + " (derived from the node seed; one seed drives both)");

                // The account wallet IS the node's own wallet (M2).
                System.out.println("[parlons-node] account wallet = node wallet: "
                        + NodeWallet.address() + " (" + NodeWallet.miniAddress() + ") — "
                        + NodeWallet.balance());
                maybeSelfTestSend();

                // Wallet is live => open the phone-facing gateway (M3).
                try {
                    NodeGateway gw = NodeGateway.create(dataFolder.toPath(), gatewayPort);
                    gw.start();
                    gatewayHolder[0] = gw;
                    System.out.println("[parlons-node] wallet gateway up on " + gw.bindHost() + ":"
                            + gw.port() + "/cmd (megammr=" + GeneralParams.IS_MEGAMMR
                            + ", bearer token in " + dataFolder + "/gateway-token.txt)");
                } catch (Throwable gt) {
                    System.out.println("[parlons-node] wallet gateway FAILED: " + gt);
                }
            } catch (Throwable t) {
                System.out.println("[parlons-node] Maxima cape FAILED to start: " + t);
                t.printStackTrace();
            }
        }, "parlons-node-cape");
        capeThread.start();

        // --- single shutdown hook stops ALL halves cleanly ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { if (gatewayHolder[0] != null) gatewayHolder[0].stop(); } catch (Throwable ignored) {}
            try { if (relayHolder[0]   != null) relayHolder[0].stop();   } catch (Throwable ignored) {}
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
                            + " | cape=" + (relayHolder[0]   == null ? "down" : "up:" + RELAY_PORT)
                            + " | gateway=" + (gatewayHolder[0] == null ? "down" : "up:" + gatewayPort));
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
     * Derive the Maxima comms identity from the node's own BIP39 seed. The node owns the seed
     * (wallet-grade); we read its 24-word phrase via the in-process {@code vault} command and feed
     * it to {@link MaximaIdentity#fromPhrase}. Blocks until the node's wallet is initialised.
     */
    private static MaximaIdentity deriveMaximaIdentityFromNode() throws Exception {
        for (int i = 0; i < 60; i++) {
            try {
                JSONObject res = CommandRunner.getRunner().runSingleCommand("vault");
                Object resp = res.get("response");
                if (resp instanceof JSONObject) {
                    Object phrase = ((JSONObject) resp).get("phrase");
                    Object locked = ((JSONObject) resp).get("locked");
                    if (phrase instanceof String && !((String) phrase).isEmpty()
                            && !Boolean.TRUE.equals(locked)) {
                        List<String> words = Arrays.asList(((String) phrase).trim().split("\\s+"));
                        return MaximaIdentity.fromPhrase(words);
                    }
                }
            } catch (Throwable ignored) {
                // node/wallet not up yet — retry
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("node seed (vault) not available after 120s — cannot derive Maxima identity");
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
