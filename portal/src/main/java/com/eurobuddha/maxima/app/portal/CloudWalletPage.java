package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Page;
import com.eurobuddha.maxima.app.ui.Qr;
import com.eurobuddha.maxima.app.wallet.NodeLink;
import com.eurobuddha.maxima.app.wallet.WalletPublisher;
import com.eurobuddha.maxima.cloud.CloudWallet;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * The cloud account's WATCH-ONLY wallet. The node holds only a device-supplied public address and
 * reads its balance through the hosted gateway — it has no keys and cannot spend (funds stay COLD
 * on the device). So this tab shows a balance + a receive address, never a Send. Data over
 * {@link ParlonsRemote#walletAddress()} + {@link ParlonsRemote#balance()}; the watch address is set
 * with {@link ParlonsRemote#setWatch(String)}.
 */
public final class CloudWalletPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mRoot;
    private volatile boolean mBusy;
    private long mLastLoad;
    private boolean mBuilt;

    private String mAddress = "";
    /** From parlons.wallet.address: node accounts can move the wallet to a new phrase. */
    private boolean mCanResync;
    private String mResyncError = "";
    private String mConfirmed = "";
    private String mSendable = "";
    private String mError = "";
    private JSONArray mTokens;            // non-Minima tokens (from walletTokens)
    private int mUses = -1;               // one-time-signature counter (from walletUses)
    private int mMaxUses = CloudWallet.MAX_USES;

    // Broadcaster: sally SIGNS; THIS device relays the signed txn via its own minimaCore (else
    // gateway). The publisher tracks the account's public address so txnbasics has the proofs.
    private final WalletPublisher mPub;
    private final NodeLink mNode;
    private volatile boolean mPrepared;   // account address tracked on the local node yet

    public CloudWalletPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mRoot = zView.findViewById(R.id.wallet_container);
        mNode = WalletPublisher.coreInstalled(mAct) ? new NodeLink(mAct, enabled -> {}) : null;
        mPub = new WalletPublisher(mAct, mNode);
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return "Wallet";
    }

    @Override
    public void render() {
        if (mBusy) {
            return;
        }
        long now = System.currentTimeMillis();
        if (mBuilt && now - mLastLoad < 5000) {
            return;
        }
        // Never a blank tab: paint a loading card synchronously BEFORE the (multi-RPC) round-trip,
        // so the first frame after tab-select is always populated even while balance+tokens+uses
        // are still in flight.
        if (!mBuilt) {
            mRoot.removeAllViews();
            mRoot.addView(PortalUi.section(mAct, "Wallet"));
            LinearLayout loading = PortalUi.card(mAct);
            loading.addView(PortalUi.label(mAct, "Opening your account wallet…"));
            mRoot.addView(loading);
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String addr = "";
                String confirmed = "";
                String sendable = "";
                String error = "";
                boolean canResync = false;
                String resyncError = "";
                JSONArray tokens = null;
                int uses = -1, maxUses = CloudWallet.MAX_USES;
                try {
                    JSONObject a = r.walletAddress();
                    addr = str(a, "address");
                    canResync = Boolean.TRUE.equals(a.get("canResync"));
                    resyncError = str(a, "resyncError");
                    // Track the account's PUBLIC address on this device's minimaCore (script+hex
                    // from sally), so a signed txn from sally can be relayed here — txnbasics needs
                    // the coin proofs. Idempotent; back-fills historic coins the same way the app does.
                    String script = str(a, "script");
                    String hex = str(a, "hex");
                    if (!mPrepared && !script.isEmpty() && !hex.isEmpty()) {
                        mPub.prepare(script, hex, new WalletPublisher.Cb() {
                            public void onResult(org.json.JSONObject r2) { mPrepared = true; }
                            public void onError(String m) {}
                        });
                    }
                    if (!addr.isEmpty()) {
                        JSONObject b = r.balance();
                        JSONObject bal = obj(b, "balance");
                        String[] cs = extract(bal);
                        confirmed = cs[0];
                        sendable = cs[1];
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                // The account's OWN wallet (not a device watch address) also has a token list and
                // the one-time-signature counter — read them too, best-effort (a miss just omits
                // the card, never blocks the balance).
                try {
                    JSONObject t = r.walletTokens();
                    if (t.get("ok") == Boolean.TRUE) {
                        tokens = nonMinima(obj(t, "balance"));
                    }
                } catch (Exception ignored) {
                }
                try {
                    JSONObject u = r.walletUses(0);
                    if (u.get("ok") == Boolean.TRUE) {
                        uses = num(u, "uses");
                        int mx = num(u, "max");
                        if (mx > 0) maxUses = mx;
                    }
                } catch (Exception ignored) {
                }
                final String fa = addr, fc = confirmed, fs = sendable, fe = error, fre = resyncError;
                final boolean fcr = canResync;
                final JSONArray ft = tokens;
                final int fu = uses, fm = maxUses;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mAddress = fa;
                    mCanResync = fcr;
                    mResyncError = fre;
                    mConfirmed = fc;
                    mSendable = fs;
                    mError = fe;
                    mTokens = ft;
                    mUses = fu;
                    mMaxUses = fm;
                    rebuild();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mError = m;
                    rebuild();
                });
            }
        });
    }

    private void rebuild() {
        boolean first = !mBuilt;
        mBuilt = true;
        Context c = mAct;
        // Never a blank screen: while the account address is still deriving on the node and no
        // watch address is set, show a loading card instead of nothing.
        if (mAddress.isEmpty() && mError.isEmpty()) {
            mRoot.removeAllViews();
            mRoot.addView(PortalUi.section(c, "Wallet"));
            LinearLayout card = PortalUi.card(c);
            card.addView(PortalUi.label(c, first ? "Opening your account wallet…" : "Loading…"));
            mRoot.addView(card);
            return;
        }
        mRoot.removeAllViews();

        if (mAddress.isEmpty()) {
            // No watch address yet — offer to set one (the device's cold receive address).
            mRoot.addView(PortalUi.section(c, "Wallet"));
            LinearLayout card = PortalUi.card(c);
            card.addView(PortalUi.title(c, "Watch-only wallet"));
            card.addView(PortalUi.gap(c, 6));
            card.addView(PortalUi.label(c, "Set your wallet's RECEIVE address and your cloud node will "
                    + "show its balance — read-only. Your spend keys never leave your device; the node "
                    + "can watch, never spend."));
            card.addView(PortalUi.gap(c, 12));
            EditText field = new EditText(c);
            field.setHint("Mx… receive address");
            field.setTextColor(c.getColor(R.color.ux_text));
            field.setHintTextColor(c.getColor(R.color.ux_subtext));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setTextSize(13);
            card.addView(field);
            card.addView(PortalUi.gap(c, 8));
            TextView set = PortalUi.button(c, "Set watch address");
            set.setOnClickListener(v -> setWatch(field.getText().toString().trim()));
            card.addView(set);
            mRoot.addView(card);
            if (!mError.isEmpty() && !mError.toLowerCase().contains("no watch")) {
                mRoot.addView(PortalUi.label(c, mError));
            }
            return;
        }

        // --- balance ---
        mRoot.addView(PortalUi.section(c, "Total balance"));
        LinearLayout bal = PortalUi.card(c);
        TextView big = new TextView(c);
        big.setText(mConfirmed.isEmpty() ? (mError.isEmpty() ? "…" : "unavailable") : mConfirmed);
        big.setTextColor(c.getColor(R.color.ux_text));
        big.setTextSize(34);
        big.setTypeface(big.getTypeface(), Typeface.BOLD);
        bal.addView(big);
        TextView unit = new TextView(c);
        // Honest custody line: the ACCOUNT's own wallet signs on the node; a device-set watch
        // address is the read-only mode.
        unit.setText(mSendable.isEmpty() ? "MINIMA · your account's wallet"
                : mSendable + " sendable · keys live on your cloud node");
        unit.setTextColor(c.getColor(R.color.ux_subtext));
        unit.setTextSize(13);
        bal.addView(unit);
        mRoot.addView(bal);

        // --- receive ---
        mRoot.addView(PortalUi.section(c, "Receive address"));
        LinearLayout rec = PortalUi.card(c);
        TextView val = PortalUi.value(c, mAddress);
        val.setTextSize(13);
        rec.addView(val);
        rec.addView(PortalUi.gap(c, 10));
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = PortalUi.ghost(c, "Copy");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = PortalUi.dp(c, 6);
        copy.setOnClickListener(v -> copy(mAddress));
        btns.addView(copy, lp1);
        TextView qr = PortalUi.ghost(c, "Show QR");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = PortalUi.dp(c, 6);
        qr.setOnClickListener(v -> showQr(mAddress));
        btns.addView(qr, lp2);
        rec.addView(btns);
        mRoot.addView(rec);

        // --- other tokens held (only for the account's own wallet, not a device watch address) ---
        if (mTokens != null && !mTokens.isEmpty()) {
            mRoot.addView(PortalUi.section(c, "Tokens"));
            LinearLayout tc = PortalUi.card(c);
            for (int i = 0; i < mTokens.size(); i++) {
                if (!(mTokens.get(i) instanceof JSONObject)) continue;
                JSONObject t = (JSONObject) mTokens.get(i);
                String name = tokenName(t);
                String amt = str(t, "confirmed");
                String tid = str(t, "tokenid");
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                int pv = PortalUi.dp(c, 8);
                row.setPadding(0, pv, 0, pv);
                LinearLayout left = new LinearLayout(c);
                left.setOrientation(LinearLayout.VERTICAL);
                TextView nm = new TextView(c);
                nm.setText(name);
                nm.setTextColor(c.getColor(R.color.ux_text));
                nm.setTextSize(15);
                nm.setTypeface(nm.getTypeface(), Typeface.BOLD);
                left.addView(nm);
                TextView id = new TextView(c);
                id.setText(tid);                                   // full token id, never truncated
                id.setTextColor(c.getColor(R.color.ux_subtext));
                id.setTextSize(10);
                id.setTypeface(Typeface.MONOSPACE);
                left.addView(id);
                row.addView(left, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView val2 = new TextView(c);
                val2.setText(amt);
                val2.setTextColor(c.getColor(R.color.ux_text));
                val2.setTextSize(15);
                val2.setGravity(android.view.Gravity.END);
                row.addView(val2);
                final String ftid = tid;
                row.setOnClickListener(v -> copy(ftid, "Token id copied"));
                tc.addView(row);
            }
            mRoot.addView(tc);
        }

        // --- key uses (the sacred one-time-signature counter) ---
        if (mUses >= 0) {
            mRoot.addView(PortalUi.section(c, "Signing key"));
            LinearLayout kc = PortalUi.card(c);
            TextView u = new TextView(c);
            u.setText(mUses + " / " + mMaxUses + " signatures used");
            u.setTextColor(c.getColor(R.color.ux_text));
            u.setTextSize(16);
            u.setTypeface(u.getTypeface(), Typeface.BOLD);
            kc.addView(u);
            kc.addView(PortalUi.gap(c, 4));
            kc.addView(PortalUi.label(c, "Each key signs a fixed number of times. When it runs low, "
                    + "detach to a fresh wallet. Only raise this if you've signed with this seed on "
                    + "ANOTHER device — a wrong count can reuse a one-time key and leak it."));
            kc.addView(PortalUi.gap(c, 8));
            TextView adj = PortalUi.ghost(c, "Set signatures used…");
            adj.setOnClickListener(v -> usesSheet());
            kc.addView(adj);
            mRoot.addView(kc);
        }

        // --- send from the account wallet (also powers the wallet-detach sweep) ---
        mRoot.addView(PortalUi.section(c, mCanResync ? "Wallet" : "Send"));
        LinearLayout sendCard = PortalUi.card(c);
        if (mCanResync) {
            // A Parlons Node account: the full wallet (tokens, NFTs, send, receive, coins,
            // history, mint) runs on the node over the paired channel.
            TextView open = PortalUi.button(c, "Open wallet");
            open.setOnClickListener(v -> mAct.startActivity(new android.content.Intent(mAct,
                    com.eurobuddha.maxima.app.portal.wallet.WalletActivity.class)));
            sendCard.addView(open);
            sendCard.addView(PortalUi.gap(c, 6));
            sendCard.addView(PortalUi.label(c, "Balances with token icons, NFT gallery, send "
                    + "(quick or coin control), receive with QR, every coin, on-chain history "
                    + "and minting - all on your node, signed there, nothing leaves the box."));
        } else {
            TextView send = PortalUi.button(c, "Send MINIMA");
            send.setOnClickListener(v -> sendSheet());
            sendCard.addView(send);
            sendCard.addView(PortalUi.gap(c, 8));
            TextView detach = PortalUi.ghost(c, "Detach wallet to this phone…");
            detach.setOnClickListener(v -> detachFlow());
            sendCard.addView(detach);
            sendCard.addView(PortalUi.gap(c, 6));
            sendCard.addView(PortalUi.label(c, "Detach = move ALL funds to a wallet whose seed "
                    + "lives on this phone (Minima Core). Your cloud identity stays; the VPS "
                    + "goes cold and keeps a watch-only view."));
        }
        if (mCanResync) {   // node accounts only: a cloud wallet IS the identity seed
            sendCard.addView(PortalUi.gap(c, 8));
            TextView resync = PortalUi.ghost(c, "Resync wallet to a new phrase…");
            resync.setOnClickListener(v -> resyncFlow());
            sendCard.addView(resync);
            sendCard.addView(PortalUi.gap(c, 6));
            sendCard.addView(PortalUi.label(c, "Resync = the node's wallet moves to a NEW 24-word "
                    + "phrase (its signing keys used up, or you want a fresh seed). Your identity, "
                    + "devices and contacts stay; funds at the old phrase stay with the old phrase."));
            if (!mResyncError.isEmpty()) {
                sendCard.addView(PortalUi.gap(c, 6));
                TextView bad = PortalUi.label(c, "Last resync did NOT go through - the wallet is "
                        + "unchanged. " + mResyncError);
                bad.setTextColor(c.getColor(R.color.ux_error));
                sendCard.addView(bad);
            }
        }
        mRoot.addView(sendCard);

        // --- history: this device's ledger (sends + received payments seen while paired) ---
        java.util.List<WalletLedger.Entry> ledger = WalletLedger.entries(mAct);
        if (!ledger.isEmpty()) {
            mRoot.addView(PortalUi.section(c, "History"));
            LinearLayout hc = PortalUi.card(c);
            int shown = 0;
            for (WalletLedger.Entry e : ledger) {
                if (shown >= 30) break;   // keep the tab snappy; full record persists
                if (shown > 0) {
                    View div = new View(c);
                    div.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, PortalUi.dp(c, 1)));
                    div.setBackgroundColor(c.getColor(R.color.ux_divider));
                    hc.addView(div);
                }
                hc.addView(ledgerRow(c, e));
                shown++;
            }
            mRoot.addView(hc);
        }

        if (!mError.isEmpty()) {
            mRoot.addView(PortalUi.label(c, "Balance note: " + mError));
        }
    }

    private View ledgerRow(Context c, WalletLedger.Entry e) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pv = PortalUi.dp(c, 10);
        row.setPadding(0, pv, 0, pv);
        boolean failed = WalletLedger.FAILED.equals(e.direction);
        boolean sent = WalletLedger.SENT.equals(e.direction) || failed;

        TextView glyph = new TextView(c);
        glyph.setText(failed ? "✕" : (sent ? "↑" : "↓"));
        glyph.setTextSize(18);
        glyph.setTextColor(c.getColor(failed ? R.color.ux_error
                : (sent ? R.color.ux_subtext : R.color.ux_success)));
        glyph.setPadding(0, 0, PortalUi.dp(c, 12), 0);
        row.addView(glyph);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView who = new TextView(c);
        who.setText(failed ? "Send failed"
                : (sent ? "To " : "From ") + shortWho(e.counterparty));
        who.setTextColor(c.getColor(R.color.ux_text));
        who.setTextSize(15);
        col.addView(who);
        TextView when = new TextView(c);
        when.setText(e.time > 0 ? stamp(e.time) : "");
        when.setTextColor(c.getColor(R.color.ux_subtext));
        when.setTextSize(12);
        col.addView(when);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!failed && !e.amount.isEmpty()) {
            TextView amt = new TextView(c);
            amt.setText((sent ? "−" : "+") + e.amount + " " + (e.token.isEmpty() ? "MINIMA" : e.token));
            amt.setTextColor(c.getColor(sent ? R.color.ux_text : R.color.ux_success));
            amt.setTextSize(15);
            amt.setGravity(android.view.Gravity.END);
            row.addView(amt);
        }
        row.setOnClickListener(v -> ledgerDetail(e));
        return row;
    }

    private void ledgerDetail(WalletLedger.Entry e) {
        boolean failed = WalletLedger.FAILED.equals(e.direction);
        boolean sent = WalletLedger.SENT.equals(e.direction) || failed;
        StringBuilder sb = new StringBuilder();
        sb.append(failed ? "Send failed\n\n" : (sent ? "Sent\n\n" : "Received\n\n"));
        if (!e.amount.isEmpty()) {
            sb.append("Amount:\n").append(e.amount).append(" ")
                    .append(e.token.isEmpty() ? "MINIMA" : e.token).append("\n\n");
        }
        if (!e.counterparty.isEmpty()) {
            sb.append(sent ? "To:\n" : "From:\n").append(e.counterparty).append("\n\n");   // full
        }
        if (e.time > 0) {
            sb.append("When:\n").append(fullStamp(e.time)).append("\n\n");
        }
        if (!e.memo.isEmpty()) {
            sb.append("Note:\n").append(e.memo).append("\n\n");
        }
        if (!e.txid.isEmpty()) {
            sb.append("Transaction id:\n").append(e.txid).append("\n\n");   // full, never truncated
        }
        if (!e.error.isEmpty()) {
            sb.append("Error:\n").append(e.error).append("\n\n");
        }
        AlertDialog d = new AlertDialog.Builder(mAct)
                .setTitle("Transaction")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Close", null)
                .setNeutralButton(e.txid.isEmpty() ? null : "Copy txid",
                        e.txid.isEmpty() ? null : (di, w) -> copy(e.txid, "Transaction id copied"))
                .show();
    }

    private static String shortWho(String s) {
        if (s == null || s.isEmpty()) return "someone";
        // A name stays whole; a raw address is long — show a readable head but the DETAIL sheet
        // and copy always carry the full value (never a truncated identifier the user acts on).
        if (s.startsWith("Mx") || s.startsWith("0x") || s.startsWith("MAX#")) {
            return s.length() > 16 ? s.substring(0, 16) + "…" : s;
        }
        return s;
    }

    private static String stamp(long t) {
        return android.text.format.DateFormat.format("d MMM · HH:mm", t).toString();
    }

    private static String fullStamp(long t) {
        return android.text.format.DateFormat.format("EEE d MMM yyyy, HH:mm:ss", t).toString();
    }

    /** Send from the ACCOUNT wallet to any Minima address — one confirm naming amount+address
     *  in full (this signs and broadcasts a real transaction). */
    private void sendSheet() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(mAct);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = PortalUi.dp(mAct, 20);
        box.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        final EditText addr = new EditText(mAct);
        addr.setHint("Mx… or 0x… address");
        addr.setSingleLine(true);
        box.addView(addr);
        TextView scan = PortalUi.ghost(mAct, "Scan QR");
        scan.setOnClickListener(v -> mAct.scanTo(text -> {
            if (text != null && !text.trim().isEmpty()) {
                addr.setText(text.trim());   // scanned address drops straight into the field
            }
        }));
        box.addView(scan);
        final EditText amt = new EditText(mAct);
        amt.setHint("Amount (MINIMA)");
        amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(amt);
        new AlertDialog.Builder(mAct)
                .setTitle("Send MINIMA")
                .setMessage("Signed on your cloud node with one guarded key use.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> confirmSend(
                        addr.getText().toString().trim(), amt.getText().toString().trim()))
                .show();
    }

    private void confirmSend(String zTo, String zAmount) {
        if (zTo.isEmpty() || zAmount.isEmpty()) {
            mAct.toast("Address and amount needed");
            return;
        }
        new AlertDialog.Builder(mAct)
                .setTitle("Send " + zAmount + " MINIMA?")
                .setMessage("To:\n" + zTo + "\n\nThis signs and broadcasts a real transaction "
                        + "and cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign & send", (d, w) -> doWalletSend(zTo, zAmount, null))
                .show();
    }

    private volatile boolean mSending;   // double-tap latch

    /** Send from the account wallet. If zWatchOnSuccess is non-null, the watch is repointed to
     *  it ONLY after the walletsent push for this send arrives (the detach sweep) — never
     *  unconditionally, so a failed sweep can't hide the account's real balance. */
    private void doWalletSend(String zTo, String zAmount, final String zWatchOnSuccess) {
        if (mSending) {
            mAct.toast("A send is already in progress…");
            return;
        }
        mSending = true;
        mAct.toast("Signing on your node…");
        final String pid = java.util.UUID.randomUUID().toString();
        final PortalHub.Listener[] holder = new PortalHub.Listener[1];
        // Arm BEFORE the RPC: sally SIGNS, then pushes 'walletbuilt' carrying the signed blob for
        // THIS device to broadcast (via its minimaCore, else gateway). 'walletfail' tears us down.
        holder[0] = ev -> {
            String type = String.valueOf(ev.get("type"));
            if (!pid.equals(String.valueOf(ev.get("pid")))) return;
            if ("walletbuilt".equals(type)) {
                PortalHub.remove(holder[0]);
                final String importcmd = String.valueOf(ev.get("importcmd"));
                final String postcmd = String.valueOf(ev.get("postcmd"));
                final String txid = String.valueOf(ev.get("txid"));
                mAct.runOnUiThread(() -> mAct.toast("Broadcasting via " + mPub.backendName() + "…"));
                mPub.publish(importcmd, txid, postcmd, new WalletPublisher.Cb() {
                    public void onResult(org.json.JSONObject r2) {
                        mAct.runOnUiThread(() -> {
                            mSending = false;
                            mAct.toast("Sent");
                            if (zWatchOnSuccess != null) setWatch(zWatchOnSuccess);
                            mLastLoad = 0;
                            render();
                        });
                    }
                    public void onError(String m) {
                        mAct.runOnUiThread(() -> { mSending = false; mAct.toast("Broadcast failed: " + m); });
                    }
                });
            } else if ("walletfail".equals(type)) {
                PortalHub.remove(holder[0]);
                final String why = String.valueOf(ev.get("error"));
                mAct.runOnUiThread(() -> { mSending = false; mAct.toast("Send failed: " + why); });
            }
        };
        PortalHub.add(holder[0]);
        CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.buildSend(zTo, zAmount, pid);   // sally signs; blob via push
                    Object okv = res.get("ok");
                    if (!(okv instanceof Boolean) || !((Boolean) okv)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                if (error != null) {
                    final String err = error;
                    PortalHub.remove(holder[0]);
                    mAct.runOnUiThread(() -> { mSending = false; mAct.toast("Send failed: " + err); });
                }
                // success → wait for the walletbuilt push (the listener above finishes the send)
            }
            public void err(String m) {
                PortalHub.remove(holder[0]);
                mAct.runOnUiThread(() -> {
                    mSending = false;
                    mAct.toast("Send failed: " + m);
                });
            }
        });
    }

    /** Re-point the NODE wallet at a new phrase; identity untouched (node accounts only). */
    private void resyncFlow() {
        final android.widget.EditText field = new android.widget.EditText(mAct);
        field.setHint("24 words, space separated");
        field.setMinLines(3);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = PortalUi.dp(mAct, 16);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(mAct);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(mAct)
                .setTitle("Resync wallet to a new phrase")
                .setMessage("The node's WALLET moves to this phrase; your identity, paired devices "
                        + "and contacts stay exactly as they are. Funds at the current address stay "
                        + "with the current phrase. The node restarts (about a minute).")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Resync", (d, w) -> {
                    final String phrase = field.getText().toString().trim();
                    if (phrase.split("\\s+").length != 24) {
                        android.widget.Toast.makeText(mAct, "That is not 24 words", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
                        public void ok(com.eurobuddha.maxima.cloud.ParlonsRemote r) {
                            String msg;
                            try {
                                org.minima.utils.json.JSONObject o = r.walletResync(phrase);
                                Object okv = o.get("ok");
                                msg = (okv instanceof Boolean && (Boolean) okv)
                                        ? "Resync started - the node restarts in about a minute; same account, new wallet address"
                                        : "Refused: " + o.getOrDefault("error", o);
                            } catch (Exception e) {
                                msg = "Could not reach the node: " + e.getMessage();
                            }
                            final String fm = msg;
                            mAct.runOnUiThread(() -> android.widget.Toast.makeText(mAct, fm, android.widget.Toast.LENGTH_LONG).show());
                        }
                        public void err(String m) {
                            mAct.runOnUiThread(() -> android.widget.Toast.makeText(mAct, "Could not reach the node: " + m, android.widget.Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
    }

    /** The detach flow (user decision: the new seed lives ON THE DEVICE): sweep everything to
     *  a Minima Core wallet on this phone, then watch that address from the cloud. */
    private void detachFlow() {
        boolean coreInstalled;
        try {
            mAct.getPackageManager().getPackageInfo("org.minimarex.minimacore", 0);
            coreInstalled = true;
        } catch (Exception e) {
            coreInstalled = false;
        }
        if (!coreInstalled) {
            new AlertDialog.Builder(mAct)
                    .setTitle("Minima Core needed")
                    .setMessage("The detached wallet lives in Minima Core on this phone — its "
                            + "seed never touches the VPS. Install it first (Settings → Minima "
                            + "Core on this phone), create a wallet there, then come back.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(mAct);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = PortalUi.dp(mAct, 20);
        box.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        final EditText addr = new EditText(mAct);
        addr.setHint("Mx… address from Minima Core");
        addr.setSingleLine(true);
        box.addView(addr);
        new AlertDialog.Builder(mAct)
                .setTitle("Detach wallet to this phone")
                .setMessage("1. In Minima Core: Wallet → Receive → copy your address.\n"
                        + "2. Paste it below. ALL sendable funds sweep to it in one "
                        + "transaction, and the cloud switches to watching that address.\n\n"
                        + "Your cloud IDENTITY is untouched — chats, calls and contacts keep "
                        + "working exactly as before.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> confirmDetach(
                        addr.getText().toString().trim()))
                .show();
    }

    private void confirmDetach(String zTo) {
        if (!zTo.matches("Mx[0-9A-Z]+") && !zTo.matches("0x[0-9A-Fa-f]{64}")) {
            mAct.toast("That doesn't look like a full Minima address");
            return;
        }
        String amount = mSendable.isEmpty() ? "" : mSendable;
        if (amount.isEmpty() || "0".equals(amount)) {
            mAct.toast("Nothing sendable right now — sync first or wait for confirmations");
            return;
        }
        final String amt = amount;
        new AlertDialog.Builder(mAct)
                .setTitle("Sweep " + amt + " MINIMA?")
                .setMessage("Everything sendable goes to:\n" + zTo + "\n\nThe cloud then "
                        + "watches this address read-only. This signs a real transaction and "
                        + "cannot be undone.")
                .setNegativeButton("Cancel", null)
                // Watch flips ONLY after the sweep's walletsent push — a failed sweep must not
                // hide the account's real balance behind an empty watch address.
                .setPositiveButton("Sweep & detach", (d, w) -> doWalletSend(zTo, amt, zTo))
                .show();
    }

    /** Raise-only adjust of the one-time-signature counter (for a seed signed elsewhere).
     *  Double-guarded: naming the danger, then confirming the exact new value. */
    private void usesSheet() {
        final EditText field = new EditText(mAct);
        field.setHint("New used count (> " + mUses + ")");
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        int pad = PortalUi.dp(mAct, 20);
        field.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        new AlertDialog.Builder(mAct)
                .setTitle("Set signatures used")
                .setMessage("ONLY if this seed has signed transactions on another device. The "
                        + "counter can only go UP — setting it too low risks reusing a key and "
                        + "leaking it. Current: " + mUses + ".")
                .setView(field)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> {
                    String s = field.getText().toString().trim();
                    int to;
                    try {
                        to = Integer.parseInt(s);
                    } catch (Exception e) {
                        mAct.toast("Enter a whole number");
                        return;
                    }
                    if (to <= mUses) {
                        mAct.toast("Must be higher than the current " + mUses);
                        return;
                    }
                    confirmUses(to);
                })
                .show();
    }

    private void confirmUses(final int zTo) {
        new AlertDialog.Builder(mAct)
                .setTitle("Raise to " + zTo + "?")
                .setMessage("This permanently marks " + zTo + " signatures as used and cannot be "
                        + "undone. Do this only to match a seed already used elsewhere.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Raise counter", (d, w) -> doRaiseUses(zTo))
                .show();
    }

    private void doRaiseUses(final int zTo) {
        mAct.toast("Updating counter…");
        CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.walletUses(zTo);
                    if (res.get("ok") != Boolean.TRUE) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    mAct.toast(err == null ? "Counter raised" : "Could not raise: " + err);
                    mLastLoad = 0;
                    render();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast("Could not raise: " + m));
            }
        });
    }

    private void setWatch(String address) {
        if (address.isEmpty()) {
            mAct.toast("Enter a receive address");
            return;
        }
        mAct.toast("Setting…");
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.setWatch(address);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    if (err == null) {
                        mAct.toast("Watch address set");
                        mLastLoad = 0;
                        render();
                    } else {
                        mAct.toast("Could not set: " + err);
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast("Could not set: " + m));
            }
        });
    }

    /** Pull confirmed + sendable for Minima (tokenid 0x00) out of the gateway balance JSON. */
    private static String[] extract(JSONObject bal) {
        try {
            Object resp = bal.get("response");
            if (resp instanceof JSONArray) {
                JSONArray arr = (JSONArray) resp;
                for (Object o : arr) {
                    if (!(o instanceof JSONObject)) continue;
                    JSONObject t = (JSONObject) o;
                    String tid = str(t, "tokenid");
                    if (tid.isEmpty() || "0x00".equalsIgnoreCase(tid) || "0x00".equals(tid)) {
                        return new String[]{str(t, "confirmed"), str(t, "sendable")};
                    }
                }
                if (!arr.isEmpty() && arr.get(0) instanceof JSONObject) {
                    JSONObject t = (JSONObject) arr.get(0);
                    return new String[]{str(t, "confirmed"), str(t, "sendable")};
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{"", ""};
    }

    /** All tokens EXCEPT Minima (0x00) from the gateway balance JSON — the "Tokens" section. */
    private static JSONArray nonMinima(JSONObject bal) {
        JSONArray out = new JSONArray();
        try {
            Object resp = bal.get("response");
            if (resp instanceof JSONArray) {
                for (Object o : (JSONArray) resp) {
                    if (!(o instanceof JSONObject)) continue;
                    String tid = str((JSONObject) o, "tokenid");
                    if (tid.isEmpty() || "0x00".equalsIgnoreCase(tid)) continue;
                    out.add(o);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A token's display name — the "token" field may be a bare string or a {name:…} object. */
    private static String tokenName(JSONObject t) {
        Object tok = t.get("token");
        if (tok instanceof JSONObject) {
            Object n = ((JSONObject) tok).get("name");
            if (n != null && !String.valueOf(n).isEmpty()) return String.valueOf(n);
        } else if (tok != null && !String.valueOf(tok).isEmpty()) {
            return String.valueOf(tok);
        }
        return "Token";
    }

    private static int num(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    private void copy(String s) {
        copy(s, "Address copied");
    }

    private void copy(String s, String toast) {
        if (s == null || s.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("value", s));   // full, never truncated
        mAct.toast(toast);
    }

    private void showQr(String s) {
        if (s == null || s.isEmpty()) return;
        int px = PortalUi.dp(mAct, 260);
        Bitmap bmp = Qr.encode(s, px);
        ImageView iv = new ImageView(mAct);
        iv.setImageBitmap(bmp);
        int pad = PortalUi.dp(mAct, 20);
        iv.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(mAct)
                .setTitle("Receive address")
                .setView(iv)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static JSONObject obj(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }
}
