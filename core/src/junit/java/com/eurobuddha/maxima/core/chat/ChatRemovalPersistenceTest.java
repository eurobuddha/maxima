package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.identity.Keys;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.maxima.core.store.Store;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ChatRemovalPersistenceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static ChatEngine chat(Store store) {
        ChatPort port = (ChatPort) Proxy.newProxyInstance(ChatPort.class.getClassLoader(),
                new Class<?>[]{ChatPort.class}, (proxy, method, args) -> method.getName().equals("addFlushHook") ? true : null);
        ChatEngine chat = new ChatEngine(port); chat.setStore(store); return chat;
    }
    private static MaximaMessage message(String id, int peer, long time) {
        MaximaMessage message = new MaximaMessage(); message.mFrom = new MiniData(new byte[]{(byte) peer});
        message.mTimeMilli = new MiniNumber(time); message.mApplication = new MiniString(ChatMessage.APPLICATION);
        message.mData = new MiniData(ChatMessage.text(id, "private payload " + id, time).encode().getBytes(StandardCharsets.UTF_8));
        return message;
    }
    private static String peer(int value) { return Keys.norm(new MiniData(new byte[]{(byte) value}).to0xString()); }

    @Test public void clearRejectsAnExactReplayBeforeAndAfterRestart() throws Exception {
        Path dir = tmp.newFolder("store").toPath(); FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true); MaximaMessage message = message("original", 1, System.currentTimeMillis());
        ChatEngine chat = chat(store);
        try {
            chat.onInbound(message, "0xAA"); assertEquals(1, chat.clearConversation(peer(1)));
            chat.onInbound(message, "0xAA"); assertTrue(chat.allMessages().isEmpty());
        } finally { chat.close(); }
        String saved = Files.readString(dir.resolve("chat_messages.tsv"));
        assertFalse("removed content is replaced, not retained in the marker", saved.contains("private payload"));
        ChatEngine reopened = chat(new FileStore(dir.toFile()));
        try {
            reopened.onInbound(message, "0xAA"); assertTrue(reopened.allMessages().isEmpty());
            reopened.onInbound(message("fresh", 1, System.currentTimeMillis()), "0xBB");
            assertNotNull("clearing never blocks new messages", reopened.message("fresh"));
        } finally { reopened.close(); }
    }

    @Test public void prunedMessagesStayPrunedAfterRestart() throws Exception {
        Path dir = tmp.newFolder("store").toPath(); FileStore store = new FileStore(dir.toFile()); store.setWriteBehind(true);
        ChatEngine chat = chat(store); chat.setMaxPerConversation(2); long now = System.currentTimeMillis();
        MaximaMessage old = message("old", 1, now - 3000);
        try {
            chat.onInbound(old, "0xAA"); chat.message("old").arrived = now - 3000;
            chat.onInbound(message("middle", 1, now - 2000), "0xBB"); chat.message("middle").arrived = now - 2000;
            chat.onInbound(message("new", 1, now - 1000), "0xCC"); assertNull(chat.message("old"));
        } finally { chat.close(); }
        ChatEngine reopened = chat(new FileStore(dir.toFile()));
        try { reopened.onInbound(old, "0xAA"); assertNull(reopened.message("old")); assertEquals(2, reopened.allMessages().size()); }
        finally { reopened.close(); }
    }

    @Test public void theRemovalBudgetAndOrderSurviveRestart() throws Exception {
        Path dir = tmp.newFolder("store").toPath(); FileStore store = new FileStore(dir.toFile()); store.setWriteBehind(true);
        ChatEngine chat = chat(store); long now = System.currentTimeMillis();
        MaximaMessage recentRemoval = message("inserted-first-removed-last", 1, now);
        try {
            chat.onInbound(recentRemoval, "0xAA");
            chat.onInbound(message("oldest-removal", 2, now), "0xBB"); chat.clearConversation(peer(2));
            for (int i = 0; i < 3998; i++) {
                chat.onInbound(message("removed-" + i, 3, now), "0xCC"); chat.clearConversation(peer(3));
            }
            chat.clearConversation(peer(1));
        } finally { chat.close(); }
        FileStore restored = new FileStore(dir.toFile()); restored.setWriteBehind(true);
        ChatEngine reopened = chat(restored);
        try {
            reopened.onInbound(message("next", 4, now), "0xDD"); reopened.clearConversation(peer(4));
            assertEquals("the existing 4,000-removal limit is retained on disk", 4000, restored.all("chat_messages").size());
            assertNull("evict by removal order, not original message insertion", restored.get("chat_messages", "oldest-removal"));
            reopened.onInbound(recentRemoval, "0xAA"); assertTrue(reopened.allMessages().isEmpty());
        } finally { reopened.close(); }
    }

    @Test public void retryAfterFailedEvictionCannotLeaveAnOriginalPayloadOnDisk() throws Exception {
        Path dir = tmp.newFolder("store").toPath(), saved = dir.resolveSibling("saved");
        FileStore disk = new FileStore(dir.toFile()); disk.setWriteBehind(true); AtomicBoolean armed = new AtomicBoolean();
        Store failing = (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[]{Store.class}, (proxy, method, args) -> {
            boolean obstruct = method.getName().equals("remove") && args[0].equals("chat_messages") && armed.compareAndSet(true, false);
            if (obstruct) { Files.move(dir, saved); Files.write(dir, new byte[]{1}); }
            try {
                try { return method.invoke(disk, args); } catch (InvocationTargetException e) { throw e.getCause(); }
            } finally { if (obstruct) { Files.delete(dir); Files.move(saved, dir); } }
        });
        ChatEngine chat = chat(failing); long now = System.currentTimeMillis();
        try {
            for (int i = 0; i < 4000; i++) {
                chat.onInbound(message("removed-" + i, 1, now), "0xAA"); chat.clearConversation(peer(1));
            }
            disk.flush(); disk.setWriteBehind(false);
            chat.onInbound(message("retry", 2, now), "0xBB"); armed.set(true);
            assertThrows(java.io.UncheckedIOException.class, () -> chat.clearConversation(peer(2)));
            // A late receipt/state change can have captured the entry before the failed clear.
            Field dirtyField = ChatEngine.class.getDeclaredField("mDirty"); dirtyField.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> dirty = (Set<String>) dirtyField.get(chat); dirty.add("retry");
            chat.flushState();
            assertEquals(1, chat.clearConversation(peer(2)));
        } finally { chat.close(); }
        ChatEngine reopened = chat(new FileStore(dir.toFile()));
        try { assertTrue(reopened.allMessages().isEmpty()); } finally { reopened.close(); }
        assertEquals(4000, new FileStore(dir.toFile()).all("chat_messages").size());
    }

    @Test public void deferredStateCannotOverwriteAConcurrentRemoval() throws Exception {
        Path dir = tmp.newFolder("store").toPath(); FileStore disk = new FileStore(dir.toFile()); disk.setWriteBehind(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        Store gated = (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[]{Store.class}, (proxy, method, args) -> {
            if (method.getName().equals("put") && args[0].equals("chat_messages") && args[1].equals("original")
                    && ((String) args[2]).contains("\"id\"") && armed.compareAndSet(true, false)) {
                entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("state writer not released");
            }
            try { return method.invoke(disk, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
        ChatEngine chat = chat(gated); ExecutorService worker = Executors.newFixedThreadPool(2);
        try {
            chat.onInbound(message("original", 1, System.currentTimeMillis()), "0xAA");
            Field dirtyField = ChatEngine.class.getDeclaredField("mDirty"); dirtyField.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> dirty = (Set<String>) dirtyField.get(chat); dirty.add("original"); armed.set(true);
            Future<Integer> flush = worker.submit(chat::flushState); assertTrue(entered.await(2, TimeUnit.SECONDS));
            CountDownLatch clearing = new CountDownLatch(1);
            Future<Integer> clear = worker.submit(() -> { clearing.countDown(); return chat.clearConversation(peer(1)); });
            assertTrue(clearing.await(2, TimeUnit.SECONDS));
            assertThrows("clear must serialize with a state write that already captured the entry", TimeoutException.class,
                    () -> clear.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); flush.get(2, TimeUnit.SECONDS); assertEquals(1, (int) clear.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); worker.shutdownNow(); worker.awaitTermination(3, TimeUnit.SECONDS); chat.close(); }
        ChatEngine reopened = chat(new FileStore(dir.toFile()));
        try { assertTrue(reopened.allMessages().isEmpty()); } finally { reopened.close(); }
    }
}
