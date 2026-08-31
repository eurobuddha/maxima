package com.eurobuddha.maxima.app.portal;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Avatars;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Search across the account's contacts, group names and message bodies — the portal port of the
 * app's SearchActivity, running against parlons.chat.search (server-side). 250ms debounced,
 * generation-guarded so stale results never render; tap a result to open its conversation.
 */
public final class CloudSearchActivity extends AppCompatActivity {

    private EditText mField;
    private LinearLayout mResults;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile int mGen;
    private final SimpleDateFormat mDay = new SimpleDateFormat("d MMM yyyy", Locale.UK);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.ux_bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(R.color.ux_header));
        int pad = PortalUi.dp(this, 12);
        bar.setPadding(pad, pad, pad, pad);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(getColor(R.color.ux_on_header));
        back.setPadding(0, 0, PortalUi.dp(this, 14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        mField = new EditText(this);
        mField.setHint("Search messages…");
        mField.setSingleLine(true);
        mField.setTextColor(getColor(R.color.ux_on_header));
        mField.setHintTextColor(getColor(R.color.ux_subtext));
        bar.addView(mField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        mResults = new LinearLayout(this);
        mResults.setOrientation(LinearLayout.VERTICAL);
        mResults.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(mResults);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        final int barTop = bar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, 0, sb.right, sb.bottom);
            bar.setPadding(bar.getPaddingLeft(), barTop + sb.top,
                    bar.getPaddingRight(), bar.getPaddingBottom());
            return WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(getColor(R.color.ux_header));

        mField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b1, int c) { }
            public void onTextChanged(CharSequence s, int a, int b1, int c) { }
            public void afterTextChanged(Editable s) {
                final int gen = ++mGen;
                mMain.removeCallbacksAndMessages(null);
                mMain.postDelayed(() -> search(s.toString().trim(), gen), 250);
            }
        });
        mField.requestFocus();
        mMain.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) {
                im.showSoftInput(mField, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    private void search(String q, int gen) {
        if (q.isEmpty()) {
            mResults.removeAllViews();
            return;
        }
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                JSONObject res = null;
                try {
                    res = r.search(q);
                } catch (Exception ignored) {
                }
                final JSONObject fr = res;
                runOnUiThread(() -> {
                    if (gen != mGen || isFinishing() || isDestroyed()) {
                        return;   // a newer query superseded this one
                    }
                    render(q, fr);
                });
            }
            public void err(String m) {
            }
        });
    }

    private void render(String q, JSONObject res) {
        mResults.removeAllViews();
        if (res == null) {
            return;
        }
        JSONArray convs = (JSONArray) res.get("conversations");
        JSONArray msgs = (JSONArray) res.get("messages");
        if ((convs == null || convs.isEmpty()) && (msgs == null || msgs.isEmpty())) {
            mResults.addView(PortalUi.label(this, "No matches."));
            return;
        }
        if (convs != null && !convs.isEmpty()) {
            mResults.addView(PortalUi.section(this, "Contacts & groups"));
            for (Object o : convs) {
                JSONObject c = (JSONObject) o;
                addRow(str(c, "peer"), str(c, "name"), bool(c, "group"),
                        str(c, "name"), 0, q);
            }
        }
        if (msgs != null && !msgs.isEmpty()) {
            mResults.addView(PortalUi.section(this, "Messages"));
            for (Object o : msgs) {
                JSONObject m = (JSONObject) o;
                addRow(str(m, "peer"), str(m, "name"), bool(m, "group"),
                        str(m, "body"), lng(m, "time"), q);
            }
        }
    }

    private void addRow(String peer, String name, boolean group, String snippet, long time,
                        String q) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = PortalUi.dp(this, 8);
        row.setPadding(0, pv, 0, pv);
        row.setClickable(true);
        TextView av = new TextView(this);
        int sz = PortalUi.dp(this, 38);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(sz, sz);
        alp.rightMargin = PortalUi.dp(this, 12);
        av.setLayoutParams(alp);
        Avatars.apply(av, peer, name);
        row.addView(av);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name + (time > 0 ? "   " + mDay.format(new Date(time)) : ""));
        t.setTextColor(getColor(R.color.ux_text));
        t.setTextSize(15);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(highlight(snippet, q));
        s.setTextColor(getColor(R.color.ux_subtext));
        s.setTextSize(13);
        s.setMaxLines(1);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            Intent i = new Intent(this, CloudChatActivity.class);
            i.putExtra(CloudChatActivity.EXTRA_PEER, peer);
            i.putExtra(CloudChatActivity.EXTRA_NAME, name);
            i.putExtra(CloudChatActivity.EXTRA_GROUP, group);
            startActivity(i);
        });
        mResults.addView(row);
    }

    private CharSequence highlight(String text, String q) {
        String low = text.toLowerCase();
        int at = low.indexOf(q.toLowerCase());
        if (at < 0) {
            return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        }
        int start = Math.max(0, at - 20);
        int end = Math.min(text.length(), at + q.length() + 40);
        String pre = (start > 0 ? "…" : "") + text.substring(start, at);
        String hit = text.substring(at, at + q.length());
        String post = text.substring(at + q.length(), end) + (end < text.length() ? "…" : "");
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(pre);
        int hs = sb.length();
        sb.append(hit);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                hs, sb.length(), 0);
        sb.append(post);
        return sb;
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static boolean bool(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Boolean && (Boolean) v;
    }
}
