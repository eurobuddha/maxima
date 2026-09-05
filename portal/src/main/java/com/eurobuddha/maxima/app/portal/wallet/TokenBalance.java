package com.eurobuddha.maxima.app.portal.wallet;

import org.json.JSONObject;

/** One row of the node's "balance" command: an aggregate per token, with full metadata. */
public class TokenBalance {

    public String tokenid;
    public TokenMeta meta;
    public String name;          // convenience = meta.name
    public String confirmed;
    public String unconfirmed;
    public String sendable;
    public String total;
    public int coins;

    public static TokenBalance from(JSONObject b) {
        TokenBalance t = new TokenBalance();
        t.tokenid     = b.optString("tokenid", Util.MINIMA_TOKENID);
        t.meta        = TokenMeta.parse(b.opt("token"), t.tokenid);
        t.name        = t.meta.name;
        t.confirmed   = b.optString("confirmed", "0");
        t.unconfirmed = b.optString("unconfirmed", "0");
        t.sendable    = b.optString("sendable", t.confirmed);
        t.total       = b.optString("total", t.confirmed);
        t.coins       = b.optInt("coins", 0);
        // balance entries sometimes carry decimals at the top level
        if (t.meta.decimals.isEmpty()) t.meta.decimals = b.optString("decimals", "");
        return t;
    }

    /**
     * True when this row's command-bound field (the tokenid) is a plain hex id.
     *
     * `balance` is the second chain-data ingestion path alongside `coins`, and it carries the same
     * hazard: the node serialises a JSON-shaped token name unescaped, so a hostile token can inject
     * a synthetic balance row with an attacker-chosen tokenid. That tokenid reaches `send`,
     * `tokens` and `coins` commands, which are whitespace-parsed and split on ';'. Rows failing
     * this are dropped at ingestion and never reach the balance list.
     */
    public boolean isWellFormed() {
        return Util.isValidHexId(tokenid);
    }

    public boolean isMinima() { return Util.isMinima(tokenid); }

    public boolean hasIcon() { return meta != null && meta.iconUrl != null && !meta.iconUrl.isEmpty(); }

    /** An NFT: a non-Minima token of total supply 1 with no fractional decimals. */
    public boolean isNft() {
        if (isMinima()) return false;
        String d = meta == null ? "" : meta.decimals;
        boolean whole = d.isEmpty() || d.equals("0");
        return whole && "1".equals(total == null ? "" : total.trim());
    }
}
