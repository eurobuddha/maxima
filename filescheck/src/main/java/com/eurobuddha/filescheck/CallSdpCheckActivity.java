package com.eurobuddha.filescheck;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import com.eurobuddha.maxima.app.call.GatheredCallSdp;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.webrtc.*;

/** Two ephemeral native peers in this test app. No chat identity, microphone or remote server. */
public final class CallSdpCheckActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean done = new AtomicBoolean();
    private final PeerConnection[] peers = new PeerConnection[2];
    private final GatheredCallSdp[] batches = new GatheredCallSdp[2];
    private final boolean[] connected = new boolean[2];
    private PeerConnectionFactory factory;
    private TextView text;
    private final Runnable timeout = () -> dispatch(() -> finishCheck("FAIL: native SDP check timed out"));

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this); text.setText("Testing native candidate batching with two ephemeral peers…");
        text.setPadding(32,48,32,32); setContentView(text);
        main.postDelayed(timeout, 20000);
        dispatch(() -> {
            try {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
                for (int i = 0; i < 2; i++) {
                    final int who = i;
                    PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
                    config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
                    peers[i] = factory.createPeerConnection(config, observer(who));
                    batches[i] = new GatheredCallSdp(peers[i], () -> !done.get(), this::dispatch,
                            (r, ms) -> main.postDelayed(r, ms), main::removeCallbacks,
                            (kind, sdp) -> signal(who, kind, sdp), error -> finishCheck("FAIL: " + error));
                }
                // Data creates a real ICE/DTLS transport without accessing microphone or camera.
                peers[0].createDataChannel("synthetic-sdp-check", new DataChannel.Init());
                peers[0].createOffer(new Sdp() {
                    @Override public void onCreateSuccess(SessionDescription sdp) { dispatch(() -> batches[0].start(sdp)); }
                }, new MediaConstraints());
            } catch (Throwable e) { finishCheck("FAIL: " + e); }
        });
    }
    private void signal(int who, String kind, String body) {
        if (done.get()) return;
        if (kind.equals("ice")) { finishCheck("FAIL: unexpected separate ICE after complete local gathering"); return; }
        int count = body.split("a=candidate:", -1).length - 1;
        if (count == 0) { finishCheck("FAIL: native " + kind + " contains no candidates"); return; }
        android.util.Log.i("CallSdpCheck", kind + " contains " + count + " native candidates");
        final int other = 1 - who;
        peers[other].setRemoteDescription(new Sdp() {
            @Override public void onSetSuccess() { dispatch(() -> {
                if (done.get() || !kind.equals("offer")) return;
                peers[other].createAnswer(new Sdp() {
                    @Override public void onCreateSuccess(SessionDescription sdp) { dispatch(() -> batches[other].start(sdp)); }
                }, new MediaConstraints());
            }); }
        }, new SessionDescription(kind.equals("offer") ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER, body));
    }
    private PeerConnection.Observer observer(int who) {
        return new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate ice) { dispatch(() -> { if (!done.get()) batches[who].candidate(ice); }); }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { dispatch(() -> { if (!done.get()) batches[who].gathering(state); }); }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) { dispatch(() -> {
                if (done.get()) return;
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    connected[who] = true;
                    if (connected[0] && connected[1]) finishCheck("PASS: native Android peers connected using only candidate-bearing offer and answer; no separate ICE, microphone, camera or chat account");
                } else if (state == PeerConnection.PeerConnectionState.FAILED) finishCheck("FAIL: native peer connection failed");
            }); }
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
            @Override public void onIceConnectionReceivingChange(boolean value) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] ice) { }
            @Override public void onAddStream(MediaStream stream) { }
            @Override public void onRemoveStream(MediaStream stream) { }
            @Override public void onDataChannel(DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) { }
        };
    }
    private class Sdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { dispatch(() -> finishCheck("FAIL: " + error)); }
        @Override public void onSetFailure(String error) { dispatch(() -> finishCheck("FAIL: " + error)); }
    }
    private void dispatch(Runnable work) {
        try { worker.execute(work); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void finishCheck(String result) {
        if (!done.compareAndSet(false, true)) return;
        main.removeCallbacks(timeout);
        android.util.Log.i("CallSdpCheck", result);
        try (java.io.FileOutputStream file = openFileOutput("call-sdp-result.txt", MODE_PRIVATE)) {
            file.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        main.post(() -> text.setText(result));
        for (GatheredCallSdp batch : batches) if (batch != null) batch.close();
        for (PeerConnection peer : peers) if (peer != null) { peer.close(); peer.dispose(); }
        if (factory != null) factory.dispose();
        worker.shutdown();
    }
    @Override public void onDestroy() {
        dispatch(() -> finishCheck("STOPPED: test screen closed"));
        super.onDestroy();
    }
}
