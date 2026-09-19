package com.eurobuddha.wallet;

import org.minima.objects.Token;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * Token-aware amount handling for the wallet: the ONE place that knows a token coin carries two
 * views of the same value, and the ONE place that decides whether a transaction actually paid us.
 *
 * <h3>The two views of a token amount</h3>
 * A token coin's on-chain {@code amount} is a <b>Minima-scaled</b> value (MxUSD's scale is 36, so
 * 25 MxUSD is stored as 25e-36), while {@code tokenamount} is the human value the node already
 * scaled for us. Native Minima ({@code 0x00}) has one view: raw == displayed. Mixing the two is how
 * a token send silently pays 10^scale too much or too little, so every comparison here states which
 * view it is using.
 */
public final class TokenAmount {

    private TokenAmount() {
    }

    /**
     * The RAW on-chain amount to hand {@link CoinSelector} and {@link TxnFactory} for a send of
     * {@code zHuman} of {@code zTokenId}. Native Minima passes straight through (raw == displayed).
     *
     * <p>The token's scale is read from the descriptor on OUR OWN coins - the ones we are about to
     * spend - and then <b>cross-checked against the node's own two views of that same coin</b>
     * ({@code amount} scaled by 10^scale must equal {@code tokenamount}). That check is what makes
     * it safe to multiply money by this scale; it uses data the node itself produced, and unlike
     * recomputing the tokenid from the descriptor it cannot false-fail on a JSON-object token name
     * whose keys re-serialize in a different order (MxUSD has exactly such a name).
     *
     * <p>Every failure throws rather than returning an approximation: an amount finer than the
     * token's grain is refused, never silently floored.
     *
     * @throws IllegalArgumentException if the wallet holds no coin of that token, the descriptor is
     *         missing or inconsistent, or the amount cannot be represented exactly.
     */
    public static MiniNumber toRaw(JSONArray zCoins, String zTokenId, MiniNumber zHuman) {
        if (zHuman == null || !zHuman.isMore(MiniNumber.ZERO)) {
            throw new IllegalArgumentException("that amount is not a positive number");
        }
        if (Util.isMinima(zTokenId)) {
            return zHuman;
        }
        JSONObject coin = firstCoinOf(zCoins, zTokenId);
        if (coin == null) {
            throw new IllegalArgumentException("no coins of token " + zTokenId + " in this wallet");
        }
        Token token = TxnFactory.fromCoinJson(coin, 0).getToken();
        if (token == null) {
            throw new IllegalArgumentException(
                    "this coin carries no token descriptor for " + zTokenId + " - not signing");
        }
        assertScaleAgreesWithTheNode(coin, token, zTokenId);

        MiniNumber raw = token.getScaledMinimaAmount(zHuman);
        // Round-trip or refuse. Scaling down by 10^scale (36 for MxUSD) can land below MiniNumber's
        // precision, and a silently floored amount is a silently wrong payment.
        if (!token.getScaledTokenAmount(raw).isEqual(zHuman)) {
            throw new IllegalArgumentException("that amount is finer than this token's smallest unit");
        }
        if (!raw.isMore(MiniNumber.ZERO)) {
            throw new IllegalArgumentException("that amount is too small to send");
        }
        return raw;
    }

    /**
     * The node reports a token coin twice: {@code amount} (Minima-scaled) and {@code tokenamount}
     * (human). If our reading of the descriptor's scale cannot turn one into the other, the scale
     * is not what we think it is - and scale is the number we are about to multiply money by.
     */
    private static void assertScaleAgreesWithTheNode(JSONObject zCoin, Token zToken, String zTokenId) {
        Object shown = zCoin.get("tokenamount");
        if (shown == null) {
            throw new IllegalArgumentException(
                    "this coin of " + zTokenId + " has no tokenamount to check its scale against");
        }
        try {
            MiniNumber raw = new MiniNumber(String.valueOf(zCoin.get("amount")));
            MiniNumber human = new MiniNumber(String.valueOf(shown));
            if (!zToken.getScaledTokenAmount(raw).isEqual(human)) {
                throw new IllegalArgumentException("the token scale for " + zTokenId
                        + " does not match this coin (" + raw + " raw vs " + human
                        + " shown) - not signing");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "this coin of " + zTokenId + " has an unreadable amount - not signing");
        }
    }

    /** The first coin of {@code zTokenId} in a {@code coins} response, or null. */
    private static JSONObject firstCoinOf(JSONArray zCoins, String zTokenId) {
        if (zCoins == null || zTokenId == null) {
            return null;
        }
        for (Object o : zCoins) {
            if (!(o instanceof JSONObject)) {
                continue;
            }
            JSONObject c = (JSONObject) o;
            if (zTokenId.equalsIgnoreCase(String.valueOf(c.get("tokenid")))
                    && c.get("token") instanceof JSONObject) {
                return c;
            }
        }
        return null;
    }

    /**
     * Does any output of a transaction pay US {@code zWantHuman} of {@code zTokenId}?
     *
     * <p>Token AND amount must both match: a 5 MINIMA output must never confirm a "5 MxUSD" claim.
     * Token outputs are compared in DISPLAY units ({@code tokenamount}, which {@code Coin.toJSON}
     * writes only when the coin carries its token descriptor) - a token output with no descriptor
     * cannot be compared and therefore does not confirm. Never throws; a malformed output is simply
     * not a match.
     */
    public static boolean paysUs(JSONArray zOutputs, String zOurHexAddress,
                                 String zTokenId, MiniNumber zWantHuman) {
        if (zOutputs == null || zOurHexAddress == null || zOurHexAddress.isEmpty()
                || zWantHuman == null) {
            return false;
        }
        boolean minima = Util.isMinima(zTokenId);
        for (Object o : zOutputs) {
            if (!(o instanceof JSONObject)) {
                continue;
            }
            JSONObject out = (JSONObject) o;
            if (!zOurHexAddress.equalsIgnoreCase(String.valueOf(out.get("address")))) {
                continue;
            }
            String tok = String.valueOf(out.get("tokenid"));
            if (minima ? !Util.isMinima(tok) : !zTokenId.equalsIgnoreCase(tok)) {
                continue;
            }
            // Minima: the raw amount IS the displayed amount. Token: only tokenamount is comparable
            // to what the sender claimed - the raw amount is scaled by the token's scale.
            Object amt = minima ? out.get("amount") : out.get("tokenamount");
            if (amt == null) {
                continue;
            }
            try {
                if (new MiniNumber(String.valueOf(amt)).isEqual(zWantHuman)) {
                    return true;
                }
            } catch (Exception ignored) {
                // A malformed amount is not a match.
            }
        }
        return false;
    }
}
