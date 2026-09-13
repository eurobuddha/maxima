package com.eurobuddha.maxima.app.call;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.webrtc.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class GatheredCallSdpTest {
    private PeerConnection pc;
    private GatheredCallSdp batch;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<SdpObserver> set = new AtomicReference<>();
    private final AtomicReference<Runnable> timer = new AtomicReference<>();
    private final List<String> sent = new ArrayList<>(), errors = new ArrayList<>();
    private final IceCandidate host = new IceCandidate("0", 0, "candidate:1 1 udp 100 192.0.2.1 4000 typ host");
    private final IceCandidate publicIce = new IceCandidate("0", 0, "candidate:2 1 udp 90 198.51.100.1 4001 typ srflx");
    private String sdp(IceCandidate ice) { return "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=" + ice.sdp + "\r\n"; }

    @Before public void setup() {
        pc = mock(PeerConnection.class);
        when(pc.iceGatheringState()).thenReturn(PeerConnection.IceGatheringState.GATHERING);
        doAnswer(i -> { set.set(i.getArgument(0)); return null; }).when(pc).setLocalDescription(any(), any());
        batch = new GatheredCallSdp(pc, active::get, Runnable::run,
                (task, ms) -> { assertEquals(1500L, ms.longValue()); timer.set(task); },
                task -> timer.compareAndSet(task, null),
                (kind, body) -> sent.add(kind + "\n" + body), errors::add);
    }
    private void start(String kind) {
        batch.start(new SessionDescription(kind.equals("offer") ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER, "initial"));
        set.get().onSetSuccess();
    }

    @Test public void gatheredPublicCandidateTravelsInTheInitialAnswer() {
        start("answer"); batch.candidate(host); batch.candidate(publicIce);
        assertTrue(sent.isEmpty());
        String full = sdp(host) + "a=" + publicIce.sdp + "\r\n";
        when(pc.getLocalDescription()).thenReturn(new SessionDescription(SessionDescription.Type.ANSWER, full));
        batch.gathering(PeerConnection.IceGatheringState.COMPLETE);
        assertEquals(java.util.Collections.singletonList("answer\n" + full), sent);
        assertNull(timer.get());
        batch.candidate(publicIce);
        batch.candidate(new IceCandidate("0", 0, publicIce.sdp + " ufrag synthetic"));
        assertEquals(1, sent.size());
    }

    @Test public void deadlinePublishesAvailableCandidatesAndKeepsLateTrickle() {
        start("offer");
        when(pc.getLocalDescription()).thenReturn(new SessionDescription(SessionDescription.Type.OFFER, sdp(host)));
        timer.get().run();
        batch.candidate(host); batch.candidate(publicIce);
        assertEquals(2, sent.size()); assertEquals("offer\n" + sdp(host), sent.get(0));
        assertEquals("ice\n0\n0\n" + publicIce.sdp, sent.get(1));
        batch.gathering(PeerConnection.IceGatheringState.COMPLETE); assertEquals(2, sent.size());
    }

    @Test public void completionBeforeLocalDescriptionCallbackStillPublishesOnce() {
        batch.start(new SessionDescription(SessionDescription.Type.OFFER, "initial"));
        batch.gathering(PeerConnection.IceGatheringState.COMPLETE); assertTrue(sent.isEmpty());
        when(pc.iceGatheringState()).thenReturn(PeerConnection.IceGatheringState.COMPLETE);
        when(pc.getLocalDescription()).thenReturn(new SessionDescription(SessionDescription.Type.OFFER, sdp(host)));
        set.get().onSetSuccess(); assertEquals(1, sent.size()); assertNull(timer.get());
    }

    @Test public void hangupAndReplacementCannotPublishOldSdp() {
        start("offer"); Runnable late = timer.get(); batch.close();
        late.run(); set.get().onSetSuccess(); batch.candidate(publicIce);
        assertTrue(sent.isEmpty()); assertNull(timer.get());
    }

    @Test public void invalidCallCannotPublishAfterGathering() {
        start("answer"); active.set(false); timer.get().run();
        batch.gathering(PeerConnection.IceGatheringState.COMPLETE); assertTrue(sent.isEmpty());
    }

    @Test public void localDescriptionFailureIsReportedWithoutSending() {
        batch.start(new SessionDescription(SessionDescription.Type.OFFER, "initial"));
        set.get().onSetFailure("synthetic failure");
        assertEquals(java.util.Collections.singletonList("synthetic failure"), errors);
        assertTrue(sent.isEmpty()); assertNull(timer.get());
    }

    @Test public void sameCandidateInAnotherMediaSectionIsNotDiscarded() {
        start("answer");
        when(pc.getLocalDescription()).thenReturn(new SessionDescription(SessionDescription.Type.ANSWER, sdp(host)));
        timer.get().run(); batch.candidate(new IceCandidate("1", 1, host.sdp));
        assertEquals(2, sent.size());
    }
}
