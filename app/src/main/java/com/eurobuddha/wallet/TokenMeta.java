package com.eurobuddha.wallet;

import org.minima.utils.json.JSONObject;

/**
 * Parsed token metadata for icon display. A Minima token "name" can be a plain string or a JSON
 * object {name, url, description, icon, ticker, ...}, and the icon/url may live at either level —
 * so we dig defensively, exactly like the NFTwallet / utxoWallet reference.
 *
 * Adapted from {@code apks/NFTwallet TokenMeta} to Minima's own JSON type
 * ({@link org.minima.utils.json.JSONObject}), because that is what the {@code coins} response — and
 * therefore {@link CoinAggregator.Agg#tokenJson} — is parsed into. The icon-resolution rules are
 * unchanged; only the JSON accessor differs (no {@code optString} on the Minima class).
 */
public final class TokenMeta {

    public String name = "Token";
    public String ticker = "";
    /** A loadable icon source (data: URI or http(s) URL), or "" when the token carries no icon. */
    public String iconUrl = "";

    private TokenMeta() {
    }

    public static TokenMeta parse(JSONObject token, String tokenid) {
        TokenMeta m = new TokenMeta();
        if (tokenid == null || Util.isMinima(tokenid)) {
            m.name = "Minima";
            m.ticker = "MINIMA";
            return m;
        }
        if (token == null) {
            return m;
        }

        JSONObject meta = null;
        Object nameNode = token.get("name");
        if (nameNode instanceof JSONObject) {
            meta = (JSONObject) nameNode;
            m.name = str(meta, "name", "Token");
        } else if (nameNode != null) {
            m.name = String.valueOf(nameNode);
        }

        // Canonical icon location is token.url; also honour a nested name.url / name.icon / token.icon.
        // IconResolver handles the %-decode + artimage/data/http/svg/base64 shapes (or returns null).
        String resolved = IconResolver.resolve(first(
                str(meta, "url"),
                str(token, "url"),
                str(meta, "icon"),
                str(token, "icon")));
        m.iconUrl = resolved == null ? "" : resolved;

        m.ticker = first(str(meta, "ticker"), str(token, "ticker"));
        return m;
    }

    private static String str(JSONObject jo, String key) {
        return str(jo, key, "");
    }

    private static String str(JSONObject jo, String key, String def) {
        if (jo == null) {
            return def;
        }
        Object v = jo.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static String first(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
