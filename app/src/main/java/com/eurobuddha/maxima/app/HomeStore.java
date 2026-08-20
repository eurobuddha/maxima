package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The home relays this device has recently attended, each with a last-attached
 * stamp.
 *
 * With classic-scale routing (k=2 home relays instead of the whole fleet), a
 * contact may still be fanning mail to a PREVIOUS home of ours for a while
 * after we move (until their copy of our address heals via refresh/MLS). The
 * relay's mailbox holds that mail - but only a visit drains it. So on startup
 * (and after a home change) the service briefly re-attaches each recent old
 * home, lets the push reader drain the held mail, and detaches. Entries older
 * than {@link #MAX_AGE_MS} are forgotten.
 */
public final class HomeStore {

    private static final String PREFS = "maxima_homes";

    /** How long an old home is still worth a drain visit. */
    public static final long MAX_AGE_MS = 48L * 60 * 60 * 1000;

    private HomeStore() {
    }

    /** Record that we are attached to this relay right now. */
    public static void attached(Context zCtx, String zHostPort) {
        if (zHostPort == null || zHostPort.isEmpty()) {
            return;
        }
        prefs(zCtx).edit().putLong(zHostPort, System.currentTimeMillis()).apply();
    }

    /** Recent homes (within {@link #MAX_AGE_MS}), pruning the rest. */
    public static List<String> recent(Context zCtx) {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        SharedPreferences p = prefs(zCtx);
        SharedPreferences.Editor ed = p.edit();
        List<String> out = new ArrayList<>();
        boolean dirty = false;
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Long && (Long) v >= cutoff) {
                out.add(e.getKey());
            } else {
                ed.remove(e.getKey());
                dirty = true;
            }
        }
        if (dirty) {
            ed.apply();
        }
        return out;
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
