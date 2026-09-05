package com.eurobuddha.maxima.app.portal.wallet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A single UTXO as returned by the node's "coins relevant:true" command — full granularity. */
public class Coin {

    public String coinid;
    public String address;
    public String miniaddress;
    public String tokenid;
    public String amount;       // human-readable (Minima "amount" or token "tokenamount")
    public String rawAmount;    // the raw "amount" field (Minima-scale) — differs for tokens
    public String tokenName;
    public boolean confirmed = true;
    public boolean sendable = false;   // set from "coins relevant:true sendable:true"

    // ---- deep detail (coin explorer / coin modal) ----
    public String created = "";        // block the coin was created in
    public String mmrentry = "";
    public boolean storestate = false;
    public boolean spent = false;
    /** State variables carried by the coin: each entry is {port, data}, in node order. */
    public final List<String[]> state = new ArrayList<>();
    /** The node's raw coin JSON — StateNFT transfer/bury replay the state from this verbatim. */
    public JSONObject raw;

    public static Coin from(JSONObject c) {
        Coin x = new Coin();
        x.raw = c;
        x.coinid      = c.optString("coinid", "");
        x.address     = c.optString("address", "");
        x.miniaddress = c.optString("miniaddress", "");
        x.tokenid     = c.optString("tokenid", Util.MINIMA_TOKENID);

        boolean minima = Util.isMinima(x.tokenid);
        x.rawAmount = c.optString("amount", "0");
        x.amount    = minima ? x.rawAmount
                             : c.optString("tokenamount", x.rawAmount);
        x.tokenName = Util.tokenName(c.opt("token"), x.tokenid);

        x.created    = c.optString("created", "");
        x.mmrentry   = c.optString("mmrentry", "");
        x.storestate = c.optBoolean("storestate", false);
        x.spent      = c.optBoolean("spent", false);
        JSONArray st = c.optJSONArray("state");
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                JSONObject s = st.optJSONObject(i);
                if (s != null) {
                    x.state.add(new String[]{ s.optString("port", ""), s.optString("data", "") });
                }
            }
        }

        // The node's "coins" command returns confirmed, unspent coins — the Coin JSON has no
        // per-coin confirmation flag (verified against the node's command classes), so any coin
        // we get back here is confirmed. Spendability is gated separately by the sendable query.
        x.confirmed = true;
        return x;
    }

    /**
     * True when every field this coin contributes to a node command is a plain, well-formed value.
     *
     * Coin JSON is chain data: the node serialises a JSON-shaped token name into its replies
     * unescaped, so a hostile token can inject entirely synthetic coin objects with attacker-chosen
     * ids/amounts. Node commands are whitespace-parsed and multi-commands split on ';', so ONE
     * unvetted field is arbitrary command execution. Coins that fail this are dropped at ingestion
     * (WalletActivity) and never reach the coin list, a txn builder, or the UI.
     */
    public boolean isWellFormed() {
        return Util.isValidHexId(coinid)
                && Util.isValidHexId(tokenid)
                && Util.isValidAmount(amount)
                && Util.isValidAmount(rawAmount)
                && (address == null || address.isEmpty() || Util.isValidHexId(address));
    }

    /** Blocks since creation at the given chain tip, or -1 when unknown. */
    public long ageBlocks(int chainBlock) {
        try { return Math.max(0, chainBlock - Long.parseLong(created)); }
        catch (Exception e) { return -1; }
    }
}
