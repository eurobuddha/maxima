package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/**
 * Transfer and Bury for StateNFT unit coins — the statenft-suite flows, wallet-side.
 *
 * Hard rules honoured here (proven on-chain):
 *  - never {@code send} (script coins are sendable:0) — always the manual txn path;
 *  - replay EVERY state port verbatim with storestate:true (SAMESTATE);
 *  - refuse coins whose state fails {@link StateNft#replayableState} — hostile state could
 *    smuggle extra command parameters when replayed;
 *  - {@code txndelete} on every failure path (CmdChain cleanup);
 *  - never trust txnpost status — confirm by watching the input coin leave the UTXO set.
 */
public final class StateNftActions {

    private static final int WATCH_INTERVAL_MS = 20000;
    private static final int WATCH_TRIES = 20;

    private StateNftActions() {}

    // ===================== transfer =====================

    /** Ask for a recipient, confirm, then post the identity-preserving transfer. */
    public static void transferDialog(WalletActivity act, String tokenid, String displayName, JSONObject coin) {
        if (!idsSafe(act, tokenid, coin)) return;
        if (StateNft.stamped(coin) == null) {
            // An unstamped unit still carries the sentinel — the creator bypass is LIVE on it, so a
            // recipient would hold a coin the creator can rewrite; and an active mint's MOVE phase
            // would fight to pull it back. Only sealed identities leave the wallet.
            Sheet.create(act, msg(R.string.l10n_node_nft_cannot_transfer))
                .subtitle(msg(R.string.l10n_node_nft_unstamped_transfer))
                .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }
        if (!StateNft.replayableState(coin)) {
            Sheet.create(act, msg(R.string.l10n_node_nft_cannot_transfer))
                .subtitle(msg(R.string.l10n_node_nft_unsafe_transfer))
                .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, msg(R.string.l10n_node_nft_transfer_help));
        t.setTextColor(Design.dim());
        t.setTextSize(12f);
        box.addView(t);

        LinearLayout addrRow = new LinearLayout(act);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addrRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText addr = new EditText(act);
        Language.hint(addr, msg(R.string.l10n_node_nft_recipient));
        addr.setHintTextColor(Design.dim2());
        addr.setTextColor(Design.text());
        addr.setTextSize(13f);
        addr.setTypeface(android.graphics.Typeface.MONOSPACE);
        addr.setBackgroundColor(Design.surface2());
        addr.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        addr.setInputType(InputType.TYPE_CLASS_TEXT);
        addrRow.addView(addr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView scan = new TextView(act);
        scan.setText(" ⌗ ");
        Language.description(scan, msg(R.string.l10n_common_scan_qr));
        scan.setTextColor(Design.accent());
        scan.setTextSize(16f);
        scan.setOnClickListener(v -> act.scanQr(text -> { if (text != null) addr.setText(text.trim()); }));
        addrRow.addView(scan);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = dp(act, 10);
        addrRow.setLayoutParams(arlp);
        box.addView(addrRow);

        Sheet.create(act, msg(R.string.l10n_node_nft_transfer_title, displayName))
                .stackActions()
                .body(box)
                .action(msg(R.string.l10n_common_back), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_nft_transfer_action), Sheet.Style.PRIMARY, () -> {
                    String to = addr.getText().toString().trim();
                    if (!Util.isValidAddress(to)) {
                        android.widget.Toast.makeText(act, Language.text(act, R.string.l10n_node_send_not_minima_address),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runTransfer(act, tokenid, displayName, coin, to);
                })
                .show();
    }

    private static void runTransfer(WalletActivity act, String tokenid, String displayName,
                                    JSONObject coin, String to) {
        final String coinid = coin.optString("coinid", "");
        String txn = "tr" + shortId(coinid);
        List<String> cmds = StateNft.transferCommands(txn, tokenid, coin, to);
        final Sheet.Progress progress = progressDialog(act,
                msg(R.string.l10n_node_nft_transferring, displayName), msg(R.string.l10n_node_nft_building_transfer));
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                setMessage(progress, msg(R.string.l10n_node_nft_waiting_transfer));
                makeDismissable(progress);
                watchDeparture(act, tokenid, coinid, progress,
                        msg(R.string.l10n_node_nft_transferred, displayName, Util.shorten(to)));
            }
            @Override public void fail(String message) {
                setMessage(progress, msg(R.string.l10n_node_send_failed, message));
                makeDismissable(progress);
            }
        });
    }

    // ===================== bury =====================

    /** Typed-confirmation bury to the graveyard address (irreversible). Stamped units only. */
    public static void buryDialog(WalletActivity act, String tokenid, String collectionName,
                                  String displayName, JSONObject coin) {
        if (!idsSafe(act, tokenid, coin)) return;
        if (StateNft.stamped(coin) == null) {
            Sheet.create(act, msg(R.string.l10n_node_nft_cannot_bury))
                .subtitle(msg(R.string.l10n_node_nft_unstamped_bury))
                .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }
        if (!StateNft.replayableState(coin)) {
            Sheet.create(act, msg(R.string.l10n_node_nft_cannot_bury))
                .subtitle(msg(R.string.l10n_node_nft_unsafe_bury))
                .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, msg(R.string.l10n_node_nft_bury_warning, displayName, collectionName));
        t.setTextColor(Design.red());
        t.setTextSize(12f);
        box.addView(t);

        final EditText confirm = new EditText(act);
        confirm.setHint(collectionName);
        confirm.setHintTextColor(Design.dim2());
        confirm.setTextColor(Design.text());
        confirm.setTextSize(13f);
        confirm.setBackgroundColor(Design.surface2());
        confirm.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(act, 10);
        confirm.setLayoutParams(clp);
        box.addView(confirm);

        Sheet.create(act, msg(R.string.l10n_node_nft_bury_title, displayName))
                .stackActions()
                .body(box)
                .action(msg(R.string.l10n_common_back), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_nft_bury_forever), Sheet.Style.DANGER, () -> {
                    if (!confirm.getText().toString().trim().equals(collectionName)) {
                        android.widget.Toast.makeText(act, Language.text(act, R.string.l10n_node_nft_exact_name),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runBury(act, tokenid, displayName, coin);
                })
                .show();
    }

    private static void runBury(WalletActivity act, String tokenid, String displayName, JSONObject coin) {
        final String coinid = coin.optString("coinid", "");
        String txn = "by" + shortId(coinid);
        // Stamped unit: identity-preserving path, owner signature only (creatorPk empty).
        List<String> cmds = StateNft.buryCommands(txn, tokenid, "", coin, true);
        final Sheet.Progress progress = progressDialog(act,
                msg(R.string.l10n_node_nft_burying, displayName), msg(R.string.l10n_node_nft_building_bury));
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                setMessage(progress, msg(R.string.l10n_node_nft_waiting_bury));
                makeDismissable(progress);
                watchDeparture(act, tokenid, coinid, progress,
                        msg(R.string.l10n_node_nft_buried, displayName));
            }
            @Override public void fail(String message) {
                setMessage(progress, msg(R.string.l10n_node_send_failed, message));
                makeDismissable(progress);
            }
        });
    }

    // ===================== transfer a whole collection =====================

    /**
     * Send every item of a collection you hold to one address.
     *
     * This is ONE action but it is NOT one transaction, and it cannot be. Minima state is
     * per-TRANSACTION — a transaction carries a single state map — while every stamped item holds a
     * different index at port 0. The locked-edition script asserts SAMESTATE over that index, so
     * two items with different identities can never satisfy it in the same transaction. One txn per
     * item is the protocol, not an inefficiency in this wallet.
     *
     * So we do what the bury flow does: enumerate, then post in sequence with honest progress.
     */
    public static void transferCollectionDialog(final WalletActivity act, final String tokenid,
                                                final String collectionName, final Runnable onDone) {
        if (!Util.isValidHexId(tokenid)) {
            Sheet.create(act, msg(R.string.l10n_node_nft_refuse_collection))
                    .subtitle(msg(R.string.l10n_node_nft_bad_token_id))
                    .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, msg(R.string.l10n_node_nft_transfer_collection_help, collectionName));
        t.setTextColor(Design.dim());
        t.setTextSize(12f);
        box.addView(t);

        LinearLayout addrRow = new LinearLayout(act);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addrRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText addr = new EditText(act);
        Language.hint(addr, msg(R.string.l10n_node_nft_recipient));
        addr.setHintTextColor(Design.dim2());
        addr.setTextColor(Design.text());
        addr.setTextSize(13f);
        addr.setTypeface(android.graphics.Typeface.MONOSPACE);
        addr.setBackgroundColor(Design.surface2());
        addr.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        addr.setInputType(InputType.TYPE_CLASS_TEXT);
        addrRow.addView(addr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView scan = new TextView(act);
        scan.setText(" ⌗ ");
        Language.description(scan, msg(R.string.l10n_common_scan_qr));
        scan.setTextColor(Design.accent());
        scan.setTextSize(16f);
        scan.setOnClickListener(v -> act.scanQr(text -> { if (text != null) addr.setText(text.trim()); }));
        addrRow.addView(scan);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = dp(act, 10);
        addrRow.setLayoutParams(arlp);
        box.addView(addrRow);

        Sheet.create(act, msg(R.string.l10n_node_gallery_send_collection))
                .stackActions()
                .body(box)
                .action(msg(R.string.l10n_common_back), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_nft_send_all), Sheet.Style.PRIMARY, () -> {
                    String to = addr.getText().toString().trim();
                    if (!Util.isValidAddress(to)) {
                        android.widget.Toast.makeText(act, Language.text(act, R.string.l10n_node_send_not_minima_address),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    gatherAndTransfer(act, tokenid, collectionName, to, onDone);
                })
                .show();
    }

    private static void gatherAndTransfer(final WalletActivity act, final String tokenid,
                                          final String collectionName, final String to,
                                          final Runnable onDone) {
        final Sheet.Progress progress = progressDialog(act,
                msg(R.string.l10n_node_nft_sending, collectionName), msg(R.string.l10n_node_nft_finding_items));
        act.node().cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                org.json.JSONArray arr = json.optJSONArray("response");
                final java.util.List<JSONObject> coins = new java.util.ArrayList<>();
                int skipped = 0;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        // Same refusals as a single transfer: a malformed id or state this wallet
                        // will not replay, and unstamped units whose creator bypass is still live.
                        if (!Util.isValidHexId(c.optString("coinid", ""))
                                || !StateNft.replayableState(c)
                                || StateNft.stamped(c) == null) { skipped++; continue; }
                        coins.add(c);
                    }
                }
                if (coins.isEmpty()) {
                    setMessage(progress, skipped > 0
                            ? msg(R.string.l10n_node_nft_nothing_sendable, skipped)
                            : msg(R.string.l10n_node_nft_no_items));
                    makeDismissable(progress);
                    return;
                }
                transferNext(act, tokenid, to, coins, 0, new int[]{0}, skipped, new String[]{null},
                        progress, onDone);
            }
            @Override public void onError(String message) {
                setMessage(progress, NodeApi.ERR_TOO_LONG.equals(message)
                        ? msg(R.string.l10n_node_nft_transfer_list_too_long)
                        : msg(R.string.l10n_node_nft_list_items_error, message));
                makeDismissable(progress);
            }
        });
    }

    private static void transferNext(final WalletActivity act, final String tokenid, final String to,
                                     final java.util.List<JSONObject> coins, final int i,
                                     final int[] done, final int skipped, final String[] firstError,
                                     final Sheet.Progress progress, final Runnable onDone) {
        if (i >= coins.size()) {
            int failed = coins.size() - done[0];
            setMessage(progress, Language.join(msg(R.string.l10n_node_nft_transfers_posted, done[0], coins.size()),
                    skipped > 0 ? Language.join(" ", msg(R.string.l10n_node_nft_skipped, skipped)) : "",
                    "\n\n", failed > 0 && firstError[0] != null
                        ? Language.join(msg(R.string.l10n_node_nft_failed, failed, firstError[0]), "\n\n") : "",
                    msg(R.string.l10n_node_nft_transfer_confirmations)));
            makeDismissable(progress);
            act.reload();
            if (onDone != null) onDone.run();
            return;
        }
        final JSONObject coin = coins.get(i);
        String idx = StateNft.stamped(coin);
        setMessage(progress, msg(R.string.l10n_node_nft_sending_item, i + 1, coins.size(), idx == null ? "" : "  (#" + idx + ")"));
        final String txn = "tc" + shortId(coin.optString("coinid", ""));
        java.util.List<String> cmds = StateNft.transferCommands(txn, tokenid, coin, to);
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                done[0]++;
                transferNext(act, tokenid, to, coins, i + 1, done, skipped, firstError, progress, onDone);
            }
            @Override public void fail(String message) {
                // One failure must not strand the rest — carry on and report the true tally,
                // keeping the first reason so the summary can say WHY, not just how many.
                if (firstError[0] == null && message != null && !message.isEmpty()) {
                    firstError[0] = message;
                }
                transferNext(act, tokenid, to, coins, i + 1, done, skipped, firstError, progress, onDone);
            }
        });
    }

    // ===================== bury a whole collection =====================

    /**
     * Bury EVERY coin of a collection you still hold — the escape hatch for a mint that went wrong
     * (a ruined image, a bad index) and can never be edited, only destroyed.
     *
     * Each coin is a separate transaction: stamped units take the identity-preserving path with the
     * owner signature, unstamped ones need the creator signature for the bypass. They are posted in
     * sequence rather than in parallel — one input coin each, so they cannot conflict, but serial
     * posting keeps the node's single command thread sane and lets us report honest progress.
     */
    public static void buryCollectionDialog(final WalletActivity act, final String tokenid,
                                            final String collectionName, final String creatorPk,
                                            final Runnable onDone) {
        if (!Util.isValidHexId(tokenid)) {
            Sheet.create(act, msg(R.string.l10n_node_nft_refuse_collection))
                .subtitle(msg(R.string.l10n_node_nft_bad_token_id))
                .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        int pad = dp(act, 18);
        box.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView t = new TextView(act);
        Language.setText(t, msg(R.string.l10n_node_nft_bury_collection_warning, collectionName));
        t.setTextColor(Design.red());
        t.setTextSize(12f);
        box.addView(t);

        final EditText confirm = new EditText(act);
        confirm.setHint(collectionName);
        confirm.setHintTextColor(Design.dim2());
        confirm.setTextColor(Design.text());
        confirm.setTextSize(13f);
        confirm.setBackgroundColor(Design.surface2());
        confirm.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(act, 10);
        confirm.setLayoutParams(clp);
        box.addView(confirm);

        Sheet.create(act, msg(R.string.l10n_node_nft_bury_collection_title))
                .stackActions()
                .body(box)
                .action(msg(R.string.l10n_common_back), Sheet.Style.SECONDARY, null)
                .action(msg(R.string.l10n_node_nft_bury_everything), Sheet.Style.DANGER, () -> {
                    if (!confirm.getText().toString().trim().equals(collectionName)) {
                        android.widget.Toast.makeText(act, Language.text(act, R.string.l10n_node_nft_exact_name),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    gatherAndBury(act, tokenid, collectionName, creatorPk, onDone);
                })
                .show();
    }

    private static void gatherAndBury(final WalletActivity act, final String tokenid,
                                      final String collectionName, final String creatorPk,
                                      final Runnable onDone) {
        final Sheet.Progress progress = progressDialog(act,
                msg(R.string.l10n_node_nft_burying, collectionName), msg(R.string.l10n_node_nft_finding_coins));
        act.node().cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                org.json.JSONArray arr = json.optJSONArray("response");
                final java.util.List<JSONObject> coins = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        if (!Util.isValidHexId(c.optString("coinid", ""))) continue;
                        if (!StateNft.replayableState(c)) continue;   // never replay hostile state
                        coins.add(c);
                    }
                }
                if (coins.isEmpty()) {
                    setMessage(progress, msg(R.string.l10n_node_nft_nothing_to_bury));
                    makeDismissable(progress);
                    if (onDone != null) onDone.run();
                    return;
                }
                buryNext(act, tokenid, creatorPk, coins, 0, new int[]{0}, progress, onDone);
            }
            @Override public void onError(String message) {
                setMessage(progress, NodeApi.ERR_TOO_LONG.equals(message)
                        ? msg(R.string.l10n_node_nft_bury_list_too_long)
                        : msg(R.string.l10n_node_nft_list_coins_error, message));
                makeDismissable(progress);
            }
        });
    }

    private static void buryNext(final WalletActivity act, final String tokenid, final String creatorPk,
                                 final java.util.List<JSONObject> coins, final int i, final int[] done,
                                 final Sheet.Progress progress, final Runnable onDone) {
        if (i >= coins.size()) {
            setMessage(progress, msg(R.string.l10n_node_nft_burials_posted, done[0], coins.size()));
            makeDismissable(progress);
            act.reload();
            if (onDone != null) onDone.run();
            return;
        }
        final JSONObject coin = coins.get(i);
        setMessage(progress, msg(R.string.l10n_node_nft_burying_item, i + 1, coins.size()));
        final String txn = "bc" + shortId(coin.optString("coinid", ""));
        // Stamped units preserve their identity into the graveyard; unstamped ones still carry the
        // sentinel, so they need the creator signature to satisfy the bypass.
        boolean stamped = StateNft.stamped(coin) != null;
        java.util.List<String> cmds = StateNft.buryCommands(
                txn, tokenid, stamped ? "" : creatorPk, coin, true);
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                done[0]++;
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, progress, onDone);
            }
            @Override public void fail(String message) {
                // One failure must not strand the rest — carry on and report the tally at the end.
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, progress, onDone);
            }
        });
    }

    // ===================== departure watch =====================

    /**
     * txnpost status:true is NOT proof — an invalid spend posts fine and is rejected on-chain.
     * The only proof is the input coin disappearing from {@code coins relevant:true tokenid:}.
     */
    private static void watchDeparture(WalletActivity act, String tokenid, String coinid,
                                       Sheet.Progress progress, CharSequence successMsg) {
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            // Stop when the activity is gone — a recreated activity confirms via balances anyway,
            // and polling a released NodeApi for 7 minutes just leaks the old activity + dialog.
            if (act.isFinishing() || act.isDestroyed()) return;
            act.node().cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                boolean present = false;
                org.json.JSONArray arr = json.optJSONArray("response");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c != null && coinid.equals(c.optString("coinid"))) { present = true; break; }
                    }
                }
                if (!present) {
                    setMessage(progress, successMsg);
                    makeDismissable(progress);
                    act.reload();
                    return;
                }
                reschedule();
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_TOO_LONG.equals(message)) {
                    // The reply was capped, so absence proves nothing — say so instead of
                    // polling to a false "may have been rejected".
                    setMessage(progress, msg(R.string.l10n_node_nft_cannot_confirm));
                    makeDismissable(progress);
                    act.reload();
                    return;
                }
                reschedule();
            }
            private void reschedule() {
                if (++tries[0] >= WATCH_TRIES) {
                    setMessage(progress, Language.count(R.plurals.l10n_node_nft_unconfirmed, WATCH_TRIES));
                    makeDismissable(progress);
                    return;
                }
                h.postDelayed(poll[0], WATCH_INTERVAL_MS);
            }
        });
        };
        h.postDelayed(poll[0], WATCH_INTERVAL_MS);
    }

    // ===================== plumbing =====================

    /**
     * Both ids are replayed into node commands, and both arrive as chain data — the node emits a
     * JSON-shaped token name unescaped, so a hostile token can inject synthetic id strings into a
     * balance/coins reply. Anything that isn't plain hex never reaches a command.
     */
    private static boolean idsSafe(WalletActivity act, String tokenid, JSONObject coin) {
        String coinid = coin == null ? "" : coin.optString("coinid", "");
        if (Util.isValidHexId(tokenid) && Util.isValidHexId(coinid)) return true;
        Sheet.create(act, msg(R.string.l10n_node_nft_refuse_coin))
                .subtitle(msg(R.string.l10n_node_nft_bad_coin_id))
                .action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null)
                .show();
        return false;
    }

    private static final NodeApi.Cb NOOP = new NodeApi.Cb() {
        @Override public void onResult(JSONObject json) {}
        @Override public void onError(String message) {}
    };

    private static Sheet.Progress progressDialog(WalletActivity act, CharSequence title, CharSequence msg) {
        return Sheet.progress(act, title, msg);
    }

    private static void setMessage(Sheet.Progress d, CharSequence msg) { d.text(msg); }

    /** The work is over — let the user close it, and give them the button. */
    private static void makeDismissable(Sheet.Progress d) { d.finish(); }

    private static String shortId(String coinid) {
        String c = coinid == null ? "" : coinid.replace("0x", "");
        return c.length() > 10 ? c.substring(0, 10) : c;
    }

    private static int dp(WalletActivity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
