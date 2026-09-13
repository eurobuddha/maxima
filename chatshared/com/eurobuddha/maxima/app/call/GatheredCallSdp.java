package com.eurobuddha.maxima.app.call;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

/** Gather the initial candidates into SDP so a slow signalling route costs one send.
 * All state runs on the owning call executor. Late ICE still uses the existing wire format. */
public final class GatheredCallSdp {
    public static final long WAIT_MS = 1500;
    private final PeerConnection pc;
    private final BooleanSupplier valid;
    private final Consumer<Runnable> dispatch, cancel;
    private final BiConsumer<Runnable, Long> schedule;
    private final BiConsumer<String, String> send;
    private final Consumer<String> failed;
    private final Runnable deadline;
    private boolean ready, sent, closed;
    private String kind, published = "";

    public GatheredCallSdp(PeerConnection peer, BooleanSupplier active,
            Consumer<Runnable> executor, BiConsumer<Runnable, Long> timer,
            Consumer<Runnable> untimer, BiConsumer<String, String> signal,
            Consumer<String> error) {
        pc = peer; valid = active; dispatch = executor; schedule = timer;
        cancel = untimer; send = signal; failed = error;
        deadline = () -> dispatch.accept(this::publish);
    }

    public void start(SessionDescription description) {
        if (closed || !valid.getAsBoolean()) return;
        kind = description.type.canonicalForm();
        pc.setLocalDescription(new SdpObserver() {
            @Override public void onSetSuccess() { dispatch.accept(() -> {
                if (closed || !valid.getAsBoolean()) return;
                ready = true;
                if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) publish();
                else schedule.accept(deadline, WAIT_MS);
            }); }
            @Override public void onSetFailure(String error) { dispatch.accept(() -> {
                if (!closed && valid.getAsBoolean()) { close(); failed.accept(error); }
            }); }
            @Override public void onCreateSuccess(SessionDescription ignored) { }
            @Override public void onCreateFailure(String ignored) { }
        }, description);
    }

    public void gathering(PeerConnection.IceGatheringState state) {
        if (state == PeerConnection.IceGatheringState.COMPLETE) publish();
    }

    public void candidate(IceCandidate ice) {
        // Before publication, WebRTC itself retains gathered ICE in localDescription.
        if (closed || !sent || !valid.getAsBoolean()) return;
        if (!contains(published, ice)) {
            send.accept("ice", ice.sdpMid + "\n" + ice.sdpMLineIndex + "\n" + ice.sdp);
        }
    }

    private void publish() {
        if (closed || sent || !ready || !valid.getAsBoolean()) return;
        SessionDescription local = pc.getLocalDescription();
        if (local == null) { close(); failed.accept("local description unavailable"); return; }
        published = local.description;
        sent = true;
        cancel.accept(deadline);
        send.accept(kind, published);
    }

    /** Compare within the candidate's media section, not another audio/video section. */
    static boolean contains(String sdp, IceCandidate ice) {
        // Trickle ICE repeats ufrag; SDP carries it as a separate media attribute.
        String candidate = ice.sdp.replaceAll("\\s+ufrag\\s+\\S+", "");
        int section = -1;
        for (String line : sdp.split("\\r?\\n")) {
            if (line.startsWith("m=")) section++;
            if (section == ice.sdpMLineIndex && line.equals("a=" + candidate)) return true;
        }
        return false;
    }

    public void close() { closed = true; cancel.accept(deadline); }
}
