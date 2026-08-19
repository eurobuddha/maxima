package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The remembered swarm — relays this device has discovered or attached to, each
 * with a last-seen stamp. This is the phone's analog of classic Maxima's
 * {@code MaximaHost} DB: hosts are learned from the network (gossip), kept so
 * the swarm feels "always there" across restarts, and aged out when they go
 * quiet — {@link #prune} mirrors {@code MaximaManager.deleteOldHosts}.
 *
 * {@link RelayStore} is the trusted floor + user edits; this is the fluid,
 * self-maintaining membership on top. On launch the pump seeds candidates from
 * {@code RelayStore.get() ∪ recent()}; on every successful attach or fresh
 * discovery it calls {@link #seen}; the heartbeat calls {@link #prune}.
 */
public final class SwarmStore {

    private static final String PREFS = "maxima_swarm";

    /** A relay unseen for this long is forgotten — the same 7 days classic uses. */
    public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    private SwarmStore() {
    }

    /** Record (or refresh) that a relay is part of the swarm right now. */
    public static void seen(Context zCtx, String zHostPort) {
        if (zHostPort == null || zHostPort.isEmpty()) {
            return;
        }
        prefs(zCtx).edit().putLong(zHostPort, System.currentTimeMillis()).apply();
    }

    /** Every relay seen within {@link #MAX_AGE_MS}, freshest membership first is
     *  not guaranteed - order is irrelevant, the pool scores them itself. */
    public static List<String> recent(Context zCtx) {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(zCtx).getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Long && (Long) v >= cutoff) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Forget relays unseen for longer than {@link #MAX_AGE_MS}. Heartbeat-driven. */
    public static void prune(Context zCtx) {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        SharedPreferences p = prefs(zCtx);
        SharedPreferences.Editor ed = p.edit();
        boolean any = false;
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof Long) || (Long) v < cutoff) {
                ed.remove(e.getKey());
                any = true;
            }
        }
        if (any) {
            ed.apply();
        }
    }

    /**
     * Forget the entire learned swarm. Used as a one-time scrub on upgrade to
     * flush entries that should never have been persisted (e.g. our own stale
     * direct-forward address, or a classic Minima node that greets but relays
     * nothing). The {@link RelayStore#DEFAULTS} fleet floor is separate and
     * untouched, so the phone still has its full, correct relay set immediately.
     */
    public static void clear(Context zCtx) {
        prefs(zCtx).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
