package com.eurobuddha.maxima.app.wallet;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIListener;

/**
 * Thin wrapper around the Minima Core native IPC SDK, merged from the two hardened siblings:
 *
 *  - minimaSwap's self-healing pairing: write-in-flight accounting so reads queued behind a
 *    PoW grind aren't misread as a dead node, consecutive-timeout unpair, reRegister() to
 *    re-send the one-shot REGISTER broadcast, edge-triggered pairing callback.
 *  - pandapools' over-limit net: the node caps replies at 256,000 bytes and returns a stub —
 *    routed to onError(ERR_TOO_LONG) instead of being parsed as an empty result. NOTE this
 *    stub only exists when the reply was ASSEMBLED then capped; a reply that overflows the
 *    Binder parcel kills the app before any callback — every query must be bounded by design
 *    (the bundled AAR consumes the fork node's content:// file hand-off for oversized replies; bounded queries remain the rule for legacy nodes).
 *  - Throwable containment around callback dispatch so a bad reply can't crash the main Looper.
 *  - An explicit-timeout cmd() overload for callers that know better (filez pattern).
 */
public class NodeLink {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    public interface PairingListener {
        void onEnabled(boolean enabled);
    }

    /** Returned as the error message when the node says we are not enabled yet. */
    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";
    /** Returned when the node capped the reply at 256,000 bytes — shrink the query and retry. */
    public static final String ERR_TOO_LONG = "TOO_LONG";

    private static final long READ_TIMEOUT_MS = 30000;
    private static final long WRITE_TIMEOUT_MS = 180000;   // build + proof-of-work + post is slow on mobile

    /** Transaction/PoW commands can take a long time on a phone; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("send") || c.startsWith("consolidate") || c.startsWith("txnsign")
                || c.startsWith("txnpost") || c.startsWith("tokencreate") || c.startsWith("txnbasics")) {
            return WRITE_TIMEOUT_MS;
        }
        return READ_TIMEOUT_MS;
    }

    private MinimaAPI mApi;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final PairingListener mPairing;
    private final Context mContext;
    private final MinimaAPIListener mRegisterListener;
    // Pending timeout Runnables (main-thread only) so they can be cancelled on destroy.
    private final java.util.HashSet<Runnable> mPending = new java.util.HashSet<>();
    private boolean mReleased = false;

    // ---- pairing state (main-thread only). The register reply is a one-shot: if the node app isn't
    // running when we construct (e.g. right after a phone reboot), the broadcast is silently lost and
    // NOTHING would ever pair — the host must call reRegister() on a timer while !isEnabled(). ----
    private Boolean mEnabled = null;          // null until the first signal
    private long mLastOkMs = 0;               // last time ANY node reply arrived
    private int mConsecTimeouts = 0;
    // Writes (send/txnpost/…) grind proof-of-work for minutes on the node's SINGLE command thread, so
    // reads queued behind one routinely time out — that's "busy", not "dead". While a write is pending,
    // read timeouts don't count toward unpairing and reRegister() must not drop the write's reply.
    private int mPendingWrites = 0;
    /** Command timeouts in a row before we consider the node dead (~90-180s of silence). */
    private static final int TIMEOUTS_TO_UNPAIR = 3;

    public NodeLink(Context ctx, PairingListener pairing) {
        mContext = ctx;
        mPairing = pairing;
        mRegisterListener = new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                final boolean enabled = zResponse.optBoolean("enabled", false);
                mMain.post(() -> {
                    if (dead()) return;
                    noteEnabled(enabled);
                });
            }
        };
        // Constructing MinimaAPI auto-sends the REGISTER broadcast; the reply tells us
        // whether the user has enabled this app in Minima Core -> Apps yet.
        mApi = new MinimaAPI(ctx, mRegisterListener);
    }

    /** Fire the pairing listener ONLY on a state change — so recovery/loss is signalled exactly once. */
    private void noteEnabled(boolean enabled) {
        if (mEnabled != null && mEnabled == enabled) return;
        mEnabled = enabled;
        android.util.Log.d("NFTWallet", "pairing -> " + enabled);
        if (mPairing != null) mPairing.onEnabled(enabled);
    }

    public boolean isEnabled() { return mEnabled != null && mEnabled; }

    public long lastOkMs() { return mLastOkMs; }

    /**
     * Re-send the pairing REGISTER by recreating the SDK instance — {@code MinimaAPI.Register()} is
     * private and constructor-only. Safe and idempotent: the app/node uids are persisted in the SDK's
     * own prefs, so the identity is stable across instances, and the node persists the enabled flag in
     * its DB. In-flight response handlers from the old instance are dropped — their timeouts (above)
     * already surface as onError. Call only while unpaired or after consecutive timeouts.
     */
    public void reRegister() {
        if (mReleased) return;
        if (mPendingWrites > 0) {   // a slow write (PoW) is in flight — recreating would drop its reply
            android.util.Log.d("NFTWallet", "reREGISTER skipped — write in flight");
            return;
        }
        android.util.Log.d("NFTWallet", "reREGISTER");
        try { mApi.onDestroy(); } catch (Exception ignored) {}
        mApi = new MinimaAPI(mContext, mRegisterListener);
    }

    /** True once the hosting Activity is gone — don't deliver callbacks into dead views. */
    private boolean dead() {
        return mContext instanceof Activity
                && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed());
    }

    /** The node's over-limit stub: {@code status:false} with a String {@code response} containing "too long".
     *  The node discards any command reply over 256,000 bytes and returns this instead of the data. */
    private static boolean isTooLong(JSONObject j) {
        if (j == null || j.optBoolean("status", true)) return false;
        Object r = j.opt("response");
        if (!(r instanceof String)) return false;
        String s = ((String) r).toLowerCase();
        // match the current wording ("Result too long!") and the size cap itself, so a reworded message
        // still trips the guard as long as the byte-cap marker is present.
        return s.contains("too long") || s.contains("max(256000)");
    }

    public void cmd(String command, Cb cb) {
        cmd(command, timeoutFor(command), cb);
    }

    public void cmd(String command, long timeoutMs, Cb cb) {
        if (mReleased) {
            // ALWAYS dispatch: a caller that never hears back can wedge forever — BookRepository
            // sets `scanning = true` before calling and only clears it in the callback, so a
            // silent return makes every later refresh() a no-op.
            //
            // POSTED, never inline: every other reply in this class arrives via mMain, and
            // callers set their own state AFTER the call returns. Running the callback inside
            // cmd() would re-enter them mid-setup — BookScanner would chain its next query and
            // deliver a whole book callback before refresh() had even finished starting.
            if (cb != null) mMain.post(() -> cb.onError("Node API released"));
            return;
        }
        final boolean isWrite = timeoutFor(command) == WRITE_TIMEOUT_MS;
        if (isWrite) mPendingWrites++;
        final boolean[] done = {false};
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0]) return;
            done[0] = true;
            // decrement BEFORE the dead() bail-out: leaking this counter permanently pins it
            // above zero, which disables both reRegister() and the unpair detector
            if (isWrite) mPendingWrites--;
            if (dead()) return;
            // The "paired-then-node-died" detector: a run of dead-air commands means the node app is gone
            // (rebooted phone, force-stop, crash). Flip to unpaired so the hosts start re-registering.
            // NOT while a write is pending — the node is likely just grinding that write's PoW.
            if (mPendingWrites == 0 && ++mConsecTimeouts >= TIMEOUTS_TO_UNPAIR) noteEnabled(false);
            if (cb != null) cb.onError("Minima Core didn't respond. Is it installed, running and enabled?");
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, timeoutMs);

        mApi.Command(command, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    if (isWrite) mPendingWrites--;
                    // Any real reply proves the node is alive — track BEFORE the dead-view check
                    // (pairing state isn't a view).
                    mLastOkMs = System.currentTimeMillis();
                    mConsecTimeouts = 0;
                    if (dead()) return;   // cleaned up above; just don't touch dead views

                    // Contain any exception a callback throws (most often JSON parsing of a large/odd reply)
                    // so it can't reach the main Looper and crash the whole app. On failure route to onError
                    // so the caller resets its loading state / shows a message instead of dying.
                    try {
                        // "enabled":false only appears on the gating reply; real command
                        // responses omit the key, so default true.
                        if (!zResponse.optBoolean("enabled", true)) {
                            noteEnabled(false);
                            if (cb != null) cb.onError(ERR_NOT_ENABLED);
                            return;
                        }
                        // A successful command means the node ran it as an enabled app — if we thought we
                        // were unpaired (e.g. the register reply got lost), this is the recovery signal.
                        noteEnabled(true);
                        if (isTooLong(zResponse)) { if (cb != null) cb.onError(ERR_TOO_LONG); return; }
                        if (cb != null) cb.onResult(zResponse);
                    } catch (Throwable t) {
                        if (cb != null) { try { cb.onError("Bad node reply"); } catch (Throwable ignore) {} }
                    }
                });
            }
        });
    }

    public void onDestroy() {
        mReleased = true;
        for (Runnable r : mPending) mMain.removeCallbacks(r);
        mPending.clear();
        if (mApi != null) mApi.onDestroy();
    }
}
