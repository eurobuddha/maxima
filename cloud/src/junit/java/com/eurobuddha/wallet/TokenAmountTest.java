package com.eurobuddha.wallet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;

import org.junit.Test;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * The output matcher that decides whether a received payment really landed.
 *
 * The bug this pins: matching on amount alone means a 5 MINIMA output confirms a "5 MxUSD" claim.
 */
public class TokenAmountTest {

    private static final String OURS = "0x" + "AB".repeat(32);
    private static final String THEIRS = "0x" + "CD".repeat(32);
    private static final String MXUSD =
            "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    /** MxUSD's scale is 36: the on-chain amount of 25 MxUSD is 25e-36. */
    private static String scaled(String zHuman) {
        return new BigDecimal(zHuman).movePointLeft(36).toPlainString();
    }

    private static JSONObject out(String zAddress, String zTokenId, String zAmount,
                                  String zTokenAmount) {
        JSONObject o = new JSONObject();
        o.put("address", zAddress);
        o.put("tokenid", zTokenId);
        o.put("amount", zAmount);
        if (zTokenAmount != null) {
            o.put("tokenamount", zTokenAmount);
        }
        return o;
    }

    private static JSONArray outs(JSONObject... zOuts) {
        JSONArray a = new JSONArray();
        for (JSONObject o : zOuts) {
            a.add(o);
        }
        return a;
    }

    @Test
    public void minimaOutputPaysAMinimaClaim() {
        JSONArray o = outs(out(OURS, "0x00", "5", null));
        assertTrue(TokenAmount.paysUs(o, OURS, "0x00", new MiniNumber("5")));
        assertFalse(TokenAmount.paysUs(o, OURS, "0x00", new MiniNumber("4")));
    }

    /** The reported hole: cross-token confirmation, both directions. */
    @Test
    public void aMinimaOutputNeverConfirmsATokenClaim() {
        JSONArray minimaOut = outs(out(OURS, "0x00", "5", null));
        assertFalse(TokenAmount.paysUs(minimaOut, OURS, MXUSD, new MiniNumber("5")));

        JSONArray tokenOut = outs(out(OURS, MXUSD, scaled("5"), "5"));
        assertFalse(TokenAmount.paysUs(tokenOut, OURS, "0x00", new MiniNumber("5")));
    }

    /** A token is compared in DISPLAY units - never against the Minima-scaled raw amount. */
    @Test
    public void tokenOutputIsMatchedOnTheDisplayedAmount() {
        JSONArray o = outs(out(OURS, MXUSD, scaled("25"), "25"));
        assertTrue(TokenAmount.paysUs(o, OURS, MXUSD, new MiniNumber("25")));
        assertTrue(TokenAmount.paysUs(o, OURS, MXUSD.toLowerCase(), new MiniNumber("25")));
        // The raw amount must NOT be what a claim is compared against.
        assertFalse(TokenAmount.paysUs(o, OURS, MXUSD, new MiniNumber(scaled("25"))));
    }

    /** No descriptor, no tokenamount, no comparison - it must not confirm. */
    @Test
    public void aTokenOutputWithoutATokenAmountDoesNotConfirm() {
        JSONArray o = outs(out(OURS, MXUSD, scaled("25"), null));
        assertFalse(TokenAmount.paysUs(o, OURS, MXUSD, new MiniNumber("25")));
    }

    @Test
    public void paymentToSomeoneElseDoesNotConfirm() {
        JSONArray o = outs(out(THEIRS, MXUSD, scaled("25"), "25"));
        assertFalse(TokenAmount.paysUs(o, OURS, MXUSD, new MiniNumber("25")));
    }

    @Test
    public void theRightOutputAmongManyConfirms() {
        JSONArray o = outs(
                out(THEIRS, MXUSD, scaled("25"), "25"),
                out(OURS, "0x00", "1", null),
                out(OURS, MXUSD, scaled("25"), "25"));
        assertTrue(TokenAmount.paysUs(o, OURS, MXUSD, new MiniNumber("25")));
        assertTrue(TokenAmount.paysUs(o, OURS, "0x00", new MiniNumber("1")));
        assertFalse(TokenAmount.paysUs(o, OURS, "0x00", new MiniNumber("25")));
    }

    /** Addresses come back in either case from different node versions. */
    @Test
    public void addressMatchIsCaseInsensitive() {
        JSONArray o = outs(out(OURS.toLowerCase(), "0x00", "5", null));
        assertTrue(TokenAmount.paysUs(o, OURS, "0x00", new MiniNumber("5")));
    }

    /** Never throws: garbage in, false out. */
    @Test
    public void malformedInputIsNotAMatch() {
        assertFalse(TokenAmount.paysUs(null, OURS, "0x00", new MiniNumber("5")));
        assertFalse(TokenAmount.paysUs(outs(), OURS, "0x00", new MiniNumber("5")));
        assertFalse(TokenAmount.paysUs(outs(out(OURS, "0x00", "not a number", null)),
                OURS, "0x00", new MiniNumber("5")));
        assertFalse(TokenAmount.paysUs(outs(out(OURS, "0x00", "5", null)), "", "0x00",
                new MiniNumber("5")));
        assertFalse(TokenAmount.paysUs(outs(out(OURS, "0x00", "5", null)), OURS, "0x00", null));
    }
}
