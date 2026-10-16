package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

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
        if (act.getResources().getConfiguration().fontScale > 1.2f) {
            LinearLayout modes = (LinearLayout) modeQuick.getParent();
            modes.setOrientation(LinearLayout.VERTICAL);
            for (TextView label : new TextView[]{modeQuick, modeCoins})
                label.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        }
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
            CharSequence name = b == null ? "Minima" : displayName(b);
            Language.setText(tokenLabel, name);
            Language.setText(tokenBal, b == null ? "" : Format.amount(b.sendable));
            boolean minima = Util.isMinima(quickTokenid);
            burnInput.setEnabled(true);   // burn rides alongside any send in quick mode
            burnNote.setVisibility(View.GONE);
            previewBtn.setEnabled(!sending);
            if (!minima && b != null && b.isNft()) {
                setNote(splitNote, msg(R.string.l10n_node_send_nft_no_split), R.color.ux_subtext);
            } else {
                splitNote.setVisibility(View.GONE);
            }
            return;
        }

        List<Coin> sel = act.selectedCoins();
        fromList.removeAllViews();

        if (sel.isEmpty()) {
            TextView t = new TextView(act);
            Language.setText(t, msg(R.string.l10n_node_send_select_coins_help));
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
        View totalRow = row(msg(R.string.l10n_node_send_total_in), Util.tidyAmount(total.toPlainString()) + " " + tokenName, true);
        fromList.addView(totalRow);

        // In expert mode burn is spent from the selected Minima coins, so it needs a Minima selection.
        burnInput.setEnabled(minima);
        if (!minima) {
            burnInput.setText("");
            setNote(burnNote, msg(R.string.l10n_node_send_burn_minima_only), R.color.ux_subtext);
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

    private CharSequence displayName(TokenBalance b) {
        CharSequence name = Util.displayName(b);
        if (b.meta != null && b.meta.ticker != null && !b.meta.ticker.isEmpty()
                && !b.meta.ticker.equalsIgnoreCase(b.name))
            return Language.join(name, "  ·  ", b.meta.ticker.toUpperCase(java.util.Locale.ROOT));
        return name;
    }

    /** Theme-styled token chooser listing every balance with its sendable amount. */
    private void showTokenPicker() {
        final List<TokenBalance> all = new ArrayList<>(act.balances());
        if (all.isEmpty()) { status(msg(R.string.l10n_node_send_no_balances), false); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        final Sheet pick = Sheet.create(act, msg(R.string.l10n_node_send_pick_token));
        pick.body(box);
        pick.action(msg(R.string.l10n_common_cancel), Sheet.Style.SECONDARY, null);
        for (final TokenBalance b : all) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(12), dp(12), dp(12));
            TextView n = new TextView(act);
            Language.setText(n, displayName(b));
            n.setTextColor(Design.text()); n.setTextSize(14f); n.setTypeface(Design.typefaceBold());
            TextView amt = new TextView(act);
            Language.setText(amt, Format.amount(b.sendable));
            amt.setTextColor(Design.dim()); amt.setTextSize(13f);
            amt.setTypeface(android.graphics.Typeface.MONOSPACE);
            amt.setGravity(Gravity.END);
            r.addView(n, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            r.addView(amt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
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
        if (sel.isEmpty()) { status(msg(R.string.l10n_node_send_select_coins), false); return; }

        final String recipient = addrInput.getText().toString().trim();
        if (recipient.isEmpty()) { setNote(addrNote, msg(R.string.l10n_node_send_enter_recipient), R.color.ux_error); return; }
        if (!Util.isValidAddress(recipient)) { setNote(addrNote, msg(R.string.l10n_node_send_invalid_address), R.color.ux_error); return; }
        addrNote.setVisibility(View.GONE);

        final String tokenid = act.selectedTokenid();
        final boolean minima = Util.isMinima(tokenid);
        final String tokenName = sel.get(0).tokenName;
        final BigDecimal total = sum(sel);
        final BigDecimal burn = minima ? parseBurn() : BigDecimal.ZERO;
        if (burn.signum() < 0) { setNote(burnNote, msg(R.string.l10n_node_send_invalid_burn), R.color.ux_error); return; }
        burnNote.setVisibility(minima ? View.GONE : burnNote.getVisibility());

        BigDecimal maxSpendable = minima ? total.subtract(burn) : total;
        if (maxSpendable.signum() <= 0) { setNote(amountNote, msg(R.string.l10n_node_send_burn_exceeds), R.color.ux_error); return; }

        final BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setNote(amountNote, msg(R.string.l10n_node_send_valid_amount), R.color.ux_error); return; }
        if (amount.signum() <= 0) { setNote(amountNote, msg(R.string.l10n_node_send_positive_amount), R.color.ux_error); return; }
        if (amount.compareTo(maxSpendable) > 0) { setNote(amountNote, msg(R.string.l10n_node_send_max_sendable, Util.tidyAmount(maxSpendable.toPlainString())), R.color.ux_error); return; }
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(amount) > dec) { setNote(amountNote, Language.count(R.plurals.l10n_node_send_decimals, dec), R.color.ux_error); return; }
        amountNote.setVisibility(View.GONE);

        // Change = inputs − amount − burn; only require/validate a change address when there's any left.
        final BigDecimal change = total.subtract(amount).subtract(burn);
        final boolean hasChange = change.signum() > 0;
        final String changeStr = hasChange ? change.stripTrailingZeros().toPlainString() : null;
        final String changeAddr = changeInput.getText().toString().trim();
        if (hasChange) {
            if (changeAddr.isEmpty()) { setNote(changeNote, msg(R.string.l10n_node_send_change_required), R.color.ux_error); return; }
            if (!Util.isValidAddress(changeAddr)) { setNote(changeNote, msg(R.string.l10n_node_send_invalid_change), R.color.ux_error); return; }
        }
        changeNote.setVisibility(View.GONE);

        final String burnStr = burn.signum() > 0 ? burn.stripTrailingZeros().toPlainString() : null;

        // Confirm the recipient with the node before showing the breakdown.
        previewBtn.setEnabled(false);
        status(msg(R.string.l10n_node_send_checking), true);
        act.node().cmd("checkaddress address:" + recipient, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                previewBtn.setEnabled(true);
                if (!json.optBoolean("status", false)) {
                    setNote(addrNote, msg(R.string.l10n_node_send_not_minima_address), R.color.ux_error);
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
                        ? msg(R.string.l10n_node_send_pair_first) : msg(R.string.l10n_node_send_validate_failed), R.color.ux_error);
                statusView.setVisibility(View.GONE);
            }
        });
    }

    /** Quick-mode validation → checkaddress → Confirm dialog → node `send`. */
    private void onPreviewQuick() {
        final TokenBalance tok = quickToken();
        final boolean minima = Util.isMinima(quickTokenid);
        if (tok == null && !minima) { status(msg(R.string.l10n_node_send_pick_first), false); return; }

        final String recipient = addrInput.getText().toString().trim();
        if (recipient.isEmpty()) { setNote(addrNote, msg(R.string.l10n_node_send_enter_recipient), R.color.ux_error); return; }
        if (!Util.isValidAddress(recipient)) { setNote(addrNote, msg(R.string.l10n_node_send_invalid_address), R.color.ux_error); return; }
        addrNote.setVisibility(View.GONE);

        final BigDecimal burn = parseBurn();
        if (burn.signum() < 0) { setNote(burnNote, msg(R.string.l10n_node_send_invalid_burn), R.color.ux_error); return; }
        burnNote.setVisibility(View.GONE);

        final BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setNote(amountNote, msg(R.string.l10n_node_send_valid_amount), R.color.ux_error); return; }
        if (amount.signum() <= 0) { setNote(amountNote, msg(R.string.l10n_node_send_positive_amount), R.color.ux_error); return; }
        BigDecimal sendable = BigDecimal.ZERO;
        try { if (tok != null) sendable = new BigDecimal(tok.sendable); } catch (Exception ignored) {}
        // For Minima the burn is spent from the same balance — amount + burn must fit.
        BigDecimal needed = minima ? amount.add(burn) : amount;
        if (tok != null && needed.compareTo(sendable) > 0) {
            setNote(amountNote, minima && burn.signum() > 0
                    ? msg(R.string.l10n_node_send_amount_burn_exceeds, Util.tidyAmount(tok.sendable))
                    : msg(R.string.l10n_node_send_max_sendable, Util.tidyAmount(tok.sendable)), R.color.ux_error); return;
        }
        int dec = act.tokenDecimals(quickTokenid);
        if (dec >= 0 && Util.decimalPlaces(amount) > dec) { setNote(amountNote, Language.count(R.plurals.l10n_node_send_decimals, dec), R.color.ux_error); return; }
        amountNote.setVisibility(View.GONE);

        final int split = parseSplit();
        if (split < 1 || split > 20) { setNote(splitNote, msg(R.string.l10n_node_send_split_range), R.color.ux_error); return; }
        splitNote.setVisibility(View.GONE);

        final CharSequence tokenName = tok == null ? "Minima" : Util.displayName(tok);
        final String amountStr = amount.stripTrailingZeros().toPlainString();
        final String burnStr = burn.signum() > 0 ? burn.stripTrailingZeros().toPlainString() : null;

        previewBtn.setEnabled(false);
        status(msg(R.string.l10n_node_send_checking), true);
        act.node().cmd("checkaddress address:" + recipient, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                previewBtn.setEnabled(true);
                statusView.setVisibility(View.GONE);
                if (!json.optBoolean("status", false)) {
                    setNote(addrNote, msg(R.string.l10n_node_send_not_minima_address), R.color.ux_error);
                    return;
                }
                showQuickConfirm(recipient, amountStr, burnStr, split, tokenName);
            }
            @Override public void onError(String message) {
                previewBtn.setEnabled(true);
                setNote(addrNote, NodeApi.ERR_NOT_ENABLED.equals(message)
                        ? msg(R.string.l10n_node_send_pair_first) : msg(R.string.l10n_node_send_validate_failed), R.color.ux_error);
                statusView.setVisibility(View.GONE);
            }
        });
    }

    /** Quick-mode confirm: recipient / amount / burn / split breakdown before the node send. */
    private void showQuickConfirm(final String recipient, final String amountStr,
                                  final String burnStr, final int split, final CharSequence tokenName) {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Design.bg());
        int pad = dp(18);
        body.setPadding(pad, dp(8), pad, 0);

        body.addView(sectionLabel(Language.upper(msg(R.string.l10n_chat_send))));
        body.addView(row(msg(R.string.l10n_wallet_token), tokenName, true));
        body.addView(row(Language.join("→ ", Util.shorten(recipient), isMine(recipient) ? Language.join("  ", msg(R.string.l10n_node_send_yours)) : ""),
                Language.join(Util.tidyAmount(amountStr), " ", tokenName), false));
        if (burnStr != null) body.addView(row(msg(R.string.l10n_node_send_burn), Util.tidyAmount(burnStr) + " MINIMA", false));
        if (split > 1) body.addView(row(msg(R.string.l10n_node_send_split), Language.count(R.plurals.l10n_node_send_equal_coins, split), false));
        body.addView(row(msg(R.string.l10n_wallet_coins), msg(R.string.l10n_node_send_node_chooses), false));

        TextView warn = new TextView(act);
        Language.setText(warn, msg(R.string.l10n_node_send_quick_warning));
        warn.setTextColor(Design.red());
        warn.setTextSize(12f);
        warn.setPadding(0, dp(14), 0, 0);
        body.addView(warn);

        Sheet.create(act, msg(R.string.l10n_node_send_confirm))
                .body(body)
                .action(msg(R.string.l10n_common_back), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_send_send), Sheet.Style.PRIMARY,
                        () -> runQuickSend(recipient, amountStr, burnStr, split, tokenName))
                .show();
    }

    /** Posts the node-level `send` and reports the outcome. */
    private void runQuickSend(final String recipient, final String amountStr,
                              final String burnStr, final int split, final CharSequence tokenName) {
        sending = true;
        previewBtn.setEnabled(false);
        status(msg(R.string.l10n_node_send_sending), true);
        // The tokenid rides straight into the command. Balance rows are vetted at ingestion, but
        // this is the one place a bad id would become a chained command — never take it on trust.
        if (!Util.isValidHexId(quickTokenid)) {
            sending = false;
            previewBtn.setEnabled(true);
            status(msg(R.string.l10n_node_send_bad_token_id), false);
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
                    status(msg(R.string.l10n_node_send_sent, Util.tidyAmount(amountStr), tokenName, Util.shorten(recipient)), true);
                    addrInput.setText("");
                    amountInput.setText("");
                    burnInput.setText("");
                    splitInput.setText("");
                    act.reload();
                } else {
                    String err = json.optString("error", json.optString("message", ""));
                    status(msg(R.string.l10n_node_send_failed, err.isEmpty() ? msg(R.string.l10n_cloud_wallet_send_failed) : err), false);
                }
            }
            @Override public void onError(String message) {
                sending = false;
                previewBtn.setEnabled(true);
                status(msg(R.string.l10n_node_send_failed, NodeApi.ERR_NOT_ENABLED.equals(message) ? msg(R.string.l10n_node_send_pair_first) : message), false);
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

        body.addView(sectionLabel(Language.upper(msg(R.string.l10n_node_history_inputs))));
        for (Coin c : sel) body.addView(row("• " + Util.shorten(c.coinid), Util.tidyAmount(c.amount), false));
        body.addView(row(msg(R.string.l10n_node_send_total_in), Util.tidyAmount(total.toPlainString()) + " " + tokenName, true));

        body.addView(sectionLabel(Language.upper(msg(R.string.l10n_node_history_outputs))));
        body.addView(row(Language.join("→ ", Util.shorten(recipient), isMine(recipient) ? Language.join("  ", msg(R.string.l10n_node_send_yours)) : ""),
                Language.join(Util.tidyAmount(amountStr), " ", tokenName), false));
        if (changeStr != null) {
            // Flag a change address we don't recognise as ours with "(verify)" — guards against typos
            // in a hand-edited change field sending the remainder to a stranger.
            body.addView(row(Language.join("↩ ", Util.shorten(changeAddr), "  ", isMine(changeAddr) ? msg(R.string.l10n_node_send_yours) : msg(R.string.l10n_node_send_verify)),
                    Util.tidyAmount(changeStr) + " " + tokenName, false));
        }
        if (burnStr != null) {
            body.addView(row(msg(R.string.l10n_node_send_burn), Util.tidyAmount(burnStr) + " MINIMA", false));
        }

        TextView warn = new TextView(act);
        Language.setText(warn, msg(R.string.l10n_node_send_sign_warning));
        warn.setTextColor(Design.red());
        warn.setTextSize(12f);
        warn.setPadding(0, dp(14), 0, 0);
        body.addView(warn);

        Sheet.create(act, msg(R.string.l10n_node_send_confirm))
                .body(body)
                .action(msg(R.string.l10n_common_back), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_send_sign_post), Sheet.Style.PRIMARY, () ->
                        buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName))
                .show();
    }

    /** Records a History row, builds/signs/posts the txn, then updates that row by outcome. */
    private void buildSend(List<Coin> sel, final String recipient, final String amountStr,
                           String changeAddr, String changeStr, String burnStr,
                           final String tokenid, final String tokenName) {
        sending = true;
        previewBtn.setEnabled(false);
        status(msg(R.string.l10n_node_send_recording), true);

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
                status(msg(R.string.l10n_node_send_posted, Util.tidyAmount(amountStr), tokenName), true);
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
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status(msg(R.string.l10n_node_send_failed, NodeApi.ERR_NOT_ENABLED.equals(message) ? msg(R.string.l10n_node_send_pair_first) : message), false);
                sending = false;
                previewBtn.setEnabled(true);
            }
        }).onProgress(label -> status(label, true)).run();
    }

    // ----- small helpers -----

    /** Builds a monospaced left-label / right-value row; bold marks a total/heading. */
    private View row(CharSequence left, CharSequence right, boolean bold) {
        LinearLayout r = new LinearLayout(act);
        boolean stacked = act.getResources().getConfiguration().fontScale > 1.2f || right.length() > 14;
        r.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));

        TextView l = new TextView(act);
        Language.setText(l, left);
        l.setTextColor(bold ? Design.text() : Design.dim());
        l.setTextSize(12f);
        l.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(stacked ? LinearLayout.LayoutParams.MATCH_PARENT : 0,
                LinearLayout.LayoutParams.WRAP_CONTENT, stacked ? 0f : 1f));
        r.addView(l);

        TextView rt = new TextView(act);
        Language.setText(rt, right);
        rt.setTextColor(Design.text());
        rt.setTextSize(12f);
        rt.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        rt.setGravity(Gravity.END);
        r.addView(rt, stacked ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT) : new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** Builds a small dim, letter-spaced section heading (INPUTS / OUTPUTS) for the Confirm dialog. */
    private TextView sectionLabel(CharSequence text) {
        TextView t = new TextView(act);
        Language.setText(t, text);
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
    private void setNote(TextView note, CharSequence msg, int colorRes) {
        note.setVisibility(View.VISIBLE);
        Language.setText(note, msg);
        note.setTextColor(colorRes == R.color.ux_error ? Design.red() : Design.dim());
    }

    /** Shows the bottom status line; green on ok, red on failure. */
    private void status(CharSequence msg, boolean ok) {
        statusView.setVisibility(View.VISIBLE);
        Language.setText(statusView, msg);
        statusView.setTextColor(ok ? Design.success() : Design.red());
    }

    /** Converts density-independent pixels to raw pixels for this device. */
    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
