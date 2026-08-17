package com.eurobuddha.maxima.app.wallet;

import android.content.Context;

import com.eurobuddha.wallet.CoinSelector;
import com.eurobuddha.wallet.TxnFactory;

import org.json.JSONObject;
import org.minima.objects.base.MiniNumber;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends Minima from a chat, reusing the SAME proven pipeline as the wallet tab
 * ({@code WalletPage.send()}): coins → select → build → SIGN (one guarded
 * Winternitz use) → publish, via the local minimaCore when paired else the
 * hosted gateway. Kept separate from WalletPage so the working wallet screen is
 * untouched; the signing/build steps are identical on purpose.
 *
 * One instance per open conversation. The wallet (seed → WOTS address walk) is
 * opened once, off-main, on construction, so a later send is fast.
 */
public final class PaymentSender {

    public interface Cb {
        void onProgress(String zStep);

        /** @param zTxid the transaction id, for the in-chat payment record */
        void onSent(String zTxid);

        void onError(String zMessage);
    }

    private final Context mCtx;
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private volatile MaximaWallet mWallet;
    private volatile WalletPublisher mPub;
    private NodeLink mNode;
    private volatile Runnable mOnReady;

    public PaymentSender(Context zCtx) {
        mCtx = zCtx.getApplicationContext();
        if (WalletPublisher.coreInstalled(mCtx)) {
            mNode = new NodeLink(mCtx, enabled -> {
            });
        }
        mPub = new WalletPublisher(mCtx, mNode);
        mIo.execute(() -> {
            MaximaWallet w = MaximaWallet.open(mCtx);
            w.ensureAddress();
            mWallet = w;
            Runnable r = mOnReady;
            if (r != null) {
                r.run();
            }
            // Make pre-existing funds spendable via the gateway - idempotent,
            // can never move funds.
            mPub.trackScript(w.script(), new WalletPublisher.Cb() {
                public void onResult(JSONObject r) {
                }

                public void onError(String m) {
                }
            });
        });
    }

    public boolean ready() {
        return mWallet != null;
    }

    /** Release the worker thread and any bound node service. */
    public void close() {
        mIo.shutdownNow();
        if (mNode != null) {
            try {
                mNode.onDestroy();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
    }

    /** Run once the wallet has opened (immediately if it already has). Runs on a
     *  worker thread; hop to the UI thread yourself if needed. */
    public void whenReady(Runnable zR) {
        if (mWallet != null) {
            zR.run();
        } else {
            mOnReady = zR;
        }
    }

    /** Our own Mx wallet receive address, or null until the wallet has opened. */
    public String myAddress() {
        MaximaWallet w = mWallet;
        return w == null ? null : w.mxAddress();
    }

    /** How many guarded key-uses remain shown as a fraction, for a caveat line. */
    public String usesLine() {
        MaximaWallet w = mWallet;
        return w == null ? "" : ("key uses: " + w.uses(mCtx) + " / 262144");
    }

    /**
     * Build, sign and publish a send of {@code zAmount} Minima to {@code zTo}.
     * Callbacks arrive on a worker thread; the caller must hop to the UI thread.
     */
    public void send(final String zTo, final MiniNumber zAmount, final Cb zCb) {
        final MaximaWallet w = mWallet;
        if (w == null) {
            zCb.onError("Wallet still opening");
            return;
        }
        mIo.execute(() -> {
            try {
                zCb.onProgress("Selecting coins…");
                final Object lock = new Object();
                final JSONObject[] resp = new JSONObject[1];
                final String[] err = new String[1];
                mPub.coins(w.hexAddress(), new WalletPublisher.Cb() {
                    public void onResult(JSONObject r) {
                        synchronized (lock) {
                            resp[0] = r;
                            lock.notifyAll();
                        }
                    }

                    public void onError(String m) {
                        synchronized (lock) {
                            err[0] = m;
                            lock.notifyAll();
                        }
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

                org.minima.utils.json.JSONObject full =
                        (org.minima.utils.json.JSONObject) new org.minima.utils.json.parser.JSONParser()
                                .parse(resp[0].toString());
                org.minima.utils.json.JSONArray coins =
                        (org.minima.utils.json.JSONArray) full.get("response");

                List<org.minima.utils.json.JSONObject> sel =
                        CoinSelector.selectToCover(coins, "0x00", zAmount);
                List<TxnFactory.InputCoin> inputs = new ArrayList<>();
                for (org.minima.utils.json.JSONObject cn : sel) {
                    inputs.add(TxnFactory.fromCoinJson(cn, MaximaWallet.KEY_INDEX));
                }

                zCb.onProgress("Signing…");
                TxnFactory factory = new TxnFactory(w.core());
                final TxnFactory.BuiltTxn built = factory.buildSend(inputs, zTo, zAmount,
                        TxnFactory.TOKEN_MINIMA, MiniNumber.ZERO,
                        "mxw" + System.currentTimeMillis());

                zCb.onProgress("Publishing via " + mPub.backendName() + "…");
                mPub.publish(built.getTxnImportCommand(), built.getID(),
                        built.getTxnPostCommand(), new WalletPublisher.Cb() {
                            public void onResult(JSONObject r) {
                                zCb.onSent(built.getID());
                            }

                            public void onError(String m) {
                                zCb.onError("Publish failed: " + m);
                            }
                        });
            } catch (CoinSelector.InsufficientFundsException ife) {
                zCb.onError("Not enough confirmed funds");
            } catch (Exception e) {
                zCb.onError("Send failed: " + e.getMessage());
            }
        });
    }
}
