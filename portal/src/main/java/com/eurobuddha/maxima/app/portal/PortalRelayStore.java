package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.content.SharedPreferences;

import com.eurobuddha.maxima.core.session.SeedRelays;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The relays THIS PHONE starts from to find its account: your own (typed, pasted or scanned
 * from a relay's QR) first, then the compiled-in list while it is switched on. The compiled-in
 * list is one seed source among several - never the only one (see {@link SeedRelays}).
 */
public final class PortalRelayStore {

    private static final String PREFS = "portal_relays";
    private static final String KEY = "relays";
    private static final String KEY_EXCLUDED = "relays_excluded";
    private static final String KEY_BUILTIN = "relays_builtin";

    private PortalRelayStore() {
    }

    public static List<String> get(Context zCtx) {
        return SeedRelays.compose(userSeeds(zCtx), null, builtInEnabled(zCtx), excluded(zCtx));
    }

    public static List<String> userSeeds(Context zCtx) {
        Set<String> s = prefs(zCtx).getStringSet(KEY, null);
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String h : s) {
                if (!SeedRelays.isBuiltIn(h) && SeedRelays.isValid(h)) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    public static Set<String> excluded(Context zCtx) {
        Set<String> s = prefs(zCtx).getStringSet(KEY_EXCLUDED, null);
        return s == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s);
    }

    public static boolean builtInEnabled(Context zCtx) {
        return prefs(zCtx).getBoolean(KEY_BUILTIN, true);
    }

    /** Refuses to switch OFF with no relay of your own: the phone needs somewhere to start. */
    public static boolean setBuiltInEnabled(Context zCtx, boolean zOn) {
        if (!zOn && userSeeds(zCtx).isEmpty()) {
            return false;
        }
        prefs(zCtx).edit().putBoolean(KEY_BUILTIN, zOn).apply();
        return true;
    }

    public static void add(Context zCtx, String zHostPort) {
        String hp = zHostPort.trim();
        if (SeedRelays.isBuiltIn(hp)) {
            Set<String> ex = excluded(zCtx);
            ex.remove(hp);
            prefs(zCtx).edit().putStringSet(KEY_EXCLUDED, ex).apply();
            return;
        }
        Set<String> s = new LinkedHashSet<>(userSeeds(zCtx));
        s.add(hp);
        prefs(zCtx).edit().putStringSet(KEY, s).apply();
    }

    /** Drop a relay. True when that was the last seed while the compiled-in list was off: the
     *  list is switched back on, so this phone is never left with nowhere to start. */
    public static boolean remove(Context zCtx, String zHostPort) {
        String hp = zHostPort.trim();
        if (SeedRelays.isBuiltIn(hp)) {
            Set<String> ex = excluded(zCtx);
            ex.add(hp);
            prefs(zCtx).edit().putStringSet(KEY_EXCLUDED, ex).apply();
            return false;
        }
        Set<String> s = new LinkedHashSet<>(userSeeds(zCtx));
        s.remove(hp);
        prefs(zCtx).edit().putStringSet(KEY, s).apply();
        if (SeedRelays.builtInMustReturn(s, null, builtInEnabled(zCtx))) {
            prefs(zCtx).edit().putBoolean(KEY_BUILTIN, true).apply();
            return true;
        }
        return false;
    }

    public static void reset(Context zCtx) {
        prefs(zCtx).edit().remove(KEY).remove(KEY_EXCLUDED).remove(KEY_BUILTIN).apply();
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
