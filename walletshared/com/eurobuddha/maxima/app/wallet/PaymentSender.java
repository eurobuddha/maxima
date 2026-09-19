package com.eurobuddha.maxima.app.wallet;

import android.content.Context;

import com.eurobuddha.wallet.CoinSelector;
import com.eurobuddha.wallet.TokenAmount;
import com.eurobuddha.wallet.TxnFactory;
import com.eurobuddha.wallet.Util;

import org.json.JSONObject;
import org.minima.objects.base.MiniData;
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

        /** Fired the instant the transaction is signed (txid known), BEFORE the
         *  broadcast round-trip - so the sender can show a pending bubble now. */
        void onBuilt(String zTxid);

        /** The broadcast was accepted. @param zTxid the transaction id. */
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
            mPub.prepare(w.script(), w.hexAddress(), new WalletPublisher.Cb() {
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

    public interface Arrival {
        void onArrived(boolean zArrived);
    }

    /**
     * Verify a RECEIVED payment by its txid - not by amount coincidence. A
     * fabricated TYPE_PAYMENT claim carries an arbitrary txid; only a real
     * on-chain TxPoW with that exact id, carrying an output that pays OUR
     * address the claimed amount OF THE CLAIMED TOKEN, confirms. This is what
     * makes the received "Confirmed" badge an on-chain fact rather than the
     * sender's word.
     *
     * <p>{@code zTokenId} is what closes the cross-token hole: without it a 5
     * MINIMA output confirms a "5 MxUSD" claim. See {@link TokenAmount#paysUs}.
     */
    public void verifyIncomingPayment(final String zTxid, final String zAmount,
            final String zTokenId, final Arrival zCb) {
        final MaximaWallet w = mWallet;
        if (w == null || zTxid == null || zTxid.isEmpty()
                || zAmount == null || zAmount.isEmpty()) {
            zCb.onArrived(false);
            return;
        }
        final String want = zAmount;
        final String ours = w.hexAddress();
        mPub.gcmd("txpow txpowid:" + zTxid, new WalletPublisher.Cb() {
            public void onResult(JSONObject r) {
                boolean ok = false;
                try {
                    org.minima.utils.json.JSONObject full =
                            (org.minima.utils.json.JSONObject) new org.minima.utils.json.parser
                                    .JSONParser().parse(r.toString());
                    Object status = full.get("status");
                    org.minima.utils.json.JSONObject resp =
                            (org.minima.utils.json.JSONObject) full.get("response");
                    if (Boolean.TRUE.equals(status) && resp != null) {
                        org.minima.utils.json.JSONObject txpow =
                                (org.minima.utils.json.JSONObject) resp.get("txpow");
                        org.minima.utils.json.JSONObject body = txpow == null ? null
                                : (org.minima.utils.json.JSONObject) txpow.get("body");
                        org.minima.utils.json.JSONObject txn = body == null ? null
                                : (org.minima.utils.json.JSONObject) body.get("txn");
                        org.minima.utils.json.JSONArray outs = txn == null ? null
                                : (org.minima.utils.json.JSONArray) txn.get("outputs");
                        // The exact txid resolved to a real TxPoW: does it pay our address the
                        // claimed amount OF THE CLAIMED TOKEN?
                        ok = com.eurobuddha.wallet.TokenAmount.paysUs(
                                outs, ours, zTokenId, new MiniNumber(want));
                    }
                } catch (Exception ignored) {
                }
                zCb.onArrived(ok);
            }

            public void onError(String m) {
                zCb.onArrived(false);
            }
        });
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
     * Build, sign and publish a send of {@code zAmount} Minima to {@code zTo} - the shape every
     * caller used before a currency could be chosen.
     * Callbacks arrive on a worker thread; the caller must hop to the UI thread.
     */
    public void send(final String zTo, final MiniNumber zAmount, final Cb zCb) {
        send(zTo, zAmount, Util.MINIMA_TOKENID, zCb);
    }

    /**
     * Send {@code zAmount} of {@code zTokenId} - the amount is the HUMAN (displayed) value, exactly
     * what the user typed and what the chat bubble will show. For a token that is converted to raw
     * on-chain units once, in {@link TokenAmount#toRaw}, and the single result feeds both coin
     * selection and the transaction build so the two cannot disagree about units.
     */
    public void send(final String zTo, final MiniNumber zAmount, final String zTokenId,
            final Cb zCb) {
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

                // ONE conversion, ONE variable: raw feeds both the selector and the builder, so a
                // token send can never select against one unit and build against the other.
                final MiniNumber raw = TokenAmount.toRaw(coins, zTokenId, zAmount);

                List<org.minima.utils.json.JSONObject> sel =
                        CoinSelector.selectToCover(coins, zTokenId, raw);
                List<TxnFactory.InputCoin> inputs = new ArrayList<>();
                for (org.minima.utils.json.JSONObject cn : sel) {
                    inputs.add(TxnFactory.fromCoinJson(cn, MaximaWallet.KEY_INDEX));
                }

                zCb.onProgress("Signing…");
                TxnFactory factory = new TxnFactory(w.core());
                // Burn stays zero: TxnFactory refuses a burn on a token send, and a native-Minima
                // burn here was always zero too.
                final TxnFactory.BuiltTxn built = factory.buildSend(inputs, zTo, raw,
                        new MiniData(zTokenId), MiniNumber.ZERO,
                        "mxw" + System.currentTimeMillis());
                // Signed: the txid is fixed now, so the sender can show a pending
                // bubble immediately instead of waiting for the broadcast.
                zCb.onBuilt(built.getID());

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
            } catch (IllegalArgumentException iae) {
                // TokenAmount/TxnFactory refusals are user-actionable and already worded for a
                // person ("that amount is finer than this token's smallest unit") - pass them
                // through rather than burying them behind "Send failed".
                zCb.onError(iae.getMessage());
            } catch (Exception e) {
                zCb.onError("Send failed: " + e.getMessage());
            }
        });
    }
}
