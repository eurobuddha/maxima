package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.store.FileStore;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contacts-only keets inbound (the Android no-mirror rule): with auto-create
 * off, a message from an unknown pubkey is consumed without creating a thread;
 * a known contact's message still lands. Default stays auto-create (node and
 * desktop flavours, where Parlons is the only chat UI).
 */
public class KeetsContactsOnlyTest {

    private static final String STRANGER =
            "0x1111111111111111111111111111111111111111111111111111111111111111";
    private static final String FRIEND =
            "0x2222222222222222222222222222222222222222222222222222222222222222";

    private static final class FakePort implements ChatPort {
        final boolean knowsFriend;
        boolean stored = false;
        FakePort(boolean zKnowsFriend) { knowsFriend = zKnowsFriend; }
        public String publicKeyHex() { return "0xABCD"; }
        public String name() { return "test"; }
        public Contact contact(String zKey) {
            if (knowsFriend && FRIEND.equalsIgnoreCase(zKey)) {
                Contact c = new Contact();
                c.publicKey = FRIEND;
                c.kind = "keets";
                return c;
            }
            return null;
        }
        public List<Contact> contacts() { return Collections.emptyList(); }
        public MaximaSender.Result sendToContact(Contact zC, String zApp, byte[] zData) {
            return MaximaSender.Result.of(0);
        }
        public void introduce(String zAddr, boolean zIntro) { }
        public boolean removeContact(String zKey) { return false; }
        public List<String> myAddresses() { return Collections.emptyList(); }
        public void log(String zLine) { }
        public Contact storeKeetsContact(String zPubkey, String zName) {
            stored = true;
            Contact c = new Contact();
            c.publicKey = zPubkey;
            c.kind = "keets";
            return c;
        }
    }

    private static ChatEngine engine(FakePort zPort) throws Exception {
        File tmp = Files.createTempDirectory("keets-contacts-only").toFile();
        ChatEngine chat = new ChatEngine(zPort);
        chat.setStore(new FileStore(new File(tmp, "chat")));
        return chat;
    }

    private static String wire(String zId, String zBody) {
        return "{\"application\":\"maxima_chat_v1\",\"id\":\"" + zId
                + "\",\"body\":\"" + zBody + "\",\"name\":\"X\",\"ts\":1791940000000}";
    }

    @Test
    public void strangerIsDroppedWhenAutoCreateOff() throws Exception {
        FakePort port = new FakePort(false);
        ChatEngine chat = engine(port);
        chat.setKeetsAutoCreate(false);
        assertTrue(chat.onKeetsInbound(STRANGER, wire("kc-s1", "psst"), "t1"));
        assertEquals(0, chat.conversation(STRANGER).size());
        assertTrue("no contact may be auto-created", !port.stored);
    }

    @Test
    public void knownContactStillLandsWhenAutoCreateOff() throws Exception {
        ChatEngine chat = engine(new FakePort(true));
        chat.setKeetsAutoCreate(false);
        assertTrue(chat.onKeetsInbound(FRIEND, wire("kc-f1", "hello friend"), "t2"));
        List<ChatEngine.Entry> conv = chat.conversation(FRIEND);
        assertEquals(1, conv.size());
        assertEquals("hello friend", conv.get(0).body);
    }

    @Test
    public void strangerStillLandsByDefault() throws Exception {
        FakePort port = new FakePort(false);
        ChatEngine chat = engine(port);
        assertTrue(chat.onKeetsInbound(STRANGER, wire("kc-s2", "gday"), "t3"));
        assertEquals(1, chat.conversation(STRANGER).size());
        assertTrue("default keeps auto-create", port.stored);
    }
}
