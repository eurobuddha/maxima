package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The relays this device attaches to, editable by the user.
 *
 * Hardcoding a relay list would make the app useless the moment someone stands
 * up their own - which is the normal case, since the whole point is that
 * relays are cheap and plentiful.
 */
public final class RelayStore {

    private static final String PREFS = "maxima_relays";
    private static final String KEY = "relays";

    /**
     * Shipped defaults. First entry is a relay running this implementation;
     * the rest are stock Minima nodes, which relay for us perfectly well.
     * Multi-homing means we do not depend on any single one.
     */
    /**
     * Where a fresh install attaches.
     *
     * Ours first, because they run this build and therefore answer mailbox,
     * directory and witness requests; the trailing classic Minima nodes are
     * fallbacks that can relay but know nothing of the extensions. Spread
     * across four operators and four countries on purpose - a default list
     * that all lands in one datacentre is a single point of failure wearing
     * the costume of a decentralised one.
     */
    public static final List<String> DEFAULTS = Arrays.asList(
            "95.179.179.181:9501",     // sally      - Amsterdam, NL
            "65.109.31.226:9501",      // eurobuddha - Helsinki, FI
            "45.77.246.226:9501",      // maxima     - Singapore, SG
            "78.141.237.9:9501",       // openproject- London, GB
            "45.77.57.24:9501",        // vigilance  - London, GB
            "192.248.151.55:9501",     // megammr    - London, GB
            "31.125.188.214:8001",     // the Pi     - residential, GB
            "34.105.180.174:9001",     // classic Minima nodes below here
            "168.138.15.32:9001",
            "34.32.118.123:9001");

    private RelayStore() {
    }

    public static List<String> get(Context zCtx) {
        Set<String> s = prefs(zCtx).getStringSet(KEY, null);
        if (s == null || s.isEmpty()) {
            return new ArrayList<>(DEFAULTS);
        }
        return new ArrayList<>(s);
    }

    public static void add(Context zCtx, String zHostPort) {
        Set<String> s = new LinkedHashSet<>(get(zCtx));
        s.add(zHostPort.trim());
        prefs(zCtx).edit().putStringSet(KEY, s).apply();
    }

    public static void remove(Context zCtx, String zHostPort) {
        Set<String> s = new LinkedHashSet<>(get(zCtx));
        s.remove(zHostPort.trim());
        prefs(zCtx).edit().putStringSet(KEY, s).apply();
    }

    public static void reset(Context zCtx) {
        prefs(zCtx).edit().remove(KEY).apply();
    }

    /** "host:port" with a plausible port. */
    public static boolean isValid(String zHostPort) {
        int c = zHostPort.lastIndexOf(':');
        if (c <= 0 || c == zHostPort.length() - 1) {
            return false;
        }
        try {
            int p = Integer.parseInt(zHostPort.substring(c + 1).trim());
            return p > 0 && p < 65536 && !zHostPort.substring(0, c).trim().isEmpty();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
