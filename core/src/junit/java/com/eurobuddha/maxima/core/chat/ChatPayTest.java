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
}
