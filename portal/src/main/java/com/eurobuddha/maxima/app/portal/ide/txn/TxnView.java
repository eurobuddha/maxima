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

import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

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
        wire(R.id.txn_basics, v -> simple("txnbasics", msg(R.string.l10n_txn_workbench_basics_added)));
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
            Toast.makeText(mActivity, msg(R.string.l10n_txn_workbench_id_required), Toast.LENGTH_SHORT).show();
            return null;
        }
        if (hasCommandChars(id)) {
            Toast.makeText(mActivity, msg(R.string.l10n_txn_workbench_id_invalid), Toast.LENGTH_SHORT).show();
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

    private boolean rejectBadField(CharSequence label, String value, boolean allowColon) {
        boolean bad = allowColon ? hasWhitespaceOrSemicolon(value) : hasCommandChars(value);
        if (bad) {
            Toast.makeText(mActivity, msg(allowColon ? R.string.l10n_txn_workbench_field_invalid_colon
                    : R.string.l10n_txn_workbench_field_invalid, label), Toast.LENGTH_LONG).show();
        }
        return bad;
    }

    private void setStatus(CharSequence text, int color) {
        Language.setText(mStatus, text);
        mStatus.setTextColor(color);
    }

    private void setSummary(CharSequence text, int color) {
        Language.setText(mSummary, text);
        mSummary.setTextColor(color);
    }

    // ---------------- actions ----------------

    private void create() {
        String id = txnId();
        if (id == null) return;
        run("txncreate id:" + id, msg(R.string.l10n_txn_workbench_created, id), true);
    }

    private void pickCoin() {
        String id = txnId();
        if (id == null) return;
        setStatus(msg(R.string.l10n_txn_workbench_loading_coins), OutputFormatter.COL_DIM);
        // Shrink the reply at the SOURCE with simplestate:true (drops the large per-coin
        // state blobs) — an over-limit Binder reply crashes app+node uncatchably (see
        // minima-ipc-gotchas). We keep relevant+sendable (never hide coins by age) and
        // the coin picker doesn't need state, so this is lossless for our purpose.
        mNode.cmd("coins relevant:true sendable:true simplestate:true", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONArray coins = json.optJSONArray("response");
                if (coins == null || coins.length() == 0) {
                    setStatus(msg(R.string.l10n_txn_workbench_no_coins), OutputFormatter.COL_ERROR);
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
                CharSequence title = capped ? msg(R.string.l10n_txn_workbench_coin_capped, n, coins.length())
                        : msg(R.string.l10n_txn_workbench_coin_title, coins.length());
                new LanguageDialog(mActivity)
                        .setTitle(title)
                        .setItems(labels, (d, which) ->
                                run("txninput id:" + id + " coinid:" + coinids[which],
                                        msg(R.string.l10n_txn_workbench_input_added), true))
                        .setNegativeButton(msg(R.string.l10n_common_cancel), null)
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
        Language.apply(v);
        EditText amount = v.findViewById(R.id.out_amount);
        EditText address = v.findViewById(R.id.out_address);
        EditText tokenid = v.findViewById(R.id.out_tokenid);
        CheckBox storestate = v.findViewById(R.id.out_storestate);
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_txn_workbench_output_title))
                .setView(v)
                .setPositiveButton(msg(R.string.l10n_common_add), (d, w) -> {
                    String amt = amount.getText().toString().trim();
                    String addr = address.getText().toString().trim();
                    if (amt.isEmpty() || addr.isEmpty()) {
                        Toast.makeText(mActivity, msg(R.string.l10n_txn_workbench_amount_address_required), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String tok = tokenid.getText().toString().trim();
                    if (rejectBadField(msg(R.string.l10n_wallet_amount), amt, false) || rejectBadField(msg(R.string.l10n_common_address), addr, false)
                            || rejectBadField(msg(R.string.l10n_txn_workbench_token_id), tok, false)) {
                        return;
                    }
                    String cmd = "txnoutput id:" + id + " amount:" + amt + " address:" + addr
                            + (tok.isEmpty() ? "" : " tokenid:" + tok)
                            + " storestate:" + storestate.isChecked();
                    run(cmd, msg(R.string.l10n_txn_workbench_output_added), true);
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
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
            Toast.makeText(mActivity, msg(R.string.l10n_txn_workbench_change_in_progress), Toast.LENGTH_SHORT).show();
            return;
        }
        mChangeInFlight = true;
        setStatus(msg(R.string.l10n_txn_workbench_calculating_change), OutputFormatter.COL_DIM);
        mNode.cmd("txncheck id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                JSONArray coins = resp != null ? resp.optJSONArray("coins") : null;
                if (coins == null) {
                    mChangeInFlight = false;
                    setStatus(Language.join("✖ ", msg(R.string.l10n_txn_workbench_missing, id)), OutputFormatter.COL_ERROR);
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
                    setStatus(msg(R.string.l10n_txn_workbench_balanced),
                            OutputFormatter.COL_STRING);
                    return;
                }
                new LanguageDialog(mActivity)
                        .setTitle(msg(R.string.l10n_txn_workbench_change_title))
                        .setMessage(msg(R.string.l10n_txn_workbench_change_body, planned.toString()))
                        .setPositiveButton(msg(R.string.l10n_txn_workbench_add_change), (d, w) -> fetchAddressAndAdd(id, changes))
                        .setNegativeButton(msg(R.string.l10n_common_cancel), (d, w) -> mChangeInFlight = false)
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
                    setStatus(msg(R.string.l10n_txn_workbench_address_failed), OutputFormatter.COL_ERROR);
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
            setStatus(msg(R.string.l10n_txn_workbench_change_added, changes.size()), OutputFormatter.COL_STRING);
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
                    setStatus(msg(R.string.l10n_txn_workbench_change_failed, idx + 1, changes.size(),
                            SystemMessages.message(json.optString("error", "txnoutput failed")), left.toString()),
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
        Language.hint(port, msg(R.string.l10n_txn_workbench_port_hint));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText value = new EditText(mActivity);
        Language.hint(value, msg(R.string.l10n_node_mint_value));
        box.addView(port);
        box.addView(value);
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_txn_workbench_state_title))
                .setMessage(msg(R.string.l10n_txn_workbench_state_body))
                .setView(box)
                .setPositiveButton(msg(R.string.l10n_native_settings_set), (d, w) -> {
                    String p = port.getText().toString().trim();
                    String val = value.getText().toString().trim();
                    if (p.isEmpty() || val.isEmpty()) {
                        Toast.makeText(mActivity, msg(R.string.l10n_txn_workbench_port_value_required), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // value may be hex (0x..) but must not carry spaces/';' onto the command line.
                    if (rejectBadField(msg(R.string.l10n_txn_workbench_port), p, false) || rejectBadField(msg(R.string.l10n_txn_workbench_value), val, true)) return;
                    run("txnstate id:" + id + " port:" + p + " value:" + val, msg(R.string.l10n_txn_workbench_state_set), true);
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void signDialog() {
        String id = txnId();
        if (id == null) return;
        EditText key = new EditText(mActivity);
        Language.hint(key, msg(R.string.l10n_txn_workbench_key_hint));
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_txn_workbench_sign_title))
                .setMessage(msg(R.string.l10n_txn_workbench_sign_body))
                .setView(key)
                .setPositiveButton(msg(R.string.l10n_txn_workbench_sign), (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (rejectBadField(msg(R.string.l10n_native_contacts_public_key), k, false)) return;
                    run("txnsign id:" + id + " publickey:" + (k.isEmpty() ? "auto" : k),
                            msg(R.string.l10n_txn_workbench_signed), true);
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void simple(String cmd, CharSequence okMsg) {
        String id = txnId();
        if (id == null) return;
        run(cmd + " id:" + id, okMsg, true);
    }

    private void postDialog() {
        String id = txnId();
        if (id == null) return;
        EditText burn = new EditText(mActivity);
        Language.hint(burn, msg(R.string.l10n_txn_workbench_burn_hint));
        burn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_txn_workbench_post_title))
                .setMessage(msg(R.string.l10n_txn_workbench_post_body))
                .setView(burn)
                .setPositiveButton(msg(R.string.l10n_txn_workbench_post), (d, w) -> {
                    String b = burn.getText().toString().trim();
                    if (rejectBadField(msg(R.string.l10n_node_mint_burn), b, false)) return;
                    run("txnpost id:" + id + (b.isEmpty() ? "" : " burn:" + b),
                            msg(R.string.l10n_txn_workbench_posted), true);
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void exportTxn() {
        String id = txnId();
        if (id == null) return;
        setStatus(msg(R.string.l10n_txn_workbench_exporting), OutputFormatter.COL_DIM);
        mNode.cmd("txnexport id:" + id, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                String data = resp != null ? resp.optString("data", "") : "";
                if (data.isEmpty()) {
                    setStatus(msg(R.string.l10n_txn_workbench_export_failed, SystemMessages.message(json.optString("error", "no data"))),
                            OutputFormatter.COL_ERROR);
                    return;
                }
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText("txn", data));
                setStatus(msg(R.string.l10n_txn_workbench_exported, data.length()), OutputFormatter.COL_STRING);
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    private void importDialog() {
        String id = txnId();
        if (id == null) return;
        EditText data = new EditText(mActivity);
        Language.hint(data, msg(R.string.l10n_txn_workbench_import_hint));
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_txn_workbench_import_title))
                .setView(data)
                .setPositiveButton(msg(R.string.l10n_android_wallet_import), (d, w) -> {
                    String hex = data.getText().toString().trim();
                    if (hex.isEmpty()) return;
                    if (rejectBadField(msg(R.string.l10n_txn_workbench_import_data), hex, false)) return;
                    run("txnimport id:" + id + " data:" + hex, msg(R.string.l10n_txn_workbench_imported), true);
                })
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void deleteDialog() {
        String id = txnId();
        if (id == null) return;
        new LanguageDialog(mActivity)
                .setTitle(msg(R.string.l10n_txn_workbench_delete_title, id))
                .setMessage(msg(R.string.l10n_txn_workbench_delete_body))
                .setPositiveButton(msg(R.string.l10n_scripts_delete), (d, w) -> run("txndelete id:" + id, msg(R.string.l10n_txn_workbench_deleted), false))
                .setNegativeButton(msg(R.string.l10n_common_cancel), null)
                .show();
    }

    private void listAll() {
        setStatus(msg(R.string.l10n_txn_workbench_listing), OutputFormatter.COL_DIM);
        mNode.cmd("txnlist", new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                // txnlist returns EVERY pending txn with full inputs/outputs/MMR proofs —
                // rendering the raw pretty-print can be huge and janks the main thread.
                // Show a compact id + in/out-count summary instead.
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) {
                    setStatus(msg(R.string.l10n_txn_workbench_no_pending), OutputFormatter.COL_DIM);
                    return;
                }
                Language.TextBuilder sb = new Language.TextBuilder().append(msg(R.string.l10n_txn_workbench_pending_title, arr.length()));
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
                            .append(msg(R.string.l10n_txn_workbench_io_counts, ins, outs)).append("\n");
                }
                sb.append("\n").append(msg(R.string.l10n_txn_workbench_inspect_hint));
                setStatus(sb.build(), OutputFormatter.COL_PLAIN);
            }

            @Override
            public void onError(String message) { nodeError(message); }
        });
    }

    // ---------------- helpers ----------------

    private void run(String cmd, CharSequence okMsg, boolean refreshAfter) {
        setStatus("… " + cmd, OutputFormatter.COL_DIM);
        mNode.cmd(cmd, new NodeApi.Cb() {
            @Override
            public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    setStatus(msg(R.string.l10n_txn_workbench_command_error, cmd, SystemMessages.message(json.optString("error", "failed"))),
                            OutputFormatter.COL_ERROR);
                    return;
                }
                setStatus(Language.join("✓ ", okMsg), OutputFormatter.COL_STRING);
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
                    setSummary(msg(R.string.l10n_txn_workbench_missing, id), OutputFormatter.COL_DIM);
                    return;
                }
                JSONObject resp = json.optJSONObject("response");
                Language.TextBuilder sb = new Language.TextBuilder();
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
                            sb.append(msg(R.string.l10n_txn_workbench_balance_line, tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid), in, outp, diff));
                            try {
                                if (new BigDecimal(diff).compareTo(BigDecimal.ZERO) > 0) burnWarning = true;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    String burn = resp.optString("burn", "");
                    if (!burn.isEmpty()) sb.append(msg(R.string.l10n_txn_workbench_burn_line, burn));
                    JSONObject valid = resp.optJSONObject("valid");
                    if (valid != null) {
                        sb.append(msg(R.string.l10n_txn_workbench_valid_line, valid.optBoolean("basic"), valid.optBoolean("signatures"),
                                valid.optBoolean("mmrproofs"), valid.optBoolean("scripts")));
                    }
                }
                if (burnWarning) {
                    sb.append("\n").append(msg(R.string.l10n_txn_workbench_burn_warning));
                }
                CharSequence summary = sb.build();
                setSummary(summary.length() == 0 ? msg(R.string.l10n_txn_workbench_exists) : summary,
                        burnWarning ? OutputFormatter.COL_BOOL : OutputFormatter.COL_PLAIN);
            }

            @Override
            public void onError(String message) { /* summary is best-effort */ }
        });
    }

    private void nodeError(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) {
            setStatus(msg(R.string.l10n_terminal_not_enabled),
                    OutputFormatter.COL_ERROR);
        } else {
            setStatus(Language.join("✖ ", SystemMessages.message(message)), OutputFormatter.COL_ERROR);
        }
    }

    private static String shorten(String hex) {
        return hex.length() > 18 ? hex.substring(0, 12) + "…" + hex.substring(hex.length() - 4) : hex;
    }
}
