package com.eurobuddha.maxima.app.portal.wallet;

import android.content.Context;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How amounts are shown in LISTS.
 *
 * Minima carries up to 44 decimal places, so a real balance renders as
 * {@code 12185.93151029742999999999999999999999999999999953} and swamps the card it sits in.
 * This trims the display to a chosen number of places.
 *
 * Two rules that must not be broken:
 *
 *  1. **Truncate, never round.** Rounding a balance up would show money that isn't there. Values
 *     are floored, and a trimmed value is marked with an ellipsis so it never reads as exact.
 *  2. **Display only.** Anything that goes into a transaction — a send amount, a confirmation
 *     breakdown, a coin's real value in its detail modal — uses the full precision string. Detail
 *     views are where you go for the exact figure, so they are never trimmed.
 */
public final class Format {

    private static final String PREFS = "nftwallet_display";
    private static final String KEY = "decimals";
    /** Sentinel for "show everything the node reported". */
    public static final int FULL = -1;
    private static final int DEFAULT_DECIMALS = 4;

    /** Cached so list rendering doesn't hit SharedPreferences once per row. */
    private static int decimals = DEFAULT_DECIMALS;

    private Format() {}

    public static void load(Context c) {
        decimals = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, DEFAULT_DECIMALS);
    }

    public static int decimals() { return decimals; }

    public static void setDecimals(Context c, int d) {
        decimals = d;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, d).apply();
    }

    public static String label(int d) {
        return d == FULL ? "Full precision" : d + " decimal places";
    }

    /** The list rendering of an amount, using the user's current setting. */
    public static String amount(String raw) {
        return trim(raw, decimals);
    }

    /**
     * Trim an amount to {@code places} decimals. Pure and side-effect free so it can be tested
     * without an Android context.
     */
    public static String trim(String raw, int places) {
        if (raw == null || raw.isEmpty()) return "0";
        if (places == FULL) return Util.tidyAmount(raw);
        final BigDecimal bd;
        try { bd = new BigDecimal(raw); } catch (Exception e) { return Util.tidyAmount(raw); }

        BigDecimal cut = bd.setScale(places, RoundingMode.DOWN);   // floor — never overstate
        String shown = cut.stripTrailingZeros().toPlainString();
        if ("0".equals(shown) && bd.signum() > 0) {
            // A dust amount would otherwise display as a flat "0", which reads as an empty wallet.
            return "<0." + zeros(places - 1) + "1";
        }
        return cut.compareTo(bd) == 0 ? shown : shown + "…";
    }

    private static String zeros(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, n); i++) sb.append('0');
        return sb.toString();
    }
}
