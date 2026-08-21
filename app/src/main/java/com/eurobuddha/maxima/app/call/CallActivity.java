package com.eurobuddha.maxima.app.call;

import android.app.Activity;
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
        mIncomingRow.addView(roundButton("Decline", 0xFFE0524D, v -> {
            CallManager.get(this).decline();
        }));
        mIncomingRow.addView(gap());
        mIncomingRow.addView(roundButton("Answer", 0xFF3E9B63, v -> {
            IncomingCallScreen.dismiss(this);
            CallManager.get(this).accept();
        }));
        root.addView(mIncomingRow);

        // Live: Mute / End / Speaker
        mLiveRow = new LinearLayout(this);
        mLiveRow.setOrientation(LinearLayout.HORIZONTAL);
        mLiveRow.setGravity(Gravity.CENTER);
        mMuteBtn = roundButton("Mute", 0xFF3A3D42, v -> {
            CallManager cm = CallManager.get(this);
            cm.setMuted(!cm.muted());
            mMuteBtn.setText(cm.muted() ? "Unmute" : "Mute");
        });
        mLiveRow.addView(mMuteBtn);
        mLiveRow.addView(gap());
        mLiveRow.addView(roundButton("End", 0xFFE0524D, v -> {
            CallManager.get(this).hangup();
        }));
        mLiveRow.addView(gap());
        mSpeakerBtn = roundButton("Speaker", 0xFF3A3D42, v -> {
            CallManager cm = CallManager.get(this);
            cm.setSpeaker(!cm.speaker());
            mSpeakerBtn.setText(cm.speaker() ? "Earpiece" : "Speaker");
        });
        mLiveRow.addView(mSpeakerBtn);
        root.addView(mLiveRow);

        setContentView(root);

        CallManager cm = CallManager.get(this);
        cm.setListener(this);
        if (getIntent().getBooleanExtra(EXTRA_OUTGOING, false)
                && cm.state() == CallManager.State.IDLE) {
            cm.startCall(mPeer);
        }
        renderState(cm.state(), null);
    }

    @Override
    public void onCallState(CallManager.State zState, String zPeerKey, String zReason) {
        renderState(zState, zReason);
    }

    private void renderState(CallManager.State zState, String zReason) {
        mIncomingRow.setVisibility(View.GONE);
        mLiveRow.setVisibility(View.GONE);
        mTick.removeCallbacksAndMessages(null);
        switch (zState) {
            case INCOMING_RINGING:
                mStatus.setText("Incoming Parlons call");
                mIncomingRow.setVisibility(View.VISIBLE);
                break;
            case OUTGOING_RINGING:
                mStatus.setText("Calling…");
                mLiveRow.setVisibility(View.VISIBLE);
                mMuteBtn.setVisibility(View.GONE);
                mSpeakerBtn.setVisibility(View.GONE);
                break;
            case CONNECTING:
                mStatus.setText("Connecting…");
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
                mStatus.setText(zReason == null ? "Call ended" : "Call " + zReason);
                mTick.postDelayed(this::finish, 1600);
                break;
            default:
                break;
        }
    }

    private void tickTimer() {
        int s = CallManager.get(this).liveSeconds();
        mStatus.setText(String.format(Locale.UK, "%d:%02d", s / 60, s % 60));
        mTick.postDelayed(this::tickTimer, 1000);
    }

    private TextView roundButton(String zLabel, int zColor, View.OnClickListener zClick) {
        TextView b = new TextView(this);
        b.setText(zLabel);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(zColor);
        bg.setCornerRadius(dp(44));
        b.setBackground(bg);
        b.setMinWidth(dp(96));
        b.setPadding(dp(18), dp(16), dp(18), dp(16));
        b.setOnClickListener(zClick);
        return b;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(18), 1));
        return v;
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mTick.removeCallbacksAndMessages(null);
        CallManager cm = CallManager.get(this);
        if (cm.state() == CallManager.State.IDLE
                || cm.state() == CallManager.State.ENDED) {
            cm.setListener(null);
        }
    }
}
