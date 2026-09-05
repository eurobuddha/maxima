package com.eurobuddha.maxima.app.portal.wallet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Iterator;

/**
 * One on-chain transaction in the History tab — a faithful port of the standalone Minima History app's
 * entry model, so the wallet's History shows the SAME numbers the SAME way. Direction + amount come from
 * the node's `details.difference` (net per-token effect on the wallet); the primary token is the largest
 * absolute move. Persisted in {@link HistoryDb} (nodetx table), keyed by txpowid.
 */
public class NodeTx {

    public String txpowid;
    public long block, timemilli;
    public String direction;        // received | sent | self
    public boolean incoming;
    public String tokenid, tokenName, amount;   // primary token moved (amount = absolute value)
    public String deltas;           // JSON { tokenid: signedAmount } — full per-token effect
    public String counterparty;     // address of the other side
    public String inputs, outputs;  // JSON arrays [{addr, amount, tokenid}]

    public static NodeTx from(JSONObject txpow, JSONObject detail) {
        NodeTx e = new NodeTx();
        e.txpowid = txpow.optString("txpowid", "");
        JSONObject hdr = txpow.optJSONObject("header");
        if (hdr != null) { e.block = hdr.optLong("block", 0); e.timemilli = hdr.optLong("timemilli", 0); }

        JSONObject diff = detail != null ? detail.optJSONObject("difference") : null;
        e.deltas = diff != null ? diff.toString() : "{}";

        String pTid = "0x00";
        BigDecimal pAmt = BigDecimal.ZERO;
        if (diff != null) for (Iterator<String> it = diff.keys(); it.hasNext(); ) {
            String tid = it.next();
            BigDecimal a = bd(diff.optString(tid, "0"));
            if (a.abs().compareTo(pAmt.abs()) > 0) { pAmt = a; pTid = tid; }
        }
        e.tokenid = pTid;
        e.amount = pAmt.signum() == 0 ? "0" : pAmt.abs().stripTrailingZeros().toPlainString();
        int sign = pAmt.signum();
        e.incoming = sign > 0;
        e.direction = sign > 0 ? "received" : sign < 0 ? "sent" : "self";
        e.tokenName = tokenNameFor(txpow, pTid);

        JSONObject txn = txn(txpow);
        JSONArray ins = txn != null ? txn.optJSONArray("inputs") : null;
        JSONArray outs = txn != null ? txn.optJSONArray("outputs") : null;
        e.inputs = coins(ins);
        e.outputs = coins(outs);
        e.counterparty = firstAddr(e.incoming ? ins : outs);   // received → a sender; sent/self → a recipient
        return e;
    }

    private static JSONObject txn(JSONObject txpow) {
        JSONObject body = txpow.optJSONObject("body");
        return body != null ? body.optJSONObject("txn") : null;
    }

    private static String tokenNameFor(JSONObject txpow, String tid) {
        if (Util.isMinima(tid)) return "Minima";
        JSONObject txn = txn(txpow);
        JSONArray outs = txn != null ? txn.optJSONArray("outputs") : null;
        if (outs != null) for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && tid.equals(o.optString("tokenid"))) {
                String n = Util.tokenName(o.opt("token"), tid);
                if (n != null && !n.isEmpty()) return n;
            }
        }
        return Util.shorten(tid);
    }

    private static String coins(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("addr", c.optString("miniaddress", c.optString("address", "")));
                o.put("amount", c.optString("amount", c.optString("tokenamount", "")));
                o.put("tokenid", c.optString("tokenid", "0x00"));
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    private static String firstAddr(JSONArray arr) {
        if (arr != null && arr.length() > 0) {
            JSONObject c = arr.optJSONObject(0);
            if (c != null) return c.optString("miniaddress", c.optString("address", ""));
        }
        return "";
    }

    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }

    // ----- split / consolidation display (a self-only coin reshuffle) -----
    private static int count(String json) {
        try { return json == null ? 0 : new JSONArray(json).length(); } catch (Exception e) { return 0; }
    }
    public boolean isSplit() { return "self".equals(direction) && count(outputs) > count(inputs) && count(outputs) > 1; }
    public boolean isConsolidation() { return "self".equals(direction) && count(inputs) > count(outputs) && count(inputs) > 1; }
    public boolean isReshuffle() { return isSplit() || isConsolidation(); }
    public String reshuffleLabel() {
        return isSplit() ? ("Split · " + count(outputs) + " coins") : ("Consolidation · " + count(inputs) + " coins");
    }
    /** For a reshuffle, the GROSS amount + token of the dominant output token (e.g. "500000  Minima") —
     *  more informative than the net "0" a self-only transaction otherwise shows. */
    public String grossDisplay() {
        try {
            JSONArray outs = new JSONArray(outputs);
            java.util.Map<String, BigDecimal> sums = new java.util.HashMap<>();
            for (int i = 0; i < outs.length(); i++) {
                JSONObject o = outs.optJSONObject(i);
                if (o == null) continue;
                sums.merge(o.optString("tokenid", "0x00"), bd(o.optString("amount", "0")), BigDecimal::add);
            }
            String domTid = "0x00"; BigDecimal domSum = BigDecimal.ZERO;
            for (java.util.Map.Entry<String, BigDecimal> en : sums.entrySet())
                if (en.getValue().compareTo(domSum) > 0) { domSum = en.getValue(); domTid = en.getKey(); }
            String name = Util.isMinima(domTid) ? "Minima" : domTid.equals(tokenid) ? tokenName : Util.shorten(domTid);
            return Util.tidyAmount(domSum.stripTrailingZeros().toPlainString()) + "  " + name;
        } catch (Exception e) { return Util.tidyAmount(amount) + "  " + tokenName; }
    }
}
