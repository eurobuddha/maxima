package com.eurobuddha.maxima.app.portal.wallet;

import org.json.JSONObject;

import java.math.BigDecimal;

public final class Util {

    public static final String MINIMA_TOKENID = "0x00";

    private Util() {}

    /** A Minima address: 0x + exactly 64 hex (32-byte script hash), or Mx + 40–118 alnum.
     *  Matches the dapp's validateAddress; a pre-filter before the node's checkaddress. */
    public static boolean isValidAddress(String a) {
        return a != null && a.matches("^(0x[0-9a-fA-F]{64}|Mx[A-Za-z0-9]{40,118})$");
    }

    /**
     * A 32-byte hex id (tokenid / coinid / txpowid / publickey / script address), or the Minima
     * sentinel "0x00".
     *
     * SECURITY: ids reaching us are chain-supplied. The node serialises a JSON-shaped token name
     * into its replies raw, so a token whose on-chain name starts with '{' can inject synthetic
     * fields — including an attacker-chosen "tokenid"/"coinid" string — into a balance or coins
     * reply. Node commands are whitespace-parsed and multi-commands split on ';', so an unvetted
     * id interpolated into a command is arbitrary command execution. Gate EVERY id with this
     * before it goes anywhere near a command string.
     */
    public static boolean isValidHexId(String id) {
        return id != null && (MINIMA_TOKENID.equals(id) || id.matches("^0x[0-9a-fA-F]{2,64}$"));
    }

    /**
     * A plain decimal amount as the node emits them. Amounts are interpolated into commands too,
     * so a chain-supplied "1 burn:9999" must never reach one.
     */
    public static boolean isValidAmount(String amt) {
        return amt != null && amt.matches("^[0-9]+(\\.[0-9]+)?$");
    }

    /** True only for a plain http(s) link — the only scheme we hand to ACTION_VIEW. */
    public static boolean isWebUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return (u.startsWith("http://") || u.startsWith("https://")) && !u.contains(" ");
    }

    /**
     * Open a chain-supplied URL, confirming the destination first. Metadata URLs are attacker
     * controlled: an arbitrary scheme would fire an implicit intent into any installed app's
     * deep-link handler, so anything that isn't http(s) is refused outright.
     */
    public static void openWebUrl(final android.content.Context ctx, final String url) {
        if (!isWebUrl(url)) {
            android.widget.Toast.makeText(ctx,
                    "This link isn't a plain web address — not opening it.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Sheet.create(ctx, "Open this link?")
                .subtitle(url + "\n\nThis address comes from the token's metadata, not from Minima.")
                .action("Cancel", Sheet.Style.SECONDARY, null)
                .action("Open", Sheet.Style.PRIMARY, () -> {
                    try {
                        ctx.startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(ctx, "No app can open that link.",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
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
}
