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

    private ChatPrefs() {
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

    private static SharedPreferences prefs(Context zCtx) {
        return zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
