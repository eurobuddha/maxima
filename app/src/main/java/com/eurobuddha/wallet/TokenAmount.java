package com.eurobuddha.wallet;

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
