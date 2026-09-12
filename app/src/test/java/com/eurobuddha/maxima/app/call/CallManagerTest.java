package com.eurobuddha.maxima.app.call;

import android.content.Context;
import com.eurobuddha.maxima.core.chat.ChatMessage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual call manager with the Android/WebRTC boundary mocked, following CloudSessionTest. */
public class CallManagerTest {
    private CallManager calls;
    private PeerConnection pc;
    private ExecutorService worker;
    private final AtomicReference<SdpObserver> remoteSet = new AtomicReference<>();
    private final AtomicReference<SessionDescription> remote = new AtomicReference<>();
    private final IceCandidate candidate = new IceCandidate("0", 0,
            "candidate:1 1 UDP 2122260223 192.0.2.1 40000 typ host");

    @Before public void setUp() throws Exception {
        Constructor<CallManager> ctor = CallManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        calls = ctor.newInstance(mock(Context.class));
        pc = mock(PeerConnection.class);
        set("mPc", pc); set("mCallId", "synthetic-call"); set("mPeerKey", "synthetic-peer");
        set("mState", CallManager.State.OUTGOING_RINGING);
        worker = (ExecutorService) get("mExec");
        when(pc.getRemoteDescription()).thenAnswer(i -> remote.get());
        doAnswer(i -> { remoteSet.set(i.getArgument(0)); return null; })
                .when(pc).setRemoteDescription(any(), any());
    }

    @After public void tearDown() throws Exception {
        for (String name : new String[]{"mExec", "mSendExec"}) {
            ExecutorService executor = (ExecutorService) get(name);
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void earlyIceWaitsForRemoteDescriptionSuccess() throws Exception {
        pending().add(candidate);
        answer();
        verify(pc, never()).addIceCandidate(any());
        finishRemote();
        verify(pc).addIceCandidate(candidate);
        assertTrue(pending().isEmpty());
    }

    @Test public void iceArrivingDuringRemoteDescriptionIsDrainedAfterSuccess() throws Exception {
        answer();
        calls.onSignal("synthetic-peer", ChatMessage.call("synthetic-call", "ice",
                candidate.sdpMid + "\n" + candidate.sdpMLineIndex + "\n" + candidate.sdp));
        idle();
        assertEquals(1, pending().size());
        finishRemote();
        verify(pc).addIceCandidate(any());
        assertTrue(pending().isEmpty());
    }

    @Test public void lateRemoteDescriptionCallbackCannotDrainReplacementCall() throws Exception {
        answer();
        PeerConnection replacement = mock(PeerConnection.class);
        set("mPc", replacement); set("mCallId", "replacement-call");
        pending().add(candidate);
        finishRemote();
        verify(replacement, never()).addIceCandidate(any());
        assertEquals(1, pending().size());
    }

    @Test public void acceptingWaitsForRemoteOfferBeforeCreatingAnswer() throws Exception {
        PeerConnectionFactory factory = mock(PeerConnectionFactory.class);
        when(factory.createPeerConnection(any(PeerConnection.RTCConfiguration.class), any(PeerConnection.Observer.class))).thenReturn(pc);
        when(factory.createAudioSource(any())).thenReturn(mock(AudioSource.class));
        when(factory.createAudioTrack(anyString(), any())).thenReturn(mock(AudioTrack.class));
        set("mFactory", factory); set("mState", CallManager.State.INCOMING_RINGING);
        set("mPendingOfferSdp", "synthetic-offer");
        calls.accept(); idle();
        assertNotNull(remoteSet.get());
        verify(pc, never()).createAnswer(any(), any());
        finishRemote();
        verify(pc).createAnswer(any(), any());
    }

    @Test public void failedRemoteDescriptionKeepsIceFromBeingApplied() throws Exception {
        pending().add(candidate);
        answer(); remoteSet.get().onSetFailure("synthetic failure"); idle();
        verify(pc, never()).addIceCandidate(any());
        verify(pc, never()).createAnswer(any(), any());
    }

    @Test public void staleNotificationCannotAnswerOrDeclineReplacementCall() throws Exception {
        set("mState", CallManager.State.INCOMING_RINGING);
        set("mPendingOfferSdp", "synthetic-offer");
        calls.accept("old-call"); calls.decline("old-call"); idle();
        assertEquals(CallManager.State.INCOMING_RINGING, calls.state());
        assertEquals("synthetic-call", calls.callId());
        verify(pc, never()).setRemoteDescription(any(), any());
        verify(pc, never()).close();
    }

    @Test public void notificationDeclinesOnlyItsOwnCall() throws Exception {
        set("mState", CallManager.State.INCOMING_RINGING);
        calls.decline("synthetic-call"); idle();
        verify(pc).close();
        assertEquals(CallManager.State.IDLE, calls.state());
        assertEquals("", calls.callId());
    }

    @Test public void duplicateAnswersCannotReapplySdpOrRestartLiveCall() throws Exception {
        answer(); answer();
        verify(pc, times(1)).setRemoteDescription(any(), any());
        finishRemote();
        set("mState", CallManager.State.LIVE);
        answer();
        assertEquals(CallManager.State.LIVE, calls.state());
        verify(pc, times(1)).setRemoteDescription(any(), any());
    }

    @Test public void answerCannotReplaceAnIncomingOffer() throws Exception {
        set("mState", CallManager.State.INCOMING_RINGING);
        calls.onSignal("synthetic-peer", ChatMessage.call("synthetic-call", "answer", "unexpected-answer"));
        idle();
        assertEquals(CallManager.State.INCOMING_RINGING, calls.state());
        verify(pc, never()).setRemoteDescription(any(), any());
    }

    @Test public void connectionFailureSendsByeForTheRetiredCall() throws Exception {
        List<Runnable> queued = queuedFailureBye();
        com.eurobuddha.maxima.core.chat.ChatEngine chat = mock(com.eurobuddha.maxima.core.chat.ChatEngine.class);
        com.eurobuddha.maxima.core.ChatPort port = mock(com.eurobuddha.maxima.core.ChatPort.class);
        com.eurobuddha.maxima.core.contacts.Contact peer = new com.eurobuddha.maxima.core.contacts.Contact("synthetic-peer");
        when(port.contact("synthetic-peer")).thenReturn(peer);
        try (org.mockito.MockedStatic<com.eurobuddha.maxima.app.MaximaService> service = mockStatic(com.eurobuddha.maxima.app.MaximaService.class)) {
            service.when(com.eurobuddha.maxima.app.MaximaService::chat).thenReturn(chat);
            service.when(com.eurobuddha.maxima.app.MaximaService::port).thenReturn(port);
            queued.get(0).run();
            org.mockito.ArgumentCaptor<ChatMessage> sent = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
            verify(chat).sendCallSignal(eq(peer), sent.capture());
            assertEquals("synthetic-call", sent.getValue().ref);
            assertEquals("bye", sent.getValue().state);
        }
    }

    /** Hold transport until after the state has been cleared, then run its actual queued send. */
    private List<Runnable> queuedFailureBye() throws Exception {
        ExecutorService sender = (ExecutorService) get("mSendExec");
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        sender.execute(() -> {
            started.countDown();
            try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        worker.submit(() -> {
            try {
                java.lang.reflect.Method end = CallManager.class.getDeclaredMethod("end", String.class, boolean.class);
                end.setAccessible(true); end.invoke(calls, "couldn't connect", true);
            } catch (Exception e) { throw new RuntimeException(e); }
        }).get(5, TimeUnit.SECONDS);
        List<Runnable> queued = sender.shutdownNow();
        assertEquals(1, queued.size());
        assertEquals("", calls.callId());
        assertEquals(CallManager.State.IDLE, calls.state());
        verify(pc).close();
        return queued;
    }

    private void answer() throws Exception {
        calls.onSignal("synthetic-peer", ChatMessage.call("synthetic-call", "answer", "synthetic-sdp"));
        idle(); assertNotNull(remoteSet.get());
    }
    private void finishRemote() throws Exception {
        remote.set(new SessionDescription(SessionDescription.Type.ANSWER, "synthetic-sdp"));
        remoteSet.get().onSetSuccess(); idle();
    }
    private void idle() throws Exception { worker.submit(() -> {}).get(5, TimeUnit.SECONDS); }
    @SuppressWarnings("unchecked") private List<IceCandidate> pending() throws Exception {
        return (List<IceCandidate>) get("mPendingIce");
    }
    private Object get(String name) throws Exception { Field f = field(name); return f.get(calls); }
    private void set(String name, Object value) throws Exception { field(name).set(calls, value); }
    private Field field(String name) throws Exception {
        Field f = CallManager.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
