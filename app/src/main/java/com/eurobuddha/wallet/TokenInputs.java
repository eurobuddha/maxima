package com.eurobuddha.wallet;

import org.minima.objects.Coin;
import org.minima.objects.CoinProof;
import org.minima.objects.Token;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom-token sends (MxUSD first) for every Parlons signer: phone, desktop and cloud.
 * <ul>
 *   <li>{@link #rawAmount}: amounts users type are display units; the engine balances raw
 *       on-chain amounts, so scale with the token's own scale.</li>
 *   <li>{@link #resolve}: every custom-token INPUT comes BYTE-EXACT from {@code coinexport}.
 *       The {@code coins} JSON re-serialises an object token name with different key order, the
 *       recomputed tokenid then mismatches, and consensus drops the transaction SILENTLY after a
 *       successful async txnpost, burning a one-time key. {@code TxnFactory.assertTokenIntegrity}
 *       refuses such an input before signing; this never produces one.</li>
 * </ul>
 */
public final class TokenInputs {

    /** MxUSD, the Minima dollar-pegged token (never "USDT" on the Minima side). */
    public static final String MXUSD_TOKENID = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    public static final String MXUSD_NAME = "MxUSD";
    public static final String MINIMA_TOKENID = "0x00";
    public static final String MINIMA_NAME = "MINIMA";

    /** Fetches the {@code coinexport} CoinProof hex of one coin. */
    public interface Exporter { String hex(String zCoinId) throws Exception; }

    private TokenInputs() {
    }

    public static boolean isMinima(String zTokenId) {
        return zTokenId == null || zTokenId.isEmpty() || "0x00".equalsIgnoreCase(zTokenId);
    }

    /** Only Minima or a full 32-byte token id; anything else is refused before any work. */
    public static boolean validTokenId(String zTokenId) {
        return isMinima(zTokenId) || zTokenId.matches("0x[0-9A-Fa-f]{64}");
    }

    /** The display name for a token id we offer; other ids show as themselves. */
    public static String nameOf(String zTokenId) {
        if (isMinima(zTokenId)) return MINIMA_NAME;
        return MXUSD_TOKENID.equalsIgnoreCase(zTokenId) ? MXUSD_NAME : zTokenId;
    }

    /** Display amount -> raw amount, using the scale of a coin of that token in {@code zCoins}. */
    public static MiniNumber rawAmount(JSONArray zCoins, String zTokenId, MiniNumber zDisplay) {
        if (isMinima(zTokenId)) return zDisplay;
        for (Object o : zCoins) {
            JSONObject cn = (JSONObject) o;
            if (!zTokenId.equalsIgnoreCase(String.valueOf(cn.get("tokenid")))) continue;
            Token t = TxnFactory.fromCoinJson(cn, 0).getToken();   // scale only, never an input
            if (t != null) return t.getScaledMinimaAmount(zDisplay);
        }
        throw new IllegalStateException("no " + nameOf(zTokenId) + " in this wallet");
    }

    /** {@code coinexport}'s {@code response}: the hex itself, or an object carrying it. */
    public static String blobOf(Object zResponse) {
        if (zResponse instanceof String) return ((String) zResponse).trim();
        if (zResponse instanceof java.util.Map) {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) zResponse;
            Object d = m.get("data") != null ? m.get("data") : m.get("coin");
            if (d != null && !String.valueOf(d).trim().isEmpty()) return String.valueOf(d).trim();
        }
        return null;
    }

    /** One {@code coinexport} CoinProof hex -> an engine input, checked to be the coin asked for. */
    public static TxnFactory.InputCoin fromExport(String zCoinId, String zHex, int zKeyIndex) {
        if (zHex == null || zHex.isEmpty()) throw new IllegalStateException("empty coinexport reply for " + zCoinId);
        CoinProof cp = CoinProof.convertMiniDataVersion(new MiniData(zHex));
        Coin coin = cp == null ? null : cp.getCoin();
        if (coin == null || !coin.getCoinID().to0xString().equalsIgnoreCase(zCoinId)) {
            throw new IllegalStateException("coinexport returned a different coin for " + zCoinId);
        }
        return TxnFactory.fromExportedCoin(coin, zKeyIndex);
    }

    /** Selected {@code coins} rows -> engine inputs: Minima from JSON, every token row via coinexport. */
    public static List<TxnFactory.InputCoin> resolve(List<JSONObject> zSelected, int zKeyIndex,
                                                     Exporter zExporter) throws Exception {
        List<TxnFactory.InputCoin> out = new ArrayList<>();
        for (JSONObject cn : zSelected) {
            if (isMinima(String.valueOf(cn.get("tokenid")))) {
                out.add(TxnFactory.fromCoinJson(cn, zKeyIndex));
            } else {
                String coinid = String.valueOf(cn.get("coinid"));
                out.add(fromExport(coinid, zExporter.hex(coinid), zKeyIndex));
            }
        }
        return out;
    }

    /** The engine's token id for a send. */
    public static MiniData tokenData(String zTokenId) {
        return isMinima(zTokenId) ? TxnFactory.TOKEN_MINIMA : new MiniData(zTokenId);
    }
}
