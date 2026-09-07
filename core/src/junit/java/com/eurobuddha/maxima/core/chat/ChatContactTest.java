package com.eurobuddha.maxima.core.chat;

import static org.junit.Assert.*;
import org.junit.Test;

public class ChatContactTest {

    @Test
    public void roundTrip() {
        String body = ChatContact.wrap("0xABCD", "Alice", "MAX#0xABCD#MxG1@1.2.3.4:9501");
        assertTrue(ChatContact.isCard(body));
        assertFalse(ChatMedia.isMedia(body));
        assertFalse(ChatPay.isPayment(body));
        assertEquals("0xABCD", ChatContact.key(body));
        assertEquals("Alice", ChatContact.name(body));
        assertEquals("MAX#0xABCD#MxG1@1.2.3.4:9501", ChatContact.address(body));
        assertEquals("👤 Contact: Alice", ChatContact.preview(body));
    }

    @Test
    public void survivesDeMarkerAndPlainTextIsNotACard() {
        String body = ChatContact.wrap("0xABCD", "Bob", "Mx1@h:1");
        assertEquals(body, ChatEngine.deMarker(body));
        assertNull(ChatContact.parse("hello"));
        assertEquals("hello", ChatContact.preview("hello"));
        assertEquals("👤 Contact: (no name)", ChatContact.preview(ChatContact.wrap("0x1", "", "Mx1@h:1")));
    }

    @Test
    public void separatorsInFieldsAreStripped() {
        String body = ChatContact.wrap("0x1x", "Eve", "Mx1@h:1");
        assertEquals("0x1x", ChatContact.key(body));
        assertEquals("Eve", ChatContact.name(body));
        assertEquals("Mx1@h:1", ChatContact.address(body));
    }
}
