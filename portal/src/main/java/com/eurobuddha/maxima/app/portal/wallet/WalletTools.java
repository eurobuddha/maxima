package com.eurobuddha.maxima.app.portal.wallet;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

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
        void show(String message, boolean ok);
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
    private EditText input(String hint, String preset, boolean decimal) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setText(preset);
        e.setInputType(decimal
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                : InputType.TYPE_CLASS_NUMBER);
        return e;
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
                            String internalid, String okLabel) {
        status.show("Posting…", true);
        new TxnBuilder(act, inputs, outs, tokenid, new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid, List<TxnBuilder.OutCoin> outputs) {
                // Don't store the txnsign-response txpowid (not the on-chain id) — the resolver fills
                // the real txpowid via the stored input coinids and marks it confirmed.
                act.history().update(internalid, HistoryDb.STATUS_POSTED, null, "");
                status.show(okLabel + " — sent!", true);
                act.clearSelection();
                act.reload();
            }
            @Override public void onFailed(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status.show("Failed: " + message, false);
            }
        }).run();
    }

    // ---- Split ----

    /** Prompt for a coin count, then split the selected coins into that many equal coins. */
    public void showSplit() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status.show("Select coins in the list first, then Split.", false); return; }
        final EditText count = input("Number of coins (2–15)", "4", false);
        Sheet.create(act, "Split selected coins")
                .body(box(count))
                .action("Cancel", Sheet.Style.SECONDARY, null)
                .action("Split", Sheet.Style.PRIMARY, () -> doSplit(sel, count.getText().toString()))
                .show();
    }

    /** Validate the count, compute equal slices, fetch one address per coin, then post the split. */
    private void doSplit(List<Coin> sel, String cStr) {
        int n;
        try { n = Integer.parseInt(cStr.trim()); } catch (Exception e) { status.show("Enter a valid number.", false); return; }
        if (n < 2 || n > 15) { status.show("Choose between 2 and 15 coins.", false); return; }

        final String tokenid = act.selectedTokenid();
        int dec = act.tokenDecimals(tokenid);
        int scale = dec >= 0 ? dec : SPLIT_SCALE;
        BigDecimal total = sum(sel);
        BigDecimal each = total.divide(new BigDecimal(n), scale, RoundingMode.DOWN);
        if (each.signum() <= 0) { status.show("Amount too small to split into " + n + ".", false); return; }
        // Rounding goes DOWN, so the last coin absorbs the remainder to keep inputs == outputs (no burn).
        final BigDecimal last = total.subtract(each.multiply(new BigDecimal(n - 1)));
        final BigDecimal eachF = each;
        final int fn = n;
        final String tokenName = sel.get(0).tokenName;

        status.show("Fetching addresses…", true);
        TxnUtil.fetchAddresses(act, n, new TxnUtil.AddrList() {
            @Override public void onAddresses(List<String> addrs) {
                List<TxnBuilder.Out> outs = new ArrayList<>();
                for (int i = 0; i < fn; i++) {
                    BigDecimal amt = (i == fn - 1) ? last : eachF;
                    outs.add(new TxnBuilder.Out(addrs.get(i), amt.stripTrailingZeros().toPlainString()));
                }
                String internalid = TxnUtil.recordPosting(act, "Split → " + fn + " coins",
                        Util.tidyAmount(sum(sel).toPlainString()), tokenid, tokenName, sel, outs, null, null);
                runBuilder(sel, outs, tokenid, internalid, "Split into " + fn + " coins");
            }
            @Override public void onError(String message) { status.show("Failed: " + message, false); }
        });
    }

    // ---- Consolidate ----
    // No selection: run the node's auto-consolidate on Minima (0x00).
    // 2+ coins selected: merge exactly those coins into one.

    /** Entry point: no selection ⇒ node auto-consolidate Minima; 2+ selected ⇒ merge exactly those. */
    public void showConsolidate() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) {
            Sheet.create(act, "Consolidate Minima")
                    .subtitle("Merge your Minima coins together automatically (consolidate tokenid:0x00)?")
                    .action("Cancel", Sheet.Style.SECONDARY, null)
                    .action("Consolidate", Sheet.Style.PRIMARY, () -> autoConsolidate(Util.MINIMA_TOKENID))
                    .show();
            return;
        }
        if (sel.size() < 2) {
            status.show("Select 2+ coins to merge them, or clear the selection to auto-consolidate Minima.", false);
            return;
        }
        final String tokenName = sel.get(0).tokenName;
        Sheet.create(act, "Consolidate selected")
                    .subtitle("Merge the " + sel.size() + " selected " + tokenName + " coins into one?")
                    .action("Cancel", Sheet.Style.SECONDARY, null)
                    .action("Merge", Sheet.Style.PRIMARY, () -> mergeSelected(sel))
                    .show();
    }

    /** Run the node's native "consolidate" command (it picks its own inputs). */
    private void autoConsolidate(String tokenid) {
        final String internalid = TxnUtil.recordPosting(act, "Consolidate " + tokenNameFor(tokenid),
                "—", tokenid, tokenNameFor(tokenid));
        status.show("Consolidating…", true);
        act.node().cmd("consolidate tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                boolean ok = json.optBoolean("status", true);
                // Node-driven consolidate picks its own inputs, so the txpowid can't be resolved by
                // input match — store null rather than the unreliable response id.
                act.history().update(internalid,
                        ok ? HistoryDb.STATUS_POSTED : HistoryDb.STATUS_ERROR, null, "");
                status.show(ok ? "Consolidate submitted." : "Nothing to consolidate.", ok);
                act.reload();
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status.show("Failed: " + message, false);
            }
        });
    }

    /** Merge exactly the selected coins into a single coin at a fresh address. */
    private void mergeSelected(final List<Coin> sel) {
        final String tokenid = act.selectedTokenid();
        final String tokenName = sel.get(0).tokenName;
        final BigDecimal total = sum(sel);
        status.show("Fetching address…", true);
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (addr.isEmpty()) { status.show("Could not get an address.", false); return; }
                List<TxnBuilder.Out> outs = new ArrayList<>();
                outs.add(new TxnBuilder.Out(addr, total.stripTrailingZeros().toPlainString()));
                String internalid = TxnUtil.recordPosting(act, "Consolidate → 1 coin",
                        Util.tidyAmount(total.toPlainString()), tokenid, tokenName, sel, outs, null, null);
                runBuilder(sel, outs, tokenid, internalid, "Merged " + sel.size() + " coins");
            }
            @Override public void onError(String message) { status.show("Failed: " + message, false); }
        });
    }

    // ---- Distribute ----

    /** Prompt for address count + per-address amount, then kick off the multi-batch DistributeManager. */
    public void showDistribute() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status.show("Select coins in the list first, then Distribute.", false); return; }
        if (act.distribute() != null && act.distribute().isBusy()) {
            status.show("A distribute job is already running.", false); return;
        }
        final EditText count = input("Number of addresses (2–56)", "20", false);
        final EditText amount = input("Amount to each", "", true);
        Sheet.create(act, "Distribute to my addresses")
                .body(box(count, amount))
                .action("Cancel", Sheet.Style.SECONDARY, null)
                .action("Distribute", Sheet.Style.PRIMARY, () ->
                        doDistribute(sel, count.getText().toString(), amount.getText().toString()))
                .show();
    }

    /** Validate count/amount/decimals/funds, reserve the job slot, fetch addresses, then start. */
    private void doDistribute(final List<Coin> sel, String cStr, String aStr) {
        int n;
        try { n = Integer.parseInt(cStr.trim()); } catch (Exception e) { status.show("Enter a valid number.", false); return; }
        if (n < 2 || n > 56) { status.show("Choose 2–56 addresses (auto-chained in up to 4 transactions).", false); return; }

        final BigDecimal per;
        try { per = new BigDecimal(aStr.trim()); } catch (Exception e) { status.show("Enter a valid amount.", false); return; }
        if (per.signum() <= 0) { status.show("Amount must be greater than zero.", false); return; }

        final String tokenid = act.selectedTokenid();
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(per) > dec) {
            status.show("This token supports at most " + dec + " decimal place" + (dec == 1 ? "" : "s") + ".", false);
            return;
        }

        BigDecimal total = sum(sel);
        BigDecimal required = per.multiply(new BigDecimal(n));
        if (required.compareTo(total) > 0) {
            status.show("Need " + Util.tidyAmount(required.toPlainString()) + " but only "
                    + Util.tidyAmount(total.toPlainString()) + " selected.", false);
            return;
        }
        final String perStr = per.stripTrailingZeros().toPlainString();
        final String tokenName = sel.get(0).tokenName;

        // Reserve before the async address fetch so a second tap can't start a parallel job.
        if (!act.distribute().reserve()) { status.show("A distribute job is already running.", false); return; }
        status.show("Fetching " + n + " addresses…", true);
        TxnUtil.fetchAddresses(act, n, new TxnUtil.AddrList() {
            @Override public void onAddresses(List<String> addrs) {
                act.distribute().start(sel, addrs, perStr, tokenid, tokenName);
                status.show("Distribute started.", true);
            }
            @Override public void onError(String message) {
                act.distribute().cancelReserve();
                status.show("Failed: " + message, false);
            }
        });
    }

    // ---- Untrack ----

    /** Confirm, then stop tracking the selected coins (they stay on-chain, just hidden locally). */
    public void untrackSelected() {
        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status.show("Select coins in the list first, then Untrack.", false); return; }
        Sheet.create(act, "Untrack " + sel.size() + " coin(s)?")
                .subtitle("They'll disappear from the wallet view but remain on-chain. Re-add later by coinid.")
                .action("Cancel", Sheet.Style.SECONDARY, null)
                .action("Untrack", Sheet.Style.PRIMARY, () -> untrackNext(sel, 0))
                .show();
    }

    // Untrack one coin at a time, recursing on success — the node has no batch cointrack call.
    /** Sequentially `cointrack enable:false` each selected coin, recursing through the list. */
    private void untrackNext(final List<Coin> sel, final int i) {
        if (i >= sel.size()) {
            status.show("Untracked " + sel.size() + " coin(s).", true);
            act.clearSelection();
            act.reload();
            return;
        }
        status.show("Untracking " + (i + 1) + "/" + sel.size() + "…", true);
        // Belt-and-braces: coins are already vetted at ingestion (Coin.isWellFormed), but this id
        // goes straight into a command, so never take that on trust.
        if (!Util.isValidHexId(sel.get(i).coinid)) { untrackNext(sel, i + 1); return; }
        act.node().cmd("cointrack enable:false coinid:" + sel.get(i).coinid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { untrackNext(sel, i + 1); }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                status.show("Untrack failed: " + message, false);
            }
        });
    }
}
