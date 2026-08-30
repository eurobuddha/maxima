package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One voice/video call at a time — the PORTAL fork of the app's CallManager.
 *
 * Identical state machine and WebRTC machinery (Opus/DTLS-SRTP peer-to-peer, our fleet's STUN),
 * with exactly the two transport seams changed: outbound signals go over the cloud control
 * channel ({@link ParlonsRemote#callSignal}) and are relayed to the peer AS the account; inbound
 * signals arrive as cloud PUSH events. The peer's phone sees a completely normal Parlons call —
 * SDP and ICE describe THIS device, so the media hole-punches straight here, never the VPS.
 */
public final class PortalCallManager {

    private static final String TAG = "ParlonsCloudCall";

    public enum State { IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, LIVE, ENDED }

    public interface Listener {
        /** Always on the main thread. */
        void onCallState(State zState, String zPeerKey, String zReason);
    }

    private static volatile PortalCallManager sInstance;

    public static PortalCallManager get(Context zCtx) {
        if (sInstance == null) {
            synchronized (PortalCallManager.class) {
                if (sInstance == null) {
                    sInstance = new PortalCallManager(zCtx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final Context mCtx;
    private final ExecutorService mExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "portal-call");
        t.setDaemon(true);
        return t;
    });
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** Signal sends are network RPCs — their own thread, so hangup/mute never queue behind one. */
    private final ExecutorService mSendExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "portal-call-signal");
        t.setDaemon(true);
        return t;
    });

    private PeerConnectionFactory mFactory;
    private PeerConnection mPc;
    private AudioSource mSource;
    private AudioTrack mTrack;

    private volatile State mState = State.IDLE;
    private volatile String mCallId = "";
    private volatile String mPeerKey = "";
    private volatile String mPeerName = "";
    private volatile long mLiveSince;
    private String mPendingOfferSdp;
    private final List<IceCandidate> mPendingIce = new ArrayList<>();
    private volatile Listener mListener;
    private Ringtone mRing;
    private Runnable mRingTimeout;
    private boolean mMuted;
    private boolean mSpeaker;
    private String mLastEndedCallId = "";
    private Runnable mConnectTimeout;

    // ---- video ----
    private volatile boolean mVideo;
    private EglBase mEgl;
    private CameraVideoCapturer mCapturer;
    private SurfaceTextureHelper mSurfaceHelper;
    private VideoSource mVideoSource;
    private VideoTrack mVideoTrack;
    private volatile VideoTrack mRemoteVideoTrack;
    private volatile VideoSink mLocalSink;
    private volatile VideoSink mRemoteSink;

    private PortalCallManager(Context zCtx) {
        mCtx = zCtx;
    }

    public void setListener(Listener zListener) {
        mListener = zListener;
    }

    public State state() {
        return mState;
    }

    public String peerKey() {
        return mPeerKey;
    }

    public String peerName() {
        return mPeerName.isEmpty() ? "contact" : mPeerName;
    }

    public int liveSeconds() {
        return mLiveSince == 0 ? 0 : (int) ((System.currentTimeMillis() - mLiveSince) / 1000);
    }

    public boolean isVideo() {
        return mVideo;
    }

    public EglBase.Context eglContext() {
        ensureFactory();
        return mEgl.getEglBaseContext();
    }

    public void attachVideoSinks(VideoSink zLocal, VideoSink zRemote) {
        mLocalSink = zLocal;
        mRemoteSink = zRemote;
        mExec.execute(() -> {
            if (mVideoTrack != null && zLocal != null) {
                mVideoTrack.addSink(zLocal);
            }
            if (mRemoteVideoTrack != null && zRemote != null) {
                mRemoteVideoTrack.addSink(zRemote);
            }
        });
    }

    public void detachVideoSinks() {
        final VideoSink l = mLocalSink, r = mRemoteSink;
        mLocalSink = null;
        mRemoteSink = null;
        mExec.execute(() -> {
            try {
                if (mVideoTrack != null && l != null) {
                    mVideoTrack.removeSink(l);
                }
                if (mRemoteVideoTrack != null && r != null) {
                    mRemoteVideoTrack.removeSink(r);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public void switchCamera() {
        mExec.execute(() -> {
            if (mCapturer != null) {
                mCapturer.switchCamera(null);
            }
        });
    }

    public boolean muted() {
        return mMuted;
    }

    public boolean speaker() {
        return mSpeaker;
    }

    // ------------------------------------------------------------------
    // Outgoing
    // ------------------------------------------------------------------

    public void startCall(final String zPeerKey, final String zPeerName, final boolean zVideo) {
        mExec.execute(() -> {
            if (mState != State.IDLE && mState != State.ENDED) {
                return;
            }
            mCallId = UUID.randomUUID().toString().substring(0, 13);
            mPeerKey = zPeerKey;
            mPeerName = zPeerName == null ? "" : zPeerName;
            mVideo = zVideo;
            setState(State.OUTGOING_RINGING, null);
            armRingTimeout();
            ensureFactory();
            createPeer();
            MediaConstraints mc = new MediaConstraints();
            mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            if (mVideo) {
                mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            }
            mPc.createOffer(new Sdp("offer-create") {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    mExec.execute(() -> {
                        mPc.setLocalDescription(new Sdp("offer-local"), sdp);
                        signal("offer", sdp.description);
                    });
                }
            }, mc);
        });
    }

    // ------------------------------------------------------------------
    // Inbound signaling — pushed by the cloud
    // ------------------------------------------------------------------

    public void onSignal(final JSONObject ev) {
        mExec.execute(() -> {
            String kind = str(ev, "kind");
            String from = str(ev, "from");
            String ref = str(ev, "ref");
            Log.i(TAG, "signal in: " + kind + " ref=" + ref + " state=" + mState);
            String payload = str(ev, "payload");
            long time = lng(ev, "time");
            switch (kind) {
                case "offer": {
                    // Relay mailboxes re-push held units on reconnect — never ghost-ring.
                    if (time > 0 && System.currentTimeMillis() - time > 90_000) {
                        Log.i(TAG, "stale call offer ignored");
                        return;
                    }
                    if (ref.equals(mLastEndedCallId) || ref.equals(mCallId)) {
                        return;
                    }
                    if (mState != State.IDLE && mState != State.ENDED) {
                        signalTo(from, ref, "busy", "");
                        return;
                    }
                    mCallId = ref;
                    mPeerKey = from;
                    mPeerName = str(ev, "name");
                    mPendingOfferSdp = payload;
                    mVideo = "video".equals(str(ev, "memo"));
                    mPendingIce.clear();
                    setState(State.INCOMING_RINGING, null);
                    armRingTimeout();
                    startRinging();
                    PortalIncomingCall.show(mCtx, from, mPeerName, mVideo);
                    break;
                }
                case "answer": {
                    if (!ref.equals(mCallId) || !from.equalsIgnoreCase(mPeerKey) || mPc == null) {
                        return;
                    }
                    stopRingTimeout();
                    setState(State.CONNECTING, null);
                    armConnectTimeout();
                    mPc.setRemoteDescription(new Sdp("answer-remote"),
                            new SessionDescription(SessionDescription.Type.ANSWER, payload));
                    drainIce();
                    break;
                }
                case "ice": {
                    if (!ref.equals(mCallId) || !from.equalsIgnoreCase(mPeerKey)) {
                        return;
                    }
                    String[] p = payload.split("\n", 3);
                    if (p.length < 3) {
                        return;
                    }
                    IceCandidate cand = new IceCandidate(p[0], Integer.parseInt(p[1]), p[2]);
                    if (mPc != null && mPc.getRemoteDescription() != null) {
                        mPc.addIceCandidate(cand);
                    } else {
                        mPendingIce.add(cand);
                    }
                    break;
                }
                case "busy": {
                    if (ref.equals(mCallId) && from.equalsIgnoreCase(mPeerKey)) {
                        end("busy", false);
                    }
                    break;
                }
                case "bye": {
                    if (ref.equals(mCallId) && from.equalsIgnoreCase(mPeerKey)
                            && mState != State.IDLE) {
                        end(mState == State.INCOMING_RINGING || mState == State.OUTGOING_RINGING
                                ? "missed" : "ended", false);
                    }
                    break;
                }
                case "taken": {
                    // Another paired device answered first — stop ringing here, quietly.
                    if (ref.equals(mCallId) && mState == State.INCOMING_RINGING) {
                        end("answered on another device", false);
                    }
                    break;
                }
                default:
                    break;
            }
        });
    }

    // ------------------------------------------------------------------
    // Incoming controls
    // ------------------------------------------------------------------

    public void accept() {
        mExec.execute(() -> {
            if (mState != State.INCOMING_RINGING || mPendingOfferSdp == null) {
                return;
            }
            stopRinging();
            stopRingTimeout();
            setState(State.CONNECTING, null);
            armConnectTimeout();
            ensureFactory();
            createPeer();
            mPc.setRemoteDescription(new Sdp("offer-remote"),
                    new SessionDescription(SessionDescription.Type.OFFER, mPendingOfferSdp));
            drainIce();
            MediaConstraints mc = new MediaConstraints();
            mPc.createAnswer(new Sdp("answer-create") {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    mExec.execute(() -> {
                        mPc.setLocalDescription(new Sdp("answer-local"), sdp);
                        signal("answer", sdp.description);
                    });
                }
            }, mc);
        });
    }

    public void decline() {
        mExec.execute(() -> {
            if (mState == State.INCOMING_RINGING) {
                signal("bye", "");
                end("declined", false);
            }
        });
    }

    public void hangup() {
        mExec.execute(() -> {
            if (mState != State.IDLE && mState != State.ENDED) {
                signal("bye", "");
                end("ended", false);
            }
        });
    }

    public void setMuted(boolean zMuted) {
        mMuted = zMuted;
        mExec.execute(() -> {
            if (mTrack != null) {
                mTrack.setEnabled(!zMuted);
            }
        });
    }

    public void setSpeaker(boolean zOn) {
        mSpeaker = zOn;
        AudioManager am = (AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setSpeakerphoneOn(zOn);
        }
    }

    // ------------------------------------------------------------------
    // Internals — identical machinery to the app's CallManager
    // ------------------------------------------------------------------

    private synchronized void ensureFactory() {
        if (mFactory != null) {
            return;
        }
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(mCtx)
                        .createInitializationOptions());
        mEgl = EglBase.create();
        mFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(JavaAudioDeviceModule.builder(mCtx)
                        .createAudioDeviceModule())
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        mEgl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        mEgl.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    private void createPeer() {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        // OUR fleet answers STUN — no third party learns who is calling.
        servers.add(PeerConnection.IceServer.builder("stun:95.179.179.181:9501").createIceServer());
        servers.add(PeerConnection.IceServer.builder("stun:65.109.31.226:9501").createIceServer());
        servers.add(PeerConnection.IceServer.builder("stun:45.77.246.226:9501").createIceServer());
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(servers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        final String callAtCreate = mCallId;
        mPc = mFactory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate c) {
                mExec.execute(() -> signal("ice",
                        c.sdpMid + "\n" + c.sdpMLineIndex + "\n" + c.sdp));
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                mExec.execute(() -> {
                    if (!callAtCreate.equals(mCallId)) {
                        return;   // stale callback from a closed PC
                    }
                    if (s == PeerConnection.PeerConnectionState.CONNECTED) {
                        stopConnectTimeout();
                        mLiveSince = System.currentTimeMillis();
                        AudioManager am = (AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                            if (mVideo) {
                                am.setSpeakerphoneOn(true);
                                mSpeaker = true;
                            }
                        }
                        setState(State.LIVE, null);
                    } else if (s == PeerConnection.PeerConnectionState.FAILED) {
                        end("couldn't connect", true);
                    } else if (s == PeerConnection.PeerConnectionState.DISCONNECTED
                            && mState == State.LIVE) {
                        end("connection lost", true);
                    }
                });
            }

            @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
            @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
            @Override public void onIceConnectionReceivingChange(boolean b) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            @Override public void onAddStream(org.webrtc.MediaStream s) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream s) { }
            @Override public void onDataChannel(org.webrtc.DataChannel d) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(org.webrtc.RtpReceiver r, org.webrtc.MediaStream[] s) {
                if (r.track() instanceof VideoTrack) {
                    mRemoteVideoTrack = (VideoTrack) r.track();
                    VideoSink sink = mRemoteSink;
                    if (sink != null) {
                        mRemoteVideoTrack.addSink(sink);
                    }
                }
            }
        });
        mSource = mFactory.createAudioSource(new MediaConstraints());
        mTrack = mFactory.createAudioTrack("a0", mSource);
        mTrack.setEnabled(!mMuted);
        mPc.addTrack(mTrack);
        if (mVideo) {
            startCamera();
        }
    }

    private void startCamera() {
        try {
            Camera2Enumerator en = new Camera2Enumerator(mCtx);
            String dev = null;
            for (String d : en.getDeviceNames()) {
                if (en.isFrontFacing(d)) {
                    dev = d;
                    break;
                }
            }
            if (dev == null && en.getDeviceNames().length > 0) {
                dev = en.getDeviceNames()[0];
            }
            if (dev == null) {
                return;
            }
            mCapturer = en.createCapturer(dev, null);
            mSurfaceHelper = SurfaceTextureHelper.create("cam", mEgl.getEglBaseContext());
            mVideoSource = mFactory.createVideoSource(false);
            mCapturer.initialize(mSurfaceHelper, mCtx, mVideoSource.getCapturerObserver());
            mCapturer.startCapture(960, 540, 24);
            mVideoTrack = mFactory.createVideoTrack("v0", mVideoSource);
            mPc.addTrack(mVideoTrack);
            VideoSink l = mLocalSink;
            if (l != null) {
                mVideoTrack.addSink(l);
            }
        } catch (Exception e) {
            Log.w(TAG, "camera unavailable: " + e.getMessage());
        }
    }

    private void drainIce() {
        for (IceCandidate c : mPendingIce) {
            mPc.addIceCandidate(c);
        }
        mPendingIce.clear();
    }

    private void armConnectTimeout() {
        stopConnectTimeout();
        mConnectTimeout = () -> mExec.execute(() -> {
            if (mState == State.CONNECTING) {
                signal("bye", "");
                end("couldn't connect", false);
            }
        });
        mMain.postDelayed(mConnectTimeout, 20_000);
    }

    private void stopConnectTimeout() {
        if (mConnectTimeout != null) {
            mMain.removeCallbacks(mConnectTimeout);
            mConnectTimeout = null;
        }
    }

    private void end(String zReason, boolean zSignalBye) {
        stopRinging();
        stopRingTimeout();
        stopConnectTimeout();
        mLastEndedCallId = mCallId;
        mCallId = "";
        PortalIncomingCall.dismiss(mCtx);
        if (zSignalBye) {
            signal("bye", "");
        }
        if (mPc != null) {
            try { mPc.close(); } catch (Exception ignored) { }
            mPc = null;
        }
        if (mSource != null) {
            try { mSource.dispose(); } catch (Exception ignored) { }
            mSource = null;
        }
        mTrack = null;
        if (mCapturer != null) {
            try { mCapturer.stopCapture(); } catch (Exception ignored) { }
            try { mCapturer.dispose(); } catch (Exception ignored) { }
            mCapturer = null;
        }
        if (mVideoSource != null) {
            try { mVideoSource.dispose(); } catch (Exception ignored) { }
            mVideoSource = null;
        }
        if (mSurfaceHelper != null) {
            try { mSurfaceHelper.dispose(); } catch (Exception ignored) { }
            mSurfaceHelper = null;
        }
        mVideoTrack = null;
        mRemoteVideoTrack = null;
        mVideo = false;
        mPendingOfferSdp = null;
        mPendingIce.clear();
        mLiveSince = 0;
        mMuted = false;
        AudioManager am = (AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(false);
        }
        mSpeaker = false;
        setState(State.ENDED, zReason);
        Log.i(TAG, "call " + zReason);
        mState = State.IDLE;
    }

    private void armRingTimeout() {
        stopRingTimeout();
        mRingTimeout = () -> mExec.execute(() -> {
            if (mState == State.OUTGOING_RINGING || mState == State.INCOMING_RINGING) {
                signal("bye", "");
                end("missed", false);
            }
        });
        mMain.postDelayed(mRingTimeout, 45_000);
    }

    private void stopRingTimeout() {
        if (mRingTimeout != null) {
            mMain.removeCallbacks(mRingTimeout);
            mRingTimeout = null;
        }
    }

    private void startRinging() {
        try {
            mRing = RingtoneManager.getRingtone(mCtx,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (mRing != null) {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    mRing.setLooping(true);
                }
                mRing.play();
            }
        } catch (Exception ignored) {
        }
    }

    private void stopRinging() {
        if (mRing != null) {
            try { mRing.stop(); } catch (Exception ignored) { }
            mRing = null;
        }
    }

    private void signal(String zKind, String zPayload) {
        signalTo(mPeerKey, mCallId, zKind, zPayload);
    }

    /** The transport seam: signals ride the cloud control channel; the node relays them to the
     *  peer AS the account over the normal chat-signal path. */
    private void signalTo(final String zPeerKey, final String zCallId,
                          final String zKind, final String zPayload) {
        final boolean video = mVideo;
        mSendExec.execute(() -> {
            String error = null;
            try {
                ParlonsRemote r = CloudSession.remoteOrNull();
                if (r == null) {
                    error = "not connected to your account";
                } else {
                    String memo = video && "offer".equals(zKind) ? "video" : "";
                    JSONObject res = r.callSignal(zPeerKey, zCallId, zKind, zPayload, memo);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            if (error != null) {
                Log.w(TAG, "call signal " + zKind + " failed: " + error);
                if ("offer".equals(zKind) || "answer".equals(zKind)) {
                    mExec.execute(() -> {
                        if (zCallId.equals(mCallId)
                                && mState != State.IDLE && mState != State.ENDED) {
                            end("couldn't reach them", false);
                        }
                    });
                }
            }
        });
    }

    private void setState(State zState, String zReason) {
        mState = zState;
        final Listener l = mListener;
        if (l != null) {
            mMain.post(() -> l.onCallState(zState, mPeerKey, zReason));
        }
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** Base SDP observer: logs failures, subclasses override the success arm. */
    private static class Sdp implements SdpObserver {
        private final String mTag;

        Sdp(String zTag) {
            mTag = zTag;
        }

        @Override public void onCreateSuccess(SessionDescription sdp) { }
        @Override public void onSetSuccess() { }

        @Override
        public void onCreateFailure(String zErr) {
            Log.w(TAG, "sdp " + mTag + ": " + zErr);
        }

        @Override
        public void onSetFailure(String zErr) {
            Log.w(TAG, "sdp " + mTag + ": " + zErr);
        }
    }
}
