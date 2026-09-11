package com.eurobuddha.maxima.app.call;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMessage;
import com.eurobuddha.maxima.core.contacts.Contact;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
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
 * One voice call at a time.
 *
 * Maxima carries ONLY the signaling (offer/answer/trickle-ICE/bye as signed,
 * end-to-end-encrypted chat-layer control messages); the audio itself is
 * WebRTC - Opus over DTLS-SRTP, phone to phone. On the same Wi-Fi the ICE
 * host candidates connect directly with zero infrastructure, exactly like
 * LAN-direct messaging; across the internet a STUN reflexive candidate
 * hole-punches most NATs. No TURN in v1: the hard-NAT minority fails with an
 * honest "couldn't connect".
 */
public final class CallManager {

    public enum State { IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, LIVE, ENDED }

    public interface Listener {
        /** Always on the main thread. */
        void onCallState(State zState, String zPeerKey, String zReason);
    }

    private static volatile CallManager sInstance;

    public static CallManager get(Context zCtx) {
        if (sInstance == null) {
            synchronized (CallManager.class) {
                if (sInstance == null) {
                    sInstance = new CallManager(zCtx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final Context mCtx;
    private final ExecutorService mExec = Executors.newSingleThreadExecutor(
            r -> {
                Thread t = new Thread(r, "call-manager");
                t.setDaemon(true);
                return t;
            });
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** Signaling sends are SYNCHRONOUS mined socket sends - to an offline
     *  peer each one blocks up to its timeouts. They get their own thread so
     *  hangup/decline/mute on mExec are never queued behind a dead send. */
    private final ExecutorService mSendExec = Executors.newSingleThreadExecutor(
            r -> {
                Thread t = new Thread(r, "call-signal");
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
    private volatile long mLiveSince;
    private String mPendingOfferSdp;
    private final List<IceCandidate> mPendingIce = new ArrayList<>();
    private volatile Listener mListener;
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

    private CallManager(Context zCtx) {
        mCtx = zCtx;
    }

    public void setListener(Listener zListener) {
        mListener = zListener;
    }

    public State state() {
        return mState;
    }

    public String callId() {
        return mCallId;
    }

    public String peerKey() {
        return mPeerKey;
    }

    /** Seconds live, 0 unless LIVE. */
    public int liveSeconds() {
        return mLiveSince == 0 ? 0
                : (int) ((System.currentTimeMillis() - mLiveSince) / 1000);
    }

    /** Is the current (or ringing) call a video call? */
    public boolean isVideo() {
        return mVideo;
    }

    /** Shared EGL context for the activity's SurfaceViewRenderers. */
    public EglBase.Context eglContext() {
        ensureFactory();
        return mEgl.getEglBaseContext();
    }

    /** The call screen hands its renderers in; tracks attach as they exist. */
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

    public void startCall(final String zPeerKey) {
        startCall(zPeerKey, false);
    }

    public void startCall(final String zPeerKey, final boolean zVideo) {
        mExec.execute(() -> {
            if (mState != State.IDLE && mState != State.ENDED) {
                return;
            }
            Contact c = contact(zPeerKey);
            if (c == null) {
                mPeerKey = zPeerKey;
                setState(State.ENDED, "no route to them");
                mState = State.IDLE;
                return;
            }
            mCallId = UUID.randomUUID().toString();
            mPeerKey = zPeerKey;
            mVideo = zVideo;
            setState(State.OUTGOING_RINGING, null);
            armRingTimeout();
            ensureFactory();
            createPeer();
            MediaConstraints mc = new MediaConstraints();
            mc.mandatory.add(new MediaConstraints.KeyValuePair(
                    "OfferToReceiveAudio", "true"));
            if (mVideo) {
                mc.mandatory.add(new MediaConstraints.KeyValuePair(
                        "OfferToReceiveVideo", "true"));
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
    // Inbound signaling (from ChatEngine, transport thread)
    // ------------------------------------------------------------------

    public void onSignal(final String zFromKey, final ChatMessage zMsg) {
        mExec.execute(() -> {
            String kind = zMsg.state;
            if (java.util.Arrays.asList("offer", "answer", "ice", "busy", "bye").contains(kind)) {
                EventLog.add("call signal in: " + kind + " state=" + mState);
            }
            switch (kind) {
                case "offer": {
                    // The relay mailbox re-pushes held units on reconnect: a
                    // stale offer must never ghost-ring a call long dead.
                    if (zMsg.time > 0
                            && System.currentTimeMillis() - zMsg.time > 90_000) {
                        EventLog.add("stale call offer ignored ("
                                + name(zFromKey) + ")");
                        return;
                    }
                    if (zMsg.ref.equals(mLastEndedCallId)) {
                        return;   // re-delivered signaling for an ended call
                    }
                    // A duplicate offer for the call we are already handling
                    // (ringing, connecting, OR live) must be a no-op - a busy
                    // reply would make the caller's busy handler end the live
                    // call. Only a DIFFERENT callId while busy gets a busy.
                    if (zMsg.ref.equals(mCallId)) {
                        return;
                    }
                    if (mState != State.IDLE && mState != State.ENDED) {
                        // one call at a time - tell the second caller we're busy
                        signalTo(zFromKey, zMsg.ref, "busy", "");
                        return;
                    }
                    // Only a known contact may ring us - an authenticated
                    // stranger who knows our key must not drive a full-screen
                    // ring (nuisance/keY-probe). accept() would fail anyway.
                    if (contact(zFromKey) == null) {
                        EventLog.add("call offer from non-contact ignored");
                        return;
                    }
                    mCallId = zMsg.ref;
                    mPeerKey = zFromKey;
                    mPendingOfferSdp = zMsg.body;
                    mVideo = "video".equals(zMsg.memo);
                    mPendingIce.clear();
                    setState(State.INCOMING_RINGING, null);
                    armRingTimeout();
                    IncomingCallScreen.show(mCtx, zFromKey);
                    break;
                }
                case "answer": {
                    if (!zMsg.ref.equals(mCallId) || !zFromKey.equals(mPeerKey) || mPc == null) {
                        return;
                    }
                    stopRingTimeout();
                    setState(State.CONNECTING, null);
                    armConnectTimeout();
                    setRemote(new SessionDescription(SessionDescription.Type.ANSWER, zMsg.body), () -> { });
                    break;
                }
                case "ice": {
                    if (!zMsg.ref.equals(mCallId) || !zFromKey.equals(mPeerKey)) {
                        return;
                    }
                    String[] p = zMsg.body.split("\n", 3);
                    if (p.length < 3) {
                        return;
                    }
                    IceCandidate cand = new IceCandidate(
                            p[0], Integer.parseInt(p[1]), p[2]);
                    if (mPc != null && mPc.getRemoteDescription() != null) {
                        mPc.addIceCandidate(cand);
                    } else {
                        mPendingIce.add(cand);
                    }
                    break;
                }
                case "busy": {
                    if (zMsg.ref.equals(mCallId) && zFromKey.equals(mPeerKey)) {
                        end("busy", false);
                    }
                    break;
                }
                case "bye": {
                    if (zMsg.ref.equals(mCallId) && zFromKey.equals(mPeerKey)
                            && mState != State.IDLE) {
                        end(mState == State.INCOMING_RINGING
                                || mState == State.OUTGOING_RINGING
                                ? "missed" : "ended", false);
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
        accept(mCallId);
    }

    /** A notification action must never answer a later, replacement call. */
    public void accept(String expectedCallId) {
        mExec.execute(() -> {
            if (expectedCallId == null || !expectedCallId.equals(mCallId)
                    || mState != State.INCOMING_RINGING || mPendingOfferSdp == null) {
                return;
            }
            stopRinging();
            stopRingTimeout();
            setState(State.CONNECTING, null);
            armConnectTimeout();
            ensureFactory();
            createPeer();
            final PeerConnection pc = mPc;
            final String call = mCallId;
            setRemote(new SessionDescription(SessionDescription.Type.OFFER, mPendingOfferSdp), () -> {
                pc.createAnswer(new Sdp("answer-create") {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        mExec.execute(() -> {
                            if (mPc != pc || !call.equals(mCallId)) { return; }
                            pc.setLocalDescription(new Sdp("answer-local"), sdp);
                            signal("answer", sdp.description);
                        });
                    }
                }, new MediaConstraints());
            });
        });
    }

    public void decline() {
        decline(mCallId);
    }

    public void decline(String expectedCallId) {
        mExec.execute(() -> {
            if (expectedCallId != null && expectedCallId.equals(mCallId)
                    && mState == State.INCOMING_RINGING) {
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
    // Internals
    // ------------------------------------------------------------------

    /** Synchronized: the call screen asks for eglContext() on the MAIN thread
     *  while the state thread may be creating the factory for accept() - an
     *  unsynchronized race builds two factories and leaks an EglBase. */
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
        // OUR fleet answers STUN (relay 0.4.22, udp on the relay port) - no
        // third party learns who is calling. Same WiFi never needs it: host
        // candidates connect directly. Four relays for redundancy.
        servers.add(PeerConnection.IceServer
                .builder("stun:95.179.179.181:9501").createIceServer());
        servers.add(PeerConnection.IceServer
                .builder("stun:65.109.31.226:9501").createIceServer());
        servers.add(PeerConnection.IceServer
                .builder("stun:45.77.246.226:9501").createIceServer());
        servers.add(PeerConnection.IceServer
                .builder("stun:78.141.237.9:9501").createIceServer());
        PeerConnection.RTCConfiguration cfg =
                new PeerConnection.RTCConfiguration(servers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        final String callAtCreate = mCallId;
        mPc = mFactory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate c) {
                mExec.execute(() -> {
                    if (!callAtCreate.equals(mCallId)) { return; }
                    EventLog.add("call local ICE: " + iceKind(c));
                    signal("ice", c.sdpMid + "\n" + c.sdpMLineIndex + "\n" + c.sdp);
                });
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                mExec.execute(() -> {
                    // Stale callback from a closed PC (WebRTC can deliver one
                    // around close): ignore unless it belongs to the CURRENT
                    // call, else a dead call's FAILED would bye the next one.
                    if (!callAtCreate.equals(mCallId)) {
                        return;
                    }
                    EventLog.add("call peer connection: " + s);
                    if (s == PeerConnection.PeerConnectionState.CONNECTED) {
                        stopConnectTimeout();
                        mLiveSince = System.currentTimeMillis();
                        AudioManager am = (AudioManager)
                                mCtx.getSystemService(Context.AUDIO_SERVICE);
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
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                mExec.execute(() -> { if (callAtCreate.equals(mCallId)) { EventLog.add("call ICE connection: " + s); } });
            }
            @Override public void onIceConnectionReceivingChange(boolean b) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                mExec.execute(() -> { if (callAtCreate.equals(mCallId)) { EventLog.add("call ICE gathering: " + s); } });
            }
            @Override public void onAddStream(org.webrtc.MediaStream s) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream s) { }
            @Override public void onDataChannel(org.webrtc.DataChannel d) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(org.webrtc.RtpReceiver r,
                    org.webrtc.MediaStream[] s) {
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

    /** Front camera -> VideoTrack on the peer connection + the local preview. */
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
            mSurfaceHelper = SurfaceTextureHelper.create("cam",
                    mEgl.getEglBaseContext());
            mVideoSource = mFactory.createVideoSource(false);
            mCapturer.initialize(mSurfaceHelper, mCtx,
                    mVideoSource.getCapturerObserver());
            mCapturer.startCapture(960, 540, 24);
            mVideoTrack = mFactory.createVideoTrack("v0", mVideoSource);
            mPc.addTrack(mVideoTrack);
            VideoSink l = mLocalSink;
            if (l != null) {
                mVideoTrack.addSink(l);
            }
        } catch (Exception e) {
            EventLog.add("camera unavailable: " + e.getMessage());
        }
    }

    /** Remote SDP is asynchronous. ICE queued before or during it is usable only after success. */
    private void setRemote(SessionDescription description, Runnable ready) {
        final PeerConnection pc = mPc;
        final String call = mCallId;
        pc.setRemoteDescription(new Sdp("remote") {
            @Override public void onSetSuccess() {
                mExec.execute(() -> {
                    if (mPc != pc || !call.equals(mCallId)) { return; }
                    drainIce();
                    ready.run();
                });
            }
        }, description);
    }

    private void drainIce() {
        for (IceCandidate c : mPendingIce) {
            boolean added = mPc.addIceCandidate(c);
            EventLog.add("call queued ICE: " + iceKind(c) + " accepted=" + added);
        }
        mPendingIce.clear();
    }

    /** Log candidate transport/type only; never SDP credentials or local/public addresses. */
    private static String iceKind(IceCandidate candidate) {
        String[] parts = candidate.sdp.split("\\s+");
        String protocol = parts.length > 2 && "tcp".equalsIgnoreCase(parts[2]) ? "tcp" : "udp";
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("typ".equals(parts[i]) && java.util.Arrays.asList("host", "srflx", "prflx", "relay").contains(parts[i + 1])) {
                return protocol + "/" + parts[i + 1];
            }
        }
        return protocol + "/unknown";
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
        mCallId = "";   // ended: late/duplicate frames must no longer match
        // The lock-screen call notification must die with the call, whether or
        // not the call screen is open to dismiss it.
        IncomingCallScreen.dismiss(mCtx);
        if (zSignalBye) {
            signal("bye", "");
        }
        if (mPc != null) {
            try {
                mPc.close();
            } catch (Exception ignored) {
            }
            mPc = null;
        }
        if (mSource != null) {
            try {
                mSource.dispose();
            } catch (Exception ignored) {
            }
            mSource = null;
        }
        mTrack = null;
        if (mCapturer != null) {
            try {
                mCapturer.stopCapture();
            } catch (Exception ignored) {
            }
            try {
                mCapturer.dispose();
            } catch (Exception ignored) {
            }
            mCapturer = null;
        }
        if (mVideoSource != null) {
            try {
                mVideoSource.dispose();
            } catch (Exception ignored) {
            }
            mVideoSource = null;
        }
        if (mSurfaceHelper != null) {
            try {
                mSurfaceHelper.dispose();
            } catch (Exception ignored) {
            }
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
        EventLog.add("call " + zReason + (mPeerKey.isEmpty() ? ""
                : " (" + name(mPeerKey) + ")"));
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

    private void stopRinging() {
        // Android owns ringtone playback; cancellation stops sound and vibration together.
        IncomingCallScreen.dismiss(mCtx);
    }

    private void signal(String zKind, String zPayload) {
        signalTo(mPeerKey, mCallId, zKind, zPayload);
    }

    private void signalTo(final String zPeerKey, final String zCallId,
            final String zKind, final String zPayload) {
        final boolean video = mVideo;
        mSendExec.execute(() -> {
            try {
                ChatEngine chat = MaximaService.chat();
                Contact c = contact(zPeerKey);
                if (chat == null || c == null) {
                    return;
                }
                ChatMessage m = ChatMessage.call(zCallId, zKind, zPayload);
                if (video && "offer".equals(zKind)) {
                    m.memo = "video";   // the flat codec's spare field
                }
                chat.sendCallSignal(c, m);
            } catch (Exception e) {
                EventLog.add("call signal " + zKind + " failed: " + e.getMessage());
                if ("offer".equals(zKind) || "answer".equals(zKind)) {
                    // Back on the state thread - and only if this call is
                    // still the current one (a late failure from a hung-up
                    // call must not kill a new one).
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

    private Contact contact(String zKey) {
        com.eurobuddha.maxima.core.ChatPort port = MaximaService.port();
        return port == null ? null : port.contact(zKey);
    }

    private String name(String zKey) {
        Contact c = contact(zKey);
        return c == null || c.name == null || c.name.isEmpty() ? "contact" : c.name;
    }

    private void setState(State zState, String zReason) {
        mState = zState;
        EventLog.add("call state: " + zState + (zReason == null ? "" : " " + zReason));
        final Listener l = mListener;
        if (l != null) {
            mMain.post(() -> l.onCallState(zState, mPeerKey, zReason));
        }
    }

    /** Base SDP observer: logs failures, subclasses override the success arm. */
    private static class Sdp implements SdpObserver {
        private final String mTag;

        Sdp(String zTag) {
            mTag = zTag;
        }

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String zErr) {
            EventLog.add("call sdp " + mTag + ": " + zErr);
        }

        @Override
        public void onSetFailure(String zErr) {
            EventLog.add("call sdp " + mTag + ": " + zErr);
        }
    }
}
