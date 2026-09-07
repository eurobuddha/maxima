package com.eurobuddha.maxima.cloud;

import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * The SIDECAR implementation of {@link AccountWallet}: a Minima node that is ALREADY running on
 * this machine (minimaDesk's classic node, any node with {@code -rpcenable}) is the wallet, over
 * its loopback RPC. The account holds the identity only; the node holds the seed, signs, and
 * broadcasts - one seed, one signer, exactly what the Parlons Node does in-process
 * ({@code NodeAccountWallet}), reached over HTTP instead of a method call.
 *
 * <p>Selected with {@code --wallet rpc:<port>[:<secret-file>]}. The secret file holds the node's
 * {@code -rpcpassword} (HTTP Basic, user {@code minima}); without one the node must run with no
 * RPC password. Commands go as {@code GET /<url-encoded command>} - the shape the desktop apps'
 * own RPC client uses - and the node's JSON comes straight back.
 *
 * <p>Same contract as the node wallet: the address is the node's default address (a migrated
 * cloud account gets a NEW receive address), the key-use counter belongs to the node (readable,
 * never raised from here), and {@link #build} has already broadcast.
 */
public final class RpcAccountWallet implements AccountWallet {

    /** A node default key: TreeKey depth 3 × 64 leaves. */
    private static final int MAX_USES = 262144;
    private static final int CONNECT_MS = 5_000;
    private static final int READ_MS = 120_000;   // a send can take a while on a busy node

    /** {@code rpc:<port>[:<secret-file>]} → a wallet; null when the spec is not an rpc spec. */
    public static RpcAccountWallet fromSpec(String zSpec, Path zDataDir) throws Exception {
        if (zSpec == null || !zSpec.startsWith("rpc:")) {
            return null;
        }
        String[] parts = zSpec.split(":", 3);
        int port = Integer.parseInt(parts[1].trim());
        String secret = "";
        if (parts.length == 3 && !parts[2].trim().isEmpty()) {
            secret = new String(Files.readAllBytes(Path.of(parts[2].trim())), StandardCharsets.UTF_8).trim();
        }
        return new RpcAccountWallet("http://127.0.0.1:" + port, secret, zDataDir);
    }

    private final String mBase;
    private final String mAuth;   // "" = no Authorization header
    private final Path mDataDir;
    private volatile String mHex = "";
    private volatile String mMx = "";
    private volatile String mScript = "";
    private volatile String mWatch = "";

    public RpcAccountWallet(String zBaseUrl, String zSecret, Path zDataDir) {
        mBase = zBaseUrl.endsWith("/") ? zBaseUrl.substring(0, zBaseUrl.length() - 1) : zBaseUrl;
        mAuth = zSecret == null || zSecret.isEmpty() ? ""
                : "Basic " + Base64.getEncoder().encodeToString(("minima:" + zSecret).getBytes(StandardCharsets.UTF_8));
        mDataDir = zDataDir;
        try {
            Path f = zDataDir.resolve("watch.txt");
            if (Files.exists(f)) {
                mWatch = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        }
    }

    public String baseUrl() {
        return mBase;
    }

    @Override public void open() throws Exception {
        JSONObject top = cmd("getaddress");
        if (!Boolean.TRUE.equals(top.get("status"))) {
            throw new Exception("node refused getaddress: " + top.getOrDefault("error", top));
        }
        JSONObject resp = response(top);
        mHex = str(resp, "address");
        mMx = str(resp, "miniaddress");
        mScript = str(resp, "script");
        if (mMx.isEmpty()) {
            throw new Exception("node returned no default address");
        }
    }

    @Override public boolean isOpen() { return !mMx.isEmpty(); }
    @Override public String mxAddress() { return mMx; }
    @Override public String hexAddress() { return mHex; }
    @Override public String script() { return mScript; }

    @Override public int uses() {
        if (!isOpen()) {
            return -1;
        }
        try {
            Object keys = response(cmd("keys")).get("keys");
            if (keys instanceof JSONArray) {
                for (Object o : (JSONArray) keys) {
                    JSONObject k = (JSONObject) o;
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

    /** One node command over loopback RPC; the node's JSON ({@code status/response/error}) back. */
    @Override public JSONObject cmd(String zCommand) throws Exception {
        URL url = new URL(mBase + "/" + URLEncoder.encode(zCommand, "UTF-8").replace("+", "%20"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        if (!mAuth.isEmpty()) {
            c.setRequestProperty("Authorization", mAuth);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (code == 401 || code == 403) {
            throw new Exception("node RPC refused the password (HTTP " + code + ")");
        }
        if (code >= 400) {
            throw new Exception("node RPC HTTP " + code + ": " + body);
        }
        Object parsed = new JSONParser().parse(body);
        if (parsed instanceof JSONArray && !((JSONArray) parsed).isEmpty()) {
            parsed = ((JSONArray) parsed).get(0);   // a single command answered as a one-element array
        }
        if (!(parsed instanceof JSONObject)) {
            throw new Exception("node RPC returned no JSON object");
        }
        return (JSONObject) parsed;
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

    /** Build + sign + BROADCAST in one node command. A node-reported refusal is a safe
     *  {@link Rejected}; a transport failure mid-command is outcome-unknown and is thrown as is. */
    @Override public Payment build(String zToAddress, MiniNumber zAmount) throws Exception {
        if (!isOpen()) {
            throw new Rejected("the node wallet is still opening");
        }
        // Interpolated into a command string: only a bare address and a decimal may pass (the
        // node runs every ';'-separated segment as a full command).
        if (zToAddress == null || !zToAddress.matches("(0x[0-9A-Fa-f]+|Mx[0-9A-Za-z]+)")) {
            throw new Rejected("refusing to send to a malformed address: " + zToAddress);
        }
        String amount = zAmount.toString();
        if (!amount.matches("[0-9]+(\\.[0-9]+)?")) {
            throw new Rejected("refusing to send a malformed amount: " + amount);
        }
        JSONObject top = cmd("send address:" + zToAddress + " amount:" + amount);
        if (!Boolean.TRUE.equals(top.get("status"))) {
            throw new Rejected(String.valueOf(top.getOrDefault("error", "the node refused the send")));
        }
        JSONObject resp = response(top);
        String txid = str(resp, "txpowid");
        if (txid.isEmpty()) {
            Object txpow = resp.get("txpow");
            if (txpow instanceof JSONObject) {
                txid = str((JSONObject) txpow, "txpowid");
            }
        }
        return new Payment(txid, "", "");
    }

    @Override public void publish(Payment zPayment) {
        // already on the chain - the node broadcast it in build()
    }

    @Override public boolean canBuildWithoutPublish() { return false; }

    /** The node tracks its own coins: nothing to backfill. */
    @Override public void upkeep(Consumer<String> zLog) {
    }

    /** The node's phrase is the node's business (its vault): not re-pointed from here. */
    @Override public boolean canResync() { return false; }
    @Override public void resyncTo(String zPhrase) {
        throw new UnsupportedOperationException("this wallet belongs to the node beside the account - resync it there");
    }

    private static JSONObject response(JSONObject zTop) {
        Object resp = zTop.get("response");
        return resp instanceof JSONObject ? (JSONObject) resp : new JSONObject();
    }

    private static String str(JSONObject zObj, String zKey) {
        Object v = zObj.get(zKey);
        return v == null ? "" : String.valueOf(v);
    }
}
