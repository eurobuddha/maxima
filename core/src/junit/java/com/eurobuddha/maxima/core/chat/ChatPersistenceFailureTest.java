package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

public class ChatPersistenceFailureTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private ChatEngine chat(FileStore store) {
        ChatPort port = (ChatPort) Proxy.newProxyInstance(ChatPort.class.getClassLoader(),
                new Class<?>[]{ChatPort.class}, (proxy, method, args) ->
                        method.getName().equals("addFlushHook") ? true : null);
        ChatEngine chat = new ChatEngine(port);
        chat.setStore(store);
        return chat;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    @Test public void aFailedStateBatchRetainsEntriesNotYetWritten() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        ChatEngine chat = chat(store);
        Map<String, ChatEngine.Entry> entries = field(chat, "mMessages");
        Set<String> dirty = field(chat, "mDirty");
        for (String id : new String[]{"one", "two"}) {
            entries.put(id, new ChatEngine.Entry(id, "0xAA", "", "0xBB", id, 1, false, Receipt.DELIVERED));
            dirty.add(id);
        }
        Path target = Files.createDirectory(dir.resolve("chat_messages.tsv"));
        Files.write(target.resolve("blocker"), new byte[]{1});
        try {
            assertThrows(UncheckedIOException.class, chat::flushState);
            assertEquals("both the failed write and unattempted writes stay queued", 2, dirty.size());
            Files.delete(target.resolve("blocker")); Files.delete(target);
            assertEquals(2, chat.flushState());
            assertEquals(2, new FileStore(dir.toFile()).all("chat_messages").size());
        } finally {
            if (Files.isDirectory(target)) {
                Files.deleteIfExists(target.resolve("blocker"));
                Files.delete(target);
            }
            chat.close();
        }
    }

    @Test public void closeFlushesNewMessagesEvenWithoutDeferredState() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true);
        ChatEngine chat = chat(store);
        store.put("chat_messages", "new", "payload");
        chat.close();
        assertEquals("payload", new FileStore(dir.toFile()).get("chat_messages", "new"));
    }

    @Test public void aFailedCloseStillShutsDownEveryChatWorkerAndCanRetry() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true);
        ChatEngine chat = chat(store);
        store.put("chat_messages", "new", "payload");
        Path target = Files.createDirectory(dir.resolve("chat_messages.tsv"));
        Files.write(target.resolve("blocker"), new byte[]{1});
        try {
            assertThrows(UncheckedIOException.class, chat::close);
            for (String name : new String[]{"mReceiptFlusher", "mGroupPool", "mReceiptPool"}) {
                assertTrue(name, ChatPersistenceFailureTest.<ExecutorService>field(chat, name).isShutdown());
            }
        } finally {
            Files.delete(target.resolve("blocker")); Files.delete(target);
            chat.close();
        }
        assertEquals("payload", new FileStore(dir.toFile()).get("chat_messages", "new"));
    }
}
