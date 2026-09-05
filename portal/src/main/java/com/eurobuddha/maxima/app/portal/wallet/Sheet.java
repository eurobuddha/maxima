package com.eurobuddha.maxima.app.portal.wallet;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The app's modal surface.
 *
 * Stock {@code AlertDialog} gives a system-coloured panel and a row of flat text buttons that
 * ignore the Design tokens entirely — so a themed app ended up with untouched grey "RECEIVE CLOSE"
 * chrome hanging off the bottom of every sheet. This replaces it: a rounded panel painted from
 * Design, a grab handle, a real title, a scrollable body, and actions that look like actions —
 * a filled primary, outlined secondaries, and a distinct danger treatment.
 *
 * Usage mirrors the builder it replaces:
 * <pre>
 *   Sheet.create(act, "Confirm transaction")
 *        .body(myView)
 *        .action("Back", Sheet.Style.SECONDARY, null)
 *        .action("Sign &amp; Post", Sheet.Style.PRIMARY, () -&gt; post())
 *        .show();
 * </pre>
 */
public final class Sheet {

    public enum Style { PRIMARY, SECONDARY, DANGER }

    public interface OnTap { void run(); }

    private final Context ctx;
    private final Dialog dialog;
    private final LinearLayout panel;
    private final LinearLayout bodyBox;
    private final LinearLayout actionRow;
    private final TextView subtitleView;

    private Sheet(Context ctx, String title) {
        this.ctx = ctx;
        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(ctx);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Design.surface());
        bg.setCornerRadius(dp(Math.max(14, (int) Design.radiusDp() * 2)));
        bg.setStroke(Math.max(1, dp(1)), Design.border());
        panel.setBackground(bg);
        panel.setPadding(0, dp(10), 0, dp(12));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panel.setLayoutParams(plp);
        root.addView(panel);

        // grab handle — signals "this is a sheet you can dismiss", and centres the eye
        View handle = new View(ctx);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Design.border2());
        hb.setCornerRadius(dp(2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(10);
        handle.setLayoutParams(hlp);
        panel.addView(handle);

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextColor(Design.heading());
            t.setTextSize(17f);
            t.setTypeface(Design.typefaceBold());
            t.setPadding(dp(20), 0, dp(20), dp(2));
            panel.addView(t);
        }

        subtitleView = new TextView(ctx);
        subtitleView.setTextColor(Design.dim());
        subtitleView.setTextSize(12.5f);
        subtitleView.setPadding(dp(20), 0, dp(20), 0);
        subtitleView.setVisibility(View.GONE);
        panel.addView(subtitleView);

        ScrollView sv = new ScrollView(ctx);
        // Cap the body so a long coin list can't push the actions off-screen.
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svlp.weight = 1f;
        sv.setLayoutParams(svlp);
        bodyBox = new LinearLayout(ctx);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        bodyBox.setPadding(dp(20), dp(8), dp(20), dp(4));
        sv.addView(bodyBox);
        panel.addView(sv);

        actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(dp(16), dp(10), dp(16), 0);
        panel.addView(actionRow);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    public static Sheet create(Context ctx, String title) { return new Sheet(ctx, title); }

    /** A line of explanatory text under the title, before the body. */
    public Sheet subtitle(String s) {
        if (s != null && !s.isEmpty()) {
            subtitleView.setText(s);
            subtitleView.setVisibility(View.VISIBLE);
            subtitleView.setPadding(dp(20), dp(2), dp(20), dp(6));
        }
        return this;
    }

    /** The container to add content to. */
    public LinearLayout body() { return bodyBox; }

    public Sheet body(View v) {
        if (v != null) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            bodyBox.addView(v);
        }
        return this;
    }

    /** Add an action. A null tap just dismisses — the common "Close"/"Back" case. */
    public Sheet action(String label, Style style, final OnTap tap) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(13.5f);
        b.setTypeface(Design.typefaceBold());
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.setAllCaps(false);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(Math.max(10, (int) Design.radiusDp() * 2)));
        int rippleOn;
        switch (style) {
            case PRIMARY:
                shape.setColor(Design.accent());
                b.setTextColor(Design.onAccent());
                rippleOn = Design.onAccent();
                break;
            case DANGER:
                shape.setColor(Design.redSoft());
                shape.setStroke(Math.max(1, dp(1)), Design.red());
                b.setTextColor(Design.red());
                rippleOn = Design.red();
                break;
            default:
                shape.setColor(Design.surface2());
                shape.setStroke(Math.max(1, dp(1)), Design.border2());
                b.setTextColor(Design.text());
                rippleOn = Design.accent();
        }
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(withAlpha(rippleOn, 0x40)), shape, null));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, style == Style.PRIMARY ? 1.5f : 1f);
        if (actionRow.getChildCount() > 0) lp.leftMargin = dp(8);
        b.setLayoutParams(lp);

        b.setOnClickListener(v -> {
            dismiss();
            if (tap != null) tap.run();
        });
        actionRow.addView(b);
        return this;
    }

    public Dialog show() {
        if (actionRow.getChildCount() == 0) action("Close", Style.SECONDARY, null);
        try { dialog.show(); } catch (Exception ignored) {}
        return dialog;
    }

    public void dismiss() {
        try { dialog.dismiss(); } catch (Exception ignored) {}
    }

    public Dialog dialog() { return dialog; }

    public Sheet cancelable(boolean c) {
        dialog.setCancelable(c);
        dialog.setCanceledOnTouchOutside(c);
        return this;
    }

    /**
     * A status modal whose message changes as work proceeds — used by the long transfer/bury
     * flows, which start uncancellable and become dismissable once the outcome is known.
     */
    public static final class Progress {
        private final Sheet sheet;
        private final TextView text;

        private Progress(Sheet sheet, TextView text) { this.sheet = sheet; this.text = text; }

        public void text(String msg) { if (msg != null) text.setText(msg); }

        /** Let the user close it, and offer the button to do so. */
        public void finish() {
            sheet.cancelable(true);
            if (sheet.actionRow.getChildCount() == 0) sheet.action("Close", Style.SECONDARY, null);
        }

        public void dismiss() { sheet.dismiss(); }
    }

    public static Progress progress(Context ctx, String title, String message) {
        Sheet s = create(ctx, title);
        TextView t = new TextView(ctx);
        t.setText(message == null ? "" : message);
        t.setTextColor(Design.text());
        t.setTextSize(13f);
        s.body().addView(t);
        s.cancelable(false);
        try { s.dialog.show(); } catch (Exception ignored) {}
        return new Progress(s, t);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
