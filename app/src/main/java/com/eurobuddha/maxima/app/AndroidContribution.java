package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;

import com.eurobuddha.maxima.core.services.ContributionPolicy;

/**
 * How much this handset is willing to do for other people, right now.
 *
 * Phones can contribute a great deal - mail, directory, gossip, storage,
 * receipts - but only storage-heavy duties actually cost anything material.
 * So the gates are graded rather than all-or-nothing:
 *
 *   witness    a signature. Costs microseconds. Always on.
 *   directory  a few hundred bytes in RAM. Always on.
 *   mailbox    holds other people's ciphertext. Needs decent battery.
 *   storage    holds arbitrary blobs. Needs charging or unmetered wifi.
 *
 * The user can force any of this off. Refusing is always safe: every Tier 1
 * duty is replicated k-of-n and idempotent precisely so an individual phone can
 * decline or disappear without the network caring.
 */
public final class AndroidContribution implements ContributionPolicy {

    private static final String PREFS = "maxima_contribution";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_UNMETERED_ONLY = "unmetered_only";
    private static final String KEY_MIN_BATTERY = "min_battery";

    private final Context mCtx;

    public AndroidContribution(Context zCtx) {
        mCtx = zCtx.getApplicationContext();
    }

    // ---- user switches ----

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static boolean unmeteredOnly(Context c) {
        return prefs(c).getBoolean(KEY_UNMETERED_ONLY, true);
    }

    public static void setUnmeteredOnly(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_UNMETERED_ONLY, v).apply();
    }

    public static int minBattery(Context c) {
        return prefs(c).getInt(KEY_MIN_BATTERY, 30);
    }

    public static void setMinBattery(Context c, int v) {
        prefs(c).edit().putInt(KEY_MIN_BATTERY, v).apply();
    }

    // ---- live device state ----

    public int batteryPercent() {
        try {
            BatteryManager bm = mCtx.getSystemService(BatteryManager.class);
            return bm == null ? 100 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return 100;
        }
    }

    public boolean isCharging() {
        try {
            BatteryManager bm = mCtx.getSystemService(BatteryManager.class);
            return bm != null && bm.isCharging();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isUnmetered() {
        try {
            ConnectivityManager cm = mCtx.getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return false;
            }
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * On a local segment (Wi-Fi or Ethernet) where same-network peers can exist.
     * This is what gates LAN direct discovery — independent of metered/contribution,
     * since talking to a peer in the same room over the LAN costs no data and is
     * about US reaching OUR contacts, not contributing to the network.
     */
    public boolean isLocalNetwork() {
        try {
            ConnectivityManager cm = mCtx.getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return false;
            }
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null
                    && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- the policy ----

    @Override
    public boolean acceptWitness() {
        return isEnabled(mCtx);
    }

    @Override
    public boolean acceptDirectory() {
        return isEnabled(mCtx);
    }

    @Override
    public boolean acceptMailbox() {
        if (!isEnabled(mCtx)) {
            return false;
        }
        if (unmeteredOnly(mCtx) && !isUnmetered()) {
            return false;
        }
        return isCharging() || batteryPercent() >= minBattery(mCtx);
    }

    @Override
    public boolean acceptStorage() {
        if (!isEnabled(mCtx)) {
            return false;
        }
        if (unmeteredOnly(mCtx) && !isUnmetered()) {
            return false;
        }
        // Blobs are the heaviest duty - only when the battery is not the user's
        // problem.
        return isCharging();
    }

    /** One line for the UI explaining what is currently on and why. */
    public String describe() {
        if (!isEnabled(mCtx)) {
            return "OFF - consuming only";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("witness+directory ON");
        sb.append(acceptMailbox() ? ", mailbox ON" : ", mailbox off");
        sb.append(acceptStorage() ? ", storage ON" : ", storage off");
        sb.append("\n  battery ").append(batteryPercent()).append('%');
        sb.append(isCharging() ? " charging" : " on battery");
        sb.append(isUnmetered() ? ", unmetered" : ", metered");
        if (unmeteredOnly(mCtx) && !isUnmetered()) {
            sb.append("  (heavy duties paused: metered)");
        } else if (!isCharging() && batteryPercent() < minBattery(mCtx)) {
            sb.append("  (heavy duties paused: low battery)");
        }
        return sb.toString();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
