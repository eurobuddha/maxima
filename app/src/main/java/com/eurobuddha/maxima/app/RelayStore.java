package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
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
    /** The compiled-in seed list - ONE source among several (see SeedRelays), never the
     *  only one: the user can drop entries from it or switch it off entirely. */
    public static final List<String> DEFAULTS = com.eurobuddha.maxima.core.session.Bootstrap.RELAYS;
    private static final String KEY_EXCLUDED = "relays_excluded";
    private static final String KEY_BUILTIN = "relays_builtin";

    private RelayStore() {
    }

    /**
     * CLASSIC-ONLY EXPERIMENT SWITCH (dev-only, no UI): when the pref
     * "classic_only_hosts" holds a non-empty comma-separated host list, the
     * seeds are dropped and ONLY those hosts are used - to demonstrate
     * Parlons running on pure stock-Minima infrastructure. Set/cleared via
     * adb: the pref lives in maxima_relays. Release users never touch it.
     */
    public static List<String> classicOnly(Context zCtx) {
        String s = prefs(zCtx).getString("classic_only_hosts", "");
        List<String> out = new ArrayList<>();
        if (s != null && !s.trim().isEmpty()) {
            for (String h : s.split(",")) {
                if (!h.trim().isEmpty()) {
                    out.add(h.trim());
                }
            }
        }
        return out;
    }

    /**
     * The seeds this phone starts from: your own relays first, then the compiled-in list if it
     * is switched on (minus any you dropped). Relays remembered from earlier runs and learned
     * at runtime are layered on top by the service / the pool.
     */
    public static List<String> get(Context zCtx) {
        List<String> classic = classicOnly(zCtx);
        if (!classic.isEmpty()) {
            return classic;
        }
        return com.eurobuddha.maxima.core.session.SeedRelays.compose(
                userSeeds(zCtx), null, builtInEnabled(zCtx), excluded(zCtx));
    }

    /** Relays YOU added (typed, pasted or scanned). Older installs persisted the merged set,
     *  built-ins included: those are filtered out here so they stay governed by the switch. */
    public static List<String> userSeeds(Context zCtx) {
        Set<String> persisted = prefs(zCtx).getStringSet(KEY, null);
        List<String> out = new ArrayList<>();
        if (persisted != null) {
            for (String h : persisted) {
                if (!DEFAULTS.contains(h) && isValid(h)) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    /** Built-in entries the user dropped. */
    public static Set<String> excluded(Context zCtx) {
        Set<String> s = prefs(zCtx).getStringSet(KEY_EXCLUDED, null);
        return s == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s);
    }

    public static boolean isBuiltIn(String zHostPort) {
        return zHostPort != null && DEFAULTS.contains(zHostPort.trim());
    }

    /** Whether the compiled-in list is used as a seed source at all (default: yes). */
    public static boolean builtInEnabled(Context zCtx) {
        return prefs(zCtx).getBoolean(KEY_BUILTIN, true);
    }

    /**
     * Switch the compiled-in list on or off. Refuses to switch it OFF while you have no relay
     * of your own and nothing remembered, because that would leave the phone with nowhere to
     * start. Returns whether the change was applied.
     */
    public static boolean setBuiltInEnabled(Context zCtx, boolean zOn) {
        if (!zOn && userSeeds(zCtx).isEmpty() && SwarmStore.recent(zCtx).isEmpty()) {
            return false;
        }
        prefs(zCtx).edit().putBoolean(KEY_BUILTIN, zOn).apply();
        return true;
    }

    public static void add(Context zCtx, String zHostPort) {
        String hp = zHostPort.trim();
        if (isBuiltIn(hp)) {
            // Re-adding a built-in you had dropped: un-drop it.
            Set<String> ex = excluded(zCtx);
            ex.remove(hp);
            prefs(zCtx).edit().putStringSet(KEY_EXCLUDED, ex).apply();
            return;
        }
        Set<String> s = new LinkedHashSet<>(userSeeds(zCtx));
        s.add(hp);
        prefs(zCtx).edit().putStringSet(KEY, s).apply();
    }

    public static void remove(Context zCtx, String zHostPort) {
        String hp = zHostPort.trim();
        if (isBuiltIn(hp)) {
            Set<String> ex = excluded(zCtx);
            ex.add(hp);
            prefs(zCtx).edit().putStringSet(KEY_EXCLUDED, ex).apply();
            return;
        }
        Set<String> s = new LinkedHashSet<>(userSeeds(zCtx));
        s.remove(hp);
        prefs(zCtx).edit().putStringSet(KEY, s).apply();
    }

    /** Back to the compiled-in list only: your seeds, your drops and the switch are cleared. */
    public static void reset(Context zCtx) {
        prefs(zCtx).edit().remove(KEY).remove(KEY_EXCLUDED).remove(KEY_BUILTIN).apply();
    }

    /** "host:port" with a plausible port. */
    public static boolean isValid(String zHostPort) {
        return com.eurobuddha.maxima.core.session.SeedRelays.isValid(zHostPort);
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
