package com.eurobuddha.maxima.desktop.wallet;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

/**
 * WHERE the desktop wallet's commands go — the same FreezePeach synergy the phone
 * uses: a connected Minima node when configured, otherwise the hosted gateway
 * ({@code relay.privateprivate.org/cmd}), which ships a read+relay-only token that
 * CANNOT move funds. Signing NEVER happens here — the seed and the Winternitz walk
 * stay on the device; this only carries reads and the pre-signed
 * txnimport→txnbasics→txnpost relay. A desktop with just Maxima has a complete
 * wallet with no node at all.
 */
public final class DesktopWalletPublisher {

    public interface Cb {
        void onResult(JSONObject r);
        void onError(String msg);
    }

    // The Parlons FLEET — MegaMMR Parlons Nodes behind TLS, tried in order with failover
    // (same list + shared read+relay token as the phone's WalletPublisher; cannot move funds).
    static final String[] FLEET_GATEWAY_URLS = {
            "https://store.eurobuddha.com/parlons-node/cmd",   // sally      - Amsterdam, NL
            "https://eurobuddha.com/parlons-node/cmd",         // eurobuddha - Helsinki, FI
    };
    static final String FLEET_GATEWAY_TOKEN =
            "9cb300300968390a91c2b998720b1385f6851242e48ab3021e724536ac9d4468";
    static final String DEFAULT_GATEWAY_URL = FLEET_GATEWAY_URLS[0];
    static final String DEFAULT_GATEWAY_TOKEN = FLEET_GATEWAY_TOKEN;

    /** Gateways discovered with the relays (the node's cape advertises its gateway); see the
     *  phone's WalletPublisher for the trust model. {url, key} pairs; null = none. */
    private static volatile java.util.concurrent.Callable<java.util.List<String[]>> sDiscovered;

    public static void setDiscoveredGateways(java.util.concurrent.Callable<java.util.List<String[]>> zSource) {
        sDiscovered = zSource;
    }

    /** This publisher's fleet: discovered + the compiled-in floor, shuffled once so the sticky
     *  index below lands each desktop on a random gateway. {url, key} pairs. */
    private final java.util.List<String[]> mFleet = fleetEndpoints();

    static java.util.List<String[]> fleetEndpoints() {
        java.util.LinkedHashMap<String, String[]> all = new java.util.LinkedHashMap<>();
        java.util.concurrent.Callable<java.util.List<String[]>> src = sDiscovered;
        if (src != null) {
            try {
                for (String[] e : src.call()) {
                    if (e != null && e.length == 2 && e[0].startsWith("https://") && !e[1].isEmpty()) {
                        all.put(e[0], e);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (String u : FLEET_GATEWAY_URLS) {
            all.putIfAbsent(u, new String[] {u, FLEET_GATEWAY_TOKEN});
        }
        java.util.List<String[]> eps = new java.util.ArrayList<>(all.values());
        java.util.Collections.shuffle(eps);
        return eps;
    }
    /** The pre-1.5.34 default (the hosted proxy on maxlite): a desktop that stored this literal
     *  as its "own" URL follows the fleet too. */
    static final String LEGACY_GATEWAY_URL = "https://relay.privateprivate.org/cmd";
    /** Index of the fleet node that last answered — every new call tries it first. */
    private volatile int mCurrent = 0;

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/eurobuddha/maxima/desktop");

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wallet-gateway");
        t.setDaemon(true);
        return t;
    });

    private final DesktopNodeLink mNodeOrNull;   // a connected Minima node, if configured

    public DesktopWalletPublisher(DesktopNodeLink zNodeOrNull) {
        mNodeOrNull = zNodeOrNull;
    }

    /** Which backend answers right now (for the UI). */
    public String backendName() {
        return mNodeOrNull != null ? "connected node" : "gateway";
    }

    private String gatewayUrl() { return PREFS.get("gateway_url", DEFAULT_GATEWAY_URL); }
    private String gatewayToken() { return PREFS.get("gateway_token", DEFAULT_GATEWAY_TOKEN); }

    /** True when the wallet follows the shipped default (the fleet), not the user's own node. */
    private boolean usesFleet() {
        String u = gatewayUrl();
        return u.isEmpty() || DEFAULT_GATEWAY_URL.equals(u) || LEGACY_GATEWAY_URL.equals(u);
    }

    /** The gateway URL in use right now (fleet node that last answered, or the user's own). */
    public String activeGatewayUrl() {
        return usesFleet() ? mFleet.get(Math.min(mCurrent, mFleet.size() - 1))[0] : gatewayUrl();
    }

    // ---- the uniform surface (mirrors the phone's WalletPublisher) ----

    public void cmd(final String zCommand, final Cb zCb) {
        io.execute(() -> {
            try {
                JSONObject r = mNodeOrNull != null
                        ? nodeCmd(zCommand)
                        : gatewayCmd(zCommand);
                javax.swing.SwingUtilities.invokeLater(() -> zCb.onResult(r));
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                javax.swing.SwingUtilities.invokeLater(() -> zCb.onError(m));
            }
        });
    }

    public void balance(String zAddress, Cb zCb) {
        cmd("balance megammr:true address:" + zAddress, zCb);
    }

    public void coins(String zAddress, Cb zCb) {
        cmd("coins megammr:true address:" + zAddress, zCb);
    }

    public void trackScript(String zScript, Cb zCb) {
        cmd("newscript trackall:true script:\"" + zScript + "\"", zCb);
    }

    /** Publish a LOCALLY-SIGNED txn: txnimport → txnbasics → txnpost. All off-EDT. */
    public void publish(final String zImportCmd, final String zId, final String zPostCmd, final Cb zCb) {
        io.execute(() -> {
            // ONE node for the whole sequence: the imported txn lives on that node, so a
            // mid-way failover would leave it half-built there (and txndelete must reach it).
            final int pin = mCurrent;
            try {
                JSONObject r1 = one(pin, zImportCmd);
                if (!r1.optBoolean("status", false)) {
                    // import failed — no row to clean, but harmless to try.
                    cleanupTxn(pin, zId);
                    fail(zCb, "txnimport: " + r1.optString("error", r1.toString()));
                    return;
                }
                JSONObject r2 = one(pin, "txnbasics id:" + zId);
                if (!r2.optBoolean("status", false)) {
                    cleanupTxn(pin, zId);
                    fail(zCb, "txnbasics: " + r2.optString("error", r2.toString()));
                    return;
                }
                JSONObject r3 = one(pin, zPostCmd);
                if (!r3.optBoolean("status", false)) {
                    cleanupTxn(pin, zId);
                    fail(zCb, "txnpost: " + r3.optString("error", r3.toString()));
                    return;
                }
                javax.swing.SwingUtilities.invokeLater(() -> zCb.onResult(r3));
            } catch (Exception e) {
                cleanupTxn(pin, zId);
                fail(zCb, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** Family hard rule: every txn error path runs txndelete so a failed send
     *  never leaves a dangling signed txn row on the node/relay. Best-effort, SAME node. */
    private void cleanupTxn(int zPin, String zId) {
        try { one(zPin, "txndelete id:" + zId); } catch (Exception ignored) { }
    }

    /** A pinned call: no failover (publish sequences). */
    private JSONObject one(int zPin, String cmd) throws Exception {
        return mNodeOrNull != null ? nodeCmd(cmd) : gatewayCmdAt(zPin, cmd);
    }

    private void fail(Cb cb, String m) {
        javax.swing.SwingUtilities.invokeLater(() -> cb.onError(m));
    }

    // ---- node backend (reuse the RPC link, converting its JSON) ----

    private JSONObject nodeCmd(String command) throws Exception {
        org.minima.utils.json.JSONObject r = mNodeOrNull.cmd(command);
        return new JSONObject(r == null ? "{}" : r.toString());
    }

    // ---- gateway backend (HTTPS POST /cmd, Bearer token) ----

    /** A transport-level failure (unreachable, timeout, 5xx): try the next fleet node. */
    private static final class Unreachable extends Exception {
        Unreachable(String m) { super(m); }
    }

    /**
     * Free call: starts at the fleet node that last answered and fails over on transport
     * trouble / 5xx, sticking with whichever answered. A node-reported 4xx (auth, allow-list,
     * bad command) is NOT a reason to fail over — every fleet node would say the same. A
     * user-configured node is a single endpoint.
     */
    private JSONObject gatewayCmd(String command) throws Exception {
        if (!usesFleet()) {
            return exchange(gatewayUrl(), gatewayToken(), command);
        }
        int n = mFleet.size();
        int start = Math.min(mCurrent, n - 1);
        String lastErr = "";
        for (int a = 0; a < n; a++) {
            int idx = (start + a) % n;
            try {
                JSONObject r = exchange(mFleet.get(idx)[0], mFleet.get(idx)[1], command);
                mCurrent = idx;
                return r;
            } catch (Unreachable u) {
                lastErr = u.getMessage();
            }
        }
        throw new Exception("no wallet node reachable (" + n + " tried): " + lastErr);
    }

    /** Pinned call: exactly one fleet node (or the user's node), no failover. */
    private JSONObject gatewayCmdAt(int zPin, String command) throws Exception {
        if (!usesFleet()) {
            return exchange(gatewayUrl(), gatewayToken(), command);
        }
        int idx = Math.min(Math.max(zPin, 0), mFleet.size() - 1);
        return exchange(mFleet.get(idx)[0], mFleet.get(idx)[1], command);
    }

    private JSONObject exchange(String url, String token, String command) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            byte[] body = new JSONObject().put("command", command)
                    .toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code >= 500) {
                // the TLS front answered but the node behind it did not — a dead node
                throw new Unreachable("HTTP " + code + " from " + url);
            }
            if (code >= 400) {
                String err;
                try { err = new JSONObject(resp).optString("error", "HTTP " + code); }
                catch (Exception pe) { err = "HTTP " + code; }
                throw new Exception(err);
            }
            return new JSONObject(resp);
        } catch (java.io.IOException io) {
            throw new Unreachable((io.getMessage() == null ? io.toString() : io.getMessage()) + " (" + url + ")");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final int MAX_RESP = 8 * 1024 * 1024;
    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n, total = 0;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > MAX_RESP) throw new Exception("node response too large");
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public void shutdown() {
        io.shutdownNow();
    }
}
