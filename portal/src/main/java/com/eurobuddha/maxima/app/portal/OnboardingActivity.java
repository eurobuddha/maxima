package com.eurobuddha.maxima.app.portal;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.cloud.ParlonsRemote;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.minima.utils.json.JSONObject;

/**
 * Parlons Cloud portal — onboarding: point this device at a cloud account (its permanent MAX#,
 * pasted or scanned) and pair (bootstrap code, or pending→approve). On success it's remembered
 * and the tabbed shell opens. This device holds only its own device key; the account lives on the
 * VPS node.
 */
public final class OnboardingActivity extends AppCompatActivity {

    private static final int BG = Color.parseColor("#F5F4F1");
    private static final int CARD = Color.parseColor("#FFFFFF");
    private static final int TEXT = Color.parseColor("#23262B");
    private static final int SUB = Color.parseColor("#797C82");
    private static final int ACCENT = Color.parseColor("#2A2E33");

    private EditText mAddr;
    private EditText mCode;
    private TextView mStatus;
    private Button mGo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Parlons Cloud");

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, p, p, p);
        sv.addView(col);
        setContentView(sv);

        col.addView(h1("Run Parlons in the cloud"));
        col.addView(body("Your identity, chats and wallet live on your always-on node — reachable "
                + "from every device. This phone pairs to it as a secure client. Nobody else can "
                + "pair without your one-time code, and you can revoke this phone anytime."));

        mAddr = input("Account address (MAX#…)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Remember the account across attempts — nobody wants to re-enter a 600-char MAX#.
        String saved = CloudSession.account(this);
        if (saved != null && !saved.isEmpty()) {
            mAddr.setText(saved);
        }
        mCode = input("Pairing code (e.g. ABCD-EFGH-JKLM)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        col.addView(card(mAddr));

        Button scan = ghost("Scan account QR");
        scan.setOnClickListener(v -> new IntentIntegrator(this)
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setPrompt("Scan your account's address QR")
                .initiateScan());
        col.addView(scan);

        col.addView(card(mCode));

        mStatus = body("");
        mStatus.setVisibility(View.GONE);
        col.addView(mStatus);

        mGo = accent("Connect & pair");
        mGo.setOnClickListener(v -> pair());
        col.addView(mGo);
    }

    private void pair() {
        String a = mAddr.getText().toString().trim();
        String c = mCode.getText().toString().trim();
        if (a.isEmpty()) { toast("Enter or scan your account address"); return; }
        CloudSession.setAccount(this, a);
        CloudSession.reset();   // fresh remote for the (possibly new) account
        mGo.setEnabled(false);
        show("Attaching to the network…");
        CloudSession.connect(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                runOnUiThread(() -> show("Account found — pairing…"));
                try {
                    JSONObject res = r.pair("android:" + android.os.Build.MODEL, c);
                    String st = String.valueOf(res.get("status"));
                    if ("authorized".equals(st) || "already".equals(st)) {
                        CloudSession.setPaired(OnboardingActivity.this, true);
                        runOnUiThread(() -> {
                            startActivity(new Intent(OnboardingActivity.this, MainActivity.class));
                            finish();
                        });
                    } else if ("pending".equals(st)) {
                        runOnUiThread(() -> { mGo.setEnabled(true);
                            show("Pairing requested. Approve it from an already-paired device, then "
                                    + "reopen.\n\nThis device:\n" + r.deviceKey()); });
                    } else {
                        runOnUiThread(() -> { mGo.setEnabled(true);
                            show("Pairing failed: " + res.getOrDefault("error", res)); });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> { mGo.setEnabled(true);
                        show("Pairing failed: " + msg(e)); });
                }
            }
            public void err(String m) {
                runOnUiThread(() -> { mGo.setEnabled(true);
                    show("Could not connect: " + m + "\n\nIs the account online and the address correct?"); });
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        IntentResult r = IntentIntegrator.parseActivityResult(req, res, data);
        if (r != null && r.getContents() != null) {
            mAddr.setText(r.getContents().trim());
            toast("Address scanned");
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    // ---- views ----
    private TextView h1(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(TEXT); t.setTextSize(22);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(10)); return t;
    }
    private TextView body(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(SUB); t.setTextSize(14);
        t.setPadding(0, dp(2), 0, dp(12)); return t;
    }
    private View card(View inner) {
        LinearLayout w = new LinearLayout(this); w.setBackgroundColor(CARD);
        int q = dp(12); w.setPadding(q, q, q, q);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(8)); w.setLayoutParams(lp);
        w.addView(inner); return w;
    }
    private EditText input(String hint, int type) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextColor(TEXT);
        e.setHintTextColor(SUB); e.setInputType(type); e.setBackgroundColor(Color.TRANSPARENT); return e;
    }
    private Button accent(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(6)); b.setLayoutParams(lp); return b;
    }
    private Button ghost(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false);
        b.setTextColor(ACCENT); b.setBackgroundColor(Color.TRANSPARENT); return b;
    }
    private void show(String s) { mStatus.setVisibility(View.VISIBLE); mStatus.setText(s); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static String msg(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
