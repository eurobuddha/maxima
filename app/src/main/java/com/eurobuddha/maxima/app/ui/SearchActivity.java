package com.eurobuddha.maxima.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.ChatActivity;
import com.eurobuddha.maxima.app.chat.Names;
import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;
import com.eurobuddha.maxima.core.contacts.Contact;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Message search: everything is already in memory (ChatEngine conversations),
 * so this is a debounced substring scan - contacts and group names first, then
 * message bodies / media captions / payment memos, newest hits first, capped.
 * Tapping a message opens the thread scrolled to that bubble.
 */
public final class SearchActivity extends Activity {

    private static final int MAX_HITS = 50;

    private EditText mQuery;
    private LinearLayout mResults;
    private final Handler mDebounce = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat mDate = new SimpleDateFormat("d MMM", Locale.UK);
    private volatile int mGeneration;

    @Override
    protected void onCreate(Bundle zState) {
        super.onCreate(zState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.ux_bg));

        // Top bar: back + query field + clear.
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(R.color.ux_header));
        bar.setPadding(dp(4), dp(8), dp(8), dp(8));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.ux_on_header));
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        mQuery = new EditText(this);
        mQuery.setHint("Search messages…");
        mQuery.setTextColor(getColor(R.color.ux_on_header));
        mQuery.setHintTextColor(0x99FFFFFF);
        mQuery.setBackground(null);
        mQuery.setSingleLine(true);
        mQuery.setTextSize(17);
        bar.addView(mQuery, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView clear = new ImageView(this);
        clear.setImageResource(R.drawable.ic_close);
        clear.setColorFilter(getColor(R.color.ux_on_header));
        clear.setPadding(dp(12), dp(12), dp(12), dp(12));
        clear.setOnClickListener(v -> mQuery.setText(""));
        bar.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));

        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        mResults = new LinearLayout(this);
        mResults.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mResults);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // Edge-to-edge (targetSdk 35): the header extends up under the status
        // bar, the results clear the nav bar / keyboard - same treatment as
        // the main shell, or the page renders flush under the system bars.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.ime());
            bar.setPadding(dp(4), dp(8) + sys.top, dp(8), dp(8));
            mResults.setPadding(0, 0, 0, sys.bottom + dp(8));
            return insets;
        });

        mQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable e) {
                mDebounce.removeCallbacksAndMessages(null);
                mDebounce.postDelayed(() -> runSearch(e.toString().trim()), 250);
            }

            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });

        mQuery.requestFocus();
        mQuery.postDelayed(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.showSoftInput(mQuery, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
        runSearch("");
    }

    // ------------------------------------------------------------------

    private static final class Hit {
        final String conversation;
        final String title;
        final CharSequence snippet;   // null = contact row
        final long time;
        final String entryId;

        Hit(String zConv, String zTitle, CharSequence zSnippet, long zTime, String zEntryId) {
            conversation = zConv;
            title = zTitle;
            snippet = zSnippet;
            time = zTime;
            entryId = zEntryId;
        }
    }

    private void runSearch(final String zQuery) {
        final int gen = ++mGeneration;
        new Thread(() -> {
            List<Hit> names = new ArrayList<>();
            List<Hit> messages = new ArrayList<>();
            ChatEngine chat = MaximaService.chat();
            ChatPort port = MaximaService.port();
            if (chat != null && port != null) {
                String q = zQuery.toLowerCase(Locale.ROOT);
                if (q.isEmpty()) {
                    // Empty query: recent conversations as a starting point.
                    for (String conv : chat.conversations()) {
                        names.add(new Hit(conv, Names.of(port, chat, conv),
                                null, 0, null));
                        if (names.size() >= 20) {
                            break;
                        }
                    }
                } else {
                    for (Contact c : port.contacts()) {
                        if (c.name != null
                                && c.name.toLowerCase(Locale.ROOT).contains(q)) {
                            names.add(new Hit(
                                    com.eurobuddha.maxima.core.identity.Keys
                                            .norm(c.publicKey),
                                    c.name, null, 0, null));
                        }
                    }
                    outer:
                    for (String conv : chat.conversations()) {
                        String title = Names.of(port, chat, conv);
                        if (title.toLowerCase(Locale.ROOT).contains(q)
                                && chat.group(conv) != null) {
                            names.add(new Hit(conv, title, null, 0, null));
                        }
                        for (ChatEngine.Entry e : chat.conversation(conv)) {
                            String text = searchable(e.body);
                            int at = text.toLowerCase(Locale.ROOT).indexOf(q);
                            if (at < 0) {
                                continue;
                            }
                            messages.add(new Hit(conv, title,
                                    snippet(text, at, q.length()), e.time, e.id));
                            if (messages.size() >= MAX_HITS) {
                                break outer;
                            }
                        }
                    }
                    messages.sort((a, b) -> Long.compare(b.time, a.time));
                }
            }
            final List<Hit> n = names, m = messages;
            runOnUiThread(() -> {
                if (gen == mGeneration) {
                    render(zQuery, n, m);
                }
            });
        }, "search").start();
    }

    /** What a human would consider the message's text - never wire artefacts. */
    private static String searchable(String zBody) {
        if (ChatPay.isPayment(zBody)) {
            return ChatPay.preview(zBody);
        }
        if (ChatMedia.isMedia(zBody)) {
            String cap = ChatMedia.caption(zBody);
            // voice notes carry "duration|waveformhex" - hex is not content
            int bar = cap.indexOf('|');
            return bar >= 0 ? cap.substring(0, bar) : cap;
        }
        return zBody;
    }

    private CharSequence snippet(String zText, int zAt, int zLen) {
        int from = Math.max(0, zAt - 28);
        int to = Math.min(zText.length(), zAt + zLen + 60);
        String pre = (from > 0 ? "…" : "") + zText.substring(from, zAt);
        String match = zText.substring(zAt, zAt + zLen);
        String post = zText.substring(zAt + zLen, to) + (to < zText.length() ? "…" : "");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(pre);
        int s = sb.length();
        sb.append(match);
        sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(post);
        return sb;
    }

    private void render(String zQuery, List<Hit> zNames, List<Hit> zMessages) {
        mResults.removeAllViews();
        if (!zNames.isEmpty()) {
            mResults.addView(sectionLabel(zQuery.isEmpty() ? "Chats" : "Contacts & groups"));
            for (Hit h : zNames) {
                mResults.addView(row(h));
            }
        }
        if (!zMessages.isEmpty()) {
            mResults.addView(sectionLabel("Messages"));
            for (Hit h : zMessages) {
                mResults.addView(row(h));
            }
        }
        if (zNames.isEmpty() && zMessages.isEmpty() && !zQuery.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No matches");
            none.setTextColor(getColor(R.color.ux_subtext));
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(40), 0, 0);
            mResults.addView(none);
        }
    }

    private TextView sectionLabel(String zText) {
        TextView t = new TextView(this);
        t.setText(zText.toUpperCase(Locale.UK));
        t.setTextSize(11);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(getColor(R.color.ux_subtext));
        t.setPadding(dp(16), dp(16), dp(16), dp(6));
        return t;
    }

    private View row(final Hit zHit) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        r.setBackgroundResource(android.R.drawable.list_selector_background);

        ImageView av = new ImageView(this);
        av.setImageBitmap(Avatars.bitmap(zHit.conversation, zHit.title, dp(42)));
        r.addView(av, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText(zHit.title);
        title.setTextColor(getColor(R.color.ux_text));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (zHit.time > 0) {
            TextView when = new TextView(this);
            when.setText(mDate.format(new Date(zHit.time)));
            when.setTextColor(getColor(R.color.ux_subtext));
            when.setTextSize(12);
            head.addView(when);
        }
        col.addView(head);

        if (zHit.snippet != null) {
            TextView sn = new TextView(this);
            sn.setText(zHit.snippet);
            sn.setTextColor(getColor(R.color.ux_subtext));
            sn.setTextSize(14);
            sn.setMaxLines(2);
            col.addView(sn);
        }
        r.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        r.setOnClickListener(v -> {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra(ChatActivity.EXTRA_CONVERSATION, zHit.conversation);
            if (zHit.entryId != null) {
                i.putExtra(ChatActivity.EXTRA_SCROLL_TO, zHit.entryId);
            }
            startActivity(i);
        });
        return r;
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }
}
