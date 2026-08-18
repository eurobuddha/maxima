package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one chat setting that is a real decision rather than a preference.
 *
 * Telling someone you have READ their message is information about you that
 * they cannot get any other way, so it is off until you turn it on. Delivery
 * receipts are not optional - they are what the second tick means, and a
 * transport that cannot say "it arrived" is the thing we set out to fix.
 */
public final class ChatPrefs {

    private static final String PREFS = "maxima_chat";
    private static final String READ_RECEIPTS = "read_receipts";
    private static final String NODE_KIND = "node_kind";
    private static final String APPEARANCE = "appearance";

    private ChatPrefs() {
    }

    /** Appearance preference as a stored word: "system" (default), "light" or
     *  "dark". "system" follows the phone's light/dark setting. */
    public static String appearance(Context zCtx) {
        return prefs(zCtx).getString(APPEARANCE, "system");
    }

    public static void setAppearance(Context zCtx, String zMode) {
        prefs(zCtx).edit().putString(APPEARANCE,
                zMode == null ? "system" : zMode).apply();
    }

    /** Advance the appearance System → Light → Dark → System and save it.
     *  Returns the new value. Caller applies it via setDefaultNightMode. */
    public static String cycleAppearance(Context zCtx) {
        String cur = appearance(zCtx);
        String next = "system".equals(cur) ? "light"
                : "light".equals(cur) ? "dark" : "system";
        setAppearance(zCtx, next);
        return next;
    }

    /** The header icon for the current appearance: sun / moon / auto. */
    public static int appearanceIcon(Context zCtx) {
        switch (appearance(zCtx)) {
            case "light":
                return com.eurobuddha.maxima.app.R.drawable.ic_sun;
            case "dark":
                return com.eurobuddha.maxima.app.R.drawable.ic_moon;
            default:
                return com.eurobuddha.maxima.app.R.drawable.ic_theme;
        }
    }

    /** Human label for a toast when cycling. */
    public static String appearanceLabel(Context zCtx) {
        switch (appearance(zCtx)) {
            case "light":
                return "Light";
            case "dark":
                return "Dark";
            default:
                return "System";
        }
    }

    /** The stored appearance as an {@code AppCompatDelegate.MODE_NIGHT_*} value. */
    public static int nightMode(Context zCtx) {
        switch (appearance(zCtx)) {
            case "light":
                return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
            case "dark":
                return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    private static final String ALLOW_SCREEN_SHARE = "allow_screen_share";

    /** Allow screen mirroring/casting/screenshots even when App Lock is on
     *  (needed for live demos). Default ON. When off + App Lock on, FLAG_SECURE. */
    public static boolean allowScreenShare(Context zCtx) {
        return prefs(zCtx).getBoolean(ALLOW_SCREEN_SHARE, true);
    }

    public static void setAllowScreenShare(Context zCtx, boolean zOn) {
        prefs(zCtx).edit().putBoolean(ALLOW_SCREEN_SHARE, zOn).apply();
    }

    public static boolean readReceipts(Context zCtx) {
        return prefs(zCtx).getBoolean(READ_RECEIPTS, false);
    }

    public static void setReadReceipts(Context zCtx, boolean zOn) {
        prefs(zCtx).edit().putBoolean(READ_RECEIPTS, zOn).apply();
        com.eurobuddha.maxima.core.chat.ChatEngine c = MaximaService.chat();
        if (c != null) {
            c.setSendReadReceipts(zOn);
        }
    }

    /** Our declared node kind, advertised to contacts: "core" for an always-on
     *  full node, "" for an ordinary phone. Default "". */
    public static String nodeKind(Context zCtx) {
        return prefs(zCtx).getString(NODE_KIND, "");
    }

    /** Set the node kind, apply it to the running node, and re-announce to
     *  contacts so their lists relabel us. */
    public static void setNodeKind(Context zCtx, String zKind) {
        String k = zKind == null ? "" : zKind.trim();
        prefs(zCtx).edit().putString(NODE_KIND, k).apply();
        com.eurobuddha.maxima.core.MaximaNode n = MaximaService.node();
        if (n != null) {
            n.setNodeKind(k);
            new Thread(() -> {
                try { n.refreshContacts(); } catch (Exception ignored) { }
            }, "kind-announce").start();
        }
    }

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
