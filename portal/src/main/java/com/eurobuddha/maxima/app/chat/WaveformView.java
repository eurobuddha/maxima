package com.eurobuddha.maxima.app.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * The voice-note waveform: rounded amplitude bars, drawn - no widgets, no
 * libraries, matching the app's drawn-UI language.
 *
 * Two modes:
 *  - LIVE (recording): {@link #append} rolls amplitudes in from the right.
 *  - STATIC (playback): {@link #setBars} shows the recorded shape and
 *    {@link #setProgress} sweeps the played fraction to full ink.
 */
public final class WaveformView extends View {

    public static final int BAR_COUNT = 32;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] mBars = new int[0];
    private float mProgress;          // 0..1 played fraction (static mode)
    private int mInk = 0xFF888888;
    private boolean mLive;
    private final int[] mLiveRing = new int[BAR_COUNT];

    public WaveformView(Context zCtx) {
        super(zCtx);
    }

    public WaveformView(Context zCtx, AttributeSet zAttrs) {
        super(zCtx, zAttrs);
    }

    public void setInk(int zColor) {
        mInk = zColor;
        invalidate();
    }

    /** Static playback shape: levels 1..15 per bar. */
    public void setBars(int[] zLevels) {
        mLive = false;
        mBars = zLevels == null ? new int[0] : zLevels;
        invalidate();
    }

    public void setProgress(float zFraction) {
        mProgress = Math.max(0f, Math.min(1f, zFraction));
        invalidate();
    }

    /** Live recording: push one amplitude (MediaRecorder.getMaxAmplitude). */
    public void append(int zAmplitude) {
        mLive = true;
        System.arraycopy(mLiveRing, 1, mLiveRing, 0, mLiveRing.length - 1);
        int level = 1 + Math.min(14, (int) (Math.sqrt(
                Math.min(32767, Math.max(0, zAmplitude)) / 32767.0) * 14));
        mLiveRing[mLiveRing.length - 1] = level;
        invalidate();
    }

    /** Turn recorded amplitudes into BAR_COUNT levels (1..15), max-pooled. */
    public static int[] summarise(java.util.List<Integer> zSamples) {
        int[] out = new int[BAR_COUNT];
        if (zSamples == null || zSamples.isEmpty()) {
            java.util.Arrays.fill(out, 4);
            return out;
        }
        int n = zSamples.size();
        int peak = 1;
        for (int v : zSamples) {
            peak = Math.max(peak, v);
        }
        for (int i = 0; i < BAR_COUNT; i++) {
            int a = i * n / BAR_COUNT, b = Math.max(a + 1, (i + 1) * n / BAR_COUNT);
            int m = 0;
            for (int j = a; j < b && j < n; j++) {
                m = Math.max(m, zSamples.get(j));
            }
            out[i] = 1 + Math.min(14, (int) (Math.sqrt(m / (double) peak) * 14));
        }
        return out;
    }

    /** Levels -> compact hex string for the wire (one nibble per bar). */
    public static String encode(int[] zLevels) {
        StringBuilder sb = new StringBuilder(zLevels.length);
        for (int v : zLevels) {
            sb.append(Character.forDigit(Math.max(0, Math.min(15, v)), 16));
        }
        return sb.toString();
    }

    /** Hex string -> levels; null if the string is not a waveform. */
    public static int[] decode(String zHex) {
        if (zHex == null || zHex.isEmpty() || zHex.length() > 64) {
            return null;
        }
        int[] out = new int[zHex.length()];
        for (int i = 0; i < zHex.length(); i++) {
            int v = Character.digit(zHex.charAt(i), 16);
            if (v < 0) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    @Override
    protected void onDraw(Canvas zCanvas) {
        int[] bars = mLive ? mLiveRing : mBars;
        int n = bars.length;
        if (n == 0) {
            return;
        }
        float w = getWidth(), h = getHeight();
        float slot = w / n;
        float bw = Math.max(2f, slot * 0.55f);
        float r = bw / 2f;
        int played = mLive ? n : Math.round(mProgress * n);
        for (int i = 0; i < n; i++) {
            float frac = Math.max(1, bars[i]) / 15f;
            float bh = Math.max(bw, h * frac);
            float cx = i * slot + slot / 2f;
            float top = (h - bh) / 2f;
            mPaint.setColor(mInk);
            mPaint.setAlpha(i < played ? 255 : 92);
            zCanvas.drawRoundRect(cx - bw / 2f, top, cx + bw / 2f, top + bh,
                    r, r, mPaint);
        }
    }
}
