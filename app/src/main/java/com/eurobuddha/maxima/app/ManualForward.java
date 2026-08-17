package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manual port-forward mode: for routers that won't open a port automatically
 * (no UPnP/NAT-PMP), the user adds a forward rule by hand and tells us their
 * public IP + the forwarded port. We then pin the direct listener to that port
 * (so the rule survives restarts), prove the address is reachable from outside
 * (a relay dials it back), and advertise it to contacts — exactly like the
 * automatic path, minus the router negotiation.
 */
public final class ManualForward {

    private static final String PREFS = "maxima_manual_fwd";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_IP = "public_ip";
    private static final String KEY_PORT = "port";

    /** A fixed, memorable default so the router rule is stable. */
    public static final int DEFAULT_PORT = 9536;

    private ManualForward() {
    }

    public static boolean enabled(Context zCtx) {
        return prefs(zCtx).getBoolean(KEY_ENABLED, false);
    }

    public static String publicIp(Context zCtx) {
        return prefs(zCtx).getString(KEY_IP, "");
    }

    public static int port(Context zCtx) {
        return prefs(zCtx).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void set(Context zCtx, boolean zEnabled, String zIp, int zPort) {
        prefs(zCtx).edit()
                .putBoolean(KEY_ENABLED, zEnabled)
                .putString(KEY_IP, zIp == null ? "" : zIp.trim())
                .putInt(KEY_PORT, zPort > 0 && zPort < 65536 ? zPort : DEFAULT_PORT)
                .apply();
    }

    public static void setEnabled(Context zCtx, boolean zEnabled) {
        prefs(zCtx).edit().putBoolean(KEY_ENABLED, zEnabled).apply();
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
