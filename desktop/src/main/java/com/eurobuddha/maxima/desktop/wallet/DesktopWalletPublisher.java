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

    // The shipped read+relay-only gateway (same as the phone; cannot move funds).
    static final String DEFAULT_GATEWAY_URL = "https://relay.privateprivate.org/cmd";
    static final String DEFAULT_GATEWAY_TOKEN =
            "c9e6177419dc7e0f200390152c6296e2180fd317071c83f8db74ceab61286188";

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
            try {
                JSONObject r1 = one(zImportCmd);
                if (!r1.optBoolean("status", false)) {
                    // import failed — no row to clean, but harmless to try.
                    cleanupTxn(zId);
                    fail(zCb, "txnimport: " + r1.optString("error", r1.toString()));
                    return;
                }
                JSONObject r2 = one("txnbasics id:" + zId);
                if (!r2.optBoolean("status", false)) {
                    cleanupTxn(zId);
                    fail(zCb, "txnbasics: " + r2.optString("error", r2.toString()));
                    return;
                }
                JSONObject r3 = one(zPostCmd);
                if (!r3.optBoolean("status", false)) {
                    cleanupTxn(zId);
                    fail(zCb, "txnpost: " + r3.optString("error", r3.toString()));
                    return;
                }
                javax.swing.SwingUtilities.invokeLater(() -> zCb.onResult(r3));
            } catch (Exception e) {
                cleanupTxn(zId);
                fail(zCb, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** Family hard rule: every txn error path runs txndelete so a failed send
     *  never leaves a dangling signed txn row on the node/relay. Best-effort. */
    private void cleanupTxn(String zId) {
        try { one("txndelete id:" + zId); } catch (Exception ignored) { }
    }

    private JSONObject one(String cmd) throws Exception {
        return mNodeOrNull != null ? nodeCmd(cmd) : gatewayCmd(cmd);
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

    private JSONObject gatewayCmd(String command) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(gatewayUrl()).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + gatewayToken());
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
            if (code >= 400) {
                String err;
                try { err = new JSONObject(resp).optString("error", "HTTP " + code); }
                catch (Exception pe) { err = "HTTP " + code; }
                throw new Exception(err);
            }
            return new JSONObject(resp);
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
