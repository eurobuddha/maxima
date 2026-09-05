package com.eurobuddha.maxima.app.portal.wallet;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

/** The old wallet's hidden-tokens list: scam/spam tokens the user chose not to see. */
public final class HiddenTokens {

    private static final String PREFS = "nftwallet_hidden";
    private static final String KEY = "hidden";

    private HiddenTokens() {}

    public static Set<String> all(Context c) {
        return new HashSet<>(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY, new HashSet<>()));
    }

    public static boolean isHidden(Context c, String tokenid) {
        return all(c).contains(tokenid);
    }

    public static void hide(Context c, String tokenid) {
        Set<String> s = all(c);
        s.add(tokenid);
        save(c, s);
    }

    public static void unhide(Context c, String tokenid) {
        Set<String> s = all(c);
        s.remove(tokenid);
        save(c, s);
    }

    private static void save(Context c, Set<String> s) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, s).apply();
    }
}
