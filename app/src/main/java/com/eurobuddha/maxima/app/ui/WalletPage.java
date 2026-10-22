package com.eurobuddha.maxima.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.wallet.MaximaWallet;
import com.eurobuddha.maxima.app.wallet.NodeLink;
import com.eurobuddha.maxima.app.wallet.WalletLedger;
import com.eurobuddha.maxima.app.wallet.WalletPublisher;
import com.eurobuddha.wallet.Amounts;
import com.eurobuddha.wallet.CoinAggregator;
import com.eurobuddha.wallet.CoinSelector;
import com.eurobuddha.wallet.Identicon;
import com.eurobuddha.wallet.ImageLoader;
import com.eurobuddha.wallet.TokenMeta;
import com.eurobuddha.wallet.TxnFactory;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONObject;
import org.minima.objects.base.MiniNumber;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallet tab — a full, first-timer-friendly wallet inside the Maxima chat
 * app, in the greyscale design language (reusing NFTwallet's card / history
 * vocabulary). Two sub-tabs — Balance and History — behind a segmented control.
 *
 * One Mx address (Derivation V3, key index 1000 — see {@link MaximaWallet}).
 * Reads and the pre-signed publish relay go to the local minimaCore when
 * installed+paired, else the hosted gateway; signing never leaves this device,
 * and every signature burns one guarded Winternitz use (the counter on the
 * Sends card is not decoration — it is the wallet's life).
 */
public final class WalletPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mBox;
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();

    private volatile MaximaWallet mWallet;
    private volatile WalletPublisher mPub;
    private NodeLink mNode;
    private volatile boolean mTracked;
    /** Re-entrancy guard: one in-flight send at a time. Burning two Winternitz
     *  key-uses on a double-tap is irreversible, so this must never race. */
    private volatile boolean mSending;
    /** One coin-import sweep at a time; and auto-run it at most once per open. */
    private volatile boolean mSyncing;
    private volatile boolean mAutoSynced;

    /** Winternitz one-time-signature budget for key #1000. */
    private static final int KEY_TOTAL = 262144;

    // Panes + segmented control.
    private TextView mSegBalance;
    private TextView mSegHistory;
    private LinearLayout mBalancePane;
    private LinearLayout mHistoryPane;

    // Balance pane.
    private TextView mHeroAmt;
    private TextView mHeroSub;
    private LinearLayout mTokenList;
    private TextView mUsesBig;
    private TextView mUses;

    // History pane.
    private LinearLayout mHistory;
    private int mFilter;   // 0 all · 1 sent · 2 received
    private final TextView[] mChips = new TextView[3];

    // Cached figures (native MINIMA), painted into the hero + detail.
    private volatile long mLastFetch;
    private volatile String mConfMinima = "0";
    private volatile String mUnconfMinima = "0";
    private volatile String mSpendMinima = "0";
    private volatile List<CoinAggregator.Agg> mAggs = new ArrayList<>();
    private volatile Map<String, String> mConf = new HashMap<>();
    private volatile Map<String, String> mUnconf = new HashMap<>();
    private volatile String mSupplyMinima;

    public WalletPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mBox = zView.findViewById(R.id.wallet_container);
        buildUi();

        if (WalletPublisher.coreInstalled(zAct)) {
            mNode = new NodeLink(zAct.getApplicationContext(), enabled -> { });
        }
        mPub = new WalletPublisher(zAct, mNode);
        openWallet();
    }

    /** Open (or re-open, after a seed change) the wallet off-main. */
    private void openWallet() {
        mIo.execute(() -> {
            final MaximaWallet w;
            try {
                w = MaximaWallet.open(mAct);
                w.ensureAddress();
            } catch (Throwable t) {
                // NEVER let a wallet-open failure crash the whole app (an uncaught
                // throwable on this pool thread would). Log it and leave mWallet null;
                // render()/repaintHero already no-op safely until the next open.
                EventLog.add("wallet: open failed: " + t);
                return;
            }
            mWallet = w;
            mLastFetch = 0;
            // A seed switch changes the address+funds — drop the previous wallet's
            // cached figures so we never show the old balance on the new wallet.
            mConfMinima = "0";
            mUnconfMinima = "0";
            mSpendMinima = "0";
            mAggs = new ArrayList<>();
            mConf = new HashMap<>();
            mUnconf = new HashMap<>();
            mSupplyMinima = null;
            mAct.runOnUiThread(() -> {
                if (mHeroAmt != null) {
                    Language.setText(mHeroAmt, "…");
                }
                if (mTokenList != null) {
                    mTokenList.removeAllViews();
                }
                renderUses(w);
                render();
            });
            mTracked = false;
            trackWallet(w, 0);
        });
    }

    /**
     * Register the wallet's script with the node so balance/coins queries see it.
     * This is essential — without it the node has no history for our key index and
     * every balance comes back empty. So a failure is retried (bounded) and, if it
     * ultimately can't register, surfaced to the event log rather than swallowed.
     * On success we force an immediate refresh so the balance appears at once
     * instead of waiting for the next 5s render tick.
     */
    private void trackWallet(final MaximaWallet w, final int zTry) {
        mPub.prepare(w.script(), w.hexAddress(), new WalletPublisher.Cb() {
            public void onResult(JSONObject r) {
                mTracked = true;
                mLastFetch = 0;       // let the next render fetch immediately
                post(() -> render());
            }

            public void onError(String m) {
                if (zTry < 3 && mWallet == w) {
                    post(() -> mView.postDelayed(() -> {
                        if (mWallet == w) {
                            trackWallet(w, zTry + 1);
                        }
                    }, 1500L * (zTry + 1)));
                } else {
                    EventLog.add("wallet: could not register script with node: " + m);
                }
            }
        });
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
        MaximaWallet w = mWallet;
        if (w == null || w.hexAddress() == null) {
            return;
        }
        renderUses(w);
        renderHistory();
        long now = System.currentTimeMillis();
        if (now - mLastFetch < 5_000) {
            return;
        }
        mLastFetch = now;
        final String hex = w.hexAddress();
        // balance → per-token confirmed / unconfirmed (display units).
        mPub.balance(hex, new WalletPublisher.Cb() {
            public void onResult(JSONObject r) {
                try {
                    Map<String, String> conf = new HashMap<>();
                    Map<String, String> unconf = new HashMap<>();
                    org.json.JSONArray arr = r.optJSONArray("response");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject t = arr.getJSONObject(i);
                            String tid = t.optString("tokenid");
                            conf.put(tid, t.optString("confirmed", "0"));
                            unconf.put(tid, t.optString("unconfirmed", "0"));
                            if ("0x00".equals(tid)) {
                                mConfMinima = t.optString("confirmed", "0");
                                mUnconfMinima = t.optString("unconfirmed", "0");
                            }
                        }
                    }
                    mConf = conf;
                    mUnconf = unconf;
                } catch (Exception e) {
                    EventLog.add("wallet: balance parse failed: " + e.getMessage());
                }
                post(() -> repaintHero());
            }

            public void onError(String m) {
                // Transient fetch errors self-heal on the next render tick; only
                // log so a persistent failure is visible in diagnostics.
                EventLog.add("wallet: balance fetch error: " + m);
            }
        });
        // coins → per-token aggregate (SIGNEDBY-aware spendable) + card list.
        mPub.coins(hex, new WalletPublisher.Cb() {
            public void onResult(JSONObject r) {
                try {
                    org.minima.utils.json.JSONObject full =
                            (org.minima.utils.json.JSONObject) new org.minima.utils.json.parser
                                    .JSONParser().parse(r.toString());
                    org.minima.utils.json.JSONArray coins =
                            (org.minima.utils.json.JSONArray) full.get("response");
                    List<CoinAggregator.Agg> aggs = CoinAggregator.aggregate(coins);
                    String spend = "0";
                    for (CoinAggregator.Agg a : aggs) {
                        if (a.isMinima()) {
                            spend = a.displayTotal.toString();
                        }
                    }
                    mAggs = aggs;
                    mSpendMinima = spend;
                } catch (Exception e) {
                    EventLog.add("wallet: coins parse failed: " + e.getMessage());
                }
                post(() -> {
                    repaintHero();
                    rebuildTokenCards();
                    maybeAutoSync();
                });
            }

            public void onError(String m) {
                EventLog.add("wallet: coins fetch error: " + m);
            }
        });
    }

    /**
     * If we are holding confirmed funds that aren't yet spendable (funded-before-
     * tracked coins), backfill them ONCE per wallet open. See {@link #syncCoins}.
     */
    private void maybeAutoSync() {
        MaximaWallet w = mWallet;
        if (w == null || mAutoSynced || mSyncing) {
            return;
        }
        if (isLess(mSpendMinima, held(mConfMinima, mUnconfMinima))) {
            mAutoSynced = true;
            syncCoins(w, null);
        }
    }

    // ---------------------------------------------------------------
    // Balance pane rendering
    // ---------------------------------------------------------------

    private void repaintHero() {
        String held = held(mConfMinima, mUnconfMinima);
        if (mHeroAmt != null) {
            // "TOTAL BALANCE" = HELD (confirmed + unconfirmed), exactly like FreezePeach
            // (Util.held) — NOT sendable. A coin funded before the wallet tracked it is
            // confirmed on-chain but not yet spendable, and showing sendable there made a
            // funded wallet look empty.
            Language.setText(mHeroAmt, Amounts.list(held));
        }
        if (mHeroSub != null) {
            boolean lag = isLess(mSpendMinima, held);
            Language.setText(mHeroSub, m(lag ? R.string.l10n_android_wallet_sendable_lag : R.string.l10n_android_wallet_sendable_now, Amounts.list(mSpendMinima)));
        }
    }

    /** confirmed + unconfirmed as a decimal MINIMA string (FreezePeach's Util.held). */
    private static String held(String confirmed, String unconfirmed) {
        try {
            return new java.math.BigDecimal(confirmed)
                    .add(new java.math.BigDecimal(unconfirmed)).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return confirmed == null ? "0" : confirmed;
        }
    }

    /** True if a &lt; b for two decimal MINIMA strings (best-effort). */
    private static boolean isLess(String a, String b) {
        try {
            return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void rebuildTokenCards() {
        if (mTokenList == null) {
            return;
        }
        mTokenList.removeAllViews();
        List<CoinAggregator.Agg> aggs = mAggs;
        if (aggs.isEmpty()) {
            // Show at least the Minima row from the hero figure.
            mTokenList.addView(emptyTokenHint());
            return;
        }
        for (CoinAggregator.Agg a : aggs) {
            mTokenList.addView(tokenCard(a), rowParams());
        }
    }

    private View emptyTokenHint() {
        return sub(m(R.string.l10n_android_wallet_empty_coins));
    }

    private View tokenCard(CoinAggregator.Agg agg) {
        LinearLayout row = hrow();
        ImageView icon = coinIcon(agg);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
        ip.rightMargin = dp(12);
        row.addView(icon, ip);

        LinearLayout mid = new LinearLayout(mAct);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView sym = new TextView(mAct);
        Language.setText(sym, tokenTicker(agg));
        sym.setTextSize(14);
        sym.setTypeface(Typeface.DEFAULT_BOLD);
        sym.setLetterSpacing(0.02f);
        sym.setTextColor(col(R.color.ux_text));
        TextView nm = new TextView(mAct);
        Language.setText(nm, tokenDisplayName(agg));
        nm.setTextSize(11.5f);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setTextColor(col(R.color.ux_subtext));
        mid.addView(sym);
        mid.addView(nm);
        LinearLayout.LayoutParams mp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(mid, mp);

        LinearLayout right = new LinearLayout(mAct);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        TextView amt = new TextView(mAct);
        Language.setText(amt, Amounts.list(agg.displayTotal.toString()));
        amt.setTextSize(15);
        amt.setTypeface(Typeface.DEFAULT_BOLD);
        amt.setTextColor(col(R.color.ux_text));
        amt.setGravity(Gravity.END);
        TextView cnt = new TextView(mAct);
        Language.setText(cnt, Language.upper(Language.count(R.plurals.l10n_cloud_wallet_coins, agg.count)));
        cnt.setTextSize(9.5f);
        cnt.setLetterSpacing(0.08f);
        cnt.setTextColor(col(R.color.ux_subtext));
        cnt.setGravity(Gravity.END);
        right.addView(amt);
        right.addView(cnt);
        row.addView(right);

        row.setOnClickListener(v -> showTokenDetail(agg));
        return row;
    }

    private ImageView coinIcon(CoinAggregator.Agg agg) {
        ImageView iv = new ImageView(mAct);
        int px = dp(40);
        if (agg.isMinima()) {
            // The real Minima logo mark (media kit), white on the dark coin disc.
            iv.setBackgroundResource(R.drawable.coin_minima_bg);
            iv.setImageResource(R.drawable.ic_minima_mark);
            iv.setColorFilter(Color.WHITE);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(px * 0.26f);
            iv.setPadding(pad, pad, pad, pad);
        } else {
            // Base layer: the deterministic identicon, circle-cropped — shown until (and unless) the
            // token's real icon loads. Clip the view to an oval so a swapped-in real icon stays circular.
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            iv.setImageBitmap(Identicon.forToken(agg.tokenid, px));
            // Real-icon layer: resolve the token's own icon from its metadata (token.url / nested
            // name.icon, incl. the on-chain <artimage> base64 wrapper) and swap it in on load. The
            // identicon stays on absence or failure. Mirrors NFTwallet BalancesView.buildCard.
            TokenMeta meta = TokenMeta.parse(agg.tokenJson, agg.tokenid);
            ImageLoader.loadOver(mAct, meta.iconUrl, iv, null);
        }
        return iv;
    }

    private String tokenTicker(CoinAggregator.Agg agg) {
        if (agg.isMinima()) {
            return "MINIMA";
        }
        String n = tokenName(agg);
        if (n != null && n.length() <= 12 && !n.equals("Token")) {
            return n.toUpperCase();
        }
        String hex = agg.tokenid == null ? "" : agg.tokenid.replaceFirst("(?i)^0x", "");
        return "TOKEN " + (hex.length() >= 4 ? hex.substring(0, 4).toUpperCase() : hex.toUpperCase());
    }

    /** Token display name, dug defensively out of the descriptor JSON. */
    private String tokenName(CoinAggregator.Agg agg) {
        if (agg.isMinima()) {
            return "Minima";
        }
        try {
            org.minima.utils.json.JSONObject tj = agg.tokenJson;
            if (tj != null) {
                Object name = tj.get("name");
                if (name instanceof org.minima.utils.json.JSONObject) {
                    Object inner = ((org.minima.utils.json.JSONObject) name).get("name");
                    if (inner != null) {
                        return String.valueOf(inner);
                    }
                } else if (name != null) {
                    return String.valueOf(name);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private CharSequence tokenDisplayName(CoinAggregator.Agg agg) {
        String name = tokenName(agg);
        return name == null ? m(R.string.l10n_wallet_token) : name;
    }

    // ---------------------------------------------------------------
    // Token detail sheet
    // ---------------------------------------------------------------

    private void showTokenDetail(CoinAggregator.Agg agg) {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        // header: icon + name
        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = coinIcon(agg);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(48), dp(48));
        ip.rightMargin = dp(12);
        head.addView(icon, ip);
        LinearLayout hn = new LinearLayout(mAct);
        hn.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(mAct);
        Language.setText(t1, tokenTicker(agg));
        t1.setTextSize(17);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(col(R.color.ux_text));
        TextView t2 = new TextView(mAct);
        Language.setText(t2, Language.join(tokenDisplayName(agg), agg.isMinima() ? Language.join(" · ", m(R.string.l10n_android_wallet_native)) : ""));
        t2.setTextSize(12);
        t2.setTextColor(col(R.color.ux_subtext));
        hn.addView(t1);
        hn.addView(t2);
        head.addView(hn);
        body.addView(head, marginBottom(dp(14)));

        String tid = agg.tokenid;
        String sendable = Amounts.full(agg.displayTotal.toString());
        String conf = Amounts.full(mapGet(mConf, tid,
                agg.isMinima() ? mConfMinima : agg.displayTotal.toString()));
        String pend = Amounts.full(mapGet(mUnconf, tid, agg.isMinima() ? mUnconfMinima : "0"));
        String locked = "0";
        try {
            BigDecimal l = new BigDecimal(conf).subtract(new BigDecimal(sendable));
            locked = l.signum() < 0 ? "0" : Amounts.tidy(l.toPlainString());
        } catch (Exception ignored) {
        }

        body.addView(kv(m(R.string.l10n_wallet_sendable), sendable));
        body.addView(kv(m(R.string.l10n_wallet_confirmed), conf));
        body.addView(kv(m(R.string.l10n_wallet_pending), pend));
        body.addView(kv(m(R.string.l10n_android_wallet_locked), locked));
        body.addView(kv(m(R.string.l10n_wallet_coins), String.valueOf(agg.count)));
        LinearLayout supplyRow = kv(m(R.string.l10n_wallet_supply),
                agg.isMinima() ? (mSupplyMinima == null ? "…" : mSupplyMinima) : "—");
        final TextView supplyVal = (TextView) supplyRow.getChildAt(1);
        body.addView(supplyRow);

        body.addView(copyField(m(R.string.l10n_wallet_token_id), agg.tokenid, m(R.string.l10n_cloud_wallet_token_copied)));

        if (agg.isMinima() && mSupplyMinima == null) {
            mPub.cmd("status", new WalletPublisher.Cb() {
                public void onResult(JSONObject r) {
                    try {
                        JSONObject resp = r.optJSONObject("response");
                        String sup = resp == null ? null : resp.optString("minima", null);
                        if (sup != null && !sup.isEmpty()) {
                            mSupplyMinima = Amounts.list(sup);
                            post(() -> Language.setText(supplyVal, mSupplyMinima));
                        }
                    } catch (Exception ignored) {
                    }
                }

                public void onError(String m) {
                }
            });
        }

        sheet(tokenDisplayName(agg), body);
    }

    // ---------------------------------------------------------------
    // Send / Receive sheets
    // ---------------------------------------------------------------

    private void showSend() {
        final MaximaWallet w = mWallet;
        if (w == null) {
            toast(m(R.string.l10n_android_wallet_opening));
            return;
        }
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        // To field + scan button
        LinearLayout toRow = new LinearLayout(mAct);
        toRow.setOrientation(LinearLayout.HORIZONTAL);
        toRow.setGravity(Gravity.CENTER_VERTICAL);
        toRow.setBackgroundResource(R.drawable.field_pill);
        int fp = dp(12);
        toRow.setPadding(fp, dp(4), dp(6), dp(4));
        final EditText to = new EditText(mAct);
        Language.hint(to, m(R.string.l10n_cloud_wallet_address_hint));
        to.setBackground(null);
        to.setTextColor(col(R.color.ux_text));
        to.setHintTextColor(col(R.color.ux_subtext));
        to.setTextSize(14);
        toRow.addView(to, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView scan = new ImageView(mAct);
        scan.setImageResource(R.drawable.ic_scan);
        Language.description(scan, m(R.string.l10n_common_scan_qr));
        scan.setColorFilter(col(R.color.ux_on_accent));
        scan.setBackgroundResource(R.drawable.btn_send_circle);
        int sp = dp(8);
        scan.setPadding(sp, sp, sp, sp);
        scan.setOnClickListener(v -> mAct.scanQr(contents -> Language.setText(to, contents)));
        toRow.addView(scan, new LinearLayout.LayoutParams(dp(38), dp(38)));
        body.addView(toRow, marginBottom(dp(11)));

        // Currency: Minima or MxUSD. Token inputs go byte-exact via coinexport (TokenSend).
        final android.widget.RadioGroup currency = new android.widget.RadioGroup(mAct);
        currency.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        final android.widget.RadioButton minimaBtn = new android.widget.RadioButton(mAct);
        minimaBtn.setText("Minima"); minimaBtn.setTextColor(col(R.color.ux_text)); minimaBtn.setChecked(true);
        final android.widget.RadioButton usdBtn = new android.widget.RadioButton(mAct);
        usdBtn.setText(com.eurobuddha.maxima.app.wallet.TokenSend.MXUSD_NAME); usdBtn.setTextColor(col(R.color.ux_text));
        currency.addView(minimaBtn); currency.addView(usdBtn);
        body.addView(currency, marginBottom(dp(6)));

        final EditText amt = new EditText(mAct);
        Language.hint(amt, m(R.string.l10n_wallet_amount));
        amt.setBackgroundResource(R.drawable.field_pill);
        amt.setPadding(fp, fp, fp, fp);
        amt.setTextColor(col(R.color.ux_text));
        amt.setHintTextColor(col(R.color.ux_subtext));
        amt.setTextSize(14);
        amt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        body.addView(amt, marginBottom(dp(6)));

        final TextView status = sub("");
        final TextView btn = primaryButton(m(R.string.l10n_desktop_chat_sign_send));
        body.addView(btn, marginBottom(dp(6)));
        body.addView(status);

        BottomSheetDialog d = sheet(m(R.string.l10n_chat_send), body);
        btn.setOnClickListener(v -> sendPayment(w, to.getText().toString().trim(),
                amt.getText().toString().trim(), status, d,
                usdBtn.isChecked() ? com.eurobuddha.maxima.app.wallet.TokenSend.MXUSD_TOKENID : com.eurobuddha.maxima.app.wallet.TokenSend.MINIMA_TOKENID,
                usdBtn.isChecked() ? com.eurobuddha.maxima.app.wallet.TokenSend.MXUSD_NAME : com.eurobuddha.maxima.app.wallet.TokenSend.MINIMA_NAME));
    }

    private void sendPayment(final MaximaWallet w, final String to, final String amtStr,
                             final TextView status, final BottomSheetDialog sheet,
                             final String tokenId, final String tokenName) {
        if (to.isEmpty() || amtStr.isEmpty()) {
            Language.setText(status, m(R.string.l10n_cloud_wallet_address_amount));
            return;
        }
        final MiniNumber amount;
        try {
            amount = new MiniNumber(amtStr);
        } catch (Exception e) {
            Language.setText(status, m(R.string.l10n_desktop_chat_bad_amount));
            return;
        }
        if (!amount.isMore(MiniNumber.ZERO)) {
            Language.setText(status, m(R.string.l10n_desktop_chat_positive_amount));
            return;
        }
        if (mSending) {
            return;   // already signing/publishing — never double-burn a key use
        }
        mSending = true;
        Language.setText(status, m(R.string.l10n_wallet_selecting));
        mIo.execute(() -> {
            try {
                final Object lock = new Object();
                final JSONObject[] resp = new JSONObject[1];
                final String[] err = new String[1];
                mPub.coins(w.hexAddress(), new WalletPublisher.Cb() {
                    public void onResult(JSONObject r) {
                        synchronized (lock) { resp[0] = r; lock.notifyAll(); }
                    }

                    public void onError(String m) {
                        synchronized (lock) { err[0] = m; lock.notifyAll(); }
                    }
                });
                synchronized (lock) {
                    long deadline = System.currentTimeMillis() + 45_000;
                    while (resp[0] == null && err[0] == null) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            break;   // real timeout, not a spurious wakeup
                        }
                        lock.wait(remaining);
                    }
                }
                if (resp[0] == null) {
                    throw new IllegalStateException(err[0] != null ? err[0] : "coins timeout");
                }

                org.minima.utils.json.JSONObject full =
                        (org.minima.utils.json.JSONObject) new org.minima.utils.json.parser.JSONParser()
                                .parse(resp[0].toString());
                org.minima.utils.json.JSONArray coins =
                        (org.minima.utils.json.JSONArray) full.get("response");

                final boolean minima = com.eurobuddha.maxima.app.wallet.TokenSend.isMinima(tokenId);
                final MiniNumber raw = com.eurobuddha.maxima.app.wallet.TokenSend.rawAmount(coins, tokenId, amount);
                List<org.minima.utils.json.JSONObject> sel =
                        CoinSelector.selectToCover(coins, minima ? "0x00" : tokenId, raw);
                List<TxnFactory.InputCoin> inputs =
                        com.eurobuddha.maxima.app.wallet.TokenSend.resolveInputs(mPub, sel, MaximaWallet.KEY_INDEX);

                post(() -> Language.setText(status, m(R.string.l10n_cloud_wallet_signing)));
                TxnFactory factory = new TxnFactory(w.core());
                final TxnFactory.BuiltTxn built = factory.buildSend(inputs, to, raw,
                        minima ? TxnFactory.TOKEN_MINIMA : new org.minima.objects.base.MiniData(tokenId),
                        MiniNumber.ZERO, "mxw" + System.currentTimeMillis());

                post(() -> Language.setText(status, m(R.string.l10n_wallet_publishing, SystemMessages.message(mPub.backendName()))));
                mPub.publish(built.getTxnImportCommand(), built.getID(),
                        built.getTxnPostCommand(), new WalletPublisher.Cb() {
                            public void onResult(JSONObject r) {
                                mSending = false;
                                WalletLedger.add(mAct, true, amount.toString(), tokenName,
                                        to, built.getID(), tokenId);
                                post(() -> {
                                    Language.setText(status, m(R.string.l10n_wallet_mining));
                                    toast(m(R.string.l10n_android_wallet_mining));
                                    renderUses(w);
                                    mLastFetch = 0;
                                    render();
                                    if (sheet != null) {
                                        status.postDelayed(sheet::dismiss, 1200);
                                    }
                                });
                            }

                            public void onError(String m) {
                                mSending = false;
                                post(() -> Language.setText(status, m(R.string.l10n_android_wallet_publish_failed, m)));
                            }
                        });
            } catch (CoinSelector.InsufficientFundsException ife) {
                mSending = false;
                post(() -> Language.setText(status, m(R.string.l10n_wallet_insufficient)));
            } catch (Exception e) {
                mSending = false;
                post(() -> Language.setText(status, m(R.string.l10n_wallet_send_failed, e.getMessage())));
            }
        });
    }

    /**
     * Make funded-before-tracked coins spendable. A coin that arrived at our address
     * BEFORE the node was tracking our script is confirmed on-chain (so `balance
     * megammr:true` shows it and it counts in TOTAL BALANCE) but is NOT in the node's
     * own tracked set — so it has no local MMR proof, {@code txnbasics} can't attach
     * one, and {@code coins} (non-megammr) reports {@code sendable:0}. That is exactly
     * why a freshly reinstalled wallet looked "empty".
     *
     * The fix is the standard Minima backfill, the same one the gateway allowlist was
     * built for: track the script, then for each coin {@code coinexport} it out of the
     * global MegaMMR and {@code coinimport track:true} it back into the node's tracked
     * set (coin + proof). After that the coins are spendable and {@code sendable} fills
     * to match {@code confirmed}. Runs OFF-MAIN. It NEVER signs and NEVER spends — no
     * key use is burned — so it is safe to auto-run when we detect the lag.
     */
    private void syncCoins(final MaximaWallet w, final Runnable zOnDone) {
        if (w == null || w.hexAddress() == null || mSyncing) {
            return;
        }
        mSyncing = true;
        mIo.execute(() -> {
            int imported = 0, failed = 0;
            try {
                // 1) Track our script so coins register locally from here on.
                try {
                    cmdBlocking("newscript trackall:true script:\"" + w.script() + "\"");
                } catch (Exception trackErr) {
                    // Non-fatal: the script may already be tracked.
                    EventLog.add("wallet: track script note: " + trackErr.getMessage());
                }

                // 2) Enumerate our coins from the global MegaMMR.
                JSONObject coinsResp = cmdBlocking("coins megammr:true address:" + w.hexAddress());
                org.json.JSONArray arr = coinsResp.optJSONArray("response");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String coinid = arr.getJSONObject(i).optString("coinid", "");
                        if (coinid.isEmpty()) {
                            continue;
                        }
                        try {
                            // 3) Export the coin+proof, then import it into the tracked set.
                            JSONObject ex = cmdBlocking("coinexport coinid:" + coinid);
                            JSONObject exResp = ex.optJSONObject("response");
                            String data = exResp != null ? exResp.optString("data", "") : "";
                            if (data.isEmpty()) {
                                failed++;
                                continue;
                            }
                            cmdBlocking("coinimport data:" + data + " track:true");
                            imported++;
                        } catch (Exception coinErr) {
                            failed++;
                            EventLog.add("wallet: coin sync skipped " + coinid
                                    + ": " + coinErr.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                EventLog.add("wallet: coin sync failed: " + e.getMessage());
            } finally {
                mSyncing = false;
            }
            final int fi = imported, ff = failed;
            EventLog.add("wallet: coin sync imported=" + fi + " failed=" + ff);
            post(() -> {
                if (fi > 0 && ff == 0) {
                    toast(m(R.string.l10n_android_wallet_synced, fi));
                } else if (ff > 0) {
                    toast(m(R.string.l10n_android_wallet_sync_pending, fi, ff));
                }
                mLastFetch = 0;   // force the next render() to refetch balance + coins
                render();
                if (zOnDone != null) {
                    zOnDone.run();
                }
            });
        });
    }

    /**
     * Run a single node/gateway command and BLOCK for its reply. Off-main only (it
     * parks the calling thread up to 45s). Mirrors {@link #sendPayment}'s lock/wait.
     */
    private JSONObject cmdBlocking(String zCommand) throws Exception {
        final Object lock = new Object();
        final JSONObject[] resp = new JSONObject[1];
        final String[] err = new String[1];
        // The coin backfill (newscript / coinexport / coinimport) is entirely MegaMMR-
        // dependent, so it MUST run against the hosted gateway, never a paired local
        // node — same reason balance/coins do (see WalletPublisher.gcmd).
        mPub.gcmd(zCommand, new WalletPublisher.Cb() {
            public void onResult(JSONObject r) {
                synchronized (lock) { resp[0] = r; lock.notifyAll(); }
            }

            public void onError(String m) {
                synchronized (lock) { err[0] = m; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 45_000;
            while (resp[0] == null && err[0] == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                lock.wait(remaining);
            }
        }
        if (resp[0] == null) {
            throw new IllegalStateException(
                    err[0] != null ? err[0] : "timeout: " + zCommand);
        }
        return resp[0];
    }

    private void showReceive() {
        final MaximaWallet w = mWallet;
        if (w == null || w.mxAddress() == null) {
            toast(m(R.string.l10n_android_wallet_opening));
            return;
        }
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView qr = new ImageView(mAct);
        Bitmap b = Qr.encode(w.mxAddress(), dp(220));
        if (b != null) {
            qr.setImageBitmap(b);
        }
        int qp = dp(12);
        qr.setPadding(qp, qp, qp, qp);
        qr.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(200), dp(200));
        qlp.bottomMargin = dp(14);
        body.addView(qr, qlp);

        body.addView(copyField(m(R.string.l10n_native_contacts_your_address), w.mxAddress(), m(R.string.l10n_native_contacts_address_copied)),
                new LinearLayout.LayoutParams(-1, -2));
        TextView note = sub(m(R.string.l10n_android_wallet_receive_help, SystemMessages.message(mPub.backendName())));
        note.setPadding(dp(2), dp(8), dp(2), 0);
        body.addView(note);

        sheet(m(R.string.l10n_wallet_receive_minima), body);
    }

    // ---------------------------------------------------------------
    // History pane
    // ---------------------------------------------------------------

    private void renderHistory() {
        if (mHistory == null) {
            return;
        }
        mHistory.removeAllViews();
        java.util.List<WalletLedger.Row> rows = WalletLedger.list(mAct);
        List<WalletLedger.Row> shownRows = new ArrayList<>();
        for (WalletLedger.Row r : rows) {
            if (mFilter == 1 && !r.sent) {
                continue;
            }
            if (mFilter == 2 && r.sent) {
                continue;
            }
            shownRows.add(r);
        }
        if (shownRows.isEmpty()) {
            mHistory.addView(sub(mFilter == 0 ? m(R.string.l10n_android_wallet_empty_history)
                    : mFilter == 1 ? m(R.string.l10n_android_wallet_empty_sent) : m(R.string.l10n_android_wallet_empty_received)));
            return;
        }
        int shown = 0;
        for (WalletLedger.Row r : shownRows) {
            if (shown >= 60) {
                break;
            }
            if (shown > 0) {
                mHistory.addView(divider());
            }
            mHistory.addView(historyRow(r));
            shown++;
        }
    }

    private View historyRow(WalletLedger.Row r) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(android.R.color.transparent);
        int vp = dp(11);
        row.setPadding(dp(4), vp, dp(4), vp);
        row.setClickable(true);
        row.setForeground(ripple());

        ImageView dir = new ImageView(mAct);
        dir.setBackgroundResource(R.drawable.avatar);
        dir.setImageResource(r.sent ? R.drawable.ic_arrow_up : R.drawable.ic_arrow_down);
        dir.setColorFilter(col(r.sent ? R.color.ux_text : R.color.ux_success));
        int dp10 = dp(10);
        dir.setPadding(dp10, dp10, dp10, dp10);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        dlp.rightMargin = dp(12);
        row.addView(dir, dlp);

        LinearLayout mid = new LinearLayout(mAct);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView a = new TextView(mAct);
        Language.setText(a, (r.sent ? "−" : "+") + Amounts.list(r.amount) + " " + r.token);
        a.setTextSize(14.5f);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        a.setTextColor(col(r.sent ? R.color.ux_text : R.color.ux_success));
        TextView who = new TextView(mAct);
        Language.setText(who, Language.join(previewWho(r), "  ·  ", relTime(r.time)));
        who.setTextSize(11.5f);
        who.setSingleLine(true);
        who.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        who.setTextColor(col(R.color.ux_subtext));
        mid.addView(a);
        mid.addView(who);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        row.setOnClickListener(v -> showHistoryDetail(r));
        return row;
    }

    private void showHistoryDetail(WalletLedger.Row r) {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        body.addView(kv(m(R.string.l10n_wallet_direction), r.sent ? m(R.string.l10n_wallet_sent) : m(R.string.l10n_wallet_received)));
        body.addView(kv(m(R.string.l10n_wallet_amount), (r.sent ? "−" : "+") + Amounts.full(r.amount) + " " + r.token));
        body.addView(kv(m(R.string.l10n_wallet_token), r.token));
        body.addView(kv(m(R.string.l10n_wallet_when), fullTime(r.time)));
        if (!"0x00".equals(r.tokenid) && !r.tokenid.isEmpty()) {
            body.addView(copyField(m(R.string.l10n_wallet_token_id), r.tokenid, m(R.string.l10n_cloud_wallet_token_copied)));
        }
        body.addView(copyField(r.sent ? m(R.string.l10n_wallet_to) : m(R.string.l10n_wallet_from),
                r.who.isEmpty() ? "—" : r.who, m(R.string.l10n_native_contacts_address_copied)));
        body.addView(copyField(m(R.string.l10n_wallet_transaction_id),
                r.txid.isEmpty() ? "—" : r.txid, m(R.string.l10n_android_chat_tx_copied)));

        sheet(Language.join(r.sent ? m(R.string.l10n_wallet_sent) : m(R.string.l10n_wallet_received), " · ", Amounts.list(r.amount), " ", r.token), body);
    }

    private CharSequence previewWho(WalletLedger.Row r) {
        if (r.who == null || r.who.isEmpty()) {
            return r.sent ? m(R.string.l10n_wallet_sent) : m(R.string.l10n_wallet_received);
        }
        String s = r.who;
        if (s.length() > 20) {
            return s.substring(0, 12) + "…" + s.substring(s.length() - 6);
        }
        return s;
    }

    // ---------------------------------------------------------------
    // Sends-remaining (key uses)
    // ---------------------------------------------------------------

    private void renderUses(MaximaWallet w) {
        int used = w.uses(mAct);
        if (mUsesBig != null) {
            Language.setText(mUsesBig, String.valueOf(used));
        }
        if (mUses != null) {
            Language.setText(mUses, m(R.string.l10n_android_wallet_signatures_left, KEY_TOTAL - used, KEY_TOTAL));
        }
    }

    /** Raise-only reconcile, then teach WHY on the way out. */
    private void adjustUses() {
        MaximaWallet w = mWallet;
        final int cur = w == null ? 0 : w.uses(mAct);
        final EditText input = new EditText(mAct);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        Language.setText(input, String.valueOf(cur));
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_android_wallet_set_uses))
                .setMessage(m(R.string.l10n_android_wallet_uses_warning, cur))
                .setView(input)
                .setNegativeButton(m(R.string.l10n_common_cancel), (d, wc) -> showUsesExplainer())
                .setPositiveButton(m(R.string.l10n_native_settings_set), (d, wc) -> {
                    int n;
                    try {
                        n = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception e) {
                        toast(m(R.string.l10n_android_wallet_number_required));
                        showUsesExplainer();
                        return;
                    }
                    if (n <= cur) {
                        toast(m(R.string.l10n_android_wallet_uses_ignored, cur));
                    } else {
                        MaximaWallet.setUsesAtLeast(mAct, n);
                        if (mWallet != null) {
                            renderUses(mWallet);
                        }
                        toast(m(R.string.l10n_android_wallet_uses_set, n));
                    }
                    showUsesExplainer();
                })
                .show();
    }

    private void showUsesExplainer() {
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_android_wallet_why))
                .setMessage(m(R.string.l10n_android_wallet_uses_help))
                .setPositiveButton(m(R.string.l10n_help_got_it), null)
                .show();
    }

    private void showWalletSettings() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView src = sub(MaximaWallet.hasCustomPhrase(mAct)
                ? m(R.string.l10n_android_wallet_independent)
                : m(R.string.l10n_android_wallet_identity));
        src.setPadding(0, 0, 0, dp(12));
        body.addView(src);
        TextView node = ghostButton(m(R.string.l10n_android_wallet_node));
        body.addView(node, marginBottom(dp(8)));
        TextView imp = primaryButton(m(R.string.l10n_android_wallet_import_phrase));
        body.addView(imp, marginBottom(dp(8)));
        TextView rev = ghostButton(m(R.string.l10n_android_wallet_use_identity));
        body.addView(rev);
        BottomSheetDialog d = sheet(m(R.string.l10n_android_wallet_settings), body);
        node.setOnClickListener(v -> { d.dismiss(); showNodeConfig(); });
        imp.setOnClickListener(v -> { d.dismiss(); importSeed(); });
        rev.setOnClickListener(v -> { d.dismiss(); revertSeed(); });
    }

    /**
     * The "Wallet node" sheet — a faithful port of FreezePeach's nodeConfigSheet. Maxima
     * has no local Minima node, so the wallet reads its balance from a Minima node with
     * MegaMMR over the /cmd proxy. By default that is the hosted MegaMMR relay (so it
     * works out of the box); set this to YOUR OWN node's endpoint to be fully self-hosted.
     * The seed never leaves the phone — only reads + the pre-signed txn relay go here.
     */
    private void showNodeConfig() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView explain = sub(m(R.string.l10n_android_wallet_node_help, WalletPublisher.fleetGatewayUrls().length));
        explain.setPadding(0, 0, 0, dp(12));
        body.addView(explain);

        final EditText urlF = new EditText(mAct);
        Language.hint(urlF, "https://your-node…/cmd");
        urlF.setSingleLine(true);
        Language.setText(urlF, com.eurobuddha.maxima.app.wallet.WalletPublisher.currentGatewayUrl(mAct));
        styleField(urlF);
        body.addView(urlF, marginBottom(dp(8)));

        final EditText tokF = new EditText(mAct);
        Language.hint(tokF, m(R.string.l10n_android_wallet_access_token));
        tokF.setSingleLine(true);
        Language.setText(tokF, com.eurobuddha.maxima.app.wallet.WalletPublisher.currentGatewayToken(mAct));
        styleField(tokF);
        body.addView(tokF, marginBottom(dp(12)));

        TextView save = primaryButton(m(R.string.l10n_android_wallet_save_node));
        body.addView(save, marginBottom(dp(8)));
        TextView reset = ghostButton(m(R.string.l10n_android_wallet_reset_node));
        body.addView(reset);

        BottomSheetDialog d = sheet(m(R.string.l10n_android_wallet_node), body);
        save.setOnClickListener(v -> {
            com.eurobuddha.maxima.app.wallet.WalletPublisher.saveGateway(mAct,
                    urlF.getText().toString(), tokF.getText().toString());
            rebuildPublisher();
            d.dismiss();
            toast(m(R.string.l10n_android_wallet_node_saved));
        });
        reset.setOnClickListener(v -> {
            com.eurobuddha.maxima.app.wallet.WalletPublisher.saveGateway(mAct, "", "");
            rebuildPublisher();
            d.dismiss();
            toast(m(R.string.l10n_android_wallet_reset_node));
        });
    }

    /** Pill-styled single-line input, matching the Send sheet's fields. */
    private void styleField(EditText zField) {
        zField.setBackgroundResource(R.drawable.field_pill);
        int fp = dp(12);
        zField.setPadding(fp, fp, fp, fp);
        zField.setTextColor(col(R.color.ux_text));
        zField.setHintTextColor(col(R.color.ux_subtext));
        zField.setTextSize(13);
    }

    /** Recreate the publisher against the newly-saved endpoint and refresh immediately. */
    private void rebuildPublisher() {
        mPub = new WalletPublisher(mAct, mNode);
        mAutoSynced = false;   // let the backfill re-run against the new node
        mLastFetch = 0;        // force an immediate balance/coins refetch
        render();
    }

    private void importSeed() {
        final EditText input = new EditText(mAct);
        Language.hint(input, m(R.string.l10n_android_wallet_phrase_hint));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_android_wallet_import_title))
                .setMessage(m(R.string.l10n_android_wallet_import_warning))
                .setView(input)
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_android_wallet_import), (d, w) -> {
                    String phrase = input.getText().toString().trim();
                    if (phrase.isEmpty()) {
                        toast(m(R.string.l10n_android_wallet_phrase_required));
                        return;
                    }
                    MaximaWallet.setCustomPhrase(mAct, phrase);
                    toast(m(R.string.l10n_android_wallet_importing));
                    openWallet();
                })
                .show();
    }

    private void revertSeed() {
        if (!MaximaWallet.hasCustomPhrase(mAct)) {
            toast(m(R.string.l10n_android_wallet_already_identity));
            return;
        }
        new LanguageDialog(mAct)
                .setTitle(m(R.string.l10n_android_wallet_revert_title))
                .setMessage(m(R.string.l10n_android_wallet_revert_warning))
                .setNegativeButton(m(R.string.l10n_common_cancel), null)
                .setPositiveButton(m(R.string.l10n_android_wallet_use_identity), (d, w) -> {
                    MaximaWallet.setCustomPhrase(mAct, null);
                    toast(m(R.string.l10n_android_wallet_reverting));
                    openWallet();
                })
                .show();
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private void buildUi() {
        // Segmented control.
        LinearLayout seg = new LinearLayout(mAct);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundResource(R.drawable.seg_track);
        int s4 = dp(4);
        seg.setPadding(s4, s4, s4, s4);
        mSegBalance = segItem(m(R.string.l10n_wallet_balance));
        mSegHistory = segItem(m(R.string.l10n_wallet_history));
        seg.addView(mSegBalance, new LinearLayout.LayoutParams(0, -2, 1f));
        seg.addView(mSegHistory, new LinearLayout.LayoutParams(0, -2, 1f));
        mSegBalance.setOnClickListener(v -> setSeg(0));
        mSegHistory.setOnClickListener(v -> setSeg(1));
        mBox.addView(seg, marginBottom(dp(14)));

        mBalancePane = new LinearLayout(mAct);
        mBalancePane.setOrientation(LinearLayout.VERTICAL);
        mHistoryPane = new LinearLayout(mAct);
        mHistoryPane.setOrientation(LinearLayout.VERTICAL);
        mBox.addView(mBalancePane);
        mBox.addView(mHistoryPane);

        buildBalancePane();
        buildHistoryPane();
        setSeg(0);
    }

    private void buildBalancePane() {
        // Hero card.
        LinearLayout hero = card();
        hero.setBackgroundResource(R.drawable.card);
        hero.addView(label(m(R.string.l10n_cloud_wallet_total_balance)));
        LinearLayout amtRow = new LinearLayout(mAct);
        amtRow.setOrientation(LinearLayout.HORIZONTAL);
        amtRow.setGravity(Gravity.BOTTOM);
        mHeroAmt = new TextView(mAct);
        Language.setText(mHeroAmt, "…");
        mHeroAmt.setTextSize(33);
        mHeroAmt.setLetterSpacing(-0.02f);
        mHeroAmt.setTypeface(manrope(Typeface.BOLD));
        mHeroAmt.setTextColor(col(R.color.ux_text));
        TextView unit = new TextView(mAct);
        Language.setText(unit, " MINIMA");
        unit.setTextSize(15);
        unit.setTypeface(Typeface.DEFAULT_BOLD);
        unit.setPadding(0, 0, 0, dp(4));
        unit.setTextColor(col(R.color.ux_subtext));
        amtRow.addView(mHeroAmt);
        amtRow.addView(unit);
        LinearLayout.LayoutParams arp = new LinearLayout.LayoutParams(-2, -2);
        arp.topMargin = dp(6);
        hero.addView(amtRow, arp);
        mHeroSub = sub(m(R.string.l10n_android_wallet_sendable));
        // Tapping the sub-line re-runs the coin backfill (see syncCoins): useful if the
        // one-shot auto-sync couldn't reach the node, or funds arrived after open.
        mHeroSub.setOnClickListener(v -> {
            MaximaWallet w = mWallet;
            if (w == null) {
                return;
            }
            if (mSyncing) {
                toast(m(R.string.l10n_android_wallet_syncing));
                return;
            }
            if (isLess(mSpendMinima, held(mConfMinima, mUnconfMinima))) {
                toast(m(R.string.l10n_android_wallet_syncing));
                syncCoins(w, null);
            }
        });
        hero.addView(mHeroSub);

        LinearLayout actions = new LinearLayout(mAct);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, -2);
        acp.topMargin = dp(14);
        TextView send = primaryButton(m(R.string.l10n_chat_send));
        TextView recv = ghostButton(m(R.string.l10n_wallet_receive));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
        l.rightMargin = dp(5);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.leftMargin = dp(5);
        actions.addView(send, l);
        actions.addView(recv, rp);
        hero.addView(actions, acp);
        send.setOnClickListener(v -> showSend());
        recv.setOnClickListener(v -> showReceive());
        mBalancePane.addView(hero, marginBottom(dp(4)));

        mBalancePane.addView(sectionLabel(m(R.string.l10n_android_wallet_your_tokens)));
        mTokenList = new LinearLayout(mAct);
        mTokenList.setOrientation(LinearLayout.VERTICAL);
        mBalancePane.addView(mTokenList, marginBottom(dp(6)));

        mBalancePane.addView(sectionLabel(m(R.string.l10n_help_keyuses_title)));
        LinearLayout sends = card();
        LinearLayout sr = new LinearLayout(mAct);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        mUsesBig = new TextView(mAct);
        Language.setText(mUsesBig, "…");
        mUsesBig.setTextSize(30);
        mUsesBig.setTypeface(manrope(Typeface.BOLD));
        mUsesBig.setTextColor(col(R.color.ux_accent));
        LinearLayout.LayoutParams ubp = new LinearLayout.LayoutParams(-2, -2);
        ubp.rightMargin = dp(14);
        sr.addView(mUsesBig, ubp);
        LinearLayout st = new LinearLayout(mAct);
        st.setOrientation(LinearLayout.VERTICAL);
        st.addView(label(m(R.string.l10n_wallet_used)));
        TextView hint = sub(m(R.string.l10n_android_wallet_adjust_hint));
        hint.setPadding(0, dp(4), 0, 0);
        st.addView(hint);
        sr.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
        sends.addView(sr);
        sends.setOnLongClickListener(v -> {
            adjustUses();
            return true;
        });
        mBalancePane.addView(sends, marginBottom(dp(10)));

        TextView settings = ghostButton(m(R.string.l10n_android_wallet_settings));
        settings.setOnClickListener(v -> showWalletSettings());
        mBalancePane.addView(settings, marginBottom(dp(12)));
    }

    private void buildHistoryPane() {
        LinearLayout chips = new LinearLayout(mAct);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        CharSequence[] labels = {m(R.string.l10n_wallet_all), m(R.string.l10n_wallet_sent), m(R.string.l10n_wallet_received)};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = new TextView(mAct);
            Language.setText(c, labels[i]);
            c.setTextSize(12);
            c.setTypeface(Typeface.DEFAULT_BOLD);
            c.setPadding(dp(13), dp(6), dp(13), dp(6));
            c.setOnClickListener(v -> setFilter(idx));
            mChips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chips.addView(c, lp);
        }
        mHistoryPane.addView(chips, marginBottom(dp(10)));

        LinearLayout hc = card();
        mHistory = new LinearLayout(mAct);
        mHistory.setOrientation(LinearLayout.VERTICAL);
        hc.addView(mHistory);
        mHistoryPane.addView(hc, marginBottom(dp(12)));
        setFilter(0);
    }

    private void setSeg(int i) {
        boolean bal = i == 0;
        mSegBalance.setBackgroundResource(bal ? R.drawable.seg_thumb : 0);
        mSegHistory.setBackgroundResource(!bal ? R.drawable.seg_thumb : 0);
        mSegBalance.setTextColor(col(bal ? R.color.ux_text : R.color.ux_subtext));
        mSegHistory.setTextColor(col(!bal ? R.color.ux_text : R.color.ux_subtext));
        mBalancePane.setVisibility(bal ? View.VISIBLE : View.GONE);
        mHistoryPane.setVisibility(bal ? View.GONE : View.VISIBLE);
        if (!bal) {
            renderHistory();
        }
    }

    private void setFilter(int i) {
        mFilter = i;
        for (int c = 0; c < 3; c++) {
            boolean on = c == i;
            mChips[c].setBackgroundResource(on ? R.drawable.chip_on : R.drawable.chip_off);
            mChips[c].setTextColor(col(on ? R.color.ux_on_accent : R.color.ux_subtext));
        }
        renderHistory();
    }

    // ---------------------------------------------------------------
    // Sheet + widget helpers
    // ---------------------------------------------------------------

    private BottomSheetDialog sheet(CharSequence title, View content) {
        LinearLayout root = new LinearLayout(mAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.sheet_bg);
        root.setPadding(dp(20), dp(8), dp(20), dp(24));

        View grab = new View(mAct);
        grab.setBackgroundResource(R.drawable.grab_handle);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(10);
        root.addView(grab, gp);

        TextView t = new TextView(mAct);
        Language.setText(t, title);
        t.setTextSize(17);
        t.setTypeface(manrope(Typeface.BOLD));
        t.setTextColor(col(R.color.ux_text));
        t.setPadding(0, 0, 0, dp(14));
        root.addView(t);

        ScrollView sv = new ScrollView(mAct);
        sv.addView(content);
        root.addView(sv);

        BottomSheetDialog d = new BottomSheetDialog(mAct);
        d.setContentView(root);
        View bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) {
            bs.setBackgroundColor(Color.TRANSPARENT);
        }
        // Keyboard: a sheet with a field must RISE above it, not sit under it (the
        // "add a contact" / send / wallet-node sheets all suffered). ADJUST_RESIZE on the
        // dialog window shrinks the sheet to the space above the keyboard; EXPANDED keeps
        // the whole sheet (field AND its button) in that space instead of a half peek.
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        d.getBehavior().setSkipCollapsed(true);
        d.show();
        return d;
    }

    /** A [LABEL ......... value] row for detail sheets. The value TextView is
     *  child index 1, so a caller can update it later (e.g. async supply). */
    private LinearLayout kv(CharSequence key, CharSequence value) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView k = new TextView(mAct);
        Language.setText(k, Language.upper(key));
        k.setTextSize(10);
        k.setLetterSpacing(0.1f);
        k.setTextColor(col(R.color.ux_subtext));
        TextView v = new TextView(mAct);
        Language.setText(v, value);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.END);
        v.setTextColor(col(R.color.ux_text));
        row.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.4f));
        return row;
    }

    private String mapGet(Map<String, String> m, String key, String def) {
        String v = m.get(key);
        return v == null ? def : v;
    }

    private LinearLayout copyField(CharSequence labelText, String value, CharSequence toastMsg) {
        LinearLayout box = new LinearLayout(mAct);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.field_pill);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(12);
        box.setLayoutParams(bp);

        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(mAct);
        Language.setText(l, Language.upper(labelText));
        l.setTextSize(10);
        l.setLetterSpacing(0.12f);
        l.setTextColor(col(R.color.ux_subtext));
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView cp = new ImageView(mAct);
        cp.setImageResource(R.drawable.ic_copy);
        cp.setColorFilter(col(R.color.ux_subtext));
        head.addView(cp, new LinearLayout.LayoutParams(dp(15), dp(15)));
        box.addView(head);

        TextView v = new TextView(mAct);
        Language.setText(v, value);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(12);
        v.setPadding(0, dp(7), 0, 0);
        v.setTextColor(col(R.color.ux_text));
        v.setTextIsSelectable(true);
        box.addView(v);

        box.setOnClickListener(view -> {
            ((ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("maxima-wallet", value));
            toast(toastMsg);
        });
        return box;
    }

    private View divider() {
        View d = new View(mAct);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        d.setLayoutParams(lp);
        d.setBackgroundColor(col(R.color.ux_divider));
        return d;
    }

    private android.graphics.drawable.Drawable ripple() {
        android.util.TypedValue tv = new android.util.TypedValue();
        mAct.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        return androidx.core.content.ContextCompat.getDrawable(mAct, tv.resourceId);
    }

    private TextView segItem(CharSequence text) {
        TextView t = new TextView(mAct);
        Language.setText(t, text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private TextView primaryButton(CharSequence text) {
        TextView b = new TextView(mAct);
        Language.setText(b, text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setTypeface(manrope(Typeface.BOLD));
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setTextColor(col(R.color.ux_on_accent));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView ghostButton(CharSequence text) {
        TextView b = new TextView(mAct);
        Language.setText(b, text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setTypeface(manrope(Typeface.BOLD));
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setTextColor(col(R.color.ux_text));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(mAct);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout hrow() {
        LinearLayout c = new LinearLayout(mAct);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = dp(13);
        c.setPadding(p, p, p, p);
        c.setClickable(true);
        c.setForeground(ripple());
        return c;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(9);
        return lp;
    }

    private LinearLayout.LayoutParams marginBottom(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px;
        return lp;
    }

    private TextView sectionLabel(CharSequence t) {
        TextView v = new TextView(mAct);
        Language.setText(v, t);
        v.setTextSize(10);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(0.15f);
        v.setTextColor(col(R.color.ux_subtext));
        v.setPadding(dp(4), dp(6), dp(4), dp(9));
        return v;
    }

    private TextView label(CharSequence t) {
        TextView v = new TextView(mAct);
        Language.setText(v, t);
        v.setTextSize(10);
        v.setLetterSpacing(0.15f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(col(R.color.ux_subtext));
        return v;
    }

    private TextView sub(CharSequence t) {
        TextView v = new TextView(mAct);
        Language.setText(v, t);
        v.setTextSize(12);
        v.setTextColor(col(R.color.ux_subtext));
        return v;
    }

    private Typeface manrope(int style) {
        try {
            Typeface base = androidx.core.content.res.ResourcesCompat.getFont(mAct, R.font.manrope);
            return Typeface.create(base, style);
        } catch (Exception e) {
            return Typeface.defaultFromStyle(style);
        }
    }

    private int col(int res) {
        return mAct.getResources().getColor(res, mAct.getTheme());
    }

    private int dp(float v) {
        return (int) (v * mAct.getResources().getDisplayMetrics().density);
    }

    private CharSequence relTime(long t) { return Language.since(t); }

    private CharSequence fullTime(long t) { return Language.stamp(t, "yMMMdHm"); }

    private static Language.Message m(int resource, Object... args) { return Language.msg(resource, args); }
    private void toast(CharSequence value) { mAct.toast(value.toString()); }

    private void post(Runnable r) {
        mAct.runOnUiThread(r);
    }
}
