package com.eurobuddha.maxima.app;

import android.content.Context;

/**
 * Remembers a pinned Location Service across restarts.
 *
 * Classic persists this as `maxima_staticmls` in its UserDB; same idea. A phone
 * needs it more than a server does, because its address changes every time a
 * host drops and the MLS is how contacts find the new one.
 */
public final class MlsStore {

    private static final String PREFS = "maxima_mls";
    private static final String KEY = "static_mls";

    private MlsStore() {
    }

    public static String get(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
    }

    public static void save(Context c, String zMls) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, zMls == null ? "" : zMls.trim()).apply();
    }
}
