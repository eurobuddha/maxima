package com.eurobuddha.maxima.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The shared greyscale widget vocabulary for the Network and Settings tabs —
 * the same cards, metric rows, status pills, real switches, copy fields and
 * bottom sheets established by the Wallet and Contacts redesigns, in one place
 * so both pages stay identical. Instance-based (holds the Activity) to keep the
 * call sites terse.
 */
public final class Kit {

    public interface OnToggle {
        void onToggle(boolean on);
    }

    /** Status-pill kinds. */
    public static final int OK = 0;
    public static final int WARN = 1;
    public static final int BAD = 2;
    public static final int NEUTRAL = 3;

    private final MainActivity a;

    public Kit(MainActivity zAct) {
        a = zAct;
    }

    // ---- containers ----

    public LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = dp(15);
        c.setPadding(p, dp(6), p, dp(6));
        return c;
    }

    public LinearLayout cardTight() {
        LinearLayout c = card();
        int p = dp(15);
        c.setPadding(p, dp(12), p, dp(12));
        return c;
    }

    public TextView sectionLabel(String t) {
        TextView v = new TextView(a);
        v.setText(t.toUpperCase());
        v.setTextSize(10);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(0.15f);
        v.setTextColor(col(R.color.ux_subtext));
        v.setPadding(dp(4), dp(8), dp(4), dp(9));
        return v;
    }

    // ---- rows ----

    /** A metric row: label (+ optional hint) left, bold value right. Tapping the
     *  optional "?" is not built here — pass an onTap for a whole-row action. */
    public LinearLayout kv(String key, String hint, String value, int valueColorOr0) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(leftCol(key, hint), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(a);
        v.setText(value);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.END);
        v.setTextColor(valueColorOr0 == 0 ? col(R.color.ux_text) : valueColorOr0);
        row.addView(v);
        return row;
    }

    /** A metric row with a status pill on the right. */
    public LinearLayout kvPill(String key, String hint, String pillText, int kind) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(leftCol(key, hint), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(statusPill(pillText, kind));
        return row;
    }

    /** A real greyscale switch row. */
    public LinearLayout switchRow(String key, String hint, boolean on, OnToggle cb) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        row.addView(leftCol(key, hint), new LinearLayout.LayoutParams(0, -2, 1f));

        SwitchCompat sw = new SwitchCompat(a);
        sw.setChecked(on);
        int accent = col(R.color.ux_accent);
        int track = col(R.color.ux_card2);
        int thumbOn = col(R.color.ux_on_accent);
        int thumbOff = col(R.color.ux_bg);
        sw.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{thumbOn, thumbOff}));
        sw.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accent, track}));
        sw.setOnClickListener(v -> cb.onToggle(sw.isChecked()));
        row.addView(sw);
        return row;
    }

    private LinearLayout leftCol(String key, String hint) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView k = new TextView(a);
        k.setText(key);
        k.setTextSize(14);
        k.setTextColor(col(R.color.ux_text));
        col.addView(k);
        if (hint != null && !hint.isEmpty()) {
            TextView h = new TextView(a);
            h.setText(hint);
            h.setTextSize(11.5f);
            h.setTextColor(col(R.color.ux_subtext));
            h.setPadding(0, dp(1), 0, 0);
            col.addView(h);
        }
        return col;
    }

    public TextView statusPill(String text, int kind) {
        int c;
        switch (kind) {
            case OK: c = col(R.color.ux_success); break;
            case WARN: c = col(R.color.ux_pending); break;
            case BAD: c = col(R.color.ux_error); break;
            default: c = col(R.color.ux_subtext); break;
        }
        TextView p = new TextView(a);
        p.setText(text.toUpperCase());
        p.setTextSize(10);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setLetterSpacing(0.06f);
        p.setTextColor(c);
        p.setPadding(dp(11), dp(5), dp(11), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor((c & 0x00FFFFFF) | (0x26 << 24));   // ~15% translucent tint
        p.setBackground(bg);
        return p;
    }

    // ---- host / mono line ----

    public LinearLayout hostLine(String host, boolean connected) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        View dot = new View(a);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(connected ? col(R.color.ux_success) : col(R.color.ux_subtext));
        if (!connected) {
            d.setAlpha(120);
        }
        dot.setBackground(d);
        LinearLayout.LayoutParams dp0 = new LinearLayout.LayoutParams(dp(8), dp(8));
        dp0.rightMargin = dp(10);
        row.addView(dot, dp0);
        TextView h = new TextView(a);
        h.setText(host);
        h.setTypeface(Typeface.MONOSPACE);
        h.setTextSize(12);
        h.setTextColor(col(connected ? R.color.ux_text : R.color.ux_subtext));
        row.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView st = new TextView(a);
        st.setText(connected ? "CONNECTED" : "IDLE");
        st.setTextSize(9);
        st.setTypeface(Typeface.DEFAULT_BOLD);
        st.setLetterSpacing(0.06f);
        st.setTextColor(col(connected ? R.color.ux_success : R.color.ux_subtext));
        row.addView(st);
        return row;
    }

    // ---- copy field ----

    public LinearLayout copyField(String labelText, String value, String toastMsg) {
        return copyField(labelText, value, toastMsg, true);
    }

    /** As {@link #copyField}, but {@code upperLabel=false} keeps the label verbatim
     *  (e.g. "maxima publickey", a term of art from classic Minima that must not
     *  be upper-cased). */
    public LinearLayout copyField(String labelText, String value, String toastMsg,
                                  boolean upperLabel) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.field_pill);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(10);
        box.setLayoutParams(bp);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(a);
        l.setText(upperLabel ? labelText.toUpperCase() : labelText);
        l.setTextSize(10);
        l.setLetterSpacing(upperLabel ? 0.12f : 0.04f);
        l.setTextColor(col(R.color.ux_subtext));
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView cp = new ImageView(a);
        cp.setImageResource(R.drawable.ic_copy);
        cp.setColorFilter(col(R.color.ux_subtext));
        head.addView(cp, new LinearLayout.LayoutParams(dp(15), dp(15)));
        box.addView(head);

        TextView v = new TextView(a);
        v.setText(value);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(12);
        v.setPadding(0, dp(7), 0, 0);
        v.setTextColor(col(R.color.ux_text));
        v.setTextIsSelectable(true);
        box.addView(v);

        box.setOnClickListener(view -> {
            ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("maxima", value));
            a.toast(toastMsg);
        });
        return box;
    }

    // ---- buttons ----

    public TextView primaryButton(String text) {
        return button(text, R.drawable.btn_primary, R.color.ux_on_accent);
    }

    public TextView ghostButton(String text) {
        return button(text, R.drawable.btn_ghost, R.color.ux_text);
    }

    public TextView dangerButton(String text) {
        TextView b = button(text, R.drawable.btn_ghost, R.color.ux_error);
        return b;
    }

    private TextView button(String text, int bg, int textColor) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setTypeface(manrope(Typeface.BOLD));
        b.setBackgroundResource(bg);
        b.setTextColor(col(textColor));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public EditText field(String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setBackgroundResource(R.drawable.field_pill);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setTextColor(col(R.color.ux_text));
        e.setHintTextColor(col(R.color.ux_subtext));
        e.setTextSize(13);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    // ---- sheet ----

    public BottomSheetDialog sheet(String titleText, View content) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.sheet_bg);
        root.setPadding(dp(20), dp(8), dp(20), dp(24));

        View grab = new View(a);
        grab.setBackgroundResource(R.drawable.grab_handle);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(10);
        root.addView(grab, gp);

        TextView t = new TextView(a);
        t.setText(titleText);
        t.setTextSize(17);
        t.setTypeface(manrope(Typeface.BOLD));
        t.setTextColor(col(R.color.ux_text));
        t.setPadding(0, 0, 0, dp(14));
        root.addView(t);

        ScrollView sv = new ScrollView(a);
        sv.addView(content);
        root.addView(sv);

        BottomSheetDialog d = new BottomSheetDialog(a);
        d.setContentView(root);
        View bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) {
            bs.setBackgroundColor(Color.TRANSPARENT);
        }
        // Keyboard: a sheet with a field must RISE above it, not sit under it (the
        // "add a contact" / send / wallet-node sheets all suffered). ADJUST_RESIZE on the
        // dialog window shrinks the sheet to the space above the keyboard; EXPANDED keeps
        // the whole sheet (field AND its button) in that space instead of a half peek.
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        d.getBehavior().setSkipCollapsed(true);
        d.show();
        return d;
    }

    // ---- misc ----

    public View divider() {
        View d = new View(a);
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        d.setBackgroundColor(col(R.color.ux_divider));
        return d;
    }

    public TextView sub(String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextSize(12);
        v.setTextColor(col(R.color.ux_subtext));
        return v;
    }

    public LinearLayout.LayoutParams mb(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px;
        return lp;
    }

    public android.graphics.drawable.Drawable ripple() {
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return androidx.core.content.ContextCompat.getDrawable(a, tv.resourceId);
    }

    public Typeface manrope(int style) {
        try {
            Typeface base = androidx.core.content.res.ResourcesCompat.getFont(a, R.font.manrope);
            return Typeface.create(base, style);
        } catch (Exception e) {
            return Typeface.defaultFromStyle(style);
        }
    }

    public int col(int res) {
        return a.getResources().getColor(res, a.getTheme());
    }

    public int dp(float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}
