package com.eurobuddha.maxima.app.portal.wallet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds and posts a custom Minima transaction over the IPC, the canonical way:
 * txncreate -> txninput (per input coin) -> txnoutput (per output) ->
 * txnsign publickey:auto txnpostauto:true txndelete:true.
 *
 * Shared by Send, Split and Distribute. On success it surfaces the posted transaction's output
 * coins (coinid/address/amount) so a caller can identify e.g. the change coin to chain on.
 */
public class TxnBuilder {

    public interface Done {
        void onPosted(String txpowid, List<OutCoin> outputs);
        void onFailed(String message);
    }

    /** Live build progress (so the UI shows staged logs, not one static line). */
    public interface Progress { void stage(String label); }

    private Progress progress;
    public TxnBuilder onProgress(Progress p) { this.progress = p; return this; }
    private void stage(String s) { if (progress != null) progress.stage(s); }

    /** An output to create. */
    public static class Out {
        public final String address;
        public final String amount;
        public Out(String address, String amount) { this.address = address; this.amount = amount; }
    }

    /** An output coin as it came back in the posted transaction (both address forms kept). */
    public static class OutCoin {
        public final String coinid;
        public final String address;       // 0x… hex form
        public final String miniaddress;   // Mx… form
        public final String amount;
        public OutCoin(String coinid, String address, String miniaddress, String amount) {
            this.coinid = coinid; this.address = address; this.miniaddress = miniaddress; this.amount = amount;
        }
        /** True if either address form equals the given address. */
        public boolean addressIs(String a) {
            return a != null && (a.equals(address) || a.equals(miniaddress));
        }
    }

    private final WalletActivity act;
    private final List<Coin> inputs;
    private final List<Out> outputs;
    private final String tokenid;
    private final boolean minima;
    private final String burn;          // Minima-only burn (txnpostburn); null/"0" = none
    private final Done done;
    private final String txid;

    public TxnBuilder(WalletActivity act, List<Coin> inputs, List<Out> outputs, String tokenid, Done done) {
        this(act, inputs, outputs, tokenid, null, done);
    }

    public TxnBuilder(WalletActivity act, List<Coin> inputs, List<Out> outputs, String tokenid,
                      String burn, Done done) {
        this.act = act;
        this.inputs = inputs;
        this.outputs = outputs;
        this.tokenid = tokenid;
        this.minima = Util.isMinima(tokenid);
        this.burn = burn;
        this.done = done;
        this.txid = "uw_" + System.currentTimeMillis() + "_"
                + Integer.toHexString(new Random().nextInt(0x1000000));
    }

    public void run() {
        stage("Building transaction…");
        act.node().cmd("txncreate id:" + txid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { addInput(0); }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void addInput(int i) {
        if (i >= inputs.size()) { addOutput(0); return; }
        stage("Adding inputs (" + (i + 1) + "/" + inputs.size() + ")…");
        act.node().cmd("txninput id:" + txid + " coinid:" + inputs.get(i).coinid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { addInput(i + 1); }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void addOutput(int i) {
        if (i >= outputs.size()) { sign(); return; }
        stage("Adding outputs (" + (i + 1) + "/" + outputs.size() + ")…");
        Out o = outputs.get(i);
        String cmd = "txnoutput id:" + txid + " amount:" + o.amount + " address:" + o.address
                + (minima ? "" : " tokenid:" + tokenid);
        act.node().cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { addOutput(i + 1); }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void sign() {
        stage("Signing & posting (proof-of-work — can take a moment)…");
        // Burn is only valid for Minima sends and only when > 0 (matches the dapp's signAndPost).
        String burnParam = "";
        if (minima && burn != null && !burn.isEmpty()) {
            try { if (new java.math.BigDecimal(burn).signum() > 0) burnParam = " txnpostburn:" + burn; }
            catch (Exception ignored) {}
        }
        act.node().cmd("txnsign id:" + txid + " publickey:auto txnpostauto:true txndelete:true" + burnParam,
                new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        done.onPosted(parseTxpowid(json), parseOutputs(json));
                    }
                    @Override public void onError(String message) { fail(message); }
                });
    }

    private void fail(String message) {
        // Best-effort cleanup of the half-built custom txn on the node.
        act.node().cmd("txndelete id:" + txid, null);
        done.onFailed(message);
    }

    private JSONObject txpowOf(JSONObject json) {
        JSONObject resp = json.optJSONObject("response");
        if (resp == null) return null;
        JSONObject txpow = resp.optJSONObject("txpow");
        return txpow != null ? txpow : resp;   // some responses are the txpow directly
    }

    private String parseTxpowid(JSONObject json) {
        JSONObject txpow = txpowOf(json);
        if (txpow != null) {
            String t = txpow.optString("txpowid", "");
            if (!t.isEmpty()) return t;
        }
        return txid;
    }

    private List<OutCoin> parseOutputs(JSONObject json) {
        List<OutCoin> out = new ArrayList<>();
        JSONObject txpow = txpowOf(json);
        if (txpow == null) return out;
        JSONObject body = txpow.optJSONObject("body");
        if (body == null) return out;
        JSONObject txn = body.optJSONObject("txn");
        if (txn == null) return out;
        JSONArray arr = txn.optJSONArray("outputs");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String amt = o.optString("tokenamount", o.optString("amount", ""));
            out.add(new OutCoin(o.optString("coinid", ""), o.optString("address", ""),
                    o.optString("miniaddress", ""), amt));
        }
        return out;
    }
}
