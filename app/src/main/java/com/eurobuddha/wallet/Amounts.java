package com.eurobuddha.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How Minima amounts are shown. A native port of NFTwallet's {@code Format},
 * without the per-user decimals setting (the Maxima wallet is Minima-focused, so
 * a fixed 4-place list view is enough).
 *
 * Two rules that must not be broken:
 *  1. <b>Truncate, never round.</b> Rounding a balance up shows money that isn't
 *     there. Values are floored; a trimmed value gets an ellipsis so it never
 *     reads as exact.
 *  2. <b>Display only.</b> Anything that goes into a transaction — a send amount,
 *     a detail modal's exact figure — uses {@link #full(String)} (full precision).
 */
public final class Amounts {

    private static final int LIST_DECIMALS = 4;

    private Amounts() {
    }

    /** The list rendering of an amount: floored to 4 places, dust-marked, ellipsised. */
    public static String list(String raw) {
        return trim(raw, LIST_DECIMALS);
    }

    /** Full precision, only trailing-zero tidied. For detail views and send amounts. */
    public static String full(String raw) {
        return tidy(raw);
    }

    /** Trim to {@code places} decimals, flooring. Pure / testable. */
    public static String trim(String raw, int places) {
        if (raw == null || raw.isEmpty()) {
            return "0";
        }
        final BigDecimal bd;
        try {
            bd = new BigDecimal(raw);
        } catch (Exception e) {
            return tidy(raw);
        }
        BigDecimal cut = bd.setScale(places, RoundingMode.DOWN);   // floor — never overstate
        String shown = cut.stripTrailingZeros().toPlainString();
        if ("0".equals(shown) && bd.signum() > 0) {
            // A dust amount would otherwise show a flat "0", reading as empty.
            return "<0." + zeros(places - 1) + "1";
        }
        return cut.compareTo(bd) == 0 ? shown : shown + "…";
    }

    /** Strip trailing zeros / a dangling decimal point. */
    public static String tidy(String amt) {
        if (amt == null || amt.isEmpty()) {
            return "0";
        }
        if (!amt.contains(".")) {
            return amt;
        }
        String s = amt.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "0" : s;
    }

    private static String zeros(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, n); i++) {
            sb.append('0');
        }
        return sb.toString();
    }
}
