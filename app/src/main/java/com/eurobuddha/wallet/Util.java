package com.eurobuddha.wallet;

import org.json.JSONObject;

import java.math.BigDecimal;

public final class Util {

    public static final String MINIMA_TOKENID = "0x00";

    /**
     * One-time WOTS leaves per default {@code TreeKey} = 64^3 = 262144 (mirrors
     * {@code TreeKey.DEFAULT_KEYSPERLEVEL^DEFAULT_LEVELS}). Surfaced in the keyuses UI as
     * "signatures used: N / 262144". Kept here (a UI helper) so the fund-critical WalletCore is
     * untouched; the exhaustion guard that actually enforces it lives in
     * {@link WalletCore#signTransactionID} via {@code TreeKey.getMaxUses()}.
     */
    public static final int WOTS_MAX_USES = 262144;

    private Util() {}

    /** A Minima address: 0x + exactly 64 hex (32-byte script hash), or Mx + 40–118 alnum.
     *  Matches the dapp's validateAddress; a pre-filter before the node's checkaddress. */
    public static boolean isValidAddress(String a) {
        return a != null && a.matches("^(0x[0-9a-fA-F]{64}|Mx[A-Za-z0-9]{40,118})$");
    }

    /**
     * True for a RAW {@code 0x}-form Minima address (0x + exactly 64 hex). Unlike the {@code Mx} form,
     * the raw form carries NO checksum, so a single mistyped character silently points at a different,
     * unrecoverable address. The Send UI surfaces a warning when the recipient is this form.
     */
    public static boolean isRaw0xAddress(String a) {
        return a != null && a.matches("^0x[0-9a-fA-F]{64}$");
    }

    /** Number of significant decimal places in an amount (0 for integers). */
    public static int decimalPlaces(BigDecimal bd) {
        return Math.max(0, bd.stripTrailingZeros().scale());
    }

    /** Shorten a long hex id/address for display: 0x1234…ABCD */
    public static String shorten(String s) {
        if (s == null) return "";
        if (s.length() <= 16) return s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 6);
    }

    public static boolean isMinima(String tokenid) {
        return tokenid == null || MINIMA_TOKENID.equals(tokenid);
    }

    /**
     * Minima "token name" can be a plain string, or a JSON object {name:..,url:..},
     * or (for raw coin entries) nested. Pull a human-readable name out of whatever we get.
     */
    public static String tokenName(Object token, String tokenid) {
        if (isMinima(tokenid)) return "Minima";
        if (token instanceof String) return (String) token;
        if (token instanceof JSONObject) {
            JSONObject t = (JSONObject) token;
            Object name = t.opt("name");
            if (name instanceof JSONObject) {
                return ((JSONObject) name).optString("name", "Token");
            }
            if (name instanceof String && !((String) name).isEmpty()) {
                return (String) name;
            }
        }
        return "Token";
    }

    /** Pull a txpowid out of a posted-transaction response, falling back to the given id. */
    public static String extractTxpowid(JSONObject json, String fallback) {
        JSONObject resp = json.optJSONObject("response");
        if (resp != null) {
            String t = resp.optString("txpowid", "");
            if (t.isEmpty()) {
                JSONObject txp = resp.optJSONObject("txpow");
                if (txp != null) t = txp.optString("txpowid", "");
            }
            if (!t.isEmpty()) return t;
        }
        return fallback;
    }

    /** Trim trailing zeros from a decimal amount string for tidy display. */
    public static String tidyAmount(String amt) {
        if (amt == null || amt.isEmpty()) return "0";
        if (!amt.contains(".")) return amt;
        String s = amt.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "0" : s;
    }

    /** Display an amount with AT LEAST 5 decimals (zero-padded), keeping natural precision up to the token's
     *  decimals (default 8). Stops small balances (e.g. 0.001 USDT) collapsing to a bare "0". */
    public static String showAmount(String amt, String decimalsStr) {
        int dec = 8;
        try { int d = Integer.parseInt(decimalsStr == null ? "" : decimalsStr.trim()); if (d > 0) dec = d; } catch (Exception e) {}
        int maxDp = Math.max(5, dec);
        if (amt == null || amt.isEmpty()) amt = "0";
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(amt);
            if (bd.scale() > maxDp) bd = bd.setScale(maxDp, java.math.RoundingMode.DOWN);
            if (bd.scale() < 5)    bd = bd.setScale(5);
            return bd.toPlainString();
        } catch (Exception e) { return amt; }
    }

    /** Total HELD amount = confirmed + unconfirmed (both already human-scaled). A just-arrived coin spends its
     *  first confirmations in {@code unconfirmed}, so showing held (not confirmed-only) surfaces it immediately
     *  instead of "0.00000 · 1 coin". Defensive: falls back to confirmed on a parse error. */
    public static String held(String confirmed, String unconfirmed) {
        try {
            BigDecimal c = new BigDecimal(confirmed == null || confirmed.isEmpty() ? "0" : confirmed);
            BigDecimal u = new BigDecimal(unconfirmed == null || unconfirmed.isEmpty() ? "0" : unconfirmed);
            return c.add(u).toPlainString();
        } catch (Exception e) { return confirmed == null || confirmed.isEmpty() ? "0" : confirmed; }
    }

    /** True when there's a pending (unconfirmed) portion still gaining confirmations. */
    public static boolean hasPending(String unconfirmed) {
        try { return new BigDecimal(unconfirmed == null || unconfirmed.isEmpty() ? "0" : unconfirmed).signum() > 0; }
        catch (Exception e) { return false; }
    }
}
