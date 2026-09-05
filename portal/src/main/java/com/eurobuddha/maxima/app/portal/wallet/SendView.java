package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Send tab — two modes behind one form, like the old Wallet's send suite:
 *
 *  QUICK SEND    — pick a token, recipient, amount (+ max), optional burn and split:N; the node
 *                  chooses the input coins ({@code send} command). This is the 2.47.2-era flow.
 *  COIN CONTROL  — full UTXO transaction construction from the coins selected on the Coins tab:
 *                  FROM list, editable change address (+ next), burn; manual txn build/sign/post.
 *
 * Both modes share recipient/amount/burn fields, validation notes, the QR scan button, and the
 * Confirm-breakdown dialog before anything is signed or posted.
 */
public class SendView extends BaseView {

    private final LinearLayout fromList;
    private final EditText addrInput, amountInput, changeInput, burnInput, splitInput;
    private final TextView addrNote, amountNote, changeNote, burnNote, splitNote, statusView;
    private final Button previewBtn;
    private final TextView modeQuick, modeCoins, tokenLabel, tokenBal;
    private final View tokenBlock, fromBlock, splitBlock, changeBlock;

    /** true = QUICK SEND (token-level, node picks coins); false = COIN CONTROL (manual txn). */
    private boolean quickMode = true;
    /** Quick-mode selected token (tokenid); defaults to Minima. */
    private String quickTokenid = Util.MINIMA_TOKENID;

    /** True while a send/build-sign-post is in flight — refresh() must not re-enable the button. */
    private boolean sending = false;

    private boolean changeTouched = false;     // user edited the change field
    private boolean settingChange = false;     // guard for programmatic change-field writes
    private boolean changeFetching = false;

    /** Binds the form fields, wires Max/Next/Preview/Scan and the mode segment. */
    public SendView(WalletActivity a) {
        super(a, R.layout.view_send);
        fromList = find(R.id.sendFromList);
        addrInput = find(R.id.sendAddress);
        amountInput = find(R.id.sendAmount);
        changeInput = find(R.id.sendChange);
        burnInput = find(R.id.sendBurn);
        splitInput = find(R.id.sendSplit);
        addrNote = find(R.id.sendAddrNote);
        amountNote = find(R.id.sendAmountNote);
        changeNote = find(R.id.sendChangeNote);
        burnNote = find(R.id.sendBurnNote);
        splitNote = find(R.id.sendSplitNote);
        statusView = find(R.id.sendStatus);
        previewBtn = find(R.id.previewBtn);
        modeQuick = find(R.id.modeQuickBtn);
        modeCoins = find(R.id.modeCoinsBtn);
        tokenLabel = find(R.id.sendTokenLabel);
        tokenBal = find(R.id.sendTokenBal);
        tokenBlock = find(R.id.sendTokenBlock);
        fromBlock = find(R.id.sendFromBlock);
        splitBlock = find(R.id.sendSplitBlock);
        changeBlock = find(R.id.sendChangeBlock);

        ((TextView) find(R.id.maxBtn)).setOnClickListener(v -> onMax());
        ((TextView) find(R.id.nextBtn)).setOnClickListener(v -> fetchChange(true));
        ((TextView) find(R.id.scanBtn)).setOnClickListener(v -> act.scanQr(text -> {
            if (text != null && !text.isEmpty()) addrInput.setText(text.trim());
        }));
        previewBtn.setOnClickListener(v -> onPreview());
        modeQuick.setOnClickListener(v -> setMode(true));
        modeCoins.setOnClickListener(v -> setMode(false));
        find(R.id.sendTokenRow).setOnClickListener(v -> showTokenPicker());

        // Mark the change field "touched" once the user types, so we stop auto-prefilling it.
        changeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { if (!settingChange) changeTouched = true; }
        });
        applyDesign();
        refresh();
    }

    /** Paints the view and all input fields with the active theme. */
    private void applyDesign() {
        root.setBackgroundColor(Design.bg());
        fromList.setBackgroundColor(Design.surface());
        for (EditText e : new EditText[]{addrInput, amountInput, changeInput, burnInput, splitInput}) {
            e.setBackgroundColor(Design.surface2());
            e.setTextColor(Design.text());
        }
        find(R.id.sendTokenRow).setBackgroundColor(Design.surface2());
        tokenLabel.setTextColor(Design.text());
        tokenBal.setTextColor(Design.dim());
        previewBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        previewBtn.setTextColor(Design.onAccent());
        paintModeSegment();
    }

    private void paintModeSegment() {
        modeQuick.setTextColor(quickMode ? Design.onAccent() : Design.dim());
        modeQuick.setBackgroundColor(quickMode ? Design.accent() : Design.surface2());
        modeCoins.setTextColor(!quickMode ? Design.onAccent() : Design.dim());
        modeCoins.setBackgroundColor(!quickMode ? Design.accent() : Design.surface2());
    }

    private void setMode(boolean quick) {
        quickMode = quick;
        paintModeSegment();
        refresh();
    }

    /** Balances detail "Send" jumps here with the token preselected in quick mode. */
    public void preselectToken(String tokenid) {
        quickTokenid = (tokenid == null || tokenid.isEmpty()) ? Util.MINIMA_TOKENID : tokenid;
        setMode(true);
    }

    /** Rebuilds the mode-dependent blocks; gates burn; prefills change (expert mode). */
    @Override
    public void refresh() {
        tokenBlock.setVisibility(quickMode ? View.VISIBLE : View.GONE);
        splitBlock.setVisibility(quickMode ? View.VISIBLE : View.GONE);
        fromBlock.setVisibility(quickMode ? View.GONE : View.VISIBLE);
        changeBlock.setVisibility(quickMode ? View.GONE : View.VISIBLE);

        if (quickMode) {
            TokenBalance b = quickToken();
            if (b == null && !Util.isMinima(quickTokenid)) {
                // token vanished from balances (e.g. fully sent) — fall back to Minima
                quickTokenid = Util.MINIMA_TOKENID;
                b = quickToken();
            }
            String name = b == null ? "Minima" : displayName(b);
            tokenLabel.setText(name);
            tokenBal.setText(b == null ? "" : Format.amount(b.sendable));
            boolean minima = Util.isMinima(quickTokenid);
            burnInput.setEnabled(true);   // burn rides alongside any send in quick mode
            burnNote.setVisibility(View.GONE);
            previewBtn.setEnabled(!sending);
            if (!minima && b != null && b.isNft()) {
                setNote(splitNote, "NFTs are indivisible — split does not apply.", R.color.ux_subtext);
            } else {
                splitNote.setVisibility(View.GONE);
            }
            return;
        }

        List<Coin> sel = act.selectedCoins();
        fromList.removeAllViews();

        if (sel.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("Select coins in the Coins tab, then come here to send.");
            t.setTextColor(Design.dim());
            t.setTextSize(13f);
            fromList.addView(t);
            previewBtn.setEnabled(false);
            return;
        }
        previewBtn.setEnabled(!sending);

        String tokenName = sel.get(0).tokenName;
        boolean minima = Util.isMinima(act.selectedTokenid());

        // FROM list: one row per selected input coin, accumulating the spendable total as we go.
        BigDecimal total = BigDecimal.ZERO;
        for (Coin c : sel) {
            try { total = total.add(new BigDecimal(c.amount)); } catch (Exception ignored) {}
            fromList.addView(row("• " + Util.shorten(c.coinid), Util.tidyAmount(c.amount), false));
        }
        View totalRow = row("Total in", Util.tidyAmount(total.toPlainString()) + " " + tokenName, true);
        fromList.addView(totalRow);

        // In expert mode burn is spent from the selected Minima coins, so it needs a Minima selection.
        burnInput.setEnabled(minima);
        if (!minima) {
            burnInput.setText("");
            setNote(burnNote, "Burn is only for Minima sends.", R.color.ux_subtext);
        } else {
            burnNote.setVisibility(View.GONE);
        }

        prefillChangeIfNeeded();
    }

    /** Snapshot the typed fields — used to survive a theme recreate(). */
    public String[] fieldValues() {
        return new String[]{ addrInput.getText().toString(), amountInput.getText().toString(),
                changeInput.getText().toString(), burnInput.getText().toString(),
                splitInput.getText().toString(), quickMode ? "q" : "c", quickTokenid };
    }

    /** Restore a fieldValues() snapshot after recreate(). */
    public void setFieldValues(String[] v) {
        if (v == null || v.length < 4) return;
        addrInput.setText(v[0]); amountInput.setText(v[1]);
        changeInput.setText(v[2]); burnInput.setText(v[3]);
        if (v.length >= 7) {
            splitInput.setText(v[4]);
            quickMode = !"c".equals(v[5]);
            quickTokenid = v[6] == null || v[6].isEmpty() ? Util.MINIMA_TOKENID : v[6];
            paintModeSegment();
            refresh();   // re-apply mode block visibilities after a theme recreate()
        }
    }

    // ----- quick mode: token picker -----

    private TokenBalance quickToken() {
        for (TokenBalance b : act.balances()) if (b.tokenid.equals(quickTokenid)) return b;
        return null;
    }

    private String displayName(TokenBalance b) {
        String n = b.name == null || b.name.isEmpty() ? "Token" : b.name;
        if (b.meta != null && b.meta.ticker != null && !b.meta.ticker.isEmpty()
                && !b.meta.ticker.equalsIgnoreCase(n)) n = n + "  ·  " + b.meta.ticker.toUpperCase();
        return n;
    }

    /** Theme-styled token chooser listing every balance with its sendable amount. */
    private void showTokenPicker() {
        final List<TokenBalance> all = new ArrayList<>(act.balances());
        if (all.isEmpty()) { status("No balances loaded yet.", false); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        final Sheet pick = Sheet.create(act, "Send which token?");
        pick.body(box);
        pick.action("Cancel", Sheet.Style.SECONDARY, null);
        for (final TokenBalance b : all) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(12), dp(12), dp(12));
            TextView n = new TextView(act);
            n.setText(displayName(b));
            n.setTextColor(Design.text()); n.setTextSize(14f); n.setTypeface(Design.typefaceBold());
            TextView amt = new TextView(act);
            amt.setText(Format.amount(b.sendable));
            amt.setTextColor(Design.dim()); amt.setTextSize(13f);
            amt.setTypeface(android.graphics.Typeface.MONOSPACE);
            amt.setGravity(Gravity.END);
            r.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(amt);
            r.setOnClickListener(v -> {
                quickTokenid = b.tokenid;
                pick.dismiss();
                refresh();
            });
            box.addView(r);
        }
        pick.show();
    }

    // ----- field helpers -----

    /** Fills the amount with the full spendable total; for Minima the burn is reserved first. */
    private void onMax() {
        BigDecimal burn = parseBurn();
        if (quickMode) {
            TokenBalance b = quickToken();
            if (b == null) return;
            BigDecimal total;
            try { total = new BigDecimal(b.sendable); } catch (Exception e) { return; }
            BigDecimal max = Util.isMinima(quickTokenid) && burn.signum() > 0 ? total.subtract(burn) : total;
            if (max.signum() < 0) max = BigDecimal.ZERO;
            amountInput.setText(Util.tidyAmount(max.toPlainString()));
            return;
        }
        List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) return;
        BigDecimal total = sum(sel);
        // Minima: max = total − burn (burn is spent from the same coins); other tokens: whole total.
        BigDecimal max = Util.isMinima(act.selectedTokenid()) ? total.subtract(burn) : total;
        if (max.signum() < 0) max = BigDecimal.ZERO;
        amountInput.setText(Util.tidyAmount(max.toPlainString()));
    }

    /** Auto-fills the change address once, unless the user has edited it or a fetch is in flight. */
    private void prefillChangeIfNeeded() {
        if (changeTouched || changeFetching) return;
        if (!changeInput.getText().toString().trim().isEmpty()) return;
        fetchChange(false);
    }

    /**
     * Rotate: fetch the node's next default address into the change field.
     * userInitiated true = the NEXT button (a deliberate rotation, so it counts as "touched");
     * false = the silent prefill (stays a default, so prefill can still re-run later).
     */
    private void fetchChange(boolean userInitiated) {
        if (act.selectedCoins().isEmpty()) return;
        changeFetching = true;
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                changeFetching = false;
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (!addr.isEmpty()) {
                    settingChange = true;
                    changeInput.setText(addr);
                    settingChange = false;
                    if (!userInitiated) changeTouched = false;   // still a default
                }
            }
            @Override public void onError(String message) { changeFetching = false; }
        });
    }

    // ----- preview / validate -----

    /** Validates every field, then asks the node to confirm the recipient before Confirm. */
    private void onPreview() {
        if (quickMode) { onPreviewQuick(); return; }

        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status("Select coins in the Coins tab first.", false); return; }

        final String recipient = addrInput.getText().toString().trim();
        if (recipient.isEmpty()) { setNote(addrNote, "Enter a recipient address.", R.color.ux_error); return; }
        if (!Util.isValidAddress(recipient)) { setNote(addrNote, "Invalid address format.", R.color.ux_error); return; }
        addrNote.setVisibility(View.GONE);

        final String tokenid = act.selectedTokenid();
        final boolean minima = Util.isMinima(tokenid);
        final String tokenName = sel.get(0).tokenName;
        final BigDecimal total = sum(sel);
        final BigDecimal burn = minima ? parseBurn() : BigDecimal.ZERO;
        if (burn.signum() < 0) { setNote(burnNote, "Invalid burn amount.", R.color.ux_error); return; }
        burnNote.setVisibility(minima ? View.GONE : burnNote.getVisibility());

        BigDecimal maxSpendable = minima ? total.subtract(burn) : total;
        if (maxSpendable.signum() <= 0) { setNote(amountNote, "Burn exceeds total — nothing left to send.", R.color.ux_error); return; }

        final BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setNote(amountNote, "Enter a valid amount.", R.color.ux_error); return; }
        if (amount.signum() <= 0) { setNote(amountNote, "Amount must be greater than zero.", R.color.ux_error); return; }
        if (amount.compareTo(maxSpendable) > 0) { setNote(amountNote, "Max sendable is " + Util.tidyAmount(maxSpendable.toPlainString()) + ".", R.color.ux_error); return; }
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(amount) > dec) { setNote(amountNote, "This token supports at most " + dec + " decimals.", R.color.ux_error); return; }
        amountNote.setVisibility(View.GONE);

        // Change = inputs − amount − burn; only require/validate a change address when there's any left.
        final BigDecimal change = total.subtract(amount).subtract(burn);
        final boolean hasChange = change.signum() > 0;
        final String changeStr = hasChange ? change.stripTrailingZeros().toPlainString() : null;
        final String changeAddr = changeInput.getText().toString().trim();
        if (hasChange) {
            if (changeAddr.isEmpty()) { setNote(changeNote, "Change address required.", R.color.ux_error); return; }
            if (!Util.isValidAddress(changeAddr)) { setNote(changeNote, "Invalid change address.", R.color.ux_error); return; }
        }
        changeNote.setVisibility(View.GONE);

        final String burnStr = burn.signum() > 0 ? burn.stripTrailingZeros().toPlainString() : null;

        // Confirm the recipient with the node before showing the breakdown.
        previewBtn.setEnabled(false);
        status("Checking address…", true);
        act.node().cmd("checkaddress address:" + recipient, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                previewBtn.setEnabled(true);
                if (!json.optBoolean("status", false)) {
                    setNote(addrNote, "That isn't a valid Minima address.", R.color.ux_error);
                    statusView.setVisibility(View.GONE);
                    return;
                }
                statusView.setVisibility(View.GONE);
                showConfirm(sel, recipient, amount.stripTrailingZeros().toPlainString(),
                        hasChange ? changeAddr : null, changeStr, burnStr, tokenid, tokenName, total);
            }
            @Override public void onError(String message) {
                previewBtn.setEnabled(true);
                setNote(addrNote, NodeApi.ERR_NOT_ENABLED.equals(message)
                        ? "Enable this wallet in Minima Core → Apps first." : "Could not validate address.", R.color.ux_error);
                statusView.setVisibility(View.GONE);
            }
        });
    }

    /** Quick-mode validation → checkaddress → Confirm dialog → node `send`. */
    private void onPreviewQuick() {
        final TokenBalance tok = quickToken();
        final boolean minima = Util.isMinima(quickTokenid);
        if (tok == null && !minima) { status("Pick a token first.", false); return; }

        final String recipient = addrInput.getText().toString().trim();
        if (recipient.isEmpty()) { setNote(addrNote, "Enter a recipient address.", R.color.ux_error); return; }
        if (!Util.isValidAddress(recipient)) { setNote(addrNote, "Invalid address format.", R.color.ux_error); return; }
        addrNote.setVisibility(View.GONE);

        final BigDecimal burn = parseBurn();
        if (burn.signum() < 0) { setNote(burnNote, "Invalid burn amount.", R.color.ux_error); return; }
        burnNote.setVisibility(View.GONE);

        final BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setNote(amountNote, "Enter a valid amount.", R.color.ux_error); return; }
        if (amount.signum() <= 0) { setNote(amountNote, "Amount must be greater than zero.", R.color.ux_error); return; }
        BigDecimal sendable = BigDecimal.ZERO;
        try { if (tok != null) sendable = new BigDecimal(tok.sendable); } catch (Exception ignored) {}
        // For Minima the burn is spent from the same balance — amount + burn must fit.
        BigDecimal needed = minima ? amount.add(burn) : amount;
        if (tok != null && needed.compareTo(sendable) > 0) {
            setNote(amountNote, minima && burn.signum() > 0
                    ? "Amount + burn exceeds sendable (" + Util.tidyAmount(tok.sendable) + ")."
                    : "Max sendable is " + Util.tidyAmount(tok.sendable) + ".", R.color.ux_error); return;
        }
        int dec = act.tokenDecimals(quickTokenid);
        if (dec >= 0 && Util.decimalPlaces(amount) > dec) { setNote(amountNote, "This token supports at most " + dec + " decimals.", R.color.ux_error); return; }
        amountNote.setVisibility(View.GONE);

        final int split = parseSplit();
        if (split < 1 || split > 20) { setNote(splitNote, "Split must be between 1 and 20.", R.color.ux_error); return; }
        splitNote.setVisibility(View.GONE);

        final String tokenName = tok == null ? "Minima" : tok.name;
        final String amountStr = amount.stripTrailingZeros().toPlainString();
        final String burnStr = burn.signum() > 0 ? burn.stripTrailingZeros().toPlainString() : null;

        previewBtn.setEnabled(false);
        status("Checking address…", true);
        act.node().cmd("checkaddress address:" + recipient, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                previewBtn.setEnabled(true);
                statusView.setVisibility(View.GONE);
                if (!json.optBoolean("status", false)) {
                    setNote(addrNote, "That isn't a valid Minima address.", R.color.ux_error);
                    return;
                }
                showQuickConfirm(recipient, amountStr, burnStr, split, tokenName);
            }
            @Override public void onError(String message) {
                previewBtn.setEnabled(true);
                setNote(addrNote, NodeApi.ERR_NOT_ENABLED.equals(message)
                        ? "Enable this wallet in Minima Core → Apps first." : "Could not validate address.", R.color.ux_error);
                statusView.setVisibility(View.GONE);
            }
        });
    }

    /** Quick-mode confirm: recipient / amount / burn / split breakdown before the node send. */
    private void showQuickConfirm(final String recipient, final String amountStr,
                                  final String burnStr, final int split, final String tokenName) {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Design.bg());
        int pad = dp(18);
        body.setPadding(pad, dp(8), pad, 0);

        body.addView(sectionLabel("SEND"));
        body.addView(row("Token", tokenName, true));
        body.addView(row("→ " + Util.shorten(recipient) + (isMine(recipient) ? "  (yours)" : ""),
                Util.tidyAmount(amountStr) + " " + tokenName, false));
        if (burnStr != null) body.addView(row("🔥 burn", Util.tidyAmount(burnStr) + " MINIMA", false));
        if (split > 1) body.addView(row("Split", split + " equal coins", false));
        body.addView(row("Coins", "chosen by the node", false));

        TextView warn = new TextView(act);
        warn.setText("Once posted, this cannot be undone. Verify the recipient address before continuing.");
        warn.setTextColor(Design.red());
        warn.setTextSize(12f);
        warn.setPadding(0, dp(14), 0, 0);
        body.addView(warn);

        Sheet.create(act, "Confirm transaction")
                .body(body)
                .action("Back", Sheet.Style.SECONDARY, null)
                .action("Send →", Sheet.Style.PRIMARY,
                        () -> runQuickSend(recipient, amountStr, burnStr, split, tokenName))
                .show();
    }

    /** Posts the node-level `send` and reports the outcome. */
    private void runQuickSend(final String recipient, final String amountStr,
                              final String burnStr, final int split, final String tokenName) {
        sending = true;
        previewBtn.setEnabled(false);
        status("Sending…", true);
        // The tokenid rides straight into the command. Balance rows are vetted at ingestion, but
        // this is the one place a bad id would become a chained command — never take it on trust.
        if (!Util.isValidHexId(quickTokenid)) {
            sending = false;
            previewBtn.setEnabled(true);
            status("That token's id is malformed — refusing to build the command.", false);
            return;
        }
        StringBuilder cmd = new StringBuilder("send address:").append(recipient)
                .append(" amount:").append(amountStr)
                .append(" tokenid:").append(quickTokenid);
        if (burnStr != null) cmd.append(" burn:").append(burnStr);
        if (split > 1) cmd.append(" split:").append(split);
        act.node().cmd(cmd.toString(), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                sending = false;
                previewBtn.setEnabled(true);
                if (json.optBoolean("status", false)) {
                    status("✓ Sent — " + Util.tidyAmount(amountStr) + " " + tokenName + " to "
                            + Util.shorten(recipient) + ".", true);
                    addrInput.setText("");
                    amountInput.setText("");
                    burnInput.setText("");
                    splitInput.setText("");
                    act.reload();
                } else {
                    String err = json.optString("error", json.optString("message", "Send failed."));
                    status("Failed: " + err, false);
                }
            }
            @Override public void onError(String message) {
                sending = false;
                previewBtn.setEnabled(true);
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                status("Failed: " + message, false);
            }
        });
    }

    // ----- expert confirm breakdown -----

    /** The Confirm step: a read-only Inputs / Outputs / Change / Burn breakdown before signing. */
    private void showConfirm(final List<Coin> sel, final String recipient, final String amountStr,
                             final String changeAddr, final String changeStr, final String burnStr,
                             final String tokenid, final String tokenName, BigDecimal total) {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Design.bg());   // match the current theme (dialog frame is fixed; content must adapt)
        int pad = dp(18);
        body.setPadding(pad, dp(8), pad, 0);

        body.addView(sectionLabel("INPUTS"));
        for (Coin c : sel) body.addView(row("• " + Util.shorten(c.coinid), Util.tidyAmount(c.amount), false));
        body.addView(row("Total in", Util.tidyAmount(total.toPlainString()) + " " + tokenName, true));

        body.addView(sectionLabel("OUTPUTS"));
        body.addView(row("→ " + Util.shorten(recipient) + (isMine(recipient) ? "  (yours)" : ""),
                Util.tidyAmount(amountStr) + " " + tokenName, false));
        if (changeStr != null) {
            // Flag a change address we don't recognise as ours with "(verify)" — guards against typos
            // in a hand-edited change field sending the remainder to a stranger.
            body.addView(row("↩ " + Util.shorten(changeAddr) + (isMine(changeAddr) ? "  (yours)" : "  (verify)"),
                    Util.tidyAmount(changeStr) + " " + tokenName, false));
        }
        if (burnStr != null) {
            body.addView(row("🔥 burn", Util.tidyAmount(burnStr) + " MINIMA", false));
        }

        TextView warn = new TextView(act);
        warn.setText("Once signed and posted, this cannot be undone. Verify the recipient address before continuing.");
        warn.setTextColor(Design.red());
        warn.setTextSize(12f);
        warn.setPadding(0, dp(14), 0, 0);
        body.addView(warn);

        Sheet.create(act, "Confirm transaction")
                .body(body)
                .action("Back", Sheet.Style.SECONDARY, null)
                .action("Sign & Post →", Sheet.Style.PRIMARY, () ->
                        buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName))
                .show();
    }

    /** Records a History row, builds/signs/posts the txn, then updates that row by outcome. */
    private void buildSend(List<Coin> sel, final String recipient, final String amountStr,
                           String changeAddr, String changeStr, String burnStr,
                           final String tokenid, final String tokenName) {
        sending = true;
        previewBtn.setEnabled(false);
        status("Recording audit row…", true);

        // Outputs = recipient, plus the change output when there's any change to return.
        List<TxnBuilder.Out> outs = new ArrayList<>();
        outs.add(new TxnBuilder.Out(recipient, amountStr));
        if (changeStr != null && changeAddr != null && !changeAddr.isEmpty()) {
            outs.add(new TxnBuilder.Out(changeAddr, changeStr));
        }
        // Persist the inputs/outputs to History up-front (status PENDING) so the send is auditable
        // even if signing/posting fails; internalid links this row to the outcome callbacks below.
        final String internalid = TxnUtil.recordPosting(act, recipient, amountStr, tokenid, tokenName,
                sel, outs, (changeStr != null ? changeAddr : null), burnStr);

        new TxnBuilder(act, sel, outs, tokenid, burnStr, new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid, List<TxnBuilder.OutCoin> outputs) {
                // Do NOT store the txnsign-response txpowid — it is not the on-chain id. The resolver
                // fills the real txpowid (and marks confirmed) once the transaction is mined.
                act.history().update(internalid, HistoryDb.STATUS_POSTED, null, "");
                status("✓ Posted — " + Util.tidyAmount(amountStr) + " " + tokenName
                        + " sent. The Explorer link appears in History once it confirms on-chain.", true);
                addrInput.setText("");
                amountInput.setText("");
                burnInput.setText("");
                settingChange = true; changeInput.setText(""); settingChange = false;
                changeTouched = false;
                sending = false;
                previewBtn.setEnabled(true);
                act.clearSelection();
                act.reload();
            }
            @Override public void onFailed(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "Enable this wallet in Minima Core → Apps first.";
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status("Failed: " + message, false);
                sending = false;
                previewBtn.setEnabled(true);
            }
        }).onProgress(label -> status(label, true)).run();
    }

    // ----- small helpers -----

    /** Builds a monospaced left-label / right-value row; bold marks a total/heading. */
    private View row(String left, String right, boolean bold) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));

        TextView l = new TextView(act);
        l.setText(left);
        l.setTextColor(bold ? Design.text() : Design.dim());
        l.setTextSize(12f);
        l.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(l);

        TextView rt = new TextView(act);
        rt.setText(right);
        rt.setTextColor(Design.text());
        rt.setTextSize(12f);
        rt.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        r.addView(rt);
        return r;
    }

    /** Builds a small dim, letter-spaced section heading (INPUTS / OUTPUTS) for the Confirm dialog. */
    private TextView sectionLabel(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    /** True if the address (hex or miniaddress form) belongs to this wallet. */
    private boolean isMine(String addr) {
        for (String[] a : act.myAddresses()) if (addr.equals(a[0]) || addr.equals(a[1])) return true;
        return false;
    }

    /** Sums coin amounts, skipping any that fail to parse. */
    private BigDecimal sum(List<Coin> coins) {
        BigDecimal t = BigDecimal.ZERO;
        for (Coin c : coins) { try { t = t.add(new BigDecimal(c.amount)); } catch (Exception ignored) {} }
        return t;
    }

    /** Parses the burn field: empty = 0; unparseable = -1 (a sentinel callers reject as invalid). */
    private BigDecimal parseBurn() {
        String s = burnInput.getText().toString().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (Exception e) { return new BigDecimal("-1"); }
    }

    /** Parses the split field: empty = 1; unparseable = -1 (rejected). */
    private int parseSplit() {
        String s = splitInput.getText().toString().trim();
        if (s.isEmpty()) return 1;
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    /** Shows an inline validation note under a field; colors track the active Design mode. */
    private void setNote(TextView note, String msg, int colorRes) {
        note.setVisibility(View.VISIBLE);
        note.setText(msg);
        note.setTextColor(colorRes == R.color.ux_error ? Design.red() : Design.dim());
    }

    /** Shows the bottom status line; green on ok, red on failure. */
    private void status(String msg, boolean ok) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(msg);
        statusView.setTextColor(ok ? Design.success() : Design.red());
    }

    /** Converts density-independent pixels to raw pixels for this device. */
    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
