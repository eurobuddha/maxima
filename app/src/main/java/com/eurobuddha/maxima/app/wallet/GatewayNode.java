package com.eurobuddha.maxima.app.wallet;

import android.os.Handler;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallet's remote node transport: HTTPS to the hosted MegaMMR node's hardened command proxy
 * (see freezepeach-wallet-node/proxy.js). The seed NEVER leaves the device — this only carries the
 * non-admin read + relay commands. Uses {@code megammr:true} coin/balance reads so ONE hosted node
 * serves any address without per-user tracking. Callbacks are marshalled to the main thread.
 */
public class GatewayNode {

    public interface Cb { void onResult(JSONObject r); void onError(String msg); }

    private final String mUrl;     // full endpoint e.g. https://wallet-rpc.privateprivate.org/cmd
    private final String mToken;
    private final Handler mMain;
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();

    public GatewayNode(String zUrl, String zToken, Handler zMain) { mUrl = zUrl; mToken = zToken; mMain = zMain; }

    public boolean configured() {
        return mUrl != null && !mUrl.isEmpty() && mToken != null && !mToken.isEmpty();
    }

    public void cmd(final String zCommand, final Cb zCb) {
        mIo.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(mUrl).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Authorization", "Bearer " + mToken);
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(40000);
                byte[] body = new JSONObject().put("command", zCommand).toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream(); os.write(body); os.close();
                int code = c.getResponseCode();
                InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                String respStr = readAll(is);
                if (code >= 400) {   // surface node/auth/transport failures as errors — never as an empty result
                    String err;
                    try { err = new JSONObject(respStr).optString("error", "HTTP " + code); } catch (Exception pe) { err = "HTTP " + code; }
                    final String fe = err;
                    mMain.post(() -> zCb.onError(fe));
                    return;
                }
                final JSONObject j = new JSONObject(respStr);
                mMain.post(() -> zCb.onResult(j));
            } catch (final Exception e) {
                final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                mMain.post(() -> zCb.onError(m));
            } finally { if (c != null) c.disconnect(); }
        });
    }

    public void status(Cb cb)               { cmd("status", cb); }
    public void tokens(Cb cb)               { cmd("tokens", cb); }
    public void coins(String addr, Cb cb)   { cmd("coins megammr:true address:" + addr, cb); }
    public void balance(String addr, Cb cb) { cmd("balance megammr:true address:" + addr, cb); }

    // Make a coin funded BEFORE we tracked our address spendable: register our (public) script so the node
    // knows it, then pull the coin's proof out of the megammr and import it into the tracked set — so
    // txnbasics can attach script + proof. None of these can move funds (no key ever leaves the device).
    public void trackScript(String script, Cb cb) { cmd("newscript trackall:true script:\"" + script + "\"", cb); }
    public void coinExport(String coinid, Cb cb)  { cmd("coinexport coinid:" + coinid, cb); }
    public void coinImport(String dataHex, Cb cb) { cmd("coinimport data:" + dataHex + " track:true", cb); }

    /** Publish a locally-signed txn: txnimport -> txnbasics -> txnpost (mine:true). Phase 2. */
    public void publish(final String zImportCmd, final String zId, final String zPostCmd, final Cb zCb) {
        // Family hard rule: every txn error path runs txndelete, or the
        // imported signed txn is left on the node and its coins can be
        // re-selected into a second, conflicting spend (double-spend / wasted
        // WOTS leaf). fail() cleans up before reporting.
        final Cb self = zCb;
        cmd(zImportCmd, new Cb() {
            @Override public void onResult(JSONObject r1) {
                if (!r1.optBoolean("status", false)) { fail(zId, "txnimport: " + r1.optString("error", r1.toString()), self); return; }
                cmd("txnbasics id:" + zId, new Cb() {
                    @Override public void onResult(JSONObject r2) {
                        if (!r2.optBoolean("status", false)) { fail(zId, "txnbasics: " + r2.optString("error", r2.toString()), self); return; }
                        cmd(zPostCmd, new Cb() {
                            @Override public void onResult(JSONObject r3) {
                                if (!r3.optBoolean("status", false)) { fail(zId, "txnpost: " + r3.optString("error", r3.toString()), self); return; }
                                zCb.onResult(r3);
                            }
                            @Override public void onError(String m) { fail(zId, m, self); }
                        });
                    }
                    @Override public void onError(String m) { fail(zId, m, self); }
                });
            }
            @Override public void onError(String m) { fail(zId, m, self); }
        });
    }

    /** Clean the half-built txn off the node, THEN report the failure. */
    private void fail(final String zId, final String zMsg, final Cb zCb) {
        cmd("txndelete id:" + zId, new Cb() {
            @Override public void onResult(JSONObject r) { zCb.onError(zMsg); }
            @Override public void onError(String m) { zCb.onError(zMsg); }
        });
    }

    private static final int MAX_RESP = 8 * 1024 * 1024;   // cap node response to guard against OOM
    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n, total = 0;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > MAX_RESP) throw new IOException("node response too large");
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public void onDestroy() { mIo.shutdownNow(); }
}
