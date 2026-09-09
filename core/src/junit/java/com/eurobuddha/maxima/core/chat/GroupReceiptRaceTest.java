package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.ChatPort;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.Assert.*;

public class GroupReceiptRaceTest {
    @Test public void arrivalsDuringTheFlushSnapshotSurviveForTheNextBatch() throws Exception {
        ChatPort port = (ChatPort) Proxy.newProxyInstance(ChatPort.class.getClassLoader(),
                new Class<?>[]{ChatPort.class}, (proxy, method, args) -> null);
        ChatEngine chat = new ChatEngine(port);
        ChatEngine.Entry old = entry("old", 1), newer = entry("newer", 2), other = entry("other", 3);
        Field field = ChatEngine.class.getDeclaredField("mPendingGroupReceipts");
        field.setAccessible(true);
        Method queue = ChatEngine.class.getDeclaredMethod("queueGroupReceipt", String.class, ChatEngine.Entry.class);
        Method flush = ChatEngine.class.getDeclaredMethod("flushGroupReceipts");
        queue.setAccessible(true);
        flush.setAccessible(true);
        Map<String, ChatEngine.Entry> pending = new ConcurrentHashMap<String, ChatEngine.Entry>() {
            boolean injected;
            @Override public Set<Map.Entry<String, ChatEngine.Entry>> entrySet() {
                Map<String, ChatEngine.Entry> snapshot = new LinkedHashMap<>();
                for (Map.Entry<String, ChatEngine.Entry> e : super.entrySet()) snapshot.put(e.getKey(), e.getValue());
                if (!injected) {
                    injected = true;
                    try {
                        queue.invoke(chat, "0xAA", newer);
                        queue.invoke(chat, "0xBB", other);
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }
                return snapshot.entrySet();
            }
        };
        pending.put("0xAA", old);
        field.set(chat, pending);
        try {
            flush.invoke(chat);
            assertSame("replacement must remain queued", newer, pending.get("0xAA"));
            assertSame("new sender must remain queued", other, pending.get("0xBB"));
            flush.invoke(chat);
            assertTrue("the next flush drains both surviving entries", pending.isEmpty());
        } finally { chat.close(); }
    }

    private static ChatEngine.Entry entry(String id, long time) {
        return new ChatEngine.Entry(id, "", "group", "0xAA", "body", time, false, Receipt.DELIVERED);
    }
}
