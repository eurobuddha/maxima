package com.eurobuddha.maxima.app.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eurobuddha.maxima.cloud.ParlonsRemote;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.server.RelayRuntime;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Parlons Cloud — the phone's extra power. Log into your always-on cloud account (your identity +
 * chats live on your VPS, not just this phone) and drive it. This is the same Parlons, reachable
 * from every device: this phone is a paired CLIENT of the one account, not a second copy of it.
 *
 * First cut: connect (account MAX#) + pair (bootstrap code) + account status + your chats. It rides
 * {@link ParlonsRemote} (the proven thin-client SDK); a device identity distinct from any local
 * account lives under {@code filesDir/cloud}. Conversation view + send + full-tab parity follow.
 */
public final class CloudActivity extends AppCompatActivity {

    private static final int BG      = Color.parseColor("#F5F4F1");
    private static final int CARD    = Color.parseColor("#FFFFFF");
    private static final int HEADER  = Color.parseColor("#2A2E33");
    private static final int TEXT    = Color.parseColor("#23262B");
    private static final int SUBTEXT = Color.parseColor("#797C82");
    private static final int ACCENT  = Color.parseColor("#2A2E33");
    private static final int ON_ACC  = Color.parseColor("#FFFFFFFF");

    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private SharedPreferences mPrefs;
    private MaximaIdentity mDeviceId;
    private volatile ParlonsRemote mRemote;
    private LinearLayout mRoot;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Parlons Cloud");
        mPrefs = getSharedPreferences("parlons_cloud", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        mRoot = column(this);
        int pad = dp(20);
        mRoot.setPadding(pad, pad, pad, pad);
        sv.addView(mRoot);
        setContentView(sv);

        mIo.execute(() -> {
            try {
                RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(
                        new File(getFilesDir(), "cloud").toPath());
                mDeviceId = MaximaIdentity.fromPhrase(seed.phrase);
            } catch (Exception e) {
                mDeviceId = null;
            }
            runOnUiThread(this::render);
        });
    }

    private void render() {
        mRoot.removeAllViews();
        String account = mPrefs.getString("account", "");
        boolean paired = mPrefs.getBoolean("paired", false);
        if (account.isEmpty() || !paired) {
            renderConnect(account);
        } else {
            renderStatus(account);
        }
    }

    // ---- connect + pair ----

    private void renderConnect(String prefill) {
        mRoot.addView(h1("Run Parlons in the cloud"));
        mRoot.addView(body("Your identity, chats and wallet live on your always-on node — reachable "
                + "from every device. This phone pairs to it as a secure client. Nobody else can pair "
                + "without your one-time code, and you can revoke this phone anytime."));

        final EditText addr = input("Account address (MAX#…)", prefill,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText code = input("Pairing code (e.g. ABCD-EFGH-JKLM)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        mRoot.addView(card(addr));
        mRoot.addView(card(code));

        final TextView status = body("");
        status.setVisibility(View.GONE);
        mRoot.addView(status);

        Button go = accentButton("Connect & pair");
        go.setOnClickListener(v -> {
            String a = addr.getText().toString().trim();
            String c = code.getText().toString().trim();
            if (a.isEmpty()) { toast("Enter your account address"); return; }
            go.setEnabled(false);
            show(status, "Connecting to your account…");
            mIo.execute(() -> {
                try {
                    ParlonsRemote r = new ParlonsRemote(mDeviceId);
                    r.connect(a);
                    JSONObject res = r.pair("android:" + android.os.Build.MODEL, c);
                    String st = String.valueOf(res.get("status"));
                    if ("authorized".equals(st) || "already".equals(st)) {
                        mRemote = r;
                        mPrefs.edit().putString("account", a).putBoolean("paired", true).apply();
                        runOnUiThread(this::render);
                    } else if ("pending".equals(st)) {
                        mPrefs.edit().putString("account", a).apply();
                        runOnUiThread(() -> {
                            go.setEnabled(true);
                            show(status, "Pairing requested. Approve it from an already-paired "
                                    + "device, then reopen this screen.\n\nThis device:\n" + r.deviceKey());
                        });
                    } else {
                        runOnUiThread(() -> { go.setEnabled(true);
                            show(status, "Pairing failed: " + res.getOrDefault("error", res)); });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> { go.setEnabled(true);
                        show(status, "Could not connect: " + msg(e)
                                + "\n\nIs the account online and the address correct?"); });
                }
            });
        });
        mRoot.addView(go);
    }

    // ---- connected status + chats ----

    private void renderStatus(String account) {
        mRoot.addView(h1("Your Parlons Cloud"));
        final TextView acct = body("Connected. Loading…");
        mRoot.addView(card(acct));

        final LinearLayout chats = column(this);
        mRoot.addView(sectionLabel("CHATS"));
        mRoot.addView(chats);

        Button refresh = ghostButton("Refresh");
        refresh.setOnClickListener(v -> loadStatus(account, acct, chats));
        Button disconnect = ghostButton("Disconnect this device");
        disconnect.setOnClickListener(v -> {
            mPrefs.edit().putBoolean("paired", false).apply();
            if (mRemote != null) { mRemote.close(); mRemote = null; }
            render();
        });
        mRoot.addView(refresh);
        mRoot.addView(disconnect);

        loadStatus(account, acct, chats);
    }

    private void loadStatus(String account, TextView acct, LinearLayout chats) {
        acct.setText("Connecting…");
        mIo.execute(() -> {
            try {
                if (mRemote == null) {
                    ParlonsRemote r = new ParlonsRemote(mDeviceId);
                    r.connect(account);
                    mRemote = r;
                }
                JSONObject ping = mRemote.ping();
                JSONObject sum = mRemote.summaries();
                runOnUiThread(() -> {
                    acct.setText(String.valueOf(ping.getOrDefault("name", "Your account")));
                    chats.removeAllViews();
                    Object arr = sum.get("summaries");
                    if (arr instanceof JSONArray && !((JSONArray) arr).isEmpty()) {
                        for (Object e : (JSONArray) arr) {
                            JSONObject s = (JSONObject) e;
                            chats.addView(chatRow(
                                    String.valueOf(s.getOrDefault("name", s.get("peer"))),
                                    String.valueOf(s.getOrDefault("last", "")),
                                    n(s.get("unread"))));
                        }
                    } else {
                        chats.addView(body("No conversations yet. Add contacts to your account and "
                                + "they'll appear here on every device."));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> acct.setText("Offline: " + msg(e)));
            }
        });
    }

    // ---- tiny view helpers (Parlons palette, no XML) ----

    private LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView h1(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(TEXT); t.setTextSize(22);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(10));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(SUBTEXT); t.setTextSize(14);
        t.setPadding(0, dp(2), 0, dp(12));
        return t;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(SUBTEXT); t.setTextSize(11);
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(2), dp(14), 0, dp(6));
        return t;
    }

    private View card(View inner) {
        LinearLayout w = new LinearLayout(this);
        w.setBackgroundColor(CARD);
        int p = dp(12);
        w.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(8));
        w.setLayoutParams(lp);
        w.addView(inner);
        return w;
    }

    private View chatRow(String name, String last, int unread) {
        LinearLayout row = column(this);
        row.setBackgroundColor(CARD);
        int p = dp(12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(1));
        row.setLayoutParams(lp);
        TextView n = new TextView(this);
        n.setText(unread > 0 ? name + "  (" + unread + " new)" : name);
        n.setTextColor(TEXT); n.setTextSize(15);
        n.setTypeface(n.getTypeface(), android.graphics.Typeface.BOLD);
        TextView l = new TextView(this);
        l.setText(last); l.setTextColor(SUBTEXT); l.setTextSize(13);
        l.setMaxLines(1); l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(n); row.addView(l);
        return row;
    }

    private EditText input(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value);
        e.setTextColor(TEXT); e.setHintTextColor(SUBTEXT);
        e.setInputType(type); e.setBackgroundColor(Color.TRANSPARENT);
        return e;
    }

    private Button accentButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false);
        b.setTextColor(ON_ACC); b.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private Button ghostButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false);
        b.setTextColor(HEADER); b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private void show(TextView t, String s) {
        t.setVisibility(View.VISIBLE); t.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static int n(Object o) {
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRemote != null) {
            try { mRemote.close(); } catch (Exception ignored) { }
        }
        mIo.shutdownNow();
    }
}
