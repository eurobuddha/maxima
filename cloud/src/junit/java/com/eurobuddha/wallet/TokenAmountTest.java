package com.eurobuddha.wallet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    /**
     * A coins-response entry for a token coin, shaped exactly like the node's: raw Minima-scaled
     * {@code amount}, human {@code tokenamount}, and the descriptor with MxUSD's real scale of 36
     * and its real JSON-OBJECT name (the shape that does not survive a byte-exact round trip).
     */
    private static JSONObject tokenCoin(String zHuman, String zScale) {
        JSONObject name = new JSONObject();
        name.put("name", "USDT");
        name.put("ticker", "USDT");
        name.put("url", "https://mxusd.global/svg/USDT.svg");
        JSONObject tok = new JSONObject();
        tok.put("coinid", "0x" + "11".repeat(32));
        tok.put("scale", zScale);
        tok.put("totalamount", "0.000000000000000000000000001");
        tok.put("created", "2005172");
        tok.put("name", name);
        tok.put("script", "RETURN TRUE");

        JSONObject c = new JSONObject();
        c.put("coinid", "0x" + "22".repeat(32));
        c.put("address", OURS);
        c.put("tokenid", MXUSD);
        c.put("amount", new BigDecimal(zHuman).movePointLeft(Integer.parseInt(zScale)).toPlainString());
        c.put("tokenamount", zHuman);
        c.put("mmrentry", "728");
        c.put("created", "203728");
        c.put("storestate", Boolean.FALSE);
        c.put("token", tok);
        return c;
    }

    private static JSONObject minimaCoin(String zAmount) {
        JSONObject c = new JSONObject();
        c.put("coinid", "0x" + "33".repeat(32));
        c.put("address", OURS);
        c.put("tokenid", "0x00");
        c.put("amount", zAmount);
        c.put("mmrentry", "12");
        c.put("created", "100");
        c.put("storestate", Boolean.FALSE);
        return c;
    }

    @Test
    public void minimaPassesStraightThrough() {
        JSONArray coins = outs(minimaCoin("50"));
        // MiniNumber has no equals() - compare with its own isEqual.
        assertTrue(TokenAmount.toRaw(coins, "0x00", new MiniNumber("25")).isEqual(new MiniNumber("25")));
        // No coins needed at all for Minima - raw IS the displayed amount.
        assertTrue(TokenAmount.toRaw(null, "0x00", new MiniNumber("25")).isEqual(new MiniNumber("25")));
    }

    /** 25 MxUSD is stored on-chain as 25e-36, and must round-trip back to 25. */
    @Test
    public void tokenAmountIsScaledDownByTheTokensScale() {
        JSONArray coins = outs(tokenCoin("100", "36"), minimaCoin("5"));
        MiniNumber raw = TokenAmount.toRaw(coins, MXUSD, new MiniNumber("25"));
        assertTrue(raw.isEqual(new MiniNumber(scaled("25"))));
        assertTrue(raw.isLess(new MiniNumber("1")));
        // Case-insensitive token id, as the node returns either case.
        assertTrue(TokenAmount.toRaw(coins, MXUSD.toLowerCase(), new MiniNumber("25")).isEqual(raw));
    }

    /** An amount finer than the token's grain is refused, never silently floored. */
    @Test
    public void aSubGrainAmountIsRefused() {
        JSONArray coins = outs(tokenCoin("100", "36"));
        try {
            // MiniNumber's precision cannot hold 1e-44 scaled down by another 10^36.
            TokenAmount.toRaw(coins, MXUSD, new MiniNumber("0.00000000000001"));
            fail("a sub-grain amount must not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("smallest unit"));
        }
    }

    @Test
    public void aTokenWeHoldNoCoinsOfIsRefused() {
        try {
            TokenAmount.toRaw(outs(minimaCoin("5")), MXUSD, new MiniNumber("25"));
            fail("must refuse a token the wallet holds no coins of");
        } catch (IllegalArgumentException expected) {
            // The full id, never truncated - it is what the user would have to check.
            assertTrue(expected.getMessage().contains(MXUSD));
        }
    }

    /**
     * The scale is the number we multiply money by, so it is cross-checked against the node's own
     * two views of the coin. A descriptor claiming the wrong scale must not sign.
     */
    @Test
    public void aScaleThatDisagreesWithTheCoinIsRefused() {
        JSONObject coin = tokenCoin("100", "36");
        JSONObject tok = (JSONObject) coin.get("token");
        tok.put("scale", "8");                      // descriptor now lies about the scale
        try {
            TokenAmount.toRaw(outs(coin), MXUSD, new MiniNumber("25"));
            fail("must refuse a scale that does not reproduce the coin's own amounts");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("scale"));
        }
    }

    @Test
    public void aCoinWithNoTokenAmountCannotBeScaleChecked() {
        JSONObject coin = tokenCoin("100", "36");
        coin.remove("tokenamount");
        try {
            TokenAmount.toRaw(outs(coin), MXUSD, new MiniNumber("25"));
            fail("must refuse a coin whose scale cannot be checked");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tokenamount"));
        }
    }

    @Test
    public void aNonPositiveAmountIsRefused() {
        JSONArray coins = outs(tokenCoin("100", "36"));
        for (String bad : new String[]{"0", "-1"}) {
            try {
                TokenAmount.toRaw(coins, MXUSD, new MiniNumber(bad));
                fail("must refuse " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("positive"));
            }
        }
        try {
            TokenAmount.toRaw(coins, MXUSD, null);
            fail("must refuse a null amount");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
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
