package com.eurobuddha.maxima.cloud;

import com.eurobuddha.wallet.CoinSelector;
import com.eurobuddha.wallet.TxnFactory;

import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends Minima from the cloud account — the SAME proven pipeline as the phone's PaymentSender
 * (coins → select → build → SIGN one guarded Winternitz use → publish via the gateway), run
 * synchronously on the caller's worker thread. The account seed never leaves this node; the
 * gateway only relays the pre-signed txnimport→txnbasics→txnpost chain.
 */
public final class CloudPaymentSender {

    /** A signed-but-not-yet-published transaction: txid fixed, ready to publish. */
    public static final class Built {
        public final String txid;
        private final String importCmd;
        private final String postCmd;
        Built(String zTxid, String zImportCmd, String zPostCmd) {
            txid = zTxid;
            importCmd = zImportCmd;
            postCmd = zPostCmd;
        }
        /** The signed {@code txnimport} command — a front-end can broadcast this itself
         *  (txnimport → txnbasics → txnpost) via its own minimaCore, so the signing stays on
         *  the node while the relay rides the device's node. */
        public String importCmd() { return importCmd; }
        public String postCmd()   { return postCmd; }
    }

    private final CloudWallet mWallet;
    private final WatchWallet mGateway;

    public CloudPaymentSender(CloudWallet zWallet, WatchWallet zGateway) {
        mWallet = zWallet;
        mGateway = zGateway;
    }

    /**
     * Select coins, build and SIGN a native-Minima send — the txid is fixed on return, so the
     * caller can record the in-chat payment bubble before the broadcast (app parity).
     * BLOCKING (gateway read) — call on the send lane, never the pump.
     */
    public Built build(String zToAddress, MiniNumber zAmount) throws Exception {
        // 1. Spendable coins at our address (gateway node tracks our script + holds proofs).
        JSONObject coinsResp = mGateway.coins(mWallet.hexAddress());
        JSONArray coins = (JSONArray) coinsResp.get("response");
        if (coins == null) {
            throw new Exception("could not read the account's coins from the gateway");
        }

        // 2. Select enough native-Minima coins to cover the amount.
        List<JSONObject> sel;
        try {
            sel = CoinSelector.selectToCover(coins, "0x00", zAmount);
        } catch (CoinSelector.InsufficientFundsException ife) {
            throw new Exception("not enough confirmed funds in the account wallet");
        }
        List<TxnFactory.InputCoin> inputs = new ArrayList<>();
        for (JSONObject cn : sel) {
            inputs.add(TxnFactory.fromCoinJson(cn, CloudWallet.KEY_INDEX));
        }

        // 3. Build + sign locally (reserve-before-sign: the key-use counter is fsync'd to disk
        //    BEFORE the leaf signs — a crash wastes a leaf, never reuses one).
        TxnFactory factory = new TxnFactory(mWallet.core());
        // UUID txn row id: millis ids are guessable + collide on the SHARED gateway — a
        // predicted id could be txndelete'd (or pre-imported) by another token holder in the
        // import→post window.
        TxnFactory.BuiltTxn built = factory.buildSend(inputs, zToAddress, zAmount,
                TxnFactory.TOKEN_MINIMA, MiniNumber.ZERO,
                "pcw" + java.util.UUID.randomUUID());
        return new Built(built.getID(), built.getTxnImportCommand(), built.getTxnPostCommand());
    }

    /** Publish: txnimport → txnbasics (gateway attaches proofs) → txnpost. Throws on failure;
     *  every error path txndeletes. BLOCKING — send lane only. */
    public void publish(Built zBuilt) throws Exception {
        mGateway.publish(zBuilt.importCmd, zBuilt.txid, zBuilt.postCmd);
    }
}
