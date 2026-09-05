package com.eurobuddha.maxima.app.portal.wallet;

import org.json.JSONObject;

import java.util.List;

public final class CmdChain {

    public interface Done {
        void ok(JSONObject last);
        void fail(String message);
    }

    private CmdChain() {}

    public static void run(NodeApi node, List<String> cmds, String cleanupOnFail, Done done) {
        step(node, cmds, 0, cleanupOnFail, done);
    }

    private static void step(NodeApi node, List<String> cmds, int i, String cleanup, Done done) {
        if (i >= cmds.size()) { done.ok(null); return; }
        final boolean last = i == cmds.size() - 1;
        // txndelete is a best-effort pre-clean: "nothing to delete" is not a failure.
        final boolean optional = cmds.get(i).startsWith("txndelete");
        node.cmd(cmds.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    if (optional) { step(node, cmds, i + 1, cleanup, done); return; }
                    fail(node, cleanup, done, shortCmd(cmds.get(i)) + " failed" + errSuffix(json));
                    return;
                }
                // txncheck returns status:true for an UNBALANCED transaction — the balance lives in
                // coins[].difference. An unbalanced txn posts "fine" and is rejected on-chain, so
                // refuse to sign unless every coin nets to zero (the MintEngine.checkAndSign rule).
                if (cmds.get(i).startsWith("txncheck") && !balanced(json)) {
                    fail(node, cleanup, done, "transaction does not balance — refusing to sign");
                    return;
                }
                if (last) { done.ok(json); return; }
                step(node, cmds, i + 1, cleanup, done);
            }
            @Override public void onError(String message) {
                if (optional) { step(node, cmds, i + 1, cleanup, done); return; }
                fail(node, cleanup, done, message);
            }
        });
    }

    /** Every coin in a txncheck reply must net to zero, and there must be at least one. */
    private static boolean balanced(JSONObject json) {
        JSONObject resp = json.optJSONObject("response");
        org.json.JSONArray coins = resp == null ? null : resp.optJSONArray("coins");
        if (coins == null || coins.length() == 0) return false;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.optJSONObject(i);
            if (c == null || !"0".equals(c.optString("difference"))) return false;
        }
        return true;
    }

    private static void fail(NodeApi node, String cleanup, Done done, String msg) {
        if (cleanup != null && !cleanup.isEmpty()) {
            node.cmd(cleanup, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {}
                @Override public void onError(String message) {}
            });
        }
        done.fail(msg);
    }

    private static String errSuffix(JSONObject json) {
        String e = json.optString("error", "");
        return e.isEmpty() ? "" : " : " + e;
    }

    private static String shortCmd(String c) {
        int sp = c.indexOf(' ');
        return sp < 0 ? c : c.substring(0, sp);
    }
}
