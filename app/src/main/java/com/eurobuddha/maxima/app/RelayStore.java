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
     * The bootstrap floor a fresh install attaches to and gossip discovers FROM.
     *
     * These run this build, so they answer mailbox, directory, witness AND blob
     * requests, and they gossip their verified peers - one live attachment seeds
     * the whole swarm. Spread across operators and countries on purpose: a
     * default list that all lands in one datacentre is a single point of failure
     * wearing the costume of a decentralised one. Matches {@code Bootstrap.RELAYS}
     * (core). Classic Minima nodes on :9001 are deliberately NOT here - they can
     * relay a raw message but know nothing of the blob/mailbox/directory
     * extensions, so as media/swarm hosts they are dead weight and only add
     * connection noise. Discovery finds real relays; it does not need seeding
     * with nodes that cannot serve.
     */
    public static final List<String> DEFAULTS = Arrays.asList(
            "95.179.179.181:9501",     // sally      - Amsterdam, NL
            "65.109.31.226:9501",      // eurobuddha - Helsinki, FI
            "45.77.246.226:9501",      // maxima     - Singapore, SG
            "78.141.237.9:9501",       // openproject- London, GB
            "45.77.57.24:9501",        // vigilance  - London, GB
            "192.248.151.55:9501",     // megammr    - London, GB
            "31.125.188.214:8001");    // the Pi     - residential, GB

    private RelayStore() {
    }

    /**
     * The user-editable candidate list, with DEFAULTS as a permanent floor.
     *
     * DEFAULTS come first and are ALWAYS present: a stale or dead persisted set
     * (e.g. an old build's hosts, or a relay that has since moved port) can never
     * shadow the shipped fleet and strand the device with nothing live to seed
     * discovery from. User additions follow, deduped.
     */
    public static List<String> get(Context zCtx) {
        Set<String> merged = new LinkedHashSet<>(DEFAULTS);
        Set<String> persisted = prefs(zCtx).getStringSet(KEY, null);
        if (persisted != null) {
            merged.addAll(persisted);
        }
        return new ArrayList<>(merged);
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
