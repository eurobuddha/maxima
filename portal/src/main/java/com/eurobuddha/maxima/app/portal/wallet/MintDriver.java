package com.eurobuddha.maxima.app.portal.wallet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The one place a mint tick is started, used by both the Activity and {@link MintService}.
 *
 * Mirrors the family's shared-brain convention (Limit's {@code LimitProcessor}, AtomiX's
 * {@code SwapEngine}) so foreground and background behave identically.
 *
 * Every call returns a {@link Result}. That is the point: the old entry point returned silently
 * when a tick was already running or when it decided nothing was active — and its idea of "active"
 * excluded NEEDIMAGES, while the Resume button was shown for every phase but DONE. Tapping Resume
 * on a paused collection therefore did nothing at all, with no explanation. A caller can no longer
 * fail to know what happened.
 */
public final class MintDriver {

    public enum Result {
        /** A tick was started; the callback will fire when it finishes. */
        STARTED,
        /** A tick is already in flight — a write can hold the node for minutes. */
        BUSY,
        /** A collection is parked waiting for the user to supply images. */
        NEEDS_IMAGES,
        /** Nothing to do: every collection is DONE or BURIED. */
        NOTHING_TO_DO,
        /** The node isn't paired, so no command can be run. */
        NOT_PAIRED
    }

    public interface Done {
        void onFinished(String message);
    }

    /** Guards re-entrancy across BOTH hosts — a tick chains many commands and must not overlap. */
    private static volatile boolean running = false;
    /** When the in-flight tick started, so a lost callback can't latch {@link #running} forever. */
    private static volatile long startedAt = 0;

    /**
     * How long a tick may hold the guard before we assume its callback is never coming.
     *
     * This is not belt-and-braces. NodeApi drops callbacks once its hosting Activity is finishing
     * or destroyed (NodeApi.dead()), and a tick chains many commands with a write that can hold the
     * node for minutes — so rotating the phone or toggling the theme mid-mint destroys the Activity
     * with a tick in flight and the completion callback never fires. Because this guard is static,
     * that would wedge minting for the WHOLE process: every later tick, in the Activity and in
     * MintService alike, would return BUSY until the process died. Generous enough never to cut a
     * healthy tick short; short enough that a wedge heals on its own.
     */
    private static final long MAX_TICK_MS = 5 * 60_000;

    private MintDriver() {}

    public static boolean isRunning() { return inFlight(); }

    /** True while a tick is genuinely in flight — a stale guard past MAX_TICK_MS reads as free. */
    private static boolean inFlight() {
        if (!running) return false;
        if (System.currentTimeMillis() - startedAt > MAX_TICK_MS) {
            running = false;        // its callback was swallowed; let the next tick through
            return false;
        }
        return true;
    }

    /** Phases the engine can advance on its own, without user input. */
    public static boolean hasActiveMint(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            if (r.optInt("stuck", 0) == 1) continue;   // unrecoverable — don't tick it forever
            String p = r.optString("phase", "DONE");
            if ("CREATE".equals(p) || "MOVE".equals(p) || "SPLIT".equals(p) || "STAMP".equals(p)) return true;
        }
        return false;
    }

    /** A collection stalled because an embedded item has no image yet — needs the user, not a tick. */
    public static boolean needsImages(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null && "NEEDIMAGES".equals(r.optString("phase", ""))) return true;
        }
        return false;
    }

    /** True while any collection still needs work of any kind — what decides if the service lives. */
    public static boolean hasWork(Context ctx) {
        return hasActiveMint(ctx) || needsImages(ctx);
    }

    /** A collection that can never proceed — currently only the oversized-token-record case. */
    public static boolean isStuck(JSONObject row) {
        return row != null && row.optInt("stuck", 0) == 1;
    }

    /**
     * True when any collection is parked as unrecoverable.
     *
     * hasWork() deliberately ignores stuck rows, so a stuck collection looks exactly like a
     * finished one to the service. Without this, burning out on the 64KB cap was announced as
     * "your collection is fully stamped" — the opposite of what happened.
     */
    public static boolean hasStuck(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            if (isStuck(rows.optJSONObject(i))) return true;
        }
        return false;
    }

    /** Advance one collection by one step. {@code done} fires only when the result is STARTED. */
    public static Result tick(Context ctx, NodeApi node, final Done done) {
        if (node == null) return Result.NOT_PAIRED;
        if (inFlight()) return Result.BUSY;
        if (!hasActiveMint(ctx)) {
            return needsImages(ctx) ? Result.NEEDS_IMAGES : Result.NOTHING_TO_DO;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        MintEngine.tick(ctx, node, message -> {
            running = false;
            if (done != null) done.onFinished(message == null ? "" : message);
        });
        return Result.STARTED;
    }

    /** What to tell the user when they asked for a tick and didn't get one. */
    public static String explain(Result r) {
        switch (r) {
            case STARTED:       return "Working on it…";
            case BUSY:          return "Already working — a transaction is still in flight.";
            case NEEDS_IMAGES:  return "Waiting for you: an item still needs its image.";
            case NOT_PAIRED:    return "Not connected to Minima Core.";
            default:            return "Nothing left to mint.";
        }
    }

    // ---- progress, for the notification and the progress screen ----

    /** {stamped, size} for the first collection still being worked on, or null. */
    public static int[] activeProgress(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String p = r.optString("phase", "DONE");
            if ("DONE".equals(p) || "BURIED".equals(p)) continue;
            int size = r.optInt("size", 0);
            int stamped = 0;
            JSONArray items = MintEngine.localItems(r);
            for (int j = 0; j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                if (it != null && !it.optString("coinid", "").isEmpty()) stamped++;
            }
            return new int[]{stamped, size};
        }
        return null;
    }

    /** Name of the collection currently being worked on, or "" when there is none. */
    public static String activeName(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String p = r.optString("phase", "DONE");
            if ("DONE".equals(p) || "BURIED".equals(p)) continue;
            return r.optString("name", "Collection");
        }
        return "";
    }
}
