package com.eurobuddha.maxima.app.portal.wallet;

import org.json.JSONObject;

import java.net.URLDecoder;

/**
 * Parsed token metadata. Minima token "name" can be a plain string or a JSON object
 * {name, url, description, ticker, ...}, and icon/description may live at either level —
 * so we dig defensively.
 */
public class TokenMeta {

    public String name = "Token";
    public String ticker = "";
    public String iconUrl = "";
    public String description = "";
    public String decimals = "";
    public String owner = "";
    public String webvalidate = "";
    public String externalUrl = "";
    /** The token's "url" field verbatim when it is a plain http(s) link (openable), else "". */
    public String rawUrl = "";

    public static TokenMeta parse(Object token, String tokenid) {
        TokenMeta m = new TokenMeta();
        if (Util.isMinima(tokenid)) {
            m.name = "Minima";
            m.ticker = "MINIMA";
            return m;
        }
        if (token instanceof String) {
            m.name = (String) token;
            return m;
        }
        if (token instanceof JSONObject) {
            JSONObject t = (JSONObject) token;
            m.decimals = t.optString("decimals", "");

            JSONObject meta = null;
            Object nameNode = t.opt("name");
            if (nameNode instanceof JSONObject) {
                meta = (JSONObject) nameNode;
                m.name = meta.optString("name", "Token");
            } else if (nameNode instanceof String) {
                m.name = (String) nameNode;
            }

            // Canonical location is c.token.url; resolve it (artimage / data / http / svg / base64) exactly
            // like the utxoWallet dapp. Returns "" when there's nothing image-shaped (→ identicon).
            String resolved = IconResolver.resolve(first(
                    meta != null ? meta.optString("url", "") : "",
                    t.optString("url", ""),
                    meta != null ? meta.optString("icon", "") : "",
                    t.optString("icon", "")));
            m.iconUrl = resolved == null ? "" : resolved;
            m.description = first(
                    meta != null ? meta.optString("description", "") : "",
                    t.optString("description", ""));
            m.ticker = first(
                    meta != null ? meta.optString("ticker", "") : "",
                    t.optString("ticker", ""));
            m.owner = first(
                    meta != null ? meta.optString("owner", "") : "",
                    t.optString("owner", ""));
            m.webvalidate = first(
                    meta != null ? meta.optString("webvalidate", "") : "",
                    t.optString("webvalidate", ""));
            m.externalUrl = decode(first(
                    meta != null ? meta.optString("external_url", "") : "",
                    t.optString("external_url", "")));
            // Keep the url field itself openable when it's a plain link (it may double as the icon).
            String rawUrl = decode(first(
                    meta != null ? meta.optString("url", "") : "",
                    t.optString("url", "")));
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) m.rawUrl = rawUrl;
        }
        return m;
    }

    private static String first(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    /** URL-decode http(s) urls (token urls are often percent-encoded); leave data: URIs intact. */
    private static String decode(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("data:")) return url;
        if (url.indexOf('%') < 0) return url;
        try { return URLDecoder.decode(url, "UTF-8"); } catch (Exception e) { return url; }
    }
}
