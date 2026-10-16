package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The UTXO operations behind the Wallet tab's "Tools" dropdown: Split, Consolidate, Distribute,
 * Untrack. Split/merge build a custom transaction via {@link TxnBuilder}; Distribute hands off to
 * {@link DistributeManager}; the auto-consolidate uses the node's native "consolidate" command.
 */
public class WalletTools {

    /** Callback for surfacing a one-line status message to the user (ok = success vs. error styling). */
    public interface Status {
        void show(CharSequence message, boolean ok);
    }

    private static final int SPLIT_SCALE = 11;

    private final WalletActivity act;
    private final Status status;

    public WalletTools(WalletActivity act, Status status) {
        this.act = act;
        this.status = status;
    }

    // ---- shared helpers ----

    /** Build a numeric EditText (decimal toggles the decimal-point flag) for the dialog forms. */
    private EditText input(CharSequence hint, String preset, boolean decimal) {
        EditText e = new EditText(act);
        Language.hint(e, hint);
        e.setText(preset);
        e.setInputType(decimal
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                : InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    /** Persistent field label, using the same presentation as MintView.addField. */
    private TextView label(CharSequence text) {
        TextView label = new TextView(act);
        Language.setText(label, text);
        label.setTextColor(Design.dim());
        label.setTextSize(12f);
        float density = act.getResources().getDisplayMetrics().density;
        label.setPadding(0, (int) (10 * density), 0, (int) (4 * density));
        return label;
    }

    /** Wrap dialog children in a padded vertical layout. */
    private LinearLayout box(View... children) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad / 2, pad, 0);
        for (View c : children) l.addView(c);
        return l;
    }

    /** Total the amounts of a coin list (bad amounts are skipped). */
    private BigDecimal sum(List<Coin> coins) {
        BigDecimal t = BigDecimal.ZERO;
        for (Coin c : coins) {
            try { t = t.add(new BigDecimal(c.amount)); } catch (Exception ignored) {}
        }
        return t;
    }

    /** Resolve a human token name from balances/coins, falling back to "Minima"/"Token". */
    private String tokenNameFor(String tokenid) {
        if (Util.isMinima(tokenid)) return "Minima";
        for (TokenBalance b : act.balances()) if (b.tokenid.equals(tokenid)) return b.name;
        for (Coin c : act.coins()) if (c.tokenid.equals(tokenid)) return c.tokenName;
        return "Token";
    }

    /** Post a custom transaction via {@link TxnBuilder} and reflect the outcome on the history row. */
    private void runBuilder(List<Coin> inputs, List<TxnBuilder.Out> outs, String tokenid,
                            String internalid, CharSequence okLabel) {
        status.show(msg(R.string.l10n_node_tools_posting), true);
        new TxnBuilder(act, inputs, outs, tokenid, new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid, List<TxnBuilder.OutCoin> outputs) {
                // Don't store the txnsign-response txpowid (not the on-chain id) — the resolver fills
                // the real txpowid via the stored input coinids and marks it confirmed.
                act.history().update(internalid, HistoryDb.STATUS_POSTED, null, "");
                status.show(msg(R.string.l10n_node_tools_sent, okLabel), true);
                act.clearSelection();
                act.reload();
            }
            @Override public void onFailed(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status.show(msg(R.string.l10n_node_send_failed, SystemMessages.message(message)), false);
            }
        }).run();
    }

    // ---- Split ----

    /** Prompt for a coin count, then split the selected coins into that many equal coins. */
    public void showSplit() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status.show(msg(R.string.l10n_node_tools_select_split), false); return; }
        final EditText count = input(msg(R.string.l10n_node_tools_split_count), "4", false);
        Sheet.create(act, msg(R.string.l10n_node_tools_split_title))
                .stackActions()
                .body(box(label(msg(R.string.l10n_node_tools_split_count)), count))
                .action(msg(R.string.l10n_common_cancel), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_send_split), Sheet.Style.PRIMARY, () -> doSplit(sel, count.getText().toString()))
                .show();
    }

    /** Validate the count, compute equal slices, fetch one address per coin, then post the split. */
    private void doSplit(List<Coin> sel, String cStr) {
        int n;
        try { n = Integer.parseInt(cStr.trim()); } catch (Exception e) { status.show(msg(R.string.l10n_node_tools_valid_number), false); return; }
        if (n < 2 || n > 15) { status.show(msg(R.string.l10n_node_tools_split_range), false); return; }

        final String tokenid = act.selectedTokenid();
        int dec = act.tokenDecimals(tokenid);
        int scale = dec >= 0 ? dec : SPLIT_SCALE;
        BigDecimal total = sum(sel);
        BigDecimal each = total.divide(new BigDecimal(n), scale, RoundingMode.DOWN);
        if (each.signum() <= 0) { status.show(msg(R.string.l10n_node_tools_too_small, n), false); return; }
        // Rounding goes DOWN, so the last coin absorbs the remainder to keep inputs == outputs (no burn).
        final BigDecimal last = total.subtract(each.multiply(new BigDecimal(n - 1)));
        final BigDecimal eachF = each;
        final int fn = n;
        final String tokenName = sel.get(0).tokenName;

        status.show(msg(R.string.l10n_node_tools_fetching_addresses), true);
        TxnUtil.fetchAddresses(act, n, new TxnUtil.AddrList() {
            @Override public void onAddresses(List<String> addrs) {
                List<TxnBuilder.Out> outs = new ArrayList<>();
                for (int i = 0; i < fn; i++) {
                    BigDecimal amt = (i == fn - 1) ? last : eachF;
                    outs.add(new TxnBuilder.Out(addrs.get(i), amt.stripTrailingZeros().toPlainString()));
                }
                String internalid = TxnUtil.recordPosting(act, "Split → " + fn + " coins",
                        Util.tidyAmount(sum(sel).toPlainString()), tokenid, tokenName, sel, outs, null, null);
                runBuilder(sel, outs, tokenid, internalid, msg(R.string.l10n_node_tools_split_done, fn));
            }
            @Override public void onError(String message) { status.show(msg(R.string.l10n_node_send_failed, SystemMessages.message(message)), false); }
        });
    }

    // ---- Consolidate ----
    // No selection: run the node's auto-consolidate on Minima (0x00).
    // 2+ coins selected: merge exactly those coins into one.

    /** Entry point: no selection ⇒ node auto-consolidate Minima; 2+ selected ⇒ merge exactly those. */
    public void showConsolidate() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) {
            Sheet.create(act, msg(R.string.l10n_node_tools_consolidate_title))
                    .subtitle(msg(R.string.l10n_node_tools_consolidate_help))
                    .action(msg(R.string.l10n_common_cancel), Sheet.Style.SECONDARY, null)
                    .action(msg(R.string.l10n_node_wallet_consolidate), Sheet.Style.PRIMARY, () -> autoConsolidate(Util.MINIMA_TOKENID))
                    .show();
            return;
        }
        if (sel.size() < 2) {
            status.show(msg(R.string.l10n_node_tools_select_merge), false);
            return;
        }
        final String tokenName = sel.get(0).tokenName;
        Sheet.create(act, msg(R.string.l10n_node_tools_merge_title))
                    .subtitle(msg(R.string.l10n_node_tools_merge_help, tokenName, sel.size()))
                    .action(msg(R.string.l10n_common_cancel), Sheet.Style.SECONDARY, null)
                    .action(msg(R.string.l10n_node_tools_merge), Sheet.Style.PRIMARY, () -> mergeSelected(sel))
                    .show();
    }

    /** Run the node's native "consolidate" command (it picks its own inputs). */
    private void autoConsolidate(String tokenid) {
        final String internalid = TxnUtil.recordPosting(act, "Consolidate " + tokenNameFor(tokenid),
                "—", tokenid, tokenNameFor(tokenid));
        status.show(msg(R.string.l10n_node_tools_consolidating), true);
        act.node().cmd("consolidate tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                boolean ok = json.optBoolean("status", true);
                // Node-driven consolidate picks its own inputs, so the txpowid can't be resolved by
                // input match — store null rather than the unreliable response id.
                act.history().update(internalid,
                        ok ? HistoryDb.STATUS_POSTED : HistoryDb.STATUS_ERROR, null, "");
                status.show(ok ? msg(R.string.l10n_node_tools_consolidate_submitted) : msg(R.string.l10n_node_tools_nothing_consolidate), ok);
                act.reload();
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status.show(msg(R.string.l10n_node_send_failed, SystemMessages.message(message)), false);
            }
        });
    }

    /** Merge exactly the selected coins into a single coin at a fresh address. */
    private void mergeSelected(final List<Coin> sel) {
        final String tokenid = act.selectedTokenid();
        final String tokenName = sel.get(0).tokenName;
        final BigDecimal total = sum(sel);
        status.show(msg(R.string.l10n_node_wallet_fetching_address), true);
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (addr.isEmpty()) { status.show(msg(R.string.l10n_node_tools_no_address), false); return; }
                List<TxnBuilder.Out> outs = new ArrayList<>();
                outs.add(new TxnBuilder.Out(addr, total.stripTrailingZeros().toPlainString()));
                String internalid = TxnUtil.recordPosting(act, "Consolidate → 1 coin",
                        Util.tidyAmount(total.toPlainString()), tokenid, tokenName, sel, outs, null, null);
                runBuilder(sel, outs, tokenid, internalid, msg(R.string.l10n_node_tools_merged, sel.size()));
            }
            @Override public void onError(String message) { status.show(msg(R.string.l10n_node_send_failed, SystemMessages.message(message)), false); }
        });
    }

    // ---- Distribute ----

    /** Prompt for address count + per-address amount, then kick off the multi-batch DistributeManager. */
    public void showDistribute() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status.show(msg(R.string.l10n_node_tools_select_distribute), false); return; }
        if (act.distribute() != null && act.distribute().isBusy()) {
            status.show(msg(R.string.l10n_node_tools_distribute_busy), false); return;
        }
        final EditText count = input(msg(R.string.l10n_node_tools_address_count), "20", false);
        final EditText amount = input(msg(R.string.l10n_node_tools_amount_each), "", true);
        Sheet.create(act, msg(R.string.l10n_node_tools_distribute_title))
                .stackActions()
                .body(box(label(msg(R.string.l10n_node_tools_address_count)), count,
                        label(msg(R.string.l10n_node_tools_amount_each)), amount))
                .action(msg(R.string.l10n_common_cancel), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_tools_distribute), Sheet.Style.PRIMARY, () ->
                        doDistribute(sel, count.getText().toString(), amount.getText().toString()))
                .show();
    }

    /** Validate count/amount/decimals/funds, reserve the job slot, fetch addresses, then start. */
    private void doDistribute(final List<Coin> sel, String cStr, String aStr) {
        int n;
        try { n = Integer.parseInt(cStr.trim()); } catch (Exception e) { status.show(msg(R.string.l10n_node_tools_valid_number), false); return; }
        if (n < 2 || n > 56) { status.show(msg(R.string.l10n_node_tools_address_range), false); return; }

        final BigDecimal per;
        try { per = new BigDecimal(aStr.trim()); } catch (Exception e) { status.show(msg(R.string.l10n_node_send_valid_amount), false); return; }
        if (per.signum() <= 0) { status.show(msg(R.string.l10n_node_send_positive_amount), false); return; }

        final String tokenid = act.selectedTokenid();
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(per) > dec) {
            status.show(Language.count(R.plurals.l10n_node_send_decimals, dec), false);
            return;
        }

        BigDecimal total = sum(sel);
        BigDecimal required = per.multiply(new BigDecimal(n));
        if (required.compareTo(total) > 0) {
            status.show(msg(R.string.l10n_node_tools_need_amount, Util.tidyAmount(required.toPlainString()), Util.tidyAmount(total.toPlainString())), false);
            return;
        }
        final String perStr = per.stripTrailingZeros().toPlainString();
        final String tokenName = sel.get(0).tokenName;

        // Reserve before the async address fetch so a second tap can't start a parallel job.
        if (!act.distribute().reserve()) { status.show(msg(R.string.l10n_node_tools_distribute_busy), false); return; }
        status.show(msg(R.string.l10n_node_tools_fetching_count, n), true);
        TxnUtil.fetchAddresses(act, n, new TxnUtil.AddrList() {
            @Override public void onAddresses(List<String> addrs) {
                act.distribute().start(sel, addrs, perStr, tokenid, tokenName);
                status.show(msg(R.string.l10n_node_tools_distribute_started), true);
            }
            @Override public void onError(String message) {
                act.distribute().cancelReserve();
                status.show(msg(R.string.l10n_node_send_failed, SystemMessages.message(message)), false);
            }
        });
    }

    // ---- Untrack ----

    /** Confirm, then stop tracking the selected coins (they stay on-chain, just hidden locally). */
    public void untrackSelected() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status.show(msg(R.string.l10n_node_tools_select_untrack), false); return; }
        Sheet.create(act, msg(R.string.l10n_node_tools_untrack_title, sel.size()))
                .subtitle(msg(R.string.l10n_node_tools_untrack_help))
                .action(msg(R.string.l10n_common_cancel), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_tools_untrack), Sheet.Style.PRIMARY, () -> untrackNext(sel, 0))
                .show();
    }

    // Untrack one coin at a time, recursing on success — the node has no batch cointrack call.
    /** Sequentially `cointrack enable:false` each selected coin, recursing through the list. */
    private void untrackNext(final List<Coin> sel, final int i) {
        if (i >= sel.size()) {
            status.show(msg(R.string.l10n_node_tools_untracked, sel.size()), true);
            act.clearSelection();
            act.reload();
            return;
        }
        status.show(msg(R.string.l10n_node_tools_untracking, i + 1, sel.size()), true);
        // Belt-and-braces: coins are already vetted at ingestion (Coin.isWellFormed), but this id
        // goes straight into a command, so never take that on trust.
        if (!Util.isValidHexId(sel.get(i).coinid)) { untrackNext(sel, i + 1); return; }
        act.node().cmd("cointrack enable:false coinid:" + sel.get(i).coinid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { untrackNext(sel, i + 1); }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                status.show(msg(R.string.l10n_node_tools_untrack_failed, SystemMessages.message(message)), false);
            }
        });
    }
}
