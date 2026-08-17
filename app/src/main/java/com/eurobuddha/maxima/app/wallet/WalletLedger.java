package com.eurobuddha.maxima.app.wallet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A local, append-only record of payments made or received THROUGH this app -
 * the wallet's history. The Maxima wallet uses a key index (and often a seed)
 * the main node has no history for, so a node {@code history} query returns
 * nothing; this local ledger is the source of truth instead. Rows are written
 * on a successful send (wallet tab or in-chat) and on a received in-chat
 * payment. Bounded, newest-first, stored in app-private SharedPreferences.
 */
public final class WalletLedger {

    private static final String PREFS = "maxima_wallet_ledger";
    private static final String KEY = "rows";
    private static final int CAP = 200;

    public static final class Row {
        public long time;
        public boolean sent;
        public String amount = "";
        public String token = "MINIMA";
        public String who = "";
        public String txid = "";
    }

    private WalletLedger() {
    }

    /** Record one payment. Best-effort: a ledger write must never break a send. */
    public static void add(Context zCtx, boolean zSent, String zAmount, String zToken,
                           String zWho, String zTxid) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("s", zSent);
            o.put("a", zAmount == null ? "" : zAmount);
            o.put("tk", zToken == null ? "MINIMA" : zToken);
            o.put("w", zWho == null ? "" : zWho);
            o.put("x", zTxid == null ? "" : zTxid);
            JSONArray prev = load(zCtx);
            JSONArray next = new JSONArray();
            next.put(o);
            for (int i = 0; i < prev.length() && next.length() < CAP; i++) {
                next.put(prev.get(i));
            }
            zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, next.toString()).apply();
        } catch (Exception ignored) {
            // history is a convenience, never a blocker
        }
    }

    public static List<Row> list(Context zCtx) {
        List<Row> out = new ArrayList<>();
        try {
            JSONArray arr = load(zCtx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Row r = new Row();
                r.time = o.optLong("t");
                r.sent = o.optBoolean("s");
                r.amount = o.optString("a", "");
                r.token = o.optString("tk", "MINIMA");
                r.who = o.optString("w", "");
                r.txid = o.optString("x", "");
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONArray load(Context zCtx) {
        try {
            String s = zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]");
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
