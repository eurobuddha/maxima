package com.eurobuddha.maxima.app.portal.wallet;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.maxima.app.portal.CloudSession;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.json.JSONObject;

/**
 * The wallet's node API, Parlons-Cloud style: every command travels over the paired,
 * end-to-end encrypted control channel to the ACCOUNT's Parlons Node ({@code parlons.node.cmd})
 * and runs there on the node's console lane. Same surface the NFTwallet app had over
 * broadcast IPC ({@link #cmd}, {@link Cb}, {@link PairingListener}, {@link #isEnabled},
 * {@link #lastOkMs}, {@link #reRegister}, the error constants), so every ported screen is
 * unchanged.
 *
 * Results arrive ON THE MAIN THREAD as the node's own command JSON, complete: the node pages
 * long output out under the Maxima package ceiling and {@link ParlonsRemote#nodeCmd} stitches
 * it back, so {@link #ERR_TOO_LONG} is never produced here (it stays for the ported fallbacks).
 * Commands wait on the wallet's OWN lane, never on CloudSession's shared interactive lane.
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    public interface PairingListener {
        void onEnabled(boolean enabled);
    }

    /** Never produced here (pairing is the account's); kept because ported screens branch on it. */
    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";
    /** Never produced here (output is paged, never cut); kept because ported fallbacks branch on it. */
    public static final String ERR_TOO_LONG = "TOO_LONG";

    private static final long READ_TIMEOUT_MS = 60_000;
    private static final long WRITE_TIMEOUT_MS = 180_000;   // build + proof-of-work + post

    /** Transaction/PoW/sync commands can take a long time; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("send") || c.startsWith("consolidate") || c.startsWith("txnsign")
                || c.startsWith("txnpost") || c.startsWith("tokencreate") || c.startsWith("txnbasics")
                || c.startsWith("megammr") || c.startsWith("archive") || c.startsWith("backup")) {
            return WRITE_TIMEOUT_MS;
        }
        return READ_TIMEOUT_MS;
    }

    /** The wallet's own lane — a 3-minute command must never freeze the status pill or the
     *  other pages queued on CloudSession's shared interactive lane. */
    private static final java.util.concurrent.ExecutorService WALLET =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "portal-wallet-node");
                t.setDaemon(true);
                return t;
            });

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private final PairingListener mPairing;
    private volatile boolean mReleased;
    private volatile long mLastOkMs;

    public NodeApi(Context ctx, PairingListener pairing) {
        mContext = ctx;
        mPairing = pairing;
        // "Enabled" = this device is paired to an account. Answer at once, on the main thread,
        // the way the broadcast REGISTER reply used to.
        final boolean paired = CloudSession.isPaired(ctx);
        mMain.post(() -> {
            if (mPairing != null && !dead()) mPairing.onEnabled(paired);
        });
    }

    public boolean isEnabled() { return CloudSession.isPaired(mContext); }

    public long lastOkMs() { return mLastOkMs; }

    /** The pairing is the account's, not per app: nothing to re-send. */
    public void reRegister() {
        final boolean paired = CloudSession.isPaired(mContext);
        mMain.post(() -> {
            if (mPairing != null && !dead()) mPairing.onEnabled(paired);
        });
    }

    /** True once the hosting Activity is gone — don't deliver callbacks into dead views. */
    private boolean dead() {
        return mReleased || (mContext instanceof Activity
                && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed()));
    }

    public void cmd(String command, Cb cb) {
        cmd(command, timeoutFor(command), cb);
    }

    public void cmd(final String command, final long timeoutMs, final Cb cb) {
        if (mReleased) {
            // Always answer — a silent return would wedge callers that armed a "busy" flag first.
            mMain.post(() -> { if (cb != null) cb.onError("Node API released"); });
            return;
        }
        CloudSession.connectInteractive(mContext, new CloudSession.Cb() {
            @Override public void ok(ParlonsRemote r) {
                WALLET.execute(() -> {
                    try {
                        org.minima.utils.json.JSONObject o = r.nodeCmd(command, timeoutMs);
                        if (!Boolean.TRUE.equals(o.get("ok"))) {
                            deliverError(cb, String.valueOf(o.getOrDefault("error", "the node refused the command")));
                            return;
                        }
                        final JSONObject json = new JSONObject(String.valueOf(o.get("output")));
                        mLastOkMs = System.currentTimeMillis();
                        mMain.post(() -> {
                            if (dead()) return;
                            if (cb == null) return;
                            try {
                                cb.onResult(json);
                            } catch (Exception e) {
                                try { cb.onError("Bad node reply"); } catch (Exception ignored) { }
                            }
                        });
                    } catch (Exception e) {
                        deliverError(cb, "Your node didn't answer: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                });
            }
            @Override public void err(String message) {
                deliverError(cb, "Can't reach your node: " + message);
            }
        });
    }

    private void deliverError(final Cb cb, final String message) {
        mMain.post(() -> {
            if (dead()) return;
            if (cb != null) cb.onError(message);
        });
    }

    public void onDestroy() {
        mReleased = true;
    }
}
