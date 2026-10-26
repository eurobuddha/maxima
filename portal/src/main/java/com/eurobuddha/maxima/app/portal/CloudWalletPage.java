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

import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.SystemMessages;

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
    private EditText mWatchField;
    private TextView mWatchError;

    private String mAddress = "";
    /** From parlons.wallet.address: node accounts can move the wallet to a new phrase. */
    private boolean mCanResync;
    private String mResyncError = "";
    private String mConfirmed = "";
    private String mSendable = "";
    private String mUnconfirmed = "";
    private int mCoins = -1;
    private long mBalanceAt;              // when the balance reply landed ("updated Ns ago")
    private boolean mWholeWallet;         // node account: figures are the WHOLE wallet, every address
    private String mError = "";
    private boolean mChatOnly;
    private JSONArray mTokens;            // non-Minima tokens (whole-wallet rows on a node account)
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
        return com.eurobuddha.maxima.app.i18n.Language.msg(com.eurobuddha.maxima.app.R.string.l10n_nav_wallet);
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
            mRoot.addView(PortalUi.section(mAct, m(R.string.l10n_nav_wallet)));
            LinearLayout loading = PortalUi.card(mAct);
            loading.addView(PortalUi.label(mAct, m(R.string.l10n_cloud_wallet_opening)));
            mRoot.addView(loading);
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                try {
                    if (Boolean.TRUE.equals(r.nodeStatus().get("chatOnly"))) {
                        mAct.runOnUiThread(() -> {
                            mChatOnly = true; mBusy = false; mLastLoad = System.currentTimeMillis(); rebuild();
                        });
                        return;
                    }
                } catch (Exception ignored) { /* Older hosts still enforce their own wallet permissions. */ }
                String addr = "";
                String confirmed = "";
                String sendable = "";
                String unconfirmed = "";
                int coins = -1;
                boolean whole = false;
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
                    if (canResync) {
                        // A Parlons Node account: the REAL wallet balance is the node's own
                        // `balance` — every one of its addresses, every token — not the per-address
                        // read of whichever default address it advertised this boot. Same rules as
                        // PandaPools/AtomiX: headline = sendable; locked ≈ confirmed - sendable.
                        org.minima.utils.json.JSONObject o = r.nodeCmd("balance", 60_000);
                        if (!Boolean.TRUE.equals(o.get("ok"))) {
                            throw new IllegalStateException(String.valueOf(o.getOrDefault("error", "balance unavailable")));
                        }
                        Object parsed = new org.minima.utils.json.parser.JSONParser().parse(String.valueOf(o.get("output")));
                        JSONObject bal = parsed instanceof JSONObject ? (JSONObject) parsed : new JSONObject();
                        if (!Boolean.TRUE.equals(bal.get("status"))) {
                            throw new IllegalStateException(String.valueOf(bal.getOrDefault("error", bal.getOrDefault("message", "balance refused"))));
                        }
                        String[] cs = extractFull(bal);
                        confirmed = cs[0];
                        sendable = cs[1];
                        unconfirmed = cs[2];
                        coins = cs[3].isEmpty() ? -1 : Integer.parseInt(cs[3]);
                        whole = true;
                        tokens = nonMinima(bal);
                    } else if (!addr.isEmpty()) {
                        JSONObject b = r.balance();
                        JSONObject bal = obj(b, "balance");
                        String[] cs = extract(bal);
                        confirmed = cs[0];
                        sendable = cs[1];
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                // A cloud account's own wallet (not a device watch address) also has a token list
                // — read it best-effort (a miss just omits the card, never blocks the balance).
                if (!whole) {
                    try {
                        JSONObject t = r.walletTokens();
                        if (t.get("ok") == Boolean.TRUE) {
                            tokens = nonMinima(obj(t, "balance"));
                        }
                    } catch (Exception ignored) {
                    }
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
                final String fa = addr, fc = confirmed, fs = sendable, fe = error, fre = resyncError, fun = unconfirmed;
                final boolean fcr = canResync, fw = whole;
                final int fco = coins;
                final JSONArray ft = tokens;
                final int fu = uses, fm = maxUses;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mChatOnly = false;
                    mBusy = false;
                    mAddress = fa;
                    mCanResync = fcr;
                    mResyncError = fre;
                    mConfirmed = fc;
                    mSendable = fs;
                    mUnconfirmed = fun;
                    mCoins = fco;
                    mWholeWallet = fw;
                    if (fe.isEmpty()) mBalanceAt = System.currentTimeMillis();
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
        if (mChatOnly) {
            mWatchField = null;
            mWatchError = null;
            mRoot.removeAllViews();
            mRoot.addView(PortalUi.section(c, m(R.string.l10n_cloud_wallet_chat_only)));
            LinearLayout card = PortalUi.card(c);
            card.addView(PortalUi.label(c, m(R.string.l10n_cloud_wallet_chat_only_help)));
            mRoot.addView(card);
            return;
        }
        // Never a blank screen: while the account address is still deriving on the node and no
        // watch address is set, show a loading card instead of nothing.
        if (mAddress.isEmpty() && mError.isEmpty() && mWatchField == null) {
            mRoot.removeAllViews();
            mRoot.addView(PortalUi.section(c, m(R.string.l10n_nav_wallet)));
            LinearLayout card = PortalUi.card(c);
            card.addView(PortalUi.label(c, first ? m(R.string.l10n_cloud_wallet_opening) : m(R.string.l10n_common_loading)));
            mRoot.addView(card);
            return;
        }
        if (mAddress.isEmpty() && mWatchField != null) {
            // As in Contacts, retain the input, selection and IME composition on refresh.
            updateWatchError();
            return;
        }
        mWatchField = null;
        mWatchError = null;
        mRoot.removeAllViews();

        if (mAddress.isEmpty()) {
            // No watch address yet — offer to set one (the device's cold receive address).
            mRoot.addView(PortalUi.section(c, m(R.string.l10n_nav_wallet)));
            LinearLayout card = PortalUi.card(c);
            card.addView(PortalUi.title(c, m(R.string.l10n_cloud_wallet_watch_only)));
            card.addView(PortalUi.gap(c, 6));
            card.addView(PortalUi.label(c, m(R.string.l10n_cloud_wallet_watch_help)));
            card.addView(PortalUi.gap(c, 12));
            EditText field = new EditText(c);
            mWatchField = field;
            Language.hint(field, m(R.string.l10n_cloud_wallet_watch_hint));
            field.setTextColor(c.getColor(R.color.ux_text));
            field.setHintTextColor(c.getColor(R.color.ux_subtext));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setTextSize(13);
            card.addView(field);
            card.addView(PortalUi.gap(c, 8));
            TextView set = PortalUi.button(c, m(R.string.l10n_cloud_wallet_set_watch));
            set.setOnClickListener(v -> setWatch(field.getText().toString().trim()));
            card.addView(set);
            mRoot.addView(card);
            mWatchError = PortalUi.label(c, "");
            mRoot.addView(mWatchError);
            updateWatchError();
            return;
        }

        // --- balance ---
        mRoot.addView(PortalUi.section(c, mWholeWallet ? m(R.string.l10n_cloud_wallet_whole_balance) : m(R.string.l10n_cloud_wallet_total_balance)));
        LinearLayout bal = PortalUi.card(c);
        TextView big = new TextView(c);
        // Headline = SENDABLE (what can actually be spent); `confirmed` includes coins locked in
        // contracts and overstates it - the rule every wallet in the family follows.
        String headline = mWholeWallet ? tidy(mSendable) : mConfirmed;
        Language.setText(big, headline.isEmpty() ? (mError.isEmpty() ? "…" : m(R.string.l10n_cloud_wallet_unavailable)) : headline);
        big.setTextColor(c.getColor(R.color.ux_text));
        big.setTextSize(34);
        big.setTypeface(big.getTypeface(), Typeface.BOLD);
        bal.addView(big);
        TextView unit = new TextView(c);
        if (mWholeWallet) {
            // The PandaPools / AtomiX breakdown line, zeros included - a hidden zero is what makes
            // an all-in-a-pool wallet read as "0" with no explanation.
            CharSequence summary = mSendable.isEmpty() ? m(R.string.l10n_cloud_wallet_whole_unit)
                    : Language.join(m(R.string.l10n_cloud_wallet_whole_breakdown,
                            tidy(mConfirmed), locked(mConfirmed, mSendable), tidy(mUnconfirmed)),
                            mCoins >= 0 ? Language.join("  ·  ", Language.count(R.plurals.l10n_cloud_wallet_coins, mCoins)) : "",
                            "  ·  ", m(R.string.l10n_cloud_wallet_updated, ago(mBalanceAt)));
            Language.setText(unit, summary);
        } else {
            Language.setText(unit, mSendable.isEmpty() ? m(R.string.l10n_cloud_wallet_account_unit)
                    : m(R.string.l10n_cloud_wallet_cloud_sendable, mSendable));
        }
        unit.setTextColor(c.getColor(R.color.ux_subtext));
        unit.setTextSize(13);
        bal.addView(unit);
        if (!mError.isEmpty()) {
            TextView why = PortalUi.label(c, m(R.string.l10n_cloud_wallet_balance_error , SystemMessages.message(mError)));
            why.setTextColor(c.getColor(R.color.ux_error));
            bal.addView(why);
        }
        mRoot.addView(bal);

        // --- receive ---
        mRoot.addView(PortalUi.section(c, m(R.string.l10n_cloud_wallet_receive_address)));
        LinearLayout rec = PortalUi.card(c);
        TextView val = PortalUi.value(c, mAddress);
        val.setTextSize(13);
        rec.addView(val);
        rec.addView(PortalUi.gap(c, 10));
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = PortalUi.ghost(c, m(R.string.l10n_common_copy));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = PortalUi.dp(c, 6);
        copy.setOnClickListener(v -> copy(mAddress));
        btns.addView(copy, lp1);
        TextView qr = PortalUi.ghost(c, m(R.string.l10n_contacts_show_qr));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = PortalUi.dp(c, 6);
        qr.setOnClickListener(v -> showQr(mAddress));
        btns.addView(qr, lp2);
        rec.addView(btns);
        mRoot.addView(rec);

        // --- other tokens held (only for the account's own wallet, not a device watch address) ---
        if (mTokens != null && !mTokens.isEmpty()) {
            mRoot.addView(PortalUi.section(c, m(R.string.l10n_wallet_tokens)));
            LinearLayout tc = PortalUi.card(c);
            for (int i = 0; i < mTokens.size(); i++) {
                if (!(mTokens.get(i) instanceof JSONObject)) continue;
                JSONObject t = (JSONObject) mTokens.get(i);
                CharSequence name = tokenName(t);
                String amt = mWholeWallet ? tidy(str(t, "sendable").isEmpty() ? str(t, "confirmed") : str(t, "sendable")) : str(t, "confirmed");
                String tid = str(t, "tokenid");
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                int pv = PortalUi.dp(c, 8);
                row.setPadding(0, pv, 0, pv);
                LinearLayout left = new LinearLayout(c);
                left.setOrientation(LinearLayout.VERTICAL);
                TextView nm = new TextView(c);
                Language.setText(nm, name);
                nm.setTextColor(c.getColor(R.color.ux_text));
                nm.setTextSize(15);
                nm.setTypeface(nm.getTypeface(), Typeface.BOLD);
                left.addView(nm);
                if (mWholeWallet) {
                    String lk = locked(str(t, "confirmed"), str(t, "sendable").isEmpty() ? str(t, "confirmed") : str(t, "sendable"));
                    String un = tidy(str(t, "unconfirmed"));
                    Language.TextBuilder sub = new Language.TextBuilder().append(m(R.string.l10n_wallet_sendable));
                    if (!"0".equals(lk) && !"—".equals(lk)) sub.append("  ·  ").append(m(R.string.l10n_cloud_wallet_locked_amount, lk));
                    if (!"0".equals(un) && !un.isEmpty()) sub.append("  ·  ").append(m(R.string.l10n_cloud_wallet_unconfirmed_amount, un));
                    if (!str(t, "coins").isEmpty()) sub.append("  ·  ").append(coinCount(str(t, "coins")));
                    TextView subv = new TextView(c);
                    Language.setText(subv, sub.build());
                    subv.setTextColor(c.getColor(R.color.ux_subtext));
                    subv.setTextSize(11);
                    left.addView(subv);
                }
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
                row.setOnClickListener(v -> copy(ftid, m(R.string.l10n_cloud_wallet_token_copied)));
                tc.addView(row);
            }
            mRoot.addView(tc);
        }

        // --- key uses (the sacred one-time-signature counter) ---
        if (mUses >= 0) {
            mRoot.addView(PortalUi.section(c, m(R.string.l10n_cloud_wallet_signing_key)));
            LinearLayout kc = PortalUi.card(c);
            TextView u = new TextView(c);
            Language.setText(u, m(R.string.l10n_cloud_wallet_uses, mUses, mMaxUses));
            u.setTextColor(c.getColor(R.color.ux_text));
            u.setTextSize(16);
            u.setTypeface(u.getTypeface(), Typeface.BOLD);
            kc.addView(u);
            kc.addView(PortalUi.gap(c, 4));
            kc.addView(PortalUi.label(c, m(R.string.l10n_cloud_wallet_uses_help)));
            kc.addView(PortalUi.gap(c, 8));
            TextView adj = PortalUi.ghost(c, m(R.string.l10n_cloud_wallet_set_uses_action));
            adj.setOnClickListener(v -> usesSheet());
            kc.addView(adj);
            mRoot.addView(kc);
        }

        // --- send from the account wallet (also powers the wallet-detach sweep) ---
        mRoot.addView(PortalUi.section(c, mCanResync ? m(R.string.l10n_nav_wallet) : m(R.string.l10n_chat_send)));
        LinearLayout sendCard = PortalUi.card(c);
        if (mCanResync) {
            // A Parlons Node account: the full wallet (tokens, NFTs, send, receive, coins,
            // history, mint) runs on the node over the paired channel.
            TextView open = PortalUi.button(c, m(R.string.l10n_cloud_wallet_open_wallet));
            open.setOnClickListener(v -> mAct.startActivity(new android.content.Intent(mAct,
                    com.eurobuddha.maxima.app.portal.wallet.WalletActivity.class)));
            sendCard.addView(open);
            sendCard.addView(PortalUi.gap(c, 6));
            sendCard.addView(PortalUi.label(c, m(R.string.l10n_cloud_wallet_open_help)));
        } else {
            TextView send = PortalUi.button(c, m(R.string.l10n_chat_send));
            send.setOnClickListener(v -> sendSheet());
            sendCard.addView(send);
            sendCard.addView(PortalUi.gap(c, 8));
            TextView detach = PortalUi.ghost(c, m(R.string.l10n_cloud_wallet_detach_action));
            detach.setOnClickListener(v -> detachFlow());
            sendCard.addView(detach);
            sendCard.addView(PortalUi.gap(c, 6));
            sendCard.addView(PortalUi.label(c, m(R.string.l10n_cloud_wallet_detach_help)));
        }
        if (mCanResync) {   // node accounts only: a cloud wallet IS the identity seed
            sendCard.addView(PortalUi.gap(c, 8));
            TextView resync = PortalUi.ghost(c, m(R.string.l10n_cloud_wallet_resync_action));
            resync.setOnClickListener(v -> resyncFlow());
            sendCard.addView(resync);
            sendCard.addView(PortalUi.gap(c, 6));
            sendCard.addView(PortalUi.label(c, m(R.string.l10n_cloud_wallet_resync_help)));
            if (!mResyncError.isEmpty()) {
                sendCard.addView(PortalUi.gap(c, 6));
                TextView bad = PortalUi.label(c, m(R.string.l10n_cloud_wallet_resync_error , SystemMessages.message(mResyncError)));
                bad.setTextColor(c.getColor(R.color.ux_error));
                sendCard.addView(bad);
            }
        }
        mRoot.addView(sendCard);

        // --- history: this device's ledger (sends + received payments seen while paired) ---
        java.util.List<WalletLedger.Entry> ledger = WalletLedger.entries(mAct);
        if (!ledger.isEmpty()) {
            mRoot.addView(PortalUi.section(c, m(R.string.l10n_wallet_history)));
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
            mRoot.addView(PortalUi.label(c, m(R.string.l10n_cloud_wallet_balance_note , mError)));
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
        Language.setText(who, failed ? m(R.string.l10n_cloud_wallet_send_failed)
                : m(sent ? R.string.l10n_cloud_wallet_to : R.string.l10n_cloud_wallet_from, shortWho(e.counterparty)));
        who.setTextColor(c.getColor(R.color.ux_text));
        who.setTextSize(15);
        col.addView(who);
        TextView when = new TextView(c);
        Language.setText(when, e.time > 0 ? Language.stamp(e.time, "MMMdHm") : "");
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

    private void updateWatchError() {
        boolean visible = !mError.isEmpty() && !mError.toLowerCase(java.util.Locale.ROOT).contains("no watch");
        Language.setText(mWatchError, visible ? m(R.string.l10n_cloud_wallet_balance_error, SystemMessages.message(mError)) : "");
        mWatchError.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void ledgerDetail(WalletLedger.Entry e) {
        boolean failed = WalletLedger.FAILED.equals(e.direction);
        boolean sent = WalletLedger.SENT.equals(e.direction) || failed;
        Language.TextBuilder sb = new Language.TextBuilder();
        sb.append(m(failed ? R.string.l10n_cloud_wallet_send_failed : sent ? R.string.l10n_wallet_sent : R.string.l10n_wallet_received)).append("\n\n");
        if (!e.amount.isEmpty()) sb.append(m(R.string.l10n_wallet_amount)).append(":\n")
                .append(e.amount).append(" ").append(e.token.isEmpty() ? "MINIMA" : e.token).append("\n\n");
        if (!e.counterparty.isEmpty()) sb.append(m(sent ? R.string.l10n_wallet_to : R.string.l10n_wallet_from))
                .append(":\n").append(e.counterparty).append("\n\n");
        if (e.time > 0) sb.append(m(R.string.l10n_wallet_when)).append(":\n")
                .append(Language.stamp(e.time, "yMMMMEEEEdHms")).append("\n\n");
        if (!e.memo.isEmpty()) sb.append(m(R.string.l10n_cloud_wallet_note)).append(":\n").append(e.memo).append("\n\n");
        if (!e.txid.isEmpty()) sb.append(m(R.string.l10n_wallet_transaction_id)).append(":\n").append(e.txid).append("\n\n");
        if (!e.error.isEmpty()) sb.append(m(R.string.l10n_cloud_wallet_error)).append(":\n").append(SystemMessages.message(e.error));
        new LanguageDialog(mAct).setTitle(m(R.string.l10n_wallet_transaction)).setMessage(sb.build())
                .setPositiveButton(m(R.string.l10n_language_close), null)
                .setNeutralButton(e.txid.isEmpty() ? null : m(R.string.l10n_android_chat_copy_tx),
                        e.txid.isEmpty() ? null : (di, w) -> copy(e.txid, m(R.string.l10n_android_chat_tx_copied))).show();
    }

    private static CharSequence shortWho(String s) {
        if (s == null || s.isEmpty()) return m(R.string.l10n_cloud_wallet_someone);
        return s;   // addresses stay whole: a shortened one can't be copied or checked
    }

    /** Send from the ACCOUNT wallet to any Minima address — one confirm naming amount+address
     *  in full (this signs and broadcasts a real transaction). */
    private void sendSheet() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(mAct);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = PortalUi.dp(mAct, 20);
        box.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        final EditText addr = new EditText(mAct);
        Language.hint(addr, m(R.string.l10n_cloud_wallet_address_hint));
        addr.setSingleLine(true);
        box.addView(addr);
        TextView scan = PortalUi.ghost(mAct, m(R.string.l10n_common_scan_qr));
        scan.setOnClickListener(v -> mAct.scanTo(text -> {
            if (text != null && !text.trim().isEmpty()) {
                addr.setText(text.trim());   // scanned address drops straight into the field
            }
        }));
        box.addView(scan);
        // Minima or MxUSD (the Minima dollar-pegged token); the account node signs either.
        final com.eurobuddha.maxima.app.chat.PaymentCurrency currency = new com.eurobuddha.maxima.app.chat.PaymentCurrency(mAct);
        box.addView(currency);
        final EditText amt = new EditText(mAct);
        Language.hint(amt, m(R.string.l10n_android_chat_amount));
        currency.setOnCheckedChangeListener((g, id) -> Language.hint(amt, m(
                currency.isUsd() ? R.string.l10n_android_chat_amount_mxusd : R.string.l10n_android_chat_amount)));
        amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(amt);
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_chat_send))
                .setMessage(m(R.string.l10n_cloud_wallet_sign_help))
                .setView(box)
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_common_continue), (d, w) -> confirmSend(
                        addr.getText().toString().trim(), amt.getText().toString().trim(),
                        currency.tokenId()))
                .show();
    }

    private static final String MXUSD_TOKENID = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    private static final String MXUSD_NAME = "MxUSD";

    private void confirmSend(String zTo, String zAmount, String zTokenId) {
        if (zTo.isEmpty() || zAmount.isEmpty()) {
            mAct.toast(tr(R.string.l10n_cloud_wallet_address_amount));
            return;
        }
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_android_chat_send_amount_token, zAmount,
                        MXUSD_TOKENID.equals(zTokenId) ? MXUSD_NAME : "MINIMA"))
                .setMessage(m(R.string.l10n_cloud_wallet_confirm_send , zTo))
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_desktop_chat_sign_send), (d, w) -> doWalletSend(zTo, zAmount, null, zTokenId))
                .show();
    }

    private volatile boolean mSending;   // double-tap latch

    /** Send from the account wallet. If zWatchOnSuccess is non-null, the watch is repointed to
     *  it ONLY after the walletsent push for this send arrives (the detach sweep) — never
     *  unconditionally, so a failed sweep can't hide the account's real balance. */
    private void doWalletSend(String zTo, String zAmount, final String zWatchOnSuccess) {
        doWalletSend(zTo, zAmount, zWatchOnSuccess, "0x00");
    }

    private void doWalletSend(String zTo, String zAmount, final String zWatchOnSuccess, final String zTokenId) {
        if (mSending) {
            mAct.toast(tr(R.string.l10n_cloud_wallet_send_in_progress));
            return;
        }
        mSending = true;
        mAct.toast(tr(R.string.l10n_cloud_wallet_signing));
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
                mAct.runOnUiThread(() -> mAct.toast(tr(R.string.l10n_cloud_wallet_broadcasting , SystemMessages.message(mPub.backendName()))));
                mPub.publish(importcmd, txid, postcmd, new WalletPublisher.Cb() {
                    public void onResult(org.json.JSONObject r2) {
                        mAct.runOnUiThread(() -> {
                            mSending = false;
                            mAct.toast(tr(R.string.l10n_wallet_sent));
                            if (zWatchOnSuccess != null) setWatch(zWatchOnSuccess);
                            mLastLoad = 0;
                            render();
                        });
                    }
                    public void onError(String m) {
                        mAct.runOnUiThread(() -> { mSending = false; mAct.toast(tr(R.string.l10n_cloud_wallet_broadcast_failed , m)); });
                    }
                });
            } else if ("walletfail".equals(type)) {
                PortalHub.remove(holder[0]);
                final String why = String.valueOf(ev.get("error"));
                mAct.runOnUiThread(() -> { mSending = false; mAct.toast(tr(R.string.l10n_wallet_send_failed , SystemMessages.message(why))); });
            }
        };
        PortalHub.add(holder[0]);
        CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.buildSend(zTo, zAmount, pid, zTokenId);   // sally signs; blob via push
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
                    mAct.runOnUiThread(() -> { mSending = false; mAct.toast(tr(R.string.l10n_wallet_send_failed , SystemMessages.message(err))); });
                }
                // success → wait for the walletbuilt push (the listener above finishes the send)
            }
            public void err(String m) {
                PortalHub.remove(holder[0]);
                mAct.runOnUiThread(() -> {
                    mSending = false;
                    mAct.toast(tr(R.string.l10n_wallet_send_failed , SystemMessages.message(m)));
                });
            }
        });
    }

    /** Re-point the NODE wallet at a new phrase; identity untouched (node accounts only). */
    private void resyncFlow() {
        final android.widget.EditText field = new android.widget.EditText(mAct);
        Language.hint(field, m(R.string.l10n_cloud_wallet_phrase_hint));
        field.setMinLines(3);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = PortalUi.dp(mAct, 16);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(mAct);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(field);
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_cloud_wallet_resync_title))
                .setMessage(m(R.string.l10n_cloud_wallet_resync_warning))
                .setView(wrap)
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_cloud_wallet_resync), (d, w) -> {
                    final String phrase = field.getText().toString().trim();
                    if (phrase.split("\\s+").length != 24) {
                        android.widget.Toast.makeText(mAct, tr(R.string.l10n_cloud_wallet_phrase_invalid), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
                        public void ok(com.eurobuddha.maxima.cloud.ParlonsRemote r) {
                            String msg;
                            try {
                                org.minima.utils.json.JSONObject o = r.walletResync(phrase);
                                Object okv = o.get("ok");
                                msg = (okv instanceof Boolean && (Boolean) okv)
                                        ? tr(R.string.l10n_cloud_wallet_resync_started)
                                        : tr(R.string.l10n_cloud_wallet_refused , SystemMessages.message(String.valueOf(o.getOrDefault("error", o))));
                            } catch (Exception e) {
                                msg = tr(R.string.l10n_cloud_wallet_unreachable , SystemMessages.message(e.getMessage()));
                            }
                            final String fm = msg;
                            mAct.runOnUiThread(() -> android.widget.Toast.makeText(mAct, fm, android.widget.Toast.LENGTH_LONG).show());
                        }
                        public void err(String m) {
                            mAct.runOnUiThread(() -> android.widget.Toast.makeText(mAct, tr(R.string.l10n_cloud_wallet_unreachable , SystemMessages.message(m)), android.widget.Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
    }

    /** The detach flow (user decision: the new seed lives ON THE DEVICE): sweep everything to
     *  a Minima Core wallet on this phone, then watch that address from the cloud. */
    private void detachFlow() {
        boolean coreInstalled = com.eurobuddha.maxima.app.wallet.WalletPublisher.coreInstalled(mAct);
        if (!coreInstalled) {
            new LanguageDialog(mAct)
                    .setTitle(m(R.string.l10n_cloud_wallet_core_needed))
                    .setMessage(m(R.string.l10n_cloud_wallet_core_help))
                    .setPositiveButton(m(R.string.l10n_common_ok), null)
                    .show();
            return;
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(mAct);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = PortalUi.dp(mAct, 20);
        box.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        final EditText addr = new EditText(mAct);
        Language.hint(addr, m(R.string.l10n_cloud_wallet_core_address));
        addr.setSingleLine(true);
        box.addView(addr);
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_cloud_wallet_detach_title))
                .setMessage(m(R.string.l10n_cloud_wallet_detach_warning))
                .setView(box)
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_common_continue), (d, w) -> confirmDetach(
                        addr.getText().toString().trim()))
                .show();
    }

    private void confirmDetach(String zTo) {
        if (!zTo.matches("Mx[0-9A-Z]+") && !zTo.matches("0x[0-9A-Fa-f]{64}")) {
            mAct.toast(tr(R.string.l10n_cloud_wallet_invalid_address));
            return;
        }
        String amount = mSendable.isEmpty() ? "" : mSendable;
        if (amount.isEmpty() || "0".equals(amount)) {
            mAct.toast(tr(R.string.l10n_cloud_wallet_nothing_sendable));
            return;
        }
        final String amt = amount;
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_cloud_wallet_sweep_title , amt))
                .setMessage(m(R.string.l10n_cloud_wallet_sweep_warning , zTo))
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                // Watch flips ONLY after the sweep's walletsent push — a failed sweep must not
                // hide the account's real balance behind an empty watch address.
                .setPositiveButton(m(R.string.l10n_cloud_wallet_sweep), (d, w) -> doWalletSend(zTo, amt, zTo))
                .show();
    }

    /** Raise-only adjust of the one-time-signature counter (for a seed signed elsewhere).
     *  Double-guarded: naming the danger, then confirming the exact new value. */
    private void usesSheet() {
        final EditText field = new EditText(mAct);
        Language.hint(field, m(R.string.l10n_cloud_wallet_uses_hint, mUses));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        int pad = PortalUi.dp(mAct, 20);
        field.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_cloud_wallet_set_uses))
                .setMessage(m(R.string.l10n_cloud_wallet_uses_warning , mUses))
                .setView(field)
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_common_continue), (d, w) -> {
                    String s = field.getText().toString().trim();
                    int to;
                    try {
                        to = Integer.parseInt(s);
                    } catch (Exception e) {
                        mAct.toast(tr(R.string.l10n_cloud_wallet_whole_number));
                        return;
                    }
                    if (to <= mUses) {
                        mAct.toast(tr(R.string.l10n_cloud_wallet_higher_count , mUses));
                        return;
                    }
                    confirmUses(to);
                })
                .show();
    }

    private void confirmUses(final int zTo) {
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_cloud_wallet_raise_title , zTo))
                .setMessage(m(R.string.l10n_cloud_wallet_raise_warning , zTo))
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_cloud_wallet_raise), (d, w) -> doRaiseUses(zTo))
                .show();
    }

    private void doRaiseUses(final int zTo) {
        mAct.toast(tr(R.string.l10n_cloud_wallet_updating));
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
                    mAct.toast(err == null ? tr(R.string.l10n_cloud_wallet_raised) : tr(R.string.l10n_cloud_wallet_raise_failed , SystemMessages.message(err)));
                    mLastLoad = 0;
                    render();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast(tr(R.string.l10n_cloud_wallet_raise_failed , SystemMessages.message(m))));
            }
        });
    }

    private void setWatch(String address) {
        if (address.isEmpty()) {
            mAct.toast(tr(R.string.l10n_cloud_wallet_receive_required));
            return;
        }
        mAct.toast(tr(R.string.l10n_cloud_wallet_setting));
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
                        mAct.toast(tr(R.string.l10n_cloud_wallet_watch_set));
                        mLastLoad = 0;
                        render();
                    } else {
                        mAct.toast(tr(R.string.l10n_cloud_wallet_watch_failed , SystemMessages.message(err)));
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast(tr(R.string.l10n_cloud_wallet_watch_failed , SystemMessages.message(m))));
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

    /** The Minima row of a whole-wallet `balance`: {confirmed, sendable, unconfirmed, coins}. */
    private static String[] extractFull(JSONObject bal) {
        try {
            Object resp = bal.get("response");
            if (resp instanceof JSONArray) {
                for (Object o : (JSONArray) resp) {
                    if (!(o instanceof JSONObject)) continue;
                    JSONObject t = (JSONObject) o;
                    String tid = str(t, "tokenid");
                    if (tid.isEmpty() || "0x00".equalsIgnoreCase(tid)) {
                        String sendable = str(t, "sendable").isEmpty() ? str(t, "confirmed") : str(t, "sendable");
                        return new String[]{str(t, "confirmed"), sendable, str(t, "unconfirmed"), str(t, "coins")};
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{"", "", "", ""};
    }

    /** Trailing-zero trim (PandaBear's Format.tidyAmount): never rounds. */
    static String tidy(String s) {
        if (s == null || s.isEmpty()) return "";
        if (!s.contains(".")) return s;
        String t = s.replaceAll("0+$", "");
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.isEmpty() ? "0" : t;
    }

    /** confirmed - sendable, exact BigDecimal, clamped at 0; "—" if either is unparseable
     *  (a wrong number on a wallet card is worse than no number). */
    static String locked(String confirmed, String sendable) {
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(confirmed).subtract(new java.math.BigDecimal(sendable));
            if (d.signum() < 0) d = java.math.BigDecimal.ZERO;
            return tidy(d.toPlainString());
        } catch (Exception e) {
            return "—";
        }
    }

    static CharSequence ago(long at) {
        return at <= 0 ? m(R.string.l10n_ios_never_seen) : Language.since(at);
    }

    private static CharSequence coinCount(String value) {
        try { return Language.count(R.plurals.l10n_cloud_wallet_coins, Integer.parseInt(value)); }
        catch (NumberFormatException invalid) { return Language.join(m(R.string.l10n_wallet_coins), ": ", value); }
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
    private static CharSequence tokenName(JSONObject t) {
        Object tok = t.get("token");
        if (tok instanceof JSONObject) {
            Object n = ((JSONObject) tok).get("name");
            if (n != null && !String.valueOf(n).isEmpty()) return String.valueOf(n);
        } else if (tok != null && !String.valueOf(tok).isEmpty()) {
            return String.valueOf(tok);
        }
        return m(R.string.l10n_wallet_token);
    }

    private static int num(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    private void copy(String s) {
        copy(s, m(R.string.l10n_native_contacts_address_copied));
    }

    private void copy(String s, CharSequence toast) {
        if (s == null || s.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("value", s));   // full, never truncated
        mAct.toast(toast.toString());
    }

    private void showQr(String s) {
        if (s == null || s.isEmpty()) return;
        int px = PortalUi.dp(mAct, 260);
        Bitmap bmp = Qr.encode(s, px);
        ImageView iv = new ImageView(mAct);
        iv.setImageBitmap(bmp);
        int pad = PortalUi.dp(mAct, 20);
        iv.setPadding(pad, pad, pad, pad);
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_cloud_wallet_receive_address))
                .setView(iv)
                .setPositiveButton(m(R.string.l10n_language_close), null)
                .show();
    }

    private static Language.Message m(int resource, Object... arguments) { return Language.msg(resource, arguments); }
    private String tr(int resource, Object... arguments) { return Language.text(mAct, resource, arguments); }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static JSONObject obj(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }
}
