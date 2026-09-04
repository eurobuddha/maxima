package com.eurobuddha.maxima.app.portal.ide.txn;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.portal.ide.BaseView;
import com.eurobuddha.maxima.app.portal.ide.NodeApi;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.portal.ide.terminal.OutputFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Guided manual-UTXO transaction workbench over the node's txn* family, enforcing the
 * proven rules: sign BEFORE basics, never auto:true post for script coins, inputs must
 * equal outputs or the difference burns, txndelete on abandoned transactions.
 */
public class TxnView extends BaseView {

    NodeApi mNode;

    EditText mTxnId;
    TextView mStatus;
    TextView mSummary;

    public TxnView(Activity zActivity) {
        super(zActivity, R.layout.view_txn);

        mTxnId = mMainView.findViewById(R.id.txn_id);
        mStatus = mMainView.findViewById(R.id.txn_status);
        mSummary = mMainView.findViewById(R.id.txn_summary);

        wire(R.id.txn_create, v -> create());
        wire(R.id.txn_addinput, v -> pickCoin());
        wire(R.id.txn_addoutput, v -> outputDialog());
        wire(R.id.txn_change, v -> addChange());
        wire(R.id.txn_setstate, v -> stateDialog());
        wire(R.id.txn_sign, v -> signDialog());
        wire(R.id.txn_basics, v -> simple("txnbasics", "MMR proofs + scripts added"));
        wire(R.id.txn_check, v -> refresh());
        wire(R.id.txn_post, v -> postDialog());
        wire(R.id.txn_export, v -> exportTxn());
        wire(R.id.txn_import, v -> importDialog());
        wire(R.id.txn_delete, v -> deleteDialog());
        wire(R.id.txn_list, v -> listAll());
    }

    private void wire(int id, View.OnClickListener l) {
        ((Button) mMainView.findViewById(id)).setOnClickListener(l);
    }

    public void setNodeApi(NodeApi zNode) {
        mNode = zNode;
    }

    private String txnId() {
        String id = mTxnId.getText().toString().trim();
        if (id.isEmpty()) {
            Toast.makeText(mActivity, "Set a transaction id first", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (hasCommandChars(id)) {
            Toast.makeText(mActivity, "Transaction id can't contain spaces or ':'", Toast.LENGTH_SHORT).show();
            return null;
        }
        return id;
    }

    /**
     * A field is concatenated straight into a space-delimited node command, so any
     * whitespace or ':' would inject/split params (amount "1 000" -> stray param,
     * address "Mx.. burn:9" -> unintended burn). Reject up front.
     */
    private static boolean hasCommandChars(String s) {
        return s.contains(" ") || s.contains("\t") || s.contains("\n")
                || s.contains(";") || s.contains(":");
    }

    /** Validates a value that legitimately may contain ':' (only reject whitespace/;). */
    private static boolean hasWhitespaceOrSemicolon(String s) {
        return s.contains(" ") || s.contains("\t") || s.contains("\n") || s.contains(";");
    }

    private boolean rejectBadField(String label, String value, boolean allowColon) {
        boolean bad = allowColon ? hasWhitespaceOrSemicolon(value) : hasCommandChars(value);
        if (bad) {
            Toast.makeText(mActivity, label + " can't contain spaces"
                    + (allowColon ? " or ';'" : ", ':' or ';'"), Toast.LENGTH_LONG).show();
        }
        return bad;
    }

    private void setStatus(String text, int color) {
        mStatus.setText(OutputFormatter.colored(text, color));
    }

    // ---------------- actions ----------------

    private void create() {
        String id = txnId();
        if (id == null) return;
        run("txncreate id:" + id, "created '" + id + "' — now add inputs", true);
    }

    private void pickCoin() {
        String id = txnId();
        if (id == null) return;
        setStatus("… loading your coins", OutputFormatter.COL_DIM);
        // Shrink the reply at the SOURCE with simplestate:true (drops the large per-coin
        // state blobs) — an over-limit Binder reply crashes app+node uncatchably (see
        // minima-ipc-gotchas). We keep relevant+sendable (never hide coins by age) and
        // the coin picker doesn't need state, so this is lossless for our purpose.
        mNode.cmd("coins relevant:true sendable:true simplestate:true", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONArray coins = json.optJSONArray("response");
                if (coins == null || coins.length() == 0) {
                    setStatus("No sendable coins found", OutputFormatter.COL_ERROR);
                    return;
                }
                int n = Math.min(coins.length(), 100);
                boolean capped = coins.length() > n;
                String[] labels = new String[n];
                String[] coinids = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject c = coins.optJSONObject(i);
                    String amount = c.optString("tokenamount", c.optString("amount", "?"));
                    String tokenid = c.optString("tokenid", "0x00");
                    String token = tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid);
                    coinids[i] = c.optString("coinid", "");
                    labels[i] = amount + " " + token + "\n" + shorten(coinids[i]);
                }
                String title = capped ? "Add input coin (first " + n + " of " + coins.length() + ")"
                        : "Add input coin (" + coins.length() + " sendable)";
                new AlertDialog.Builder(mActivity)
                        .setTitle(title)
                        .setItems(labels, (d, which) ->
                                run("txninput id:" + id + " coinid:" + coinids[which],
                                        "input added", true))
                        .setNegativeButton("Cancel", null)
                        .show();
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    private void outputDialog() {
        String id = txnId();
        if (id == null) return;
        View v = mActivity.getLayoutInflater().inflate(R.layout.dialog_output, null);
        EditText amount = v.findViewById(R.id.out_amount);
        EditText address = v.findViewById(R.id.out_address);
        EditText tokenid = v.findViewById(R.id.out_tokenid);
        CheckBox storestate = v.findViewById(R.id.out_storestate);
        new AlertDialog.Builder(mActivity)
                .setTitle("Add output")
                .setMessage("Inputs must equal outputs — any difference is BURNED. Add a change "
                        + "output back to yourself. storestate:true only for covenant/phase coins.")
                .setView(v)
                .setPositiveButton("Add", (d, w) -> {
                    String amt = amount.getText().toString().trim();
                    String addr = address.getText().toString().trim();
                    if (amt.isEmpty() || addr.isEmpty()) {
                        Toast.makeText(mActivity, "Amount and address required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String tok = tokenid.getText().toString().trim();
                    if (rejectBadField("Amount", amt, false) || rejectBadField("Address", addr, false)
                            || rejectBadField("Token id", tok, false)) {
                        return;
                    }
                    String cmd = "txnoutput id:" + id + " amount:" + amt + " address:" + addr
                            + (tok.isEmpty() ? "" : " tokenid:" + tok)
                            + " storestate:" + storestate.isChecked();
                    run(cmd, "output added", true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * One-tap change output: reads txncheck's per-token input/output difference and
     * sends every positive remainder back to one of your own addresses — the #1
     * guard against accidental burns.
     */
    // Guards the whole async change flow (txncheck → dialog → getaddress → txnoutput*)
    // so a double-tap can't run two flows that both see the same pre-change difference
    // and add change twice (outputs > inputs → rejected/over-send).
    private boolean mChangeInFlight = false;

    private void addChange() {
        String id = txnId();
        if (id == null) return;
        if (mChangeInFlight) {
            Toast.makeText(mActivity, "Change calculation already in progress", Toast.LENGTH_SHORT).show();
            return;
        }
        mChangeInFlight = true;
        setStatus("… calculating change", OutputFormatter.COL_DIM);
        mNode.cmd("txncheck id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                JSONArray coins = resp != null ? resp.optJSONArray("coins") : null;
                if (coins == null) {
                    mChangeInFlight = false;
                    setStatus("✖ no txn '" + id + "' — Create it first", OutputFormatter.COL_ERROR);
                    return;
                }
                final java.util.List<String[]> changes = new java.util.ArrayList<>();  // {tokenid, amount}
                StringBuilder planned = new StringBuilder();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    if (c == null) continue;
                    String tokenid = c.optString("tokenid", "0x00");
                    String diff = c.optString("difference", "0");
                    try {
                        if (new BigDecimal(diff).compareTo(BigDecimal.ZERO) > 0) {
                            changes.add(new String[]{tokenid, diff});
                            planned.append(diff).append(" ")
                                    .append(tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid))
                                    .append("\n");
                        }
                    } catch (NumberFormatException ignored) {}
                }
                if (changes.isEmpty()) {
                    mChangeInFlight = false;
                    setStatus("✓ inputs already equal outputs — no change needed",
                            OutputFormatter.COL_STRING);
                    return;
                }
                new AlertDialog.Builder(mActivity)
                        .setTitle("Add change to your wallet")
                        .setMessage("These leftovers would BURN on post. Send them back to "
                                + "your own address?\n\n" + planned)
                        .setPositiveButton("Add change", (d, w) -> fetchAddressAndAdd(id, changes))
                        .setNegativeButton("Cancel", (d, w) -> mChangeInFlight = false)
                        .setOnCancelListener(d -> mChangeInFlight = false)
                        .show();
            }

            @Override
            public void onError(String message) {
                mChangeInFlight = false;
                nodeError(message);
            }
        });
    }

    private void fetchAddressAndAdd(String id, java.util.List<String[]> changes) {
        mNode.cmd("getaddress", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                String addr = resp != null ? resp.optString("miniaddress",
                        resp.optString("address", "")) : "";
                if (addr.isEmpty()) {
                    mChangeInFlight = false;
                    setStatus("✖ couldn't fetch a wallet address", OutputFormatter.COL_ERROR);
                    return;
                }
                addChangeOutputs(id, addr, changes, 0);
            }

            @Override
            public void onError(String message) {
                mChangeInFlight = false;
                nodeError(message);
            }
        });
    }

    private void addChangeOutputs(String id, String addr, java.util.List<String[]> changes, int idx) {
        if (idx >= changes.size()) {
            mChangeInFlight = false;
            setStatus("✓ change added for all " + changes.size()
                    + " token(s) — inputs now match outputs", OutputFormatter.COL_STRING);
            refresh();
            return;
        }
        String[] ch = changes.get(idx);
        String cmd = "txnoutput id:" + id + " amount:" + ch[1] + " address:" + addr
                + (ch[0].equals("0x00") ? "" : " tokenid:" + ch[0]) + " storestate:false";
        mNode.cmd(cmd, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    // Mid-chain failure: tokens 0..idx-1 got change, idx.. did NOT and will
                    // still BURN. Name what's left exposed so the user can fix or txndelete.
                    mChangeInFlight = false;
                    StringBuilder left = new StringBuilder();
                    for (int j = idx; j < changes.size(); j++) {
                        left.append(changes.get(j)[1]).append(" ")
                                .append(changes.get(j)[0].equals("0x00") ? "MINIMA"
                                        : shorten(changes.get(j)[0])).append("\n");
                    }
                    setStatus("✖ change failed on token " + (idx + 1) + "/" + changes.size()
                            + ": " + json.optString("error", "txnoutput failed")
                            + "\n\n⚠ Still unbalanced (would BURN on post):\n" + left
                            + "\nFix these or Delete the txn before posting.",
                            OutputFormatter.COL_ERROR);
                    return;
                }
                addChangeOutputs(id, addr, changes, idx + 1);
            }

            @Override
            public void onError(String message) {
                mChangeInFlight = false;
                nodeError(message);
            }
        });
    }

    private void stateDialog() {
        String id = txnId();
        if (id == null) return;
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (mActivity.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, 0, pad, 0);
        EditText port = new EditText(mActivity);
        port.setHint("port (0-255)");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText value = new EditText(mActivity);
        value.setHint("value");
        box.addView(port);
        box.addView(value);
        new AlertDialog.Builder(mActivity)
                .setTitle("Set state variable")
                .setMessage("State variables ride on the transaction and are stored in outputs "
                        + "with storestate:true. Unset ports read as empty — set every port your "
                        + "script touches.")
                .setView(box)
                .setPositiveButton("Set", (d, w) -> {
                    String p = port.getText().toString().trim();
                    String val = value.getText().toString().trim();
                    if (p.isEmpty() || val.isEmpty()) {
                        Toast.makeText(mActivity, "Port and value required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // value may be hex (0x..) but must not carry spaces/';' onto the command line.
                    if (rejectBadField("Port", p, false) || rejectBadField("Value", val, true)) return;
                    run("txnstate id:" + id + " port:" + p + " value:" + val, "state set", true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void signDialog() {
        String id = txnId();
        if (id == null) return;
        EditText key = new EditText(mActivity);
        key.setHint("publickey (leave empty for auto)");
        new AlertDialog.Builder(mActivity)
                .setTitle("Sign transaction")
                .setMessage("Order matters: sign FIRST, then Basics, then Post. For script-coin "
                        + "inputs sign with the specific key the script checks.")
                .setView(key)
                .setPositiveButton("Sign", (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (rejectBadField("Public key", k, false)) return;
                    run("txnsign id:" + id + " publickey:" + (k.isEmpty() ? "auto" : k),
                            "signed — now run Basics", true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void simple(String cmd, String okMsg) {
        String id = txnId();
        if (id == null) return;
        run(cmd + " id:" + id, okMsg, true);
    }

    private void postDialog() {
        String id = txnId();
        if (id == null) return;
        EditText burn = new EditText(mActivity);
        burn.setHint("burn (optional, e.g. 0.1)");
        burn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new AlertDialog.Builder(mActivity)
                .setTitle("Post transaction")
                .setMessage("This broadcasts on-chain. Success = status:true; the PoW mines "
                        + "asynchronously so istransaction:false at return is NORMAL — do not "
                        + "re-post, watch the chain instead. Did you run Sign then Basics?")
                .setView(burn)
                .setPositiveButton("Post", (d, w) -> {
                    String b = burn.getText().toString().trim();
                    if (rejectBadField("Burn", b, false)) return;
                    run("txnpost id:" + id + (b.isEmpty() ? "" : " burn:" + b),
                            "posted — status:true means accepted; it mines over the next blocks", true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exportTxn() {
        String id = txnId();
        if (id == null) return;
        setStatus("… exporting", OutputFormatter.COL_DIM);
        mNode.cmd("txnexport id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                String data = resp != null ? resp.optString("data", "") : "";
                if (data.isEmpty()) {
                    setStatus("✖ export failed: " + json.optString("error", "no data"),
                            OutputFormatter.COL_ERROR);
                    return;
                }
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText("txn", data));
                setStatus("✓ exported to clipboard (" + data.length() + " chars) — import it on "
                        + "another node with Import", OutputFormatter.COL_STRING);
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    private void importDialog() {
        String id = txnId();
        if (id == null) return;
        EditText data = new EditText(mActivity);
        data.setHint("0x... exported txn data");
        new AlertDialog.Builder(mActivity)
                .setTitle("Import transaction")
                .setView(data)
                .setPositiveButton("Import", (d, w) -> {
                    String hex = data.getText().toString().trim();
                    if (hex.isEmpty()) return;
                    if (rejectBadField("Import data", hex, false)) return;
                    run("txnimport id:" + id + " data:" + hex, "imported", true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteDialog() {
        String id = txnId();
        if (id == null) return;
        new AlertDialog.Builder(mActivity)
                .setTitle("Delete '" + id + "'?")
                .setMessage("Always delete abandoned transactions — they lock their input coins.")
                .setPositiveButton("Delete", (d, w) -> run("txndelete id:" + id, "deleted", false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void listAll() {
        setStatus("… listing transactions", OutputFormatter.COL_DIM);
        mNode.cmd("txnlist", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                // txnlist returns EVERY pending txn with full inputs/outputs/MMR proofs —
                // rendering the raw pretty-print can be huge and janks the main thread.
                // Show a compact id + in/out-count summary instead.
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) {
                    setStatus("No pending transactions", OutputFormatter.COL_DIM);
                    return;
                }
                StringBuilder sb = new StringBuilder(arr.length() + " pending transaction(s):\n\n");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String tid = t.optString("id", "?");
                    JSONObject txn = t.optJSONObject("transaction");
                    int ins = txn != null && txn.optJSONArray("inputs") != null
                            ? txn.optJSONArray("inputs").length() : 0;
                    int outs = txn != null && txn.optJSONArray("outputs") != null
                            ? txn.optJSONArray("outputs").length() : 0;
                    sb.append("• ").append(tid).append("  ")
                            .append(ins).append(" in / ").append(outs).append(" out\n");
                }
                sb.append("\nType an id above + Check to inspect one.");
                setStatus(sb.toString(), OutputFormatter.COL_PLAIN);
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    // ---------------- helpers ----------------

    private void run(String cmd, String okMsg, boolean refreshAfter) {
        setStatus("… " + cmd, OutputFormatter.COL_DIM);
        mNode.cmd(cmd, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    setStatus("✖ " + cmd + "\n" + json.optString("error", "failed")
                            + "\n\nTip: delete abandoned txns (they lock coins).",
                            OutputFormatter.COL_ERROR);
                    return;
                }
                setStatus("✓ " + okMsg, OutputFormatter.COL_STRING);
                if (refreshAfter) refresh();
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    /** Re-reads txncheck for the current txn and renders the balance summary. */
    public void refresh() {
        String id = mTxnId.getText().toString().trim();
        if (id.isEmpty() || mNode == null) return;
        mNode.cmd("txncheck id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    mSummary.setText(OutputFormatter.colored(
                            "no txn '" + id + "' — Create it first", OutputFormatter.COL_DIM));
                    return;
                }
                JSONObject resp = json.optJSONObject("response");
                StringBuilder sb = new StringBuilder();
                boolean burnWarning = false;
                if (resp != null) {
                    JSONArray coins = resp.optJSONArray("coins");
                    if (coins != null) {
                        for (int i = 0; i < coins.length(); i++) {
                            JSONObject c = coins.optJSONObject(i);
                            String tokenid = c.optString("tokenid", "0x00");
                            String in = c.optString("input", "?");
                            String outp = c.optString("output", "?");
                            String diff = c.optString("difference", "?");
                            sb.append(tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid))
                                    .append("  in:").append(in)
                                    .append("  out:").append(outp)
                                    .append("  diff:").append(diff).append("\n");
                            try {
                                if (new BigDecimal(diff).compareTo(BigDecimal.ZERO) > 0) burnWarning = true;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    String burn = resp.optString("burn", "");
                    if (!burn.isEmpty()) sb.append("burn: ").append(burn).append("\n");
                    JSONObject valid = resp.optJSONObject("valid");
                    if (valid != null) {
                        sb.append("valid: basic=").append(valid.optBoolean("basic"))
                                .append(" signatures=").append(valid.optBoolean("signatures"))
                                .append(" mmrproofs=").append(valid.optBoolean("mmrproofs"))
                                .append(" scripts=").append(valid.optBoolean("scripts"));
                    }
                }
                if (burnWarning) {
                    sb.append("\n⚠ inputs > outputs — the difference will be PERMANENTLY BURNED. "
                            + "Add a change output unless intentional.");
                }
                mSummary.setText(OutputFormatter.colored(sb.length() == 0
                                ? "txn exists — add inputs/outputs" : sb.toString(),
                        burnWarning ? OutputFormatter.COL_BOOL : OutputFormatter.COL_PLAIN));
            }

            @Override
            public void onError(String message) { /* summary is best-effort */ }
        });
    }

    private void nodeError(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) {
            setStatus("✖ This app is not enabled yet.\nOpen Minima Core → Apps and enable Terminal IDE.",
                    OutputFormatter.COL_ERROR);
        } else {
            setStatus("✖ " + message, OutputFormatter.COL_ERROR);
        }
    }

    private static String shorten(String hex) {
        return hex.length() > 18 ? hex.substring(0, 12) + "…" + hex.substring(hex.length() - 4) : hex;
    }
}
