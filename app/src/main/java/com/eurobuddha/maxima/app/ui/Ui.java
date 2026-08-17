package com.eurobuddha.maxima.app.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.maxima.app.R;

/**
 * The small pieces every screen is built from.
 *
 * Rows are built in code rather than written out in XML because the rule
 * "every figure on screen carries an explanation" is only enforceable if there
 * is exactly one way to make a row and it demands the explanation key.
 */
public final class Ui {

    private Ui() {
    }

    /**
     * A figure with a one-line description and a "?" that opens the long one.
     *
     * @param zKey the {@link Explain} key - not optional, by design
     */
    public static View stat(Activity zAct, ViewGroup zParent, String zLabel,
                            String zHint, String zValue, String zKey) {
        View v = LayoutInflater.from(zAct).inflate(R.layout.part_stat, zParent, false);
        ((TextView) v.findViewById(R.id.stat_label)).setText(zLabel);
        TextView hint = v.findViewById(R.id.stat_hint);
        hint.setText(zHint);
        hint.setVisibility(zHint == null || zHint.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) v.findViewById(R.id.stat_value)).setText(zValue);
        v.findViewById(R.id.stat_q).setOnClickListener(x -> Explain.show(zAct, zKey));
        zParent.addView(v);
        return v;
    }

    /** A stat whose value is a state word rather than a number. */
    public static View state(Activity zAct, ViewGroup zParent, String zLabel,
                             String zHint, String zValue, int zColour, String zKey) {
        View v = stat(zAct, zParent, zLabel, zHint, zValue, zKey);
        ((TextView) v.findViewById(R.id.stat_value)).setTextColor(zColour);
        return v;
    }

    /** A tappable setting that shows its current value on the right. */
    public static View toggle(Activity zAct, ViewGroup zParent, String zLabel,
                              String zHint, String zValue, String zKey,
                              Runnable zOnTap) {
        View v = stat(zAct, zParent, zLabel, zHint, zValue, zKey);
        v.setBackgroundResource(android.R.drawable.list_selector_background);
        v.setOnClickListener(x -> zOnTap.run());
        return v;
    }

    /** A line of monospace detail, e.g. one host. */
    public static TextView mono(Activity zAct, ViewGroup zParent, String zText, int zColour) {
        TextView t = new TextView(zAct);
        t.setText(zText);
        t.setTextSize(12f);
        t.setTextColor(zColour);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(zAct, 3);
        t.setLayoutParams(lp);
        zParent.addView(t);
        return t;
    }

    public static int dp(Activity zAct, int zDp) {
        return Math.round(zDp * zAct.getResources().getDisplayMetrics().density);
    }

    public static int dpc(Context zCtx, float zDp) {
        return Math.round(zDp * zCtx.getResources().getDisplayMetrics().density);
    }

    /** Same colour at a different opacity (0..255 in the top byte). */
    public static int alpha(int zArgb, int zAlpha) {
        return (zArgb & 0x00FFFFFF) | (zAlpha << 24);
    }

    /**
     * FreezePeach's signature accent glow: a centre-bright fading line with a
     * soft bloom beneath it, stacked under a header so the chrome has a subtle
     * edge of light instead of a flat hairline.
     */
    public static View glowStrip(Context zCtx) {
        int accent = zCtx.getColor(R.color.ux_accent);
        int glow = zCtx.getColor(R.color.ux_accent_glow);
        LinearLayout col = new LinearLayout(zCtx);
        col.setOrientation(LinearLayout.VERTICAL);
        View line = new View(zCtx);
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{alpha(accent, 0), accent, alpha(accent, 0)}));
        col.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpc(zCtx, 1.5f)));
        View bloom = new View(zCtx);
        bloom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{glow, alpha(glow, 0)}));
        col.addView(bloom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpc(zCtx, 8)));
        return col;
    }

    /**
     * Press physics: a subtle scale-down on touch that springs back with an
     * overshoot. Returns false so the view's own click handling is untouched.
     */
    public static void press(final View zView) {
        zView.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    public static int colour(Activity zAct, int zRes) {
        return zAct.getResources().getColor(zRes, zAct.getTheme());
    }

    /** The initial shown in a conversation avatar. */
    public static String initial(String zName) {
        String s = zName == null ? "" : zName.trim();
        if (s.isEmpty()) {
            return "?";
        }
        // A key-derived name starts "0x", whose initial would be a useless "0".
        if (s.length() > 2 && s.charAt(0) == '0' && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
            return s.substring(2, 3).toUpperCase();
        }
        return s.substring(0, 1).toUpperCase();
    }
}
