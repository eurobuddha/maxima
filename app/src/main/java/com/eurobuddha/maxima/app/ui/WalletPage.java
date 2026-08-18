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
            MaximaWallet w = MaximaWallet.open(mAct);
            w.ensureAddress();
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
                    mHeroAmt.setText("…");
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
        mPub.trackScript(w.script(), new WalletPublisher.Cb() {
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
        return "Wallet";
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
                });
            }

            public void onError(String m) {
                EventLog.add("wallet: coins fetch error: " + m);
            }
        });
    }

    // ---------------------------------------------------------------
    // Balance pane rendering
    // ---------------------------------------------------------------

    private void repaintHero() {
        if (mHeroAmt != null) {
            mHeroAmt.setText(Amounts.list(mSpendMinima));
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
        return sub("No coins yet — receive some MINIMA to get started.");
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
        sym.setText(tokenTicker(agg));
        sym.setTextSize(14);
        sym.setTypeface(Typeface.DEFAULT_BOLD);
        sym.setLetterSpacing(0.02f);
        sym.setTextColor(col(R.color.ux_text));
        TextView nm = new TextView(mAct);
        nm.setText(tokenName(agg));
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
        amt.setText(Amounts.list(agg.displayTotal.toString()));
        amt.setTextSize(15);
        amt.setTypeface(Typeface.DEFAULT_BOLD);
        amt.setTextColor(col(R.color.ux_text));
        amt.setGravity(Gravity.END);
        TextView cnt = new TextView(mAct);
        cnt.setText((agg.count + (agg.count == 1 ? " coin" : " coins")).toUpperCase());
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
            Bitmap b = Identicon.forToken(agg.tokenid, px);
            RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(mAct.getResources(), b);
            d.setCircular(true);
            iv.setImageDrawable(d);
        }
        return iv;
    }

    private String tokenTicker(CoinAggregator.Agg agg) {
        if (agg.isMinima()) {
            return "MINIMA";
        }
        String n = tokenName(agg);
        if (n.length() <= 12 && !n.equals("Token")) {
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
        return "Token";
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
        t1.setText(tokenTicker(agg));
        t1.setTextSize(17);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(col(R.color.ux_text));
        TextView t2 = new TextView(mAct);
        t2.setText(tokenName(agg) + (agg.isMinima() ? " · native" : ""));
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

        body.addView(kv("Sendable", sendable));
        body.addView(kv("Confirmed", conf));
        body.addView(kv("Pending", pend));
        body.addView(kv("Locked", locked));
        body.addView(kv("Coins", String.valueOf(agg.count)));
        LinearLayout supplyRow = kv("Supply",
                agg.isMinima() ? (mSupplyMinima == null ? "…" : mSupplyMinima) : "—");
        final TextView supplyVal = (TextView) supplyRow.getChildAt(1);
        body.addView(supplyRow);

        body.addView(copyField("Token ID", agg.tokenid, "Token ID copied"));

        if (agg.isMinima() && mSupplyMinima == null) {
            mPub.cmd("status", new WalletPublisher.Cb() {
                public void onResult(JSONObject r) {
                    try {
                        JSONObject resp = r.optJSONObject("response");
                        String sup = resp == null ? null : resp.optString("minima", null);
                        if (sup != null && !sup.isEmpty()) {
                            mSupplyMinima = Amounts.list(sup);
                            post(() -> supplyVal.setText(mSupplyMinima));
                        }
                    } catch (Exception ignored) {
                    }
                }

                public void onError(String m) {
                }
            });
        }

        sheet(tokenName(agg), body);
    }

    // ---------------------------------------------------------------
    // Send / Receive sheets
    // ---------------------------------------------------------------

    private void showSend() {
        final MaximaWallet w = mWallet;
        if (w == null) {
            mAct.toast("Wallet still opening…");
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
        to.setHint("Mx… or 0x… address");
        to.setBackground(null);
        to.setTextColor(col(R.color.ux_text));
        to.setHintTextColor(col(R.color.ux_subtext));
        to.setTextSize(14);
        toRow.addView(to, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView scan = new ImageView(mAct);
        scan.setImageResource(R.drawable.ic_scan);
        scan.setColorFilter(col(R.color.ux_on_accent));
        scan.setBackgroundResource(R.drawable.btn_send_circle);
        int sp = dp(8);
        scan.setPadding(sp, sp, sp, sp);
        scan.setOnClickListener(v -> mAct.scanQr(contents -> to.setText(contents)));
        toRow.addView(scan, new LinearLayout.LayoutParams(dp(38), dp(38)));
        body.addView(toRow, marginBottom(dp(11)));

        final EditText amt = new EditText(mAct);
        amt.setHint("Amount");
        amt.setBackgroundResource(R.drawable.field_pill);
        amt.setPadding(fp, fp, fp, fp);
        amt.setTextColor(col(R.color.ux_text));
        amt.setHintTextColor(col(R.color.ux_subtext));
        amt.setTextSize(14);
        amt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        body.addView(amt, marginBottom(dp(6)));

        final TextView status = sub("");
        final TextView btn = primaryButton("Sign & send");
        body.addView(btn, marginBottom(dp(6)));
        body.addView(status);

        BottomSheetDialog d = sheet("Send MINIMA", body);
        btn.setOnClickListener(v -> sendPayment(w, to.getText().toString().trim(),
                amt.getText().toString().trim(), status, d));
    }

    private void sendPayment(final MaximaWallet w, final String to, final String amtStr,
                             final TextView status, final BottomSheetDialog sheet) {
        if (to.isEmpty() || amtStr.isEmpty()) {
            status.setText("Address and amount needed");
            return;
        }
        final MiniNumber amount;
        try {
            amount = new MiniNumber(amtStr);
        } catch (Exception e) {
            status.setText("Bad amount");
            return;
        }
        if (!amount.isMore(MiniNumber.ZERO)) {
            status.setText("Enter an amount greater than 0");
            return;
        }
        if (mSending) {
            return;   // already signing/publishing — never double-burn a key use
        }
        mSending = true;
        status.setText("Selecting coins…");
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

                List<org.minima.utils.json.JSONObject> sel =
                        CoinSelector.selectToCover(coins, "0x00", amount);
                List<TxnFactory.InputCoin> inputs = new ArrayList<>();
                for (org.minima.utils.json.JSONObject cn : sel) {
                    inputs.add(TxnFactory.fromCoinJson(cn, MaximaWallet.KEY_INDEX));
                }

                post(() -> status.setText("Signing…"));
                TxnFactory factory = new TxnFactory(w.core());
                final TxnFactory.BuiltTxn built = factory.buildSend(inputs, to, amount,
                        TxnFactory.TOKEN_MINIMA, MiniNumber.ZERO,
                        "mxw" + System.currentTimeMillis());

                post(() -> status.setText("Publishing via " + mPub.backendName() + "…"));
                mPub.publish(built.getTxnImportCommand(), built.getID(),
                        built.getTxnPostCommand(), new WalletPublisher.Cb() {
                            public void onResult(JSONObject r) {
                                mSending = false;
                                WalletLedger.add(mAct, true, amount.toString(), "MINIMA",
                                        to, built.getID(), "0x00");
                                post(() -> {
                                    status.setText("Sent ⛏ (mining)");
                                    mAct.toast("Sent — mining into a block");
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
                                post(() -> status.setText("Publish failed: " + m));
                            }
                        });
            } catch (CoinSelector.InsufficientFundsException ife) {
                mSending = false;
                post(() -> status.setText("Not enough confirmed funds"));
            } catch (Exception e) {
                mSending = false;
                post(() -> status.setText("Send failed: " + e.getMessage()));
            }
        });
    }

    private void showReceive() {
        final MaximaWallet w = mWallet;
        if (w == null || w.mxAddress() == null) {
            mAct.toast("Wallet still opening…");
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

        body.addView(copyField("Your address", w.mxAddress(), "Address copied"),
                new LinearLayout.LayoutParams(-1, -2));
        TextView note = sub("Tap the address to copy the complete string. "
                + "Received via " + mPub.backendName() + ".");
        note.setPadding(dp(2), dp(8), dp(2), 0);
        body.addView(note);

        sheet("Receive MINIMA", body);
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
            mHistory.addView(sub(mFilter == 0 ? "No payments yet"
                    : mFilter == 1 ? "No sent payments" : "No received payments"));
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
        a.setText((r.sent ? "−" : "+") + Amounts.list(r.amount) + " " + r.token);
        a.setTextSize(14.5f);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        a.setTextColor(col(r.sent ? R.color.ux_text : R.color.ux_success));
        TextView who = new TextView(mAct);
        who.setText(previewWho(r) + "  ·  " + relTime(r.time));
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

        body.addView(kv("Direction", r.sent ? "Sent" : "Received"));
        body.addView(kv("Amount", (r.sent ? "−" : "+") + Amounts.full(r.amount) + " " + r.token));
        body.addView(kv("Token", r.token));
        body.addView(kv("When", fullTime(r.time)));
        if (!"0x00".equals(r.tokenid) && !r.tokenid.isEmpty()) {
            body.addView(copyField("Token ID", r.tokenid, "Token ID copied"));
        }
        body.addView(copyField(r.sent ? "To" : "From",
                r.who.isEmpty() ? "—" : r.who, "Address copied"));
        body.addView(copyField("Transaction ID",
                r.txid.isEmpty() ? "—" : r.txid, "Transaction ID copied"));

        sheet(r.sent ? "Sent · " + Amounts.list(r.amount) + " " + r.token
                : "Received · " + Amounts.list(r.amount) + " " + r.token, body);
    }

    private String previewWho(WalletLedger.Row r) {
        if (r.who == null || r.who.isEmpty()) {
            return r.sent ? "sent" : "received";
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
            mUsesBig.setText(String.valueOf(used));
        }
        if (mUses != null) {
            mUses.setText(String.format(java.util.Locale.UK, "%,d of %,d signatures left",
                    KEY_TOTAL - used, KEY_TOTAL));
        }
    }

    /** Raise-only reconcile, then teach WHY on the way out. */
    private void adjustUses() {
        MaximaWallet w = mWallet;
        final int cur = w == null ? 0 : w.uses(mAct);
        final EditText input = new EditText(mAct);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(cur));
        new androidx.appcompat.app.AlertDialog.Builder(mAct)
                .setTitle("Set signatures used")
                .setMessage("Currently " + cur + ". Set this to AT LEAST the number of times this "
                        + "seed's key #1000 has ever signed, anywhere. You can only raise it.")
                .setView(input)
                .setNegativeButton("Cancel", (d, wc) -> showUsesExplainer())
                .setPositiveButton("Set", (d, wc) -> {
                    int n;
                    try {
                        n = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception e) {
                        mAct.toast("Enter a number");
                        showUsesExplainer();
                        return;
                    }
                    if (n <= cur) {
                        mAct.toast("Ignored — can only raise above " + cur);
                    } else {
                        MaximaWallet.setUsesAtLeast(mAct, n);
                        if (mWallet != null) {
                            renderUses(mWallet);
                        }
                        mAct.toast("Set to " + n);
                    }
                    showUsesExplainer();
                })
                .show();
    }

    private void showUsesExplainer() {
        new androidx.appcompat.app.AlertDialog.Builder(mAct)
                .setTitle("Why this matters")
                .setMessage("Each signing key can be used a limited number of times. This counter "
                        + "protects your funds: it only ever goes up, never down, and reusing a "
                        + "spent key can expose it and lose money. You normally never touch this — "
                        + "it is here only to correct a count that under-reports real use.")
                .setPositiveButton("Got it", null)
                .show();
    }

    private void showWalletSettings() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView src = sub(MaximaWallet.hasCustomPhrase(mAct)
                ? "Independent imported phrase — separate address & key-use budget"
                : "Maxima identity seed · key #1000 (one phrase backs chat, comms and wallet)");
        src.setPadding(0, 0, 0, dp(12));
        body.addView(src);
        TextView imp = primaryButton("Import a phrase");
        body.addView(imp, marginBottom(dp(8)));
        TextView rev = ghostButton("Use Maxima seed");
        body.addView(rev);
        BottomSheetDialog d = sheet("Wallet settings", body);
        imp.setOnClickListener(v -> { d.dismiss(); importSeed(); });
        rev.setOnClickListener(v -> { d.dismiss(); revertSeed(); });
    }

    private void importSeed() {
        final EditText input = new EditText(mAct);
        input.setHint("12 / 24-word seed phrase");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        new androidx.appcompat.app.AlertDialog.Builder(mAct)
                .setTitle("Import a wallet seed")
                .setMessage("This replaces the Maxima wallet's seed with the phrase you enter — "
                        + "its address and funds change.\n\n⚠ ONLY import a seed whose key #1000 "
                        + "has NOT signed on another wallet or node. Reusing a one-time signing "
                        + "key can expose it and lose funds. An imported seed starts with a fresh "
                        + "key-use count; re-importing the same phrase returns to its own count.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (d, w) -> {
                    String phrase = input.getText().toString().trim();
                    if (phrase.isEmpty()) {
                        mAct.toast("No phrase entered");
                        return;
                    }
                    MaximaWallet.setCustomPhrase(mAct, phrase);
                    mAct.toast("Importing wallet seed…");
                    openWallet();
                })
                .show();
    }

    private void revertSeed() {
        if (!MaximaWallet.hasCustomPhrase(mAct)) {
            mAct.toast("Already using the Maxima seed");
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(mAct)
                .setTitle("Use the Maxima seed?")
                .setMessage("Switch the wallet back to your Maxima identity seed (key #1000). "
                        + "The imported wallet is not deleted — re-import its phrase to return to it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use Maxima seed", (d, w) -> {
                    MaximaWallet.setCustomPhrase(mAct, null);
                    mAct.toast("Back to the Maxima seed…");
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
        mSegBalance = segItem("Balance");
        mSegHistory = segItem("History");
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
        hero.addView(label("TOTAL BALANCE"));
        LinearLayout amtRow = new LinearLayout(mAct);
        amtRow.setOrientation(LinearLayout.HORIZONTAL);
        amtRow.setGravity(Gravity.BOTTOM);
        mHeroAmt = new TextView(mAct);
        mHeroAmt.setText("…");
        mHeroAmt.setTextSize(33);
        mHeroAmt.setLetterSpacing(-0.02f);
        mHeroAmt.setTypeface(manrope(Typeface.BOLD));
        mHeroAmt.setTextColor(col(R.color.ux_text));
        TextView unit = new TextView(mAct);
        unit.setText(" MINIMA");
        unit.setTextSize(15);
        unit.setTypeface(Typeface.DEFAULT_BOLD);
        unit.setPadding(0, 0, 0, dp(4));
        unit.setTextColor(col(R.color.ux_subtext));
        amtRow.addView(mHeroAmt);
        amtRow.addView(unit);
        LinearLayout.LayoutParams arp = new LinearLayout.LayoutParams(-2, -2);
        arp.topMargin = dp(6);
        hero.addView(amtRow, arp);
        mHeroSub = sub("sendable now");
        hero.addView(mHeroSub);

        LinearLayout actions = new LinearLayout(mAct);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, -2);
        acp.topMargin = dp(14);
        TextView send = primaryButton("Send");
        TextView recv = ghostButton("Receive");
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

        mBalancePane.addView(sectionLabel("YOUR TOKENS"));
        mTokenList = new LinearLayout(mAct);
        mTokenList.setOrientation(LinearLayout.VERTICAL);
        mBalancePane.addView(mTokenList, marginBottom(dp(6)));

        mBalancePane.addView(sectionLabel("keyuses"));
        LinearLayout sends = card();
        LinearLayout sr = new LinearLayout(mAct);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        mUsesBig = new TextView(mAct);
        mUsesBig.setText("…");
        mUsesBig.setTextSize(30);
        mUsesBig.setTypeface(manrope(Typeface.BOLD));
        mUsesBig.setTextColor(col(R.color.ux_accent));
        LinearLayout.LayoutParams ubp = new LinearLayout.LayoutParams(-2, -2);
        ubp.rightMargin = dp(14);
        sr.addView(mUsesBig, ubp);
        LinearLayout st = new LinearLayout(mAct);
        st.setOrientation(LinearLayout.VERTICAL);
        st.addView(label("SIGNATURES USED"));
        TextView hint = sub("Long-press to adjust");
        hint.setPadding(0, dp(4), 0, 0);
        st.addView(hint);
        sr.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
        sends.addView(sr);
        sends.setOnLongClickListener(v -> {
            adjustUses();
            return true;
        });
        mBalancePane.addView(sends, marginBottom(dp(10)));

        TextView settings = ghostButton("Wallet settings");
        settings.setOnClickListener(v -> showWalletSettings());
        mBalancePane.addView(settings, marginBottom(dp(12)));
    }

    private void buildHistoryPane() {
        LinearLayout chips = new LinearLayout(mAct);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"All", "Sent", "Received"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = new TextView(mAct);
            c.setText(labels[i]);
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

    private BottomSheetDialog sheet(String title, View content) {
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
        t.setText(title);
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
        d.show();
        return d;
    }

    /** A [LABEL ......... value] row for detail sheets. The value TextView is
     *  child index 1, so a caller can update it later (e.g. async supply). */
    private LinearLayout kv(String key, String value) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView k = new TextView(mAct);
        k.setText(key.toUpperCase());
        k.setTextSize(10);
        k.setLetterSpacing(0.1f);
        k.setTextColor(col(R.color.ux_subtext));
        TextView v = new TextView(mAct);
        v.setText(value);
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

    private LinearLayout copyField(String labelText, String value, String toastMsg) {
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
        l.setText(labelText.toUpperCase());
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
        v.setText(value);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(12);
        v.setPadding(0, dp(7), 0, 0);
        v.setTextColor(col(R.color.ux_text));
        v.setTextIsSelectable(true);
        box.addView(v);

        box.setOnClickListener(view -> {
            ((ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("maxima-wallet", value));
            mAct.toast(toastMsg);
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

    private TextView segItem(String text) {
        TextView t = new TextView(mAct);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private TextView primaryButton(String text) {
        TextView b = new TextView(mAct);
        b.setText(text);
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

    private TextView ghostButton(String text) {
        TextView b = new TextView(mAct);
        b.setText(text);
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

    private TextView sectionLabel(String t) {
        TextView v = new TextView(mAct);
        v.setText(t);
        v.setTextSize(10);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(0.15f);
        v.setTextColor(col(R.color.ux_subtext));
        v.setPadding(dp(4), dp(6), dp(4), dp(9));
        return v;
    }

    private TextView label(String t) {
        TextView v = new TextView(mAct);
        v.setText(t);
        v.setTextSize(10);
        v.setLetterSpacing(0.15f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(col(R.color.ux_subtext));
        return v;
    }

    private TextView sub(String t) {
        TextView v = new TextView(mAct);
        v.setText(t);
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

    private String relTime(long t) {
        long diff = System.currentTimeMillis() - t;
        long min = diff / 60000L;
        if (min < 1) {
            return "just now";
        }
        if (min < 60) {
            return min + "m ago";
        }
        long hr = min / 60;
        if (hr < 24) {
            return hr + "h ago";
        }
        long day = hr / 24;
        if (day == 1) {
            return "yesterday";
        }
        if (day < 7) {
            return day + "d ago";
        }
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.UK)
                .format(new java.util.Date(t));
    }

    private String fullTime(long t) {
        return new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.UK)
                .format(new java.util.Date(t));
    }

    private void post(Runnable r) {
        mAct.runOnUiThread(r);
    }
}
