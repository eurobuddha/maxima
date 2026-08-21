package com.eurobuddha.maxima.core.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wire encode/decode round-trip for every message type. */
public class ChatMessageTest {

    @Test
    public void textRoundTrip() {
        ChatMessage m = ChatMessage.decode(ChatMessage.text("id1", "hello", 1234L).encode());
        assertEquals(ChatMessage.TYPE_TEXT, m.type);
        assertEquals("id1", m.id);
        assertEquals("hello", m.body);
        assertEquals(1234L, m.time);
    }

    @Test
    public void paymentRoundTrip() {
        ChatMessage m = ChatMessage.decode(
                ChatMessage.payment("p1", "100", "0x00", "MINIMA", "memo", "0xTX", 9L).encode());
        assertEquals(ChatMessage.TYPE_PAYMENT, m.type);
        assertEquals("100", m.amount);
        assertEquals("MINIMA", m.tokenName);
        assertEquals("0xTX", m.txid);
        assertEquals("memo", m.memo);
    }

    @Test
    public void receiptRoundTrip() {
        ChatMessage m = ChatMessage.decode(
                ChatMessage.receipt("ref9", Receipt.DELIVERED).encode());
        assertEquals(ChatMessage.TYPE_RECEIPT, m.type);
        assertEquals("ref9", m.ref);
        assertEquals(Receipt.DELIVERED, m.state);
    }

    @Test
    public void callFactorySetsFieldsAndTimestamp() {
        ChatMessage m = ChatMessage.call("call-abc", "offer", "sdp-blob");
        assertEquals(ChatMessage.TYPE_CALL, m.type);
        assertEquals("call-abc", m.ref);
        assertEquals("offer", m.state);
        assertEquals("sdp-blob", m.body);
        assertTrue("call carries a send time for the stale-offer guard", m.time > 0);
        // null payload becomes empty, never NPE.
        assertEquals("", ChatMessage.call("c", "bye", null).body);
    }

    @Test
    public void garbageTimestampDecodesToZero() {
        ChatMessage m = ChatMessage.decode("{\"t\":\"1\",\"id\":\"x\",\"ts\":\"notanumber\"}");
        assertEquals(0L, m.time);
    }

    @Test
    public void nonNumericTypeIsZeroNotThrow() {
        ChatMessage m = ChatMessage.decode("{\"t\":\"weird\"}");
        assertEquals(0, m.type);
    }

    @Test
    public void bodyWithSpecialCharsSurvives() {
        String tricky = "comma,\"quote\"\nnewline";
        ChatMessage m = ChatMessage.decode(ChatMessage.text("i", tricky, 1L).encode());
        assertEquals(tricky, m.body);
    }
}
