package com.eurobuddha.maxima.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.wallet.MaximaWallet;
import com.eurobuddha.maxima.app.wallet.NodeLink;
import com.eurobuddha.maxima.app.wallet.WalletPublisher;
import com.eurobuddha.wallet.CoinSelector;
import com.eurobuddha.wallet.TxnFactory;

import org.json.JSONObject;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallet tab — FreezePeach's independence, inside the Maxima chat app.
 *
 * One Mx address (Derivation V3, key index 1000 — see {@link MaximaWallet}),
 * coins and tokens on Minima, no node required: reads and the pre-signed
 * publish relay go to the local minimaCore when installed+paired, else to the
 * hosted gateway. Signing never leaves this device, and every signature burns
 * one guarded Winternitz use (the counter shown on the card is not decoration —
 * it is the wallet's life).
 */
public final class WalletPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mBox;
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();

    private volatile MaximaWallet mWallet;
    private volatile WalletPublisher mPub;
    private NodeLink mNode;

    private TextView mAddr;
    private TextView mBackend;
    private TextView mBalance;
    private TextView mUses;
    private TextView mUsesBig;

    /** Winternitz one-time-signature budget for key #1000. */
    private static final int KEY_TOTAL = 262144;
    private EditText mSendTo;
    private EditText mSendAmt;
    private TextView mSendStatus;
    private volatile boolean mTracked;

    public WalletPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mBox = zView.findViewById(R.id.wallet_container);
        buildUi();

        // Local node link, when minimaCore exists on this phone (opportunistic
        // pairing; the gateway carries everything until it is enabled).
        if (WalletPublisher.coreInstalled(zAct)) {
            mNode = new NodeLink(zAct.getApplicationContext(),
                    enabled -> mAct.runOnUiThread(this::renderBackend));
        }
        mPub = new WalletPublisher(zAct, mNode);

        // Heavy: seed -> WOTS address walk. Once, off-main.
        mIo.execute(() -> {
            MaximaWallet w = MaximaWallet.open(mAct);
            w.ensureAddress();
            mWallet = w;
            mAct.runOnUiThread(() -> {
                mAddr.setText(w.mxAddress());
                renderUses(w);
                render();
            });
            // Make pre-existing funds spendable via the gateway: register our
            // (public) script once. Idempotent; can never move funds.
            mPub.trackScript(w.script(), new WalletPublisher.Cb() {
                public void onResult(JSONObject r) {
                    mTracked = true;
                }

                public void onError(String m) {
                    // benign - retried next open
                }
            });
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
        renderBackend();
        MaximaWallet w = mWallet;
        if (w == null || w.hexAddress() == null) {
            return;
        }
        renderUses(w);   // re-read the key-use count live (mTick, ~2s)
        mPub.balance(w.hexAddress(), new WalletPublisher.Cb() {
            public void onResult(JSONObject r) {
                mBalance.setText(balanceText(r));
            }

            public void onError(String m) {
                mBalance.setText("balance unavailable: " + m);
            }
        });
    }

    /** The prominent key-use figure: signatures spent, and how many remain. */
    private void renderUses(MaximaWallet w) {
        int used = w.uses(mAct);
        mUsesBig.setText(String.valueOf(used));
        mUses.setText((KEY_TOTAL - used) + " of " + KEY_TOTAL + " signatures left");
    }

    private void renderBackend() {
        mBackend.setText("via " + mPub.backendName()
                + (MaximaWallet.hasCustomPhrase(mAct)
                    ? "   ·   independent wallet phrase" : "   ·   Maxima seed, key #1000"));
    }

    // ---------------------------------------------------------------

    private void send() {
        final MaximaWallet w = mWallet;
        final String to = mSendTo.getText().toString().trim();
        final String amtStr = mSendAmt.getText().toString().trim();
        if (w == null) {
            mAct.toast("Wallet still opening…");
            return;
        }
        if (to.isEmpty() || amtStr.isEmpty()) {
            mAct.toast("Address and amount needed");
            return;
        }
        final MiniNumber amount;
        try {
            amount = new MiniNumber(amtStr);
        } catch (Exception e) {
            mAct.toast("Bad amount");
            return;
        }
        mSendStatus.setText("Selecting coins…");
        mIo.execute(() -> {
            try {
                // coins -> select -> build -> SIGN (one guarded use) -> publish
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
                    if (resp[0] == null && err[0] == null) {
                        lock.wait(45_000);
                    }
                }
                if (resp[0] == null) {
                    throw new IllegalStateException(err[0] != null ? err[0] : "coins timeout");
                }

                // Bridge org.json -> the vendored minima JSON the selector expects.
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

                post(() -> mSendStatus.setText("Signing…"));
                TxnFactory factory = new TxnFactory(w.core());
                final TxnFactory.BuiltTxn built = factory.buildSend(inputs, to, amount,
                        TxnFactory.TOKEN_MINIMA, MiniNumber.ZERO,
                        "mxw" + System.currentTimeMillis());

                post(() -> mSendStatus.setText("Publishing via " + mPub.backendName() + "…"));
                mPub.publish(built.getTxnImportCommand(), built.getID(),
                        built.getTxnPostCommand(), new WalletPublisher.Cb() {
                            public void onResult(JSONObject r) {
                                post(() -> {
                                    mSendStatus.setText("Sent ⛏ (mining)");
                                    mSendTo.setText("");
                                    mSendAmt.setText("");
                                    renderUses(w);
                                    render();
                                });
                            }

                            public void onError(String m) {
                                post(() -> mSendStatus.setText("Publish failed: " + m));
                            }
                        });
            } catch (CoinSelector.InsufficientFundsException ife) {
                post(() -> mSendStatus.setText("Not enough confirmed funds"));
            } catch (Exception e) {
                post(() -> mSendStatus.setText("Send failed: " + e.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------
    // UI scaffolding
    // ---------------------------------------------------------------

    private void buildUi() {
        // key-uses card FIRST - the finite one-time-signature budget is the
        // wallet's life, so it is front and centre and ticks live.
        LinearLayout kc = card();
        kc.addView(label("KEY USES"));
        mUsesBig = new TextView(mAct);
        mUsesBig.setText("…");
        mUsesBig.setTextSize(30);
        mUsesBig.setTypeface(Typeface.DEFAULT_BOLD);
        mUsesBig.setTextColor(mAct.getResources().getColor(R.color.ux_accent, mAct.getTheme()));
        kc.addView(mUsesBig);
        mUses = sub("of " + KEY_TOTAL + " signatures");
        kc.addView(mUses);
        mBox.addView(kc, pad());

        // receive card
        LinearLayout rc = card();
        rc.addView(label("RECEIVE"));
        mAddr = mono("deriving address…");
        mAddr.setOnClickListener(v -> {
            MaximaWallet w = mWallet;
            if (w != null && w.mxAddress() != null) {
                ((ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("maxima-wallet", w.mxAddress()));
                mAct.toast("Address copied");
            }
        });
        rc.addView(mAddr);
        mBackend = sub("");
        rc.addView(mBackend);
        mBox.addView(rc, pad());

        // balance card
        LinearLayout bc = card();
        bc.addView(label("BALANCE"));
        mBalance = mono("…");
        bc.addView(mBalance);
        Button refresh = new Button(mAct);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> render());
        bc.addView(refresh);
        mBox.addView(bc, pad());

        // send card
        LinearLayout sc = card();
        sc.addView(label("SEND MINIMA"));
        mSendTo = new EditText(mAct);
        mSendTo.setHint("Mx… or 0x… address");
        sc.addView(mSendTo);
        mSendAmt = new EditText(mAct);
        mSendAmt.setHint("amount");
        mSendAmt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sc.addView(mSendAmt);
        Button send = new Button(mAct);
        send.setText("Sign & send");
        send.setOnClickListener(v -> send());
        sc.addView(send);
        mSendStatus = sub("");
        sc.addView(mSendStatus);
        mBox.addView(sc, pad());
    }

    private String balanceText(JSONObject r) {
        try {
            org.json.JSONArray arr = r.optJSONArray("response");
            if (arr == null || arr.length() == 0) {
                return "0";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                String name = "0x00".equals(t.optString("tokenid"))
                        ? "MINIMA" : t.opt("token").toString();
                sb.append(name).append(":  ")
                        .append(t.optString("confirmed", "0")).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return r.toString();
        }
    }

    private void post(Runnable r) {
        mAct.runOnUiThread(r);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(mAct);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = (int) (16 * mAct.getResources().getDisplayMetrics().density);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout.LayoutParams pad() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) (12 * mAct.getResources().getDisplayMetrics().density);
        return lp;
    }

    private TextView label(String t) {
        TextView v = new TextView(mAct);
        v.setText(t);
        v.setTextSize(11);
        v.setLetterSpacing(0.12f);
        v.setTextColor(mAct.getResources().getColor(R.color.ux_subtext, mAct.getTheme()));
        return v;
    }

    private TextView mono(String t) {
        TextView v = new TextView(mAct);
        v.setText(t);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(12);
        v.setTextIsSelectable(true);
        v.setTextColor(mAct.getResources().getColor(R.color.ux_text, mAct.getTheme()));
        int p = (int) (6 * mAct.getResources().getDisplayMetrics().density);
        v.setPadding(0, p, 0, p);
        return v;
    }

    private TextView sub(String t) {
        TextView v = new TextView(mAct);
        v.setText(t);
        v.setTextSize(11);
        v.setTextColor(mAct.getResources().getColor(R.color.ux_subtext, mAct.getTheme()));
        return v;
    }
}
