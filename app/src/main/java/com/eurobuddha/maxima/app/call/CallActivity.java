package com.eurobuddha.maxima.app.call;

import android.app.Activity;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.Names;
import com.eurobuddha.maxima.app.ui.Avatars;

import java.util.Locale;

/**
 * The call screen: one activity, three states (incoming / connecting / live),
 * driven entirely by {@link CallManager} callbacks. Programmatic UI in the
 * app's greyscale language - avatar, name, status, round action buttons.
 */
public final class CallActivity extends Activity implements CallManager.Listener {

    public static final String EXTRA_PEER = "peer";
    public static final String EXTRA_OUTGOING = "outgoing";
    public static final String EXTRA_VIDEO = "video";

    private org.webrtc.SurfaceViewRenderer mRemoteView;
    private org.webrtc.SurfaceViewRenderer mLocalView;
    private boolean mVideoUi;

    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_ANSWER_CALL_ID = "answer_call_id";
    private String mCallId;
    private String mPendingAnswer;
    private boolean mPendingOutgoing;
    private boolean mPermissionRequest;
    private boolean mListening;
    private String mPeer;
    private TextView mStatus;
    private LinearLayout mIncomingRow, mLiveRow;
    private TextView mMuteBtn, mSpeakerBtn;
    private final Handler mTick = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle zState) {
        super.onCreate(zState);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mPeer = getIntent().getStringExtra(EXTRA_PEER);
        if (mPeer == null || mPeer.isEmpty()) {
            finish();
            return;
        }
        String who = Names.contact(MaximaService.port(), mPeer);

        CallManager pre = CallManager.get(this);
        mCallId = getIntent().getStringExtra(EXTRA_CALL_ID);
        if (mCallId != null && !mCallId.equals(pre.callId())) {
            finish();
            return;
        }
        if (mCallId == null) mCallId = pre.callId();
        mPendingAnswer = getIntent().getStringExtra(EXTRA_ANSWER_CALL_ID);
        mVideoUi = getIntent().getBooleanExtra(EXTRA_VIDEO, false) || pre.isVideo();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(getColor(R.color.ux_bg));
        root.setPadding(dp(24), dp(80), dp(24), dp(48));

        ImageView avatar = new ImageView(this);
        avatar.setImageBitmap(Avatars.bitmap(mPeer, who, dp(120)));
        root.addView(avatar, new LinearLayout.LayoutParams(dp(120), dp(120)));

        TextView name = new TextView(this);
        name.setText(who);
        name.setTextColor(getColor(R.color.ux_text));
        name.setTextSize(26);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(18), 0, dp(6));
        root.addView(name);

        mStatus = new TextView(this);
        mStatus.setTextColor(getColor(R.color.ux_subtext));
        mStatus.setTextSize(16);
        mStatus.setGravity(Gravity.CENTER);
        root.addView(mStatus);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Incoming: Decline / Answer
        mIncomingRow = new LinearLayout(this);
        mIncomingRow.setOrientation(LinearLayout.HORIZONTAL);
        mIncomingRow.setGravity(Gravity.CENTER);
        mIncomingRow.addView(roundButton(Language.msg(R.string.l10n_call_decline), 0xFFE0524D, v -> {
            CallManager.get(this).decline(mCallId);
        }));
        mIncomingRow.addView(gap());
        mIncomingRow.addView(roundButton(Language.msg(R.string.l10n_call_answer), 0xFF3E9B63, v -> {
            mPendingAnswer = mCallId;
            runPendingCallAction();
        }));
        root.addView(mIncomingRow, new LinearLayout.LayoutParams(-1, -2));

        // Live: Mute / End / Speaker
        mLiveRow = new LinearLayout(this);
        mLiveRow.setOrientation(LinearLayout.HORIZONTAL);
        mLiveRow.setGravity(Gravity.CENTER);
        mMuteBtn = roundButton(Language.msg(R.string.l10n_native_call_mute), 0xFF3A3D42, v -> {
            CallManager cm = CallManager.get(this);
            cm.setMuted(!cm.muted());
            Language.setText(mMuteBtn, cm.muted() ? Language.msg(R.string.l10n_native_call_unmute) : Language.msg(R.string.l10n_native_call_mute));
            Language.description(mMuteBtn, Language.msg(cm.muted() ? R.string.l10n_call_unmute : R.string.l10n_call_mute));
        });
        Language.description(mMuteBtn, Language.msg(R.string.l10n_call_mute));
        mLiveRow.addView(mMuteBtn);
        mLiveRow.addView(gap());
        mLiveRow.addView(roundButton(Language.msg(R.string.l10n_native_call_end), 0xFFE0524D, v -> {
            CallManager.get(this).hangup();
        }));
        mLiveRow.addView(gap());
        mSpeakerBtn = roundButton(Language.msg(R.string.l10n_native_call_speaker), 0xFF3A3D42, v -> {
            CallManager cm = CallManager.get(this);
            cm.setSpeaker(!cm.speaker());
            Language.setText(mSpeakerBtn, cm.speaker() ? Language.msg(R.string.l10n_native_call_earpiece) : Language.msg(R.string.l10n_native_call_speaker));
        });
        mLiveRow.addView(mSpeakerBtn);
        root.addView(mLiveRow, new LinearLayout.LayoutParams(-1, -2));

        if (mVideoUi) {
            // Video: remote feed fills the screen, local preview floats top-right,
            // the same control rows overlay the bottom.
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.setBackgroundColor(0xFF101113);
            mRemoteView = new org.webrtc.SurfaceViewRenderer(this);
            mRemoteView.init(CallManager.get(this).eglContext(), null);
            frame.addView(mRemoteView, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            mLocalView = new org.webrtc.SurfaceViewRenderer(this);
            mLocalView.init(CallManager.get(this).eglContext(), null);
            mLocalView.setZOrderMediaOverlay(true);
            mLocalView.setMirror(true);
            android.widget.FrameLayout.LayoutParams pip =
                    new android.widget.FrameLayout.LayoutParams(dp(104), dp(140));
            pip.gravity = Gravity.TOP | Gravity.END;
            pip.topMargin = dp(48);
            pip.rightMargin = dp(16);
            frame.addView(mLocalView, pip);
            // The audio layout becomes a translucent overlay (avatar hidden by
            // the remote feed once frames arrive).
            root.setBackgroundColor(0x00000000);
            name.setTextColor(Color.WHITE);
            mStatus.setTextColor(Color.WHITE);
            name.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            mStatus.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            frame.addView(root, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            // Flip-camera joins the live row.
            mLiveRow.addView(gap());
            TextView flip = roundButton(Language.msg(R.string.l10n_native_call_flip), 0xFF3A3D42,
                    v -> CallManager.get(this).switchCamera());
            Language.description(flip, Language.msg(R.string.l10n_native_call_flip_help));
            mLiveRow.addView(flip);
            setContentView(frame);
            CallManager.get(this).attachVideoSinks(mLocalView, mRemoteView);
        } else {
            setContentView(root);
        }

        CallManager cm = CallManager.get(this);
        cm.setListener(this);
        mListening = true;
        mPendingOutgoing = getIntent().getBooleanExtra(EXTRA_OUTGOING, false)
                && cm.state() == CallManager.State.IDLE;
        renderState(mPendingOutgoing ? CallManager.State.OUTGOING_RINGING : cm.state(), null);
        runPendingCallAction();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String call = intent.getStringExtra(EXTRA_CALL_ID);
        if (call != null && !call.equals(CallManager.get(this).callId())) return;
        if (call == null || !call.equals(mCallId)) {
            setIntent(intent);
            recreate();
            return;
        }
        setIntent(intent);
        mPendingAnswer = intent.getStringExtra(EXTRA_ANSWER_CALL_ID);
        runPendingCallAction();
    }

    private void runPendingCallAction() {
        CallManager cm = CallManager.get(this);
        if (mPendingAnswer != null && (!mPendingAnswer.equals(cm.callId())
                || cm.state() != CallManager.State.INCOMING_RINGING)) {
            mPendingAnswer = null;
        }
        if (mPendingAnswer == null && !mPendingOutgoing) return;
        if (mPermissionRequest) return;
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (mVideoUi && checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.CAMERA);
        }
        if (!missing.isEmpty()) {
            mPermissionRequest = true;
            requestPermissions(missing.toArray(new String[0]), 1);
            return;
        }
        if (mPendingAnswer != null) {
            String call = mPendingAnswer;
            mPendingAnswer = null;
            getIntent().removeExtra(EXTRA_ANSWER_CALL_ID);
            cm.accept(call);
        } else if (mPendingOutgoing) {
            mPendingOutgoing = false;
            if (cm.state() == CallManager.State.IDLE) {
                cm.startCall(mPeer, mVideoUi);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1) return;
        mPermissionRequest = false;
        boolean granted = results.length > 0;
        for (int result : results) {
            granted &= result == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            runPendingCallAction();
        } else {
            mPendingAnswer = null;
            getIntent().removeExtra(EXTRA_ANSWER_CALL_ID);
            android.widget.Toast.makeText(this, Language.text(this,mVideoUi ? R.string.l10n_native_call_video_permission : R.string.l10n_native_call_mic_permission), android.widget.Toast.LENGTH_LONG).show();
            if (mPendingOutgoing) {
                mPendingOutgoing = false;
                finish();
            }
        }
    }

    @Override
    public void onCallState(CallManager.State zState, String zPeerKey, String zReason) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        renderState(zState, zReason);
    }

    private void renderState(CallManager.State zState, String zReason) {
        mIncomingRow.setVisibility(View.GONE);
        mLiveRow.setVisibility(View.GONE);
        mTick.removeCallbacksAndMessages(null);
        switch (zState) {
            case INCOMING_RINGING:
                Language.setText(mStatus, mVideoUi ? Language.msg(R.string.l10n_call_incoming_video)
                        : Language.msg(R.string.l10n_native_call_incoming));
                mIncomingRow.setVisibility(View.VISIBLE);
                break;
            case OUTGOING_RINGING:
                Language.setText(mStatus, Language.msg(R.string.l10n_call_calling));
                mLiveRow.setVisibility(View.VISIBLE);
                mMuteBtn.setVisibility(View.GONE);
                mSpeakerBtn.setVisibility(View.GONE);
                break;
            case CONNECTING:
                Language.setText(mStatus, Language.msg(R.string.l10n_call_connecting));
                mLiveRow.setVisibility(View.VISIBLE);
                mMuteBtn.setVisibility(View.GONE);
                mSpeakerBtn.setVisibility(View.GONE);
                break;
            case LIVE:
                mMuteBtn.setVisibility(View.VISIBLE);
                mSpeakerBtn.setVisibility(View.VISIBLE);
                mLiveRow.setVisibility(View.VISIBLE);
                tickTimer();
                break;
            case ENDED:
            case IDLE:
                IncomingCallScreen.dismiss(this);
                Language.setText(mStatus, zReason == null ? Language.msg(R.string.l10n_call_ended) : SystemMessages.message("Call " + zReason));
                mTick.postDelayed(this::finish, 1600);
                break;
            default:
                break;
        }
    }

    private void tickTimer() {
        int s = CallManager.get(this).liveSeconds();
        Language.setText(mStatus, String.format(Locale.UK, "%d:%02d", s / 60, s % 60));
        mTick.postDelayed(this::tickTimer, 1000);
    }

    private TextView roundButton(CharSequence zLabel, int zColor, View.OnClickListener zClick) {
        TextView b = new TextView(this);
        Language.setText(b, zLabel);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(zColor);
        bg.setCornerRadius(dp(44));
        b.setBackground(bg);
        b.setMinWidth(0);
        b.setMinHeight(dp(64));
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        b.setFocusable(true);
        b.setPadding(dp(4), dp(12), dp(4), dp(12));
        b.setOnClickListener(zClick);
        return b;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mTick.removeCallbacksAndMessages(null);
        // Always detach - a destroyed activity must never receive callbacks.
        // Re-opening the call screen re-registers; CallManager itself keeps
        // the call (and dismisses the notification on end) without a UI.
        CallManager cm = CallManager.get(this);
        if (!mListening) return;
        cm.setListener(null);
        if (mVideoUi) {
            cm.detachVideoSinks();
            if (mLocalView != null) {
                mLocalView.release();
            }
            if (mRemoteView != null) {
                mRemoteView.release();
            }
        }
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
    }

}
