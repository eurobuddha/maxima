package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import org.minima.utils.json.JSONObject;
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
public class PortalCallManagerTest {
    private PortalCallManager calls;
    private PeerConnection pc;
    private ExecutorService worker;
    private final AtomicReference<SdpObserver> remoteSet = new AtomicReference<>();
    private final AtomicReference<SessionDescription> remote = new AtomicReference<>();
    private final IceCandidate candidate = new IceCandidate("0", 0,
            "candidate:1 1 UDP 2122260223 192.0.2.1 40000 typ host");

    @Before public void setUp() throws Exception {
        Constructor<PortalCallManager> ctor = PortalCallManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        calls = ctor.newInstance(mock(Context.class));
        pc = mock(PeerConnection.class);
        set("mPc", pc); set("mCallId", "synthetic-call"); set("mPeerKey", "synthetic-peer");
        set("mState", PortalCallManager.State.OUTGOING_RINGING);
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
        calls.onSignal(signal("ice",
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
        set("mFactory", factory); set("mState", PortalCallManager.State.INCOMING_RINGING);
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

    private JSONObject signal(String kind, String payload) {
        JSONObject signal = new JSONObject();
        signal.put("kind", kind); signal.put("payload", payload);
        signal.put("from", "synthetic-peer"); signal.put("ref", "synthetic-call");
        return signal;
    }
    private void answer() throws Exception {
        calls.onSignal(signal("answer", "synthetic-sdp"));
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
        Field f = PortalCallManager.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
