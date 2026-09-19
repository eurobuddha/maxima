package com.eurobuddha.maxima.core.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The payment envelope carried inside a chat body. */
public class ChatPayTest {

    @Test
    public void roundTrip() {
        String body = ChatPay.wrap("1000000", "MINIMA", "0xTXID", "for the pizza");
        assertTrue(ChatPay.isPayment(body));
        assertEquals("1000000", ChatPay.amount(body));
        assertEquals("MINIMA", ChatPay.tokenName(body));
        assertEquals("0xTXID", ChatPay.txid(body));
        assertEquals("for the pizza", ChatPay.memo(body));
    }

    @Test
    public void markerDiscipline() {
        assertFalse(ChatPay.isPayment("plain text"));
        assertFalse(ChatPay.isPayment("p not a marker"));
        assertFalse(ChatPay.isPayment(null));
        assertNull(ChatPay.parse("plain"));
        // A media body is not a payment (2nd marker char differs: 'm' vs 'p').
        assertFalse(ChatPay.isPayment(ChatMedia.wrap("image/jpeg", "mx1:x", "")));
    }

    @Test
    public void injectionIsSanitizedExceptMemo() {
        // A crafted amount/token/txid with an embedded SEP must not shift fields;
        // memo is the last catch-all so it may legitimately contain anything.
        String body = ChatPay.wrap("100evil", "MINIMA", "0xtx", "note|with|bars");
        assertEquals("MINIMA", ChatPay.tokenName(body));
        assertEquals("0xtx", ChatPay.txid(body));
        assertEquals("note|with|bars", ChatPay.memo(body));
    }

    @Test
    public void previewShowsAmountAndToken() {
        String preview = ChatPay.preview(ChatPay.wrap("50", "MINIMA", "0xt", "lunch"));
        assertTrue(preview.contains("50"));
        assertTrue(preview.contains("MINIMA"));
    }

    @Test
    public void previewLabelsTheToken() {
        String preview = ChatPay.preview(ChatPay.wrap("25", ChatPay.NAME_MXUSD, "0xt", "lunch"));
        assertTrue(preview.contains("25 " + ChatPay.NAME_MXUSD));
    }

    /** With two currencies the NAME is the discriminator, so no id is stored in the body. */
    @Test
    public void tokenIdComesFromTheName() {
        assertEquals(ChatPay.TOKENID_MXUSD,
                ChatPay.tokenId(ChatPay.wrap("25", ChatPay.NAME_MXUSD, "0xt", "")));
        assertEquals(ChatPay.TOKENID_MINIMA,
                ChatPay.tokenId(ChatPay.wrap("25", ChatPay.NAME_MINIMA, "0xt", "")));
        // Case-insensitive: the Parlons Node used to label native Minima "Minima", the phone "MINIMA".
        assertEquals(ChatPay.TOKENID_MXUSD, ChatPay.tokenId(ChatPay.wrap("25", "mxusd", "0xt", "")));
        assertEquals(ChatPay.TOKENID_MINIMA, ChatPay.tokenId(ChatPay.wrap("25", "Minima", "0xt", "")));
        // A non-payment body is never a token claim.
        assertEquals(ChatPay.TOKENID_MINIMA, ChatPay.tokenId("plain text"));
        assertEquals(ChatPay.TOKENID_MINIMA, ChatPay.tokenId(null));
        // An unknown label must fall back to Minima, never to the token.
        assertEquals(ChatPay.TOKENID_MINIMA, ChatPay.tokenId(ChatPay.wrap("25", "WAT", "0xt", "")));
    }

    @Test
    public void nameForIsTheInverse() {
        assertEquals(ChatPay.NAME_MXUSD, ChatPay.nameFor(ChatPay.TOKENID_MXUSD));
        assertEquals(ChatPay.NAME_MXUSD, ChatPay.nameFor(ChatPay.TOKENID_MXUSD.toLowerCase()));
        assertEquals(ChatPay.NAME_MINIMA, ChatPay.nameFor(ChatPay.TOKENID_MINIMA));
        assertEquals(ChatPay.NAME_MINIMA, ChatPay.nameFor("0xDEAD"));
    }

    /** Only the two currencies are sendable; anything else is refused, never defaulted. */
    @Test
    public void onlyTwoCurrenciesAreSendable() {
        assertTrue(ChatPay.isSendable(ChatPay.TOKENID_MINIMA));
        assertTrue(ChatPay.isSendable(ChatPay.TOKENID_MXUSD));
        assertTrue(ChatPay.isSendable(ChatPay.TOKENID_MXUSD.toLowerCase()));
        assertFalse(ChatPay.isSendable("0xSOMEOTHERTOKEN"));
        assertFalse(ChatPay.isSendable(""));
        assertFalse(ChatPay.isSendable(null));
    }

    /**
     * Pins that adding MxUSD did NOT change the on-disk body format: still exactly three
     * separators, so every payment already stored keeps parsing.
     */
    @Test
    public void bodyFormatIsUnchanged() {
        String body = ChatPay.wrap("25", ChatPay.NAME_MXUSD, "0xt", "lunch");
        char sep = body.charAt(0);              // MARK is SEP + 'p'
        int seps = 0;
        for (int i = 1; i < body.length(); i++) {
            if (body.charAt(i) == sep) {
                seps++;
            }
        }
        assertEquals(3, seps);
    }

    /**
     * A memo may contain a separator - which is exactly why the token id is derived from the
     * name instead of appended as a 5th field.
     */
    @Test
    public void memoWithASeparatorStillParses() {
        String sep = String.valueOf(ChatPay.wrap("1", "MINIMA", "0xt", "").charAt(0));
        String body = ChatPay.wrap("25", ChatPay.NAME_MXUSD, "0xt", "a" + sep + "b");
        assertEquals("25", ChatPay.amount(body));
        assertEquals(ChatPay.NAME_MXUSD, ChatPay.tokenName(body));
        assertEquals("0xt", ChatPay.txid(body));
        assertEquals("a" + sep + "b", ChatPay.memo(body));
        assertEquals(ChatPay.TOKENID_MXUSD, ChatPay.tokenId(body));
    }
}
