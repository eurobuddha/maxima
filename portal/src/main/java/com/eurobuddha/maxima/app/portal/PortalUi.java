package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Ui;

/**
 * Small view-builders for the portal's code-built tabs (Contacts / Wallet / Node), so they share
 * the exact Parlons card / label / value / button look without each re-deriving spacing + tokens.
 * Values are selectable — RULE 1: identifiers must be copyable in full, never truncated.
 */
final class PortalUi {

    private PortalUi() {
    }

    static int dp(Context c, float v) {
        return Ui.dpc(c, v);
    }

    /** A Parlons card: vertical, rounded surface, standard padding + bottom margin. */
    static LinearLayout card(Context c) {
        LinearLayout w = new LinearLayout(c);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setBackgroundResource(R.drawable.card);
        int p = dp(c, 16);
        w.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        w.setLayoutParams(lp);
        return w;
    }

    /** Uppercase section eyebrow above a card group. */
    static TextView section(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase());
        t.setTextColor(c.getColor(R.color.ux_subtext));
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(dp(c, 2), dp(c, 6), 0, dp(c, 6));
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(c.getColor(R.color.ux_text));
        t.setTextSize(17);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(c.getColor(R.color.ux_subtext));
        t.setTextSize(13);
        return t;
    }

    static TextView value(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(c.getColor(R.color.ux_text));
        t.setTextSize(15);
        t.setTextIsSelectable(true);   // RULE 1: full value, copyable
        return t;
    }

    /** A label→value row (label left in subtext, value right, tabular). */
    static View kv(Context c, String k, String v) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = dp(c, 6);
        row.setPadding(0, pv, 0, pv);
        TextView kl = label(c, k);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(kl, klp);
        TextView vv = new TextView(c);
        vv.setText(v);
        vv.setTextColor(c.getColor(R.color.ux_text));
        vv.setTextSize(15);
        vv.setGravity(Gravity.END);
        row.addView(vv);
        return row;
    }

    /** A filled accent action button. */
    static TextView button(Context c, String s) {
        TextView b = new TextView(c);
        b.setText(s);
        b.setTextColor(c.getColor(R.color.ux_on_accent));
        b.setTextSize(15);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.pill);
        b.getBackground().mutate().setTint(c.getColor(R.color.ux_accent));
        int p = dp(c, 13);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** A subtle bordered (outline) button. */
    static TextView ghost(Context c, String s) {
        TextView b = new TextView(c);
        b.setText(s);
        b.setTextColor(c.getColor(R.color.ux_accent));
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.card_inner);
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static View gap(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, dp)));
        return v;
    }

    static LinearLayout.LayoutParams matchWrap(Context c) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
