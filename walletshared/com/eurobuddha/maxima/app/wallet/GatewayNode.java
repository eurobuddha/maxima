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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallet's remote node transport: HTTPS to a MegaMMR node's hardened command proxy — a
 * Parlons Node's read+relay {@code /cmd} gateway (or the legacy hosted proxy). The seed NEVER
 * leaves the device — this only carries the non-admin read + relay commands. Uses
 * {@code megammr:true} coin/balance reads so ONE node serves any address without per-user
 * tracking. Callbacks are marshalled to the main thread.
 *
 * <p>Since 0.6.49 it holds a LIST of endpoints — the Parlons fleet — and fails over: a
 * transport failure or a 5xx on the current node moves to the next and retries, then sticks
 * with whichever answered. A node-reported error (4xx: auth, allow-list, bad command) is NOT a
 * reason to fail over — every fleet node would say the same. A {@link #publish} pins ONE node
 * for its whole txnimport → txnbasics → txnpost sequence: the imported txn lives on that node,
 * so switching mid-way would leave it half-built there (and the fund-safety txndelete must
 * reach the same node).
 */
public class GatewayNode {

    public interface Cb { void onResult(JSONObject r); void onError(String msg); }

    /** One gateway: a full {@code …/cmd} URL and its bearer token. */
    public static final class Endpoint {
        public final String url;
        public final String token;
        public Endpoint(String zUrl, String zToken) {
            url = zUrl == null ? "" : zUrl.trim();
            token = zToken == null ? "" : zToken.trim();
        }
        boolean usable() { return !url.isEmpty() && !token.isEmpty(); }
    }

    private final List<Endpoint> mEndpoints;
    /** Index of the endpoint that last answered — the one every new call tries first. */
    private volatile int mCurrent = 0;
    private final Handler mMain;
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    /** Told (on the I/O thread) with the URL that answered whenever failover moved to it. */
    private volatile java.util.function.Consumer<String> mOnSwitch;

    public void setOnSwitch(java.util.function.Consumer<String> zListener) {
        mOnSwitch = zListener;
    }

    /** A single fixed endpoint (the user's own node, or the legacy hosted proxy). */
    public GatewayNode(String zUrl, String zToken, Handler zMain) {
        this(Collections.singletonList(new Endpoint(zUrl, zToken)), zMain);
    }

    /** An ordered list of endpoints with automatic failover (the Parlons fleet). */
    public GatewayNode(List<Endpoint> zEndpoints, Handler zMain) {
        List<Endpoint> eps = new ArrayList<>();
        for (Endpoint e : zEndpoints) if (e != null && e.usable()) eps.add(e);
        mEndpoints = Collections.unmodifiableList(eps);
        mMain = zMain;
    }

    public boolean configured() {
        return !mEndpoints.isEmpty();
    }

    /** The endpoint currently in use (full URL — never truncated; it is meant to be copied). */
    public String currentUrl() {
        return mEndpoints.isEmpty() ? "" : mEndpoints.get(Math.min(mCurrent, mEndpoints.size() - 1)).url;
    }

    public void cmd(final String zCommand, final Cb zCb) {
        cmdOn(-1, zCommand, zCb);
    }

    /**
     * Run a command. {@code zPin} < 0 = start at the current endpoint and fail over on transport
     * trouble; {@code zPin} >= 0 = that endpoint ONLY (a publish sequence).
     */
    private void cmdOn(final int zPin, final String zCommand, final Cb zCb) {
        mIo.execute(() -> {
            if (mEndpoints.isEmpty()) {
                mMain.post(() -> zCb.onError("no wallet node configured"));
                return;
            }
            int n = mEndpoints.size();
            int start = zPin >= 0 ? Math.min(zPin, n - 1) : Math.min(mCurrent, n - 1);
            int attempts = zPin >= 0 ? 1 : n;
            String lastErr = "";
            for (int a = 0; a < attempts; a++) {
                int idx = (start + a) % n;
                Endpoint ep = mEndpoints.get(idx);
                Exchange x = exchange(ep, zCommand);
                if (x.transportFailure) {
                    lastErr = x.error;
                    continue;                       // this node is down/unreachable — try the next
                }
                if (zPin < 0 && mCurrent != idx) {
                    mCurrent = idx;                 // it answered: stick with it
                    java.util.function.Consumer<String> l = mOnSwitch;
                    if (l != null) {
                        try { l.accept(ep.url); } catch (Exception ignored) { }
                    }
                }
                if (x.code >= 400) {
                    final String fe = x.error;
                    mMain.post(() -> zCb.onError(fe));
                } else {
                    final JSONObject j = x.json;
                    mMain.post(() -> zCb.onResult(j));
                }
                return;
            }
            final String fe = attempts > 1
                    ? "no wallet node reachable (" + n + " tried): " + lastErr : lastErr;
            mMain.post(() -> zCb.onError(fe));
        });
    }

    /** One HTTP round trip, classified: transport failure (retry elsewhere) vs a node answer. */
    private static final class Exchange {
        boolean transportFailure;
        int code;
        JSONObject json;
        String error = "";
    }

    private static Exchange exchange(Endpoint zEp, String zCommand) {
        Exchange x = new Exchange();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(zEp.url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + zEp.token);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(40000);
            byte[] body = new JSONObject().put("command", zCommand).toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            int code = c.getResponseCode();
            x.code = code;
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String respStr = readAll(is);
            if (code >= 500) {
                // the front (apache/caddy) answered but the node behind it did not — a dead node
                x.transportFailure = true;
                x.error = "HTTP " + code + " from " + zEp.url;
                return x;
            }
            if (code >= 400) {   // surface node/auth/allow-list failures as errors — never as an empty result
                try { x.error = new JSONObject(respStr).optString("error", "HTTP " + code); }
                catch (Exception pe) { x.error = "HTTP " + code; }
                return x;
            }
            x.json = new JSONObject(respStr);
            return x;
        } catch (Exception e) {
            x.transportFailure = true;
            x.error = (e.getMessage() == null ? e.toString() : e.getMessage()) + " (" + zEp.url + ")";
            return x;
        } finally { if (c != null) c.disconnect(); }
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

    /** Publish a locally-signed txn: txnimport -> txnbasics -> txnpost (mine:true), all on ONE node. */
    public void publish(final String zImportCmd, final String zId, final String zPostCmd, final Cb zCb) {
        // Family hard rule: every txn error path runs txndelete, or the
        // imported signed txn is left on the node and its coins can be
        // re-selected into a second, conflicting spend (double-spend / wasted
        // WOTS leaf). fail() cleans up before reporting — on the SAME node.
        final int pin = Math.min(mCurrent, Math.max(0, mEndpoints.size() - 1));
        final Cb self = zCb;
        cmdOn(pin, zImportCmd, new Cb() {
            @Override public void onResult(JSONObject r1) {
                if (!r1.optBoolean("status", false)) { fail(pin, zId, "txnimport: " + r1.optString("error", r1.toString()), self); return; }
                cmdOn(pin, "txnbasics id:" + zId, new Cb() {
                    @Override public void onResult(JSONObject r2) {
                        if (!r2.optBoolean("status", false)) { fail(pin, zId, "txnbasics: " + r2.optString("error", r2.toString()), self); return; }
                        cmdOn(pin, zPostCmd, new Cb() {
                            @Override public void onResult(JSONObject r3) {
                                if (!r3.optBoolean("status", false)) { fail(pin, zId, "txnpost: " + r3.optString("error", r3.toString()), self); return; }
                                zCb.onResult(r3);
                            }
                            @Override public void onError(String m) { fail(pin, zId, m, self); }
                        });
                    }
                    @Override public void onError(String m) { fail(pin, zId, m, self); }
                });
            }
            @Override public void onError(String m) { fail(pin, zId, m, self); }
        });
    }

    /** Clean the half-built txn off the SAME node, THEN report the failure. */
    private void fail(final int zPin, final String zId, final String zMsg, final Cb zCb) {
        cmdOn(zPin, "txndelete id:" + zId, new Cb() {
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
