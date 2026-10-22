package com.eurobuddha.maxima.app.wallet;

import com.eurobuddha.wallet.TxnFactory;

import org.json.JSONObject;
import org.minima.objects.Coin;
import org.minima.objects.CoinProof;
import org.minima.objects.Token;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom-token sends for the Parlons wallet (the first token is MxUSD, the Minima dollar-pegged
 * token). The wire, the engine and every renderer already carried a token; this is the send side:
 * <ul>
 *   <li>amounts the user types are display units — {@link #rawAmount} scales them with the token's
 *       own scale, so the engine (which balances raw on-chain amounts) sees the right numbers;</li>
 *   <li>every token INPUT is sourced BYTE-EXACT from {@code coinexport} ({@link #resolveInputs}):
 *       the {@code coins} JSON re-serialises an object token name with different key order, the
 *       recomputed tokenid then mismatches, and consensus drops the transaction SILENTLY after a
 *       successful async txnpost, burning a one-time key. {@code TxnFactory.assertTokenIntegrity}
 *       refuses such an input before signing; this helper never produces one.</li>
 * </ul>
 */
public final class TokenSend {

    /** MxUSD — the one Minima token id, whatever a bridge calls it (never "USDT" on this side). */
    public static final String MXUSD_TOKENID = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    public static final String MXUSD_NAME = "MxUSD";
    public static final String MINIMA_TOKENID = "0x00";
    public static final String MINIMA_NAME = "MINIMA";

    private static final long WAIT_MS = 45_000;

    private TokenSend() {
    }

    public static boolean isMinima(String zTokenId) {
        return zTokenId == null || zTokenId.isEmpty() || "0x00".equalsIgnoreCase(zTokenId);
    }

    /** Display amount -> raw on-chain amount for {@code zTokenId}, using the scale of a coin of
     *  that token in {@code zCoins}. Minima is already raw. Throws when we hold no such coin. */
    public static MiniNumber rawAmount(org.minima.utils.json.JSONArray zCoins, String zTokenId, MiniNumber zDisplay) {
        if (isMinima(zTokenId)) return zDisplay;
        for (Object o : zCoins) {
            org.minima.utils.json.JSONObject cn = (org.minima.utils.json.JSONObject) o;
            if (!zTokenId.equalsIgnoreCase(String.valueOf(cn.get("tokenid")))) continue;
            Token t = TxnFactory.fromCoinJson(cn, 0).getToken();   // scale only - never used as an input
            if (t != null) return t.getScaledMinimaAmount(zDisplay);
        }
        throw new IllegalStateException("No " + zTokenId + " coins in this wallet");
    }

    /** Selected {@code coins} rows -> engine inputs. Minima rows convert from JSON; every custom-token
     *  row is fetched as its binary CoinProof via {@code coinexport} and converted byte-exact. */
    public static List<TxnFactory.InputCoin> resolveInputs(WalletPublisher zPub,
            List<org.minima.utils.json.JSONObject> zSelected, int zKeyIndex) throws Exception {
        List<TxnFactory.InputCoin> out = new ArrayList<>();
        for (org.minima.utils.json.JSONObject cn : zSelected) {
            String tokenid = String.valueOf(cn.get("tokenid"));
            if (isMinima(tokenid)) {
                out.add(TxnFactory.fromCoinJson(cn, zKeyIndex));
                continue;
            }
            String coinid = String.valueOf(cn.get("coinid"));
            JSONObject ex = await(cb -> zPub.coinExport(coinid, cb));
            String hex = WalletPublisher.blobOf(ex);
            if (hex == null || hex.isEmpty()) throw new IllegalStateException("empty coinexport reply for " + coinid);
            CoinProof cp = CoinProof.convertMiniDataVersion(new MiniData(hex));
            Coin coin = cp == null ? null : cp.getCoin();
            if (coin == null || !coin.getCoinID().to0xString().equalsIgnoreCase(coinid)) {
                throw new IllegalStateException("coinexport returned a different coin for " + coinid);
            }
            out.add(TxnFactory.fromExportedCoin(coin, zKeyIndex));
        }
        return out;
    }

    /** One publisher call, awaited (publisher callbacks arrive on its own threads). */
    public interface Call { void run(WalletPublisher.Cb zCb); }
    public static JSONObject await(Call zCall) throws Exception {
        final Object lock = new Object();
        final JSONObject[] resp = new JSONObject[1];
        final String[] err = new String[1];
        zCall.run(new WalletPublisher.Cb() {
            public void onResult(JSONObject r) { synchronized (lock) { resp[0] = r; lock.notifyAll(); } }
            public void onError(String m) { synchronized (lock) { err[0] = m; lock.notifyAll(); } }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (resp[0] == null && err[0] == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                lock.wait(remaining);
            }
        }
        if (resp[0] == null) throw new IllegalStateException(err[0] != null ? err[0] : "node timeout");
        return resp[0];
    }
}
