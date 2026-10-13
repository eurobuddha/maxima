package com.eurobuddha.maxima.core.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The KeetChat dialect translator. The inbound fixtures are REAL payloads
 * captured from KeetChat 1.9.25 on the live keets transport (2026-10-13), so a
 * drift in either dialect fails here before it fails on the wire.
 */
public class KeetsWireTest {

    // Verbatim from a Fold -> Mac inbox drain.
    private static final String REAL_READ =
            "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-1791916777854-779586483\","
                    + "\"body\":\"\",\"name\":\"\",\"ts\":1791916777855,"
                    + "\"state\":\"read\",\"ref\":\"kc-1791916757674-vtest\"}";

    private static final String REAL_TEXT =
            "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-1791916757674-vtest\","
                    + "\"body\":\"1.9.25 verification ping from the Mac test node\","
                    + "\"name\":\"Mac test node\",\"ts\":1791916757674}";

    @Test
    public void realTextDecodes() {
        KeetsWire.Msg k = KeetsWire.decode(REAL_TEXT);
        ChatMessage c = KeetsWire.toChatMessage(k);
        assertEquals(ChatMessage.TYPE_TEXT, c.type);
        assertEquals("kc-1791916757674-vtest", c.id);
        assertEquals("1.9.25 verification ping from the Mac test node", c.body);
        assertEquals(1791916757674L, c.time);
        assertEquals("Mac test node", k.name);
    }

    @Test
    public void realReadReceiptDecodesAsUptoRead() {
        ChatMessage c = KeetsWire.toChatMessage(KeetsWire.decode(REAL_READ));
        assertEquals(ChatMessage.TYPE_RECEIPT, c.type);
        assertEquals(Receipt.READ, c.state);
        assertEquals("kc-1791916757674-vtest", c.ref);
        assertTrue("KeetChat receipts cover everything up to ref", c.upto);
    }

    @Test
    public void imageAndControlStatesSurvive() {
        String img = "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-1-a\",\"body\":\"cap\","
                + "\"name\":\"n\",\"ts\":5,\"state\":\"image\","
                + "\"ref\":\"keets://aa/chat/img-1.jpg?hash=bb\"}";
        KeetsWire.Msg k = KeetsWire.decode(img);
        assertEquals("image", k.state);
        assertEquals("keets://aa/chat/img-1.jpg?hash=bb", k.ref);
        // image maps in the engine (it needs the transport), not in toChatMessage
        assertNull(KeetsWire.toChatMessage(k));

        String pay = "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-2\",\"ts\":6,"
                + "\"state\":\"payment\",\"amt\":\"1.5\",\"tokname\":\"MxUSD\",\"txid\":\"0xAB\"}";
        ChatMessage p = KeetsWire.toChatMessage(KeetsWire.decode(pay));
        assertEquals(ChatMessage.TYPE_PAYMENT, p.type);
        assertEquals("1.5", p.amount);
        assertEquals("MxUSD", p.tokenName);
        assertEquals("0xAB", p.txid);

        String addr = "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-3\",\"ts\":7,"
                + "\"state\":\"payaddr\",\"addr\":\"MxG087AKH9PKADQUP5VRW46ZGTKWHSRUYSCR4MUY9181H8RN5ZGMJW4WK110N5R\"}";
        ChatMessage a = KeetsWire.toChatMessage(KeetsWire.decode(addr));
        assertEquals(ChatMessage.TYPE_ADDRESS, a.type);
        assertEquals("MxG087AKH9PKADQUP5VRW46ZGTKWHSRUYSCR4MUY9181H8RN5ZGMJW4WK110N5R", a.address);

        // payaddr-req is the bridge's (never stored); unknown states are consumed
        assertNull(KeetsWire.toChatMessage(KeetsWire.decode(
                "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-4\",\"state\":\"payaddr-req\"}")));
        assertNull(KeetsWire.toChatMessage(KeetsWire.decode(
                "{\"application\":\"maxima_chat_v1\",\"id\":\"kc-5\",\"state\":\"hologram\"}")));
    }

    @Test
    public void controlsAlwaysCarryBodyNameAndNonEmptyId() {
        // KeetChat's decoder REQUIRES body to be present (else raw-text display,
        // seen live on the Fold 2026-10-13) and dedupes on id. "noname" is the
        // engine's unset sentinel and must never cross.
        ChatMessage addr = new ChatMessage();
        addr.type = ChatMessage.TYPE_ADDRESS;
        addr.address = "MxG085TEST";
        String wire = KeetsWire.encode(addr, "noname");
        assertTrue("body field present", wire.contains("\"body\":\"\""));
        assertTrue("name field present", wire.contains("\"name\":\"\""));
        KeetsWire.Msg k = KeetsWire.decode(wire);
        assertTrue("id synthesized", k.id != null && !k.id.isEmpty());
        assertTrue("synthesized ids are pn- prefixed", k.id.startsWith("pn-"));
        assertEquals("", k.name);
        assertEquals("payaddr", k.state);
        assertEquals("MxG085TEST", k.addr);

        String read = KeetsWire.encode(ChatMessage.receipt("kc-1", Receipt.READ), "me");
        assertTrue(read.contains("\"body\":\"\""));
        String img = KeetsWire.image("", "me", 1L, "keets://x/y.jpg?hash=z", null);
        assertTrue(img.contains("\"body\":\"\""));
        assertTrue(KeetsWire.decode(img).id.startsWith("pn-"));
    }

    @Test
    public void nonDialectReturnsNull() {
        assertNull(KeetsWire.decode("just some plain text"));
        assertNull(KeetsWire.decode("{\"t\":1,\"id\":\"x\",\"body\":\"ours, not keets\"}"));
        assertNull(KeetsWire.decode(null));
    }

    @Test
    public void outboundTextRoundTripsThroughKeetChatShape() {
        ChatMessage m = ChatMessage.text("pn-42", "hello from Parlons", 1791916000000L);
        String wire = KeetsWire.encode(m, "Parlons Node");
        KeetsWire.Msg k = KeetsWire.decode(wire);
        assertEquals("pn-42", k.id);
        assertEquals("hello from Parlons", k.body);
        assertEquals("Parlons Node", k.name);
        assertEquals(1791916000000L, k.ts);
        assertEquals("", k.state);
    }

    @Test
    public void outboundReceiptOnlyCrossesWhenRead() {
        ChatMessage read = ChatMessage.receipt("kc-9", Receipt.READ);
        KeetsWire.Msg k = KeetsWire.decode(KeetsWire.encode(read, ""));
        assertEquals("read", k.state);
        assertEquals("kc-9", k.ref);
        // DELIVERED has no keets equivalent - the transport result is the tick
        assertNull(KeetsWire.encode(ChatMessage.receipt("kc-9", Receipt.DELIVERED), ""));
    }

    @Test
    public void outboundImageAndGroupTypes() {
        String img = KeetsWire.image("pn-7", "me", 9L, "keets://cc/chat/img-2.jpg?hash=dd", "look");
        KeetsWire.Msg k = KeetsWire.decode(img);
        assertEquals("image", k.state);
        assertEquals("keets://cc/chat/img-2.jpg?hash=dd", k.ref);
        assertEquals("look", k.body);
        // groups/roster/call never cross
        ChatMessage g = ChatMessage.groupText("id", "gid", "hi", 1L);
        assertNull(KeetsWire.encode(g, "me"));
    }
}
