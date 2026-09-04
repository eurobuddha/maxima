package com.eurobuddha.maxima.app.portal.ide;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.maxima.app.portal.CloudSession;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.json.JSONObject;

/**
 * The Terminal IDE's node API, Parlons-Cloud style: every command travels over the paired,
 * end-to-end encrypted control channel to the ACCOUNT's Parlons Node ({@code parlons.node.cmd})
 * and runs there on the node's console lane. Same surface the Terminal IDE companion app had
 * over broadcast IPC ({@link #cmd} + {@link Cb}), so the ported screens are unchanged.
 *
 * The result arrives ON THE MAIN THREAD as the node's own command JSON, complete — the node
 * pages long output out under the Maxima package ceiling and {@link ParlonsRemote#nodeCmd}
 * stitches it back, so nothing is ever cut.
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    /** Kept for the ported screens; never produced here (pairing is the account's, not per app). */
    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";

    private static final long READ_TIMEOUT_MS = 60_000;
    private static final long WRITE_TIMEOUT_MS = 180_000;   // build + proof-of-work + post, sync jobs

    /** Transaction/PoW/sync commands can take a long time; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("send") || c.startsWith("consolidate") || c.startsWith("txnsign")
                || c.startsWith("txnpost") || c.startsWith("tokencreate") || c.startsWith("txnbasics")
                || c.startsWith("runscript") || c.startsWith("newscript") || c.startsWith("megammr")
                || c.startsWith("archive") || c.startsWith("backup") || c.startsWith("restore")) {
            return WRITE_TIMEOUT_MS;
        }
        return READ_TIMEOUT_MS;
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private volatile boolean mReleased;

    /**
     * The Terminal's OWN lane. A command can poll the node for up to three minutes; running that
     * on CloudSession's shared interactive lane would freeze the status pill and every other
     * page behind it (the lane-starvation that once showed as a 27 s "connecting…"). The session
     * is only borrowed (connectInteractive, fast) to obtain the remote; the wait happens here.
     */
    private static final java.util.concurrent.ExecutorService CONSOLE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "portal-terminal");
                t.setDaemon(true);
                return t;
            });

    public NodeApi(Context ctx) {
        mContext = ctx;
    }

    /** True once the hosting Activity is gone — don't deliver callbacks into dead views. */
    private boolean dead() {
        return mReleased || (mContext instanceof Activity
                && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed()));
    }

    public void cmd(final String command, final Cb cb) {
        if (mReleased) return;
        final long timeout = timeoutFor(command);
        CloudSession.connectInteractive(mContext, new CloudSession.Cb() {
            @Override public void ok(ParlonsRemote r) {
                CONSOLE.execute(() -> {
                    try {
                        org.minima.utils.json.JSONObject o = r.nodeCmd(command, timeout);
                        if (!Boolean.TRUE.equals(o.get("ok"))) {
                            deliverError(cb, String.valueOf(o.getOrDefault("error", "the node refused the command")));
                            return;
                        }
                        final JSONObject json = new JSONObject(String.valueOf(o.get("output")));
                        mMain.post(() -> {
                            if (dead()) return;
                            if (cb != null) cb.onResult(json);
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

    /** The account node's rolling event log (the operator log the control panel shows). */
    public void nodeLog(final Cb cb) {
        if (mReleased) return;
        CloudSession.connectInteractive(mContext, new CloudSession.Cb() {
            @Override public void ok(ParlonsRemote r) {
                CONSOLE.execute(() -> {
                    try {
                        org.minima.utils.json.JSONObject o = r.nodeLog(false);
                        final JSONObject json = new JSONObject(o.toString());
                        mMain.post(() -> {
                            if (dead()) return;
                            if (cb != null) cb.onResult(json);
                        });
                    } catch (Exception e) {
                        deliverError(cb, e.getMessage() == null ? e.toString() : e.getMessage());
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
