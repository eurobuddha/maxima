package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.*;
import com.eurobuddha.maxima.core.chat.*;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.rpc.RpcEnvelope;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real signed, encrypted held mail, through the relay's normal possession/drain/ACK path. */
public class MailboxAgeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static MaximaIdentity identity() throws Exception { return MaximaIdentity.fromPhrase(Bip39.generate(24)); }

    @Test public void dayOldTextIsDurableBeforeTheRelayDeletesItsCopy() throws Exception {
        try (Fixture f = new Fixture()) {
            Contact contact = new Contact(f.sender.publicKeyHex()); contact.lastSeen = 123;
            Field field = MaximaNode.class.getDeclaredField("mContacts"); field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, Contact> contacts = (Map<String, Contact>) field.get(f.node);
            contacts.put(Keys.norm(contact.publicKey), contact);
            f.chat(ChatMessage.text("old-text", "held text", f.now - DAY), f.now - DAY);
            f.drain();
            assertNotNull("old text must reach the conversation", f.chat.message("old-text"));
            assertTrue("payload was flushed before deletion", new FileStore(f.chatDir).get("chat_messages", "old-text").contains("held text"));
            assertEquals("held content is not proof the sender is online now", 123, contact.lastSeen);
        }
    }

    @Test public void sixDayTextGroupPaymentAndClassicContentStillArrive() throws Exception {
        try (Fixture f = new Fixture()) {
            String sender = f.sender.publicKeyHex(); long time = f.now - 6 * DAY;
            f.chat(ChatMessage.roster("group", "Test", Arrays.asList(sender, f.owner.publicKeyHex()), sender), f.now);
            f.chat(ChatMessage.text("text", "text", time), time);
            f.chat(ChatMessage.groupText("group-text", "group", "group text", time), time);
            f.chat(ChatMessage.payment("payment", "1", "0x00", "TEST", "notification only", "synthetic", time), time);
            f.queue(ClassicChat.APPLICATION, ClassicChat.build("peer", "classic text"), time);
            f.queue(ClassicChat.APPLICATION, ClassicChat.buildImage("peer", "image caption", ""), time);
            f.drain();
            assertEquals(5, f.chat.allMessages().size());
            assertNotNull(f.chat.message("text")); assertNotNull(f.chat.message("group-text")); assertNotNull(f.chat.message("payment"));
            assertEquals(5, new FileStore(f.chatDir).all("chat_messages").size());
        }
    }

    @Test public void staleControlsUnknownApplicationsAndInvalidContentStayRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            long time = f.now - DAY;
            f.chat(ChatMessage.call("call", "offer", "sdp"), time);
            f.chat(ChatMessage.address("synthetic-address"), time);
            f.chat(ChatMessage.roster("old-group", "Old", Collections.singletonList(f.sender.publicKeyHex()), f.sender.publicKeyHex()), time);
            f.chat(ChatMessage.receipt("ref", Receipt.READ), time);
            f.queue("other-app", "{}", time);
            f.queue(ChatMessage.APPLICATION, "not JSON", time);
            f.queue(ChatMessage.APPLICATION, "{\"t\":1}", time);
            f.queue(ChatMessage.APPLICATION, "{\"t\":99,\"id\":\"unknown\"}", time);
            f.queue(ClassicChat.APPLICATION, "not JSON", time);
            AtomicInteger commands = new AtomicInteger();
            f.node.services().register("age-test", req -> { commands.incrementAndGet(); return new byte[0]; });
            f.queue(RpcEnvelope.APPLICATION, RpcEnvelope.request("old-rpc", "age-test", Collections.emptyList(), new byte[0]).toBytes(), time);
            f.queue(RpcEnvelope.APPLICATION, RpcEnvelope.request("fresh-rpc", "age-test", Collections.emptyList(), new byte[0]).toBytes(), f.now);
            f.drain();
            Field rpcField = MaximaNode.class.getDeclaredField("mRpcExec"); rpcField.setAccessible(true);
            ((ExecutorService) rpcField.get(f.node)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals("only the fresh command runs", 1, commands.get());
            assertEquals("no stale app/control message reaches the listener", 0, f.deliveries.get());
            assertTrue(f.chat.allMessages().isEmpty());
        }
    }

    @Test public void futureAndBeyondRetentionContentStayRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            for (long time : new long[]{f.now + 7 * 60 * 60 * 1000L, f.now - 8 * DAY, Long.MIN_VALUE, Long.MAX_VALUE}) {
                f.chat(ChatMessage.text("time-" + time, "text", time), time);
                f.queue(ClassicChat.APPLICATION, ClassicChat.build("peer", "text"), time);
            }
            f.drain(); assertEquals(0, f.deliveries.get()); assertTrue(f.chat.allMessages().isEmpty());
        }
    }

    @Test public void delayedGroupContentCannotRestoreARemovedMembersAccess() throws Exception {
        try (Fixture f = new Fixture()) {
            String sender = f.sender.publicKeyHex(), owner = f.owner.publicKeyHex();
            f.chat(ChatMessage.roster("group", "Test", Arrays.asList(sender, owner), sender), f.now);
            // The original admin hands ownership over and removes themselves before old mail arrives.
            f.chat(ChatMessage.roster("group", "Test", Collections.singletonList(owner), owner), f.now);
            f.chat(ChatMessage.groupText("removed-member", "group", "old post", f.now - DAY), f.now - DAY);
            f.drain();
            assertEquals("the signed history reaches the normal authorization checks", 3, f.deliveries.get());
            assertNull("current membership still governs posting", f.chat.message("removed-member"));
            assertTrue(f.chat.allMessages().isEmpty());
        }
    }

    @Test public void relayRedeliveryStillDeduplicatesAnAcceptedOldMessage() throws Exception {
        try (Fixture f = new Fixture()) {
            MaximaSender.Built held = f.chat(ChatMessage.text("once", "text", f.now - DAY), f.now - DAY);
            f.drain(); assertEquals(1, f.deliveries.get());
            f.store(held);
            f.relay.sweepConnections(System.currentTimeMillis() + 90_001, Long.MAX_VALUE, Long.MAX_VALUE);
            AttachedSendTest.waitFor(() -> f.flushes.get() >= 2 && f.relay.mailbox().count(f.routeKey) == 0, 5000);
            assertEquals(1, f.deliveries.get()); assertEquals(1, f.chat.allMessages().size());
        }
    }

    private final class Fixture implements AutoCloseable {
        final long now = System.currentTimeMillis();
        final int port = AttachedSendTest.freePort(); final String hp = "127.0.0.1:" + port;
        final MaximaIdentity owner = identity(), sender = identity();
        final RelayServer relay = new RelayServer(identity(), port, "1.0.48");
        final MaximaNode node = new MaximaNode(owner, "1.0.48", 0);
        final ChatEngine chat = new ChatEngine(node);
        final File chatDir = tmp.newFolder();
        final byte[] route = owner.hostKey(hp).getPublic().getEncoded();
        final String routeKey = new MiniData(route).to0xString();
        final AtomicInteger deliveries = new AtomicInteger(), flushes = new AtomicInteger();
        Fixture() throws Exception {
            FileStore nodeStore = new FileStore(tmp.newFolder()); nodeStore.setWriteBehind(true); node.setStore(nodeStore);
            FileStore store = new FileStore(chatDir); store.setWriteBehind(true); chat.setStore(store);
            node.setMessageListener((message, id) -> { chat.onInbound(message, id.to0xString()); deliveries.incrementAndGet(); });
            node.addFlushHook(flushes::incrementAndGet);
        }
        MaximaSender.Built chat(ChatMessage message, long time) throws Exception { return queue(ChatMessage.APPLICATION, message.encode(), time); }
        MaximaSender.Built queue(String app, String body, long time) throws Exception { return queue(app, body.getBytes(StandardCharsets.UTF_8), time); }
        MaximaSender.Built queue(String app, byte[] body, long time) throws Exception {
            MaximaSender.Built built = MaximaSender.build(sender.publicKey(), sender.keyPair().getPrivate(), route, app, body, time);
            store(built); return built;
        }
        void store(MaximaSender.Built built) { assertEquals(Mailbox.Result.STORED, relay.mailbox().store(routeKey, Codec.serialise(built.unit))); }
        void drain() throws Exception {
            relay.start(); assertTrue(node.pool().attachOne(hp, 5000));
            AttachedSendTest.waitFor(() -> flushes.get() >= 1 && relay.mailbox().count(routeKey) == 0, 5000);
        }
        @Override public void close() { try { node.stop(); } finally { try { chat.close(); } finally { relay.stop(); } } }
    }
}
