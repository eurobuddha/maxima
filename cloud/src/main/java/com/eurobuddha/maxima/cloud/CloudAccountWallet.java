package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The CLOUD implementation of {@link AccountWallet}: a fully-local key-#1000 signer over the
 * account seed ({@link CloudWallet} + {@link CloudPaymentSender}) that reads balances and relays
 * pre-signed transactions through a remote MegaMMR gateway ({@link WatchWallet}). This is the
 * pre-M5 wallet, unchanged in behaviour — only lifted behind the interface so the same account
 * layer can also run inside a Parlons Node with the node's own wallet.
 */
public final class CloudAccountWallet implements AccountWallet {

    private final MaximaIdentity mIdentity;
    private final File mWalletDir;
    private final WatchWallet mGateway;

    private volatile CloudWallet mWallet;
    private volatile CloudPaymentSender mSender;
    private volatile boolean mScriptTracked;
    private volatile long mLastCoinSync;

    public CloudAccountWallet(MaximaIdentity zIdentity, Path zDataDir) {
        mIdentity = zIdentity;
        mWalletDir = new File(zDataDir.toFile(), "wallet");
        mGateway = new WatchWallet(zDataDir);
    }

    /** The gateway client (the cloud CLI + backup paths reach it directly). */
    public WatchWallet gateway() { return mGateway; }

    @Override public void open() throws Exception {
        CloudWallet w = CloudWallet.open(mIdentity, mWalletDir);
        w.ensureAddress();
        mSender = new CloudPaymentSender(w, mGateway);
        mWallet = w;
    }

    @Override public boolean isOpen() { return mWallet != null; }

    @Override public String mxAddress() { CloudWallet w = mWallet; return w == null ? "" : w.mxAddress(); }
    @Override public String hexAddress() { CloudWallet w = mWallet; return w == null ? "" : w.hexAddress(); }
    @Override public String script() { CloudWallet w = mWallet; return w == null ? "" : w.script(); }

    @Override public int uses() { CloudWallet w = mWallet; return w == null ? -1 : w.uses(); }
    @Override public int maxUses() { return CloudWallet.MAX_USES; }
    @Override public void raiseUsesTo(int zTo) {
        CloudWallet w = mWallet;
        if (w != null) {
            w.raiseUsesTo(zTo);
        }
    }

    @Override public JSONObject cmd(String zCommand) throws Exception { return mGateway.cmd(zCommand); }
    @Override public String watchAddress() { return mGateway.watchAddress(); }
    @Override public void setWatchAddress(String zAddress) throws Exception { mGateway.setWatchAddress(zAddress); }

    @Override public Payment build(String zToAddress, MiniNumber zAmount) throws Exception {
        CloudPaymentSender s = mSender;
        if (s == null) {
            throw new Rejected("the account wallet is still opening");
        }
        try {
            CloudPaymentSender.Built b = s.build(zToAddress, zAmount);
            return new Payment(b.txid, b.importCmd(), b.postCmd());
        } catch (Exception e) {
            throw classify(e);
        }
    }

    @Override public void publish(Payment zPayment) throws Exception {
        try {
            mGateway.publish(zPayment.importCmd, zPayment.txid, zPayment.postCmd);
        } catch (Exception e) {
            throw classify(e);
        }
    }

    @Override public boolean canBuildWithoutPublish() { return true; }

    /** A gateway-REPORTED failure (txnimport/txnbasics/txnpost said no, or the build never got to
     *  the wire) is a safe ✗; a transport error mid-publish is outcome-unknown. */
    private static Exception classify(Exception e) {
        String why = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof Rejected) {
            return e;
        }
        if (why.startsWith("txnimport:") || why.startsWith("txnbasics:") || why.startsWith("txnpost:")
                || why.startsWith("not enough confirmed funds")
                || why.startsWith("could not read the account's coins")) {
            return new Rejected(why);
        }
        return e;
    }

    /**
     * Gateway script tracking (payments need the proofs) and the funded-before-tracked coin
     * backfill — the app's exact WalletPage.syncCoins: a coin that arrived BEFORE the gateway
     * tracked our script is confirmed on-chain but not in the gateway's tracked set, so
     * txnbasics can't attach a proof and sendable stays 0. coinexport each of our coins from
     * the global MegaMMR and coinimport track:true it back. NEVER signs, burns no key use.
     * Rate-limited to one pass per 5 min.
     */
    @Override public void upkeep(Consumer<String> zLog) {
        CloudWallet w = mWallet;
        if (w == null) {
            return;
        }
        if (!mScriptTracked) {
            try {
                mGateway.trackScript(w.script());
                mScriptTracked = true;
                zLog.accept("wallet script tracked on the gateway");
            } catch (Exception e) {
                zLog.accept("wallet script tracking failed (will retry): " + e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        if (now - mLastCoinSync < 5 * 60_000L) {
            return;
        }
        mLastCoinSync = now;
        try {
            JSONObject bal = mGateway.cmd("balance megammr:true address:" + w.mxAddress());
            JSONArray arr = (JSONArray) bal.get("response");
            boolean lagging = false;
            if (arr != null) {
                for (Object o : arr) {
                    JSONObject t = (JSONObject) o;
                    if ("0x00".equals(String.valueOf(t.get("tokenid")))
                            && !String.valueOf(t.get("confirmed")).equals(String.valueOf(t.get("sendable")))) {
                        lagging = true;
                    }
                }
            }
            if (!lagging) {
                return;
            }
            JSONObject coinsResp = mGateway.coins(w.hexAddress());
            JSONArray coins = (JSONArray) coinsResp.get("response");
            int imported = 0, failed = 0;
            if (coins != null) {
                for (Object o : coins) {
                    String coinid = String.valueOf(((JSONObject) o).get("coinid"));
                    if (coinid.isEmpty() || "null".equals(coinid)) {
                        continue;
                    }
                    try {
                        JSONObject ex = mGateway.coinExport(coinid);
                        JSONObject resp = (JSONObject) ex.get("response");
                        String data = resp == null ? "" : String.valueOf(resp.get("data"));
                        if (data.isEmpty() || "null".equals(data)) {
                            failed++;
                            continue;
                        }
                        mGateway.coinImport(data);
                        imported++;
                    } catch (Exception coinErr) {
                        failed++;
                    }
                }
            }
            zLog.accept("wallet coin sync: imported=" + imported + " failed=" + failed);
        } catch (Exception e) {
            zLog.accept("wallet coin sync failed: " + e.getMessage());
        }
    }
}
