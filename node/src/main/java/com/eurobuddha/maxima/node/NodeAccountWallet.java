package com.eurobuddha.maxima.node;

import com.eurobuddha.maxima.cloud.AccountWallet;

import org.minima.objects.base.MiniNumber;
import org.minima.system.commands.CommandRunner;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The NODE implementation of {@link AccountWallet}: the embedded full Minima node's own wallet.
 * The node IS the chain, so a send is one in-process {@code send} command (build + sign +
 * broadcast, the node advancing its own Winternitz counter), balances for ANY address come
 * from the node's MegaMMR, and there is no gateway, no HTTP and no bearer token anywhere.
 *
 * Differences from the cloud wallet the control channel must live with:
 * <ul>
 *   <li>the address is the node's DEFAULT address ({@code getaddress}), not key #1000 — a
 *       migrated cloud account gets a NEW receive address (sweep the old one);</li>
 *   <li>the key-use counter belongs to the node — readable, never externally raised;</li>
 *   <li>{@link #build} has already broadcast: there is no signed-but-unpublished txn for a
 *       device to relay ({@link #canBuildWithoutPublish} is false).</li>
 * </ul>
 */
final class NodeAccountWallet implements AccountWallet {

    /** A node default key: TreeKey depth 3 × 64 leaves. */
    private static final int MAX_USES = 262144;

    private final Path mDataDir;
    private volatile NodeWallet.Address mAddress;
    private volatile String mScript = "";
    private volatile String mWatch = "";

    NodeAccountWallet(File zDataDir) {
        mDataDir = zDataDir.toPath();
        try {
            Path f = mDataDir.resolve("watch.txt");
            if (Files.exists(f)) {
                mWatch = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        }
    }

    @Override public void open() throws Exception {
        JSONObject resp = NodeWallet.response(NodeWallet.run("getaddress"));
        mScript = String.valueOf(resp.getOrDefault("script", ""));
        mAddress = NodeWallet.defaultAddress();
    }

    @Override public boolean isOpen() { return mAddress != null; }
    @Override public String mxAddress() { NodeWallet.Address a = mAddress; return a == null ? "" : a.mini; }
    @Override public String hexAddress() { NodeWallet.Address a = mAddress; return a == null ? "" : a.hex; }
    @Override public String script() { return mScript; }

    /** Uses of the default key, read live from the node's key table. */
    @Override public int uses() {
        NodeWallet.Address a = mAddress;
        if (a == null) {
            return -1;
        }
        try {
            JSONObject resp = NodeWallet.response(NodeWallet.run("keys"));
            Object keys = resp.get("keys");
            if (keys instanceof JSONArray) {
                for (Object o : (JSONArray) keys) {
                    JSONObject k = (JSONObject) o;
                    // the default address's key: match on the publickey inside our script
                    String pub = String.valueOf(k.get("publickey"));
                    if (!pub.isEmpty() && mScript.contains(pub)) {
                        return Integer.parseInt(String.valueOf(k.getOrDefault("uses", "0")));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    @Override public int maxUses() { return MAX_USES; }

    @Override public void raiseUsesTo(int zTo) {
        throw new UnsupportedOperationException("the node owns its own key-use counter");
    }

    /** Any node command — the control channel only ever passes reads (balance/coins). */
    @Override public JSONObject cmd(String zCommand) throws Exception {
        return NodeWallet.run(zCommand);
    }

    @Override public String watchAddress() { return mWatch; }

    @Override public synchronized void setWatchAddress(String zAddress) throws Exception {
        String a = zAddress == null ? "" : zAddress.trim();
        if (!a.isEmpty() && !a.matches("Mx[0-9A-Z]+") && !a.matches("0x[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("not a full Minima address");
        }
        Files.write(mDataDir.resolve("watch.txt"), a.getBytes(StandardCharsets.UTF_8));
        mWatch = a;
    }

    /** Build + sign + BROADCAST in one node command. A node-reported refusal (insufficient
     *  funds, bad address, locked wallet…) is a safe {@link Rejected}; anything else is
     *  outcome-unknown. */
    @Override public Payment build(String zToAddress, MiniNumber zAmount) throws Exception {
        if (mAddress == null) {
            throw new Rejected("the node wallet is still opening");
        }
        try {
            NodeWallet.SendResult r = NodeWallet.send(zToAddress, zAmount.toString());
            return new Payment(r.txid, "", "");
        } catch (NodeWallet.WalletException we) {
            throw new Rejected(we.getMessage());
        }
    }

    @Override public void publish(Payment zPayment) {
        // already on the chain — the node broadcast it in build()
    }

    @Override public boolean canBuildWithoutPublish() { return false; }

    /** The node tracks its own coins and holds its own proofs: nothing to backfill. */
    @Override public void upkeep(Consumer<String> zLog) {
    }

    @Override public boolean canResync() { return true; }

    private volatile String mLastResyncError = "";

    @Override public String lastResyncError() { return mLastResyncError; }

    /**
     * Re-point the NODE wallet at a new phrase: {@code megammrsync action:resync} against the
     * archive host resets the wallet keys to the phrase and pulls its coins, then the node
     * shuts its chain engine down — so we exit with code 3 and systemd restarts us
     * ({@code RestartForceExitStatus=3} in the unit). The identity is NOT derived from the
     * vault any more (ParlonsNodeMain pins it in identity.txt), so the same MAX#, paired
     * devices and contacts come back. Runs on its own thread; the caller replies first.
     */
    @Override public void resyncTo(String zPhrase) {
        final String host = System.getProperty("parlons.node.archive", "65.109.31.226:9001");
        final String phrase = zPhrase == null ? "" : zPhrase.trim().toLowerCase().replaceAll("\\s+", " ");
        // The phrase is interpolated into a node command: only 24 plain words may pass, whatever
        // the caller validated (a quote here would break out of the phrase:"…" argument).
        if (!phrase.matches("[a-z]+( [a-z]+){23}")) {
            throw new IllegalArgumentException("a phrase is 24 plain words");
        }
        mLastResyncError = "";
        Thread t = new Thread(() -> {
            String failure;
            try {
                Thread.sleep(1500);   // let the RPC reply leave first
                System.out.println("[parlons-node] wallet resync to a new phrase via " + host
                        + " (identity pinned, node restarts on success)");
                JSONObject r = NodeWallet.run("megammrsync action:resync host:" + host
                        + " phrase:\"" + phrase + "\"");
                if (Boolean.TRUE.equals(r.get("status"))) {
                    System.out.println("[parlons-node] wallet resync finished - restarting");
                    System.exit(3);
                    return;
                }
                failure = String.valueOf(r.getOrDefault("error", "megammrsync refused"));
            } catch (Throwable e) {
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            // FAILED: the wallet is unchanged, so the node stays up. The device learns of it from
            // resyncError on the next wallet refresh (parlons.wallet.address).
            mLastResyncError = "resync via " + host + " failed: " + failure;
            System.out.println("[parlons-node] wallet resync FAILED (node kept running, wallet unchanged): " + failure);
        }, "parlons-wallet-resync");
        t.setDaemon(false);
        t.start();
    }
}
