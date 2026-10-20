package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import com.eurobuddha.maxima.cloud.ParlonsRemote;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Production message merging with synthetic events, no account, phone or network. */
public class CloudMessagePushTest {
    private final List<Object> messages = new ArrayList<>();

    @Test public void ownPushReplacesOptimisticBubbleAndKeepsDeliveryState() throws Exception {
        echo("local:one", "hello", 1000);
        Object sent = push(event("server:one", "hello", true, 1010, "queued"));
        assertEquals(1, messages.size());
        assertSame(sent, messages.get(0));
        assertEquals(true, get(sent, "mine"));
        assertEquals("queued", get(sent, "state"));
    }

    @Test public void sameTextSendsConsumeOnlyOneEchoEachAndReplayConsumesNone() throws Exception {
        Object first = echo("local:one", "hello", 1000);
        Object second = echo("local:two", "hello", 1100);
        JSONObject event = event("server:two", "hello", true, 1101, "sent");
        push(event);
        assertEquals(2, messages.size());
        assertTrue(messages.contains(first));
        assertFalse(messages.contains(second));
        assertNull(push(event));
        assertTrue(messages.contains(first));
        push(event("server:one", "hello", true, 1001, "delivered"));
        assertEquals(2, messages.size());
        assertFalse(messages.contains(first));
    }

    @Test public void incomingSameTextNeverConsumesOurEcho() throws Exception {
        Object local = echo("local:one", "hello", 1000);
        Object incoming = push(event("incoming", "hello", false, 1010, "delivered"));
        assertEquals(2, messages.size());
        assertTrue(messages.contains(local));
        assertEquals(false, get(incoming, "mine"));
    }

    @Test public void pollingAnAlreadyPushedIdCannotConsumeTheNextIdenticalSend() throws Exception {
        echo("local:one", "hello", 1000);
        Object second = echo("local:two", "hello", 1100);
        Object confirmed = push(event("server:one", "hello", true, 1001, "sent"));
        List<Object> pending = new ArrayList<>(Collections.singletonList(second));
        Method reconcile = CloudChatActivity.class.getDeclaredMethod("consumeEchoes", List.class, List.class, List.class);
        reconcile.setAccessible(true);
        reconcile.invoke(null, pending, Collections.singletonList(confirmed), messages);
        assertEquals(Collections.singletonList(second), pending);
    }

    @Test public void anotherDeviceSendIsOursWithoutNeedingALocalEcho() throws Exception {
        Object sent = push(event("sibling", "hello", true, 1000, "sent"));
        assertEquals(1, messages.size());
        assertEquals(true, get(sent, "mine"));
        assertEquals("sent", get(sent, "state"));
    }

    @Test public void oldAndDifferentDraftsSurviveOwnPush() throws Exception {
        Object old = echo("local:old", "hello", 1000);
        Object different = echo("local:other", "other", 200000);
        push(event("server", "hello", true, 200001, "sent"));
        assertEquals(3, messages.size());
        assertTrue(messages.contains(old));
        assertTrue(messages.contains(different));
    }

    @Test public void legacyInboundPushAndMissingIdRemainSafe() throws Exception {
        JSONObject legacy = event("legacy", "hello", false, 1000, "delivered");
        legacy.remove("mine"); legacy.remove("state");
        Object incoming = push(legacy);
        assertEquals(false, get(incoming, "mine"));
        assertEquals("delivered", get(incoming, "state"));
        legacy.remove("id");
        assertNull(push(legacy));
        assertEquals(1, messages.size());
    }

    @Test public void ownPushCannotPostAnIncomingNotification() {
        Context context = mock(Context.class);
        PortalNotifier.onPushedMessage(context, event("own", "hello", true, 1000, "sent"));
        verifyNoInteractions(context);
    }

    @Test public void fullSnapshotStartedBeforePushCannotEraseConfirmedSend() throws Exception {
        CloudChatActivity screen = screen();
        echo("local:one", "hello", 1000);
        AtomicReference<CloudSession.Cb> fetch = new AtomicReference<>();
        try (MockedStatic<CloudSession> session = mockStatic(CloudSession.class)) {
            session.when(() -> CloudSession.connectInteractive(eq(screen), any()))
                    .thenAnswer(i -> { fetch.set(i.getArgument(1)); return null; });
            invoke(screen, "load");
            invoke(screen, "onPush", event("confirmed", "hello", true, 1010, "sent"));
            assertEquals(1, messages.size());
            assertEquals(0L, get(screen, "mReadMark"));
            session.verify(() -> CloudSession.connect(eq(screen), any()), never());
            ParlonsRemote remote = mock(ParlonsRemote.class);
            JSONObject beforeSend = new JSONObject(); beforeSend.put("messages", new JSONArray());
            when(remote.conversation("synthetic-peer")).thenReturn(beforeSend);
            fetch.get().ok(remote);
            assertEquals(1, messages.size());
            assertEquals("confirmed", get(messages.get(0), "id"));
            assertEquals(false, get(screen, "mBusy"));
            assertEquals(0, get(screen, "mPollN"));
        }
    }

    @Test public void incomingPushStillRequestsReadReceipt() throws Exception {
        CloudChatActivity screen = screen();
        try (MockedStatic<CloudSession> session = mockStatic(CloudSession.class)) {
            invoke(screen, "onPush", event("incoming", "hello", false, 1010, "delivered"));
            session.verify(() -> CloudSession.connect(eq(screen), any()));
            assertEquals(1010L, get(screen, "mReadMark"));
        }
    }

    private CloudChatActivity screen() throws Exception {
        CloudChatActivity screen = mock(CloudChatActivity.class, CALLS_REAL_METHODS);
        set(screen, "mPeer", "synthetic-peer"); set(screen, "mMsgs", messages);
        set(screen, "mList", mock(RecyclerView.class));
        Class<?> adapter = Class.forName(CloudChatActivity.class.getName() + "$Adapter");
        set(screen, "mAdapter", mock(adapter));
        doReturn(null).when(screen).findViewById(anyInt());
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; })
                .when(screen).runOnUiThread(any());
        return screen;
    }

    private static void invoke(CloudChatActivity screen, String name, JSONObject... event) throws Exception {
        Method m = event.length == 0 ? CloudChatActivity.class.getDeclaredMethod(name)
                : CloudChatActivity.class.getDeclaredMethod(name, JSONObject.class);
        m.setAccessible(true);
        m.invoke(screen, (Object[]) event);
    }

    private Object push(JSONObject event) throws Exception {
        Method merge = CloudChatActivity.class.getDeclaredMethod("mergePushedMessage", List.class, JSONObject.class);
        merge.setAccessible(true);
        return merge.invoke(null, messages, event);
    }

    private Object echo(String id, String body, long time) throws Exception {
        Class<?> type = Class.forName(CloudChatActivity.class.getName() + "$Msg");
        Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
        Object m = ctor.newInstance();
        set(m, "id", id); set(m, "body", body); set(m, "mine", true);
        set(m, "time", time); set(m, "state", "queued");
        messages.add(m);
        return m;
    }

    private static JSONObject event(String id, String body, boolean mine, long time, String state) {
        JSONObject ev = new JSONObject();
        ev.put("type", "message"); ev.put("peer", "synthetic-peer"); ev.put("id", id);
        ev.put("body", body); ev.put("mine", mine); ev.put("time", time); ev.put("state", state);
        return ev;
    }

    private static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static void set(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, value);
    }
}
