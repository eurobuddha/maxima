package com.eurobuddha.maxima.app.portal;

import android.content.Intent;
import com.eurobuddha.maxima.app.i18n.Language;
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
import com.eurobuddha.maxima.app.R;
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
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), sv).setAppearanceLightStatusBars(true);

        Button language = ghost(Language.msg(R.string.l10n_language_change));
        language.setOnClickListener(v -> Language.choose(this, false));
        col.addView(language);

        col.addView(h1(Language.msg(R.string.l10n_onboarding_cloud_heading)));
        col.addView(body(Language.msg(R.string.l10n_onboarding_cloud_intro)));

        mAddr = input("MAX#…",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Remember the account across attempts — nobody wants to re-enter a 600-char MAX#.
        String saved = CloudSession.account(this);
        if (saved != null && !saved.isEmpty()) {
            Language.setText(mAddr, saved);
        }
        mCode = input("ABCD-EFGH-JKLM",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        col.addView(card(Language.msg(R.string.l10n_onboarding_account_label), mAddr));

        Button scan = ghost(Language.msg(R.string.l10n_onboarding_scan_pairing));
        scan.setOnClickListener(v -> new IntentIntegrator(this)
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setPrompt(tr(R.string.l10n_onboarding_scan_prompt))
                .initiateScan());
        col.addView(scan);

        col.addView(card(Language.msg(R.string.l10n_onboarding_code_label), mCode));

        mStatus = body("");
        mStatus.setVisibility(View.GONE);
        col.addView(mStatus);

        mGo = accent(Language.msg(R.string.l10n_onboarding_connect_pair));
        mGo.setOnClickListener(v -> pair());
        col.addView(mGo);

        // --- new here? how to create the account server the phone pairs to ---
        final LinearLayout setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        setupBox.setBackgroundColor(CARD);
        int q = dp(14);
        setupBox.setPadding(q, q, q, q);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sblp.setMargins(0, dp(4), 0, dp(8));
        setupBox.setLayoutParams(sblp);
        setupBox.setVisibility(View.GONE);
        setupBox.addView(body(Language.msg(R.string.l10n_onboarding_cloud_setup)));
        setupBox.addView(codeBlock("ops/deploy-parlons-cloud.sh root@YOUR.VPS.IP --port 9501"));
        setupBox.addView(body(Language.msg(R.string.l10n_onboarding_cloud_guide)));
        setupBox.addView(codeBlock("github.com/eurobuddha/maxima → cloud/SETUP.md"));

        final Button setupToggle = ghost(Language.msg(R.string.l10n_onboarding_new_server));
        setupToggle.setOnClickListener(v -> {
            boolean open = setupBox.getVisibility() == View.GONE;
            setupBox.setVisibility(open ? View.VISIBLE : View.GONE);
            Language.setText(setupToggle, open ? Language.msg(R.string.l10n_onboarding_server_expanded)
                    : Language.msg(R.string.l10n_onboarding_new_server));
        });
        col.addView(setupToggle);
        col.addView(setupBox);
    }

    /** A tap-to-copy monospace command / path block. */
    private View codeBlock(final String s) {
        TextView t = new TextView(this);
        Language.setText(t, s);
        t.setTextColor(TEXT);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setBackgroundColor(Color.parseColor("#ECEBE6"));
        int q = dp(10);
        t.setPadding(q, q, q, q);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(10));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("setup", s));   // full, never truncated
            toast(tr(R.string.l10n_common_copied));
        });
        return t;
    }

    private void pair() {
        String[] invite = com.eurobuddha.maxima.cloud.AccountFiles.parseInvite(mAddr.getText().toString());
        String a = invite[0];
        if (!invite[1].isEmpty()) Language.setText(mCode, invite[1]);
        String c = mCode.getText().toString().trim();
        if (a.isEmpty()) { toast(tr(R.string.l10n_onboarding_address_required)); return; }
        CloudSession.setAccount(this, a);
        mGo.setEnabled(false);
        show(Language.msg(R.string.l10n_onboarding_attaching));
        CloudSession.connect(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                runOnUiThread(() -> show(Language.msg(R.string.l10n_onboarding_pairing)));
                try {
                    JSONObject res = r.pair("android:" + android.os.Build.MODEL, c);
                    String st = String.valueOf(res.get("status"));
                    if ("authorized".equals(st) || "already".equals(st)) {
                        runOnUiThread(() -> {
                            if (!CloudSession.setPaired(OnboardingActivity.this, r)) return;
                            startActivity(new Intent(OnboardingActivity.this, MainActivity.class));
                            finish();
                        });
                    } else if ("pending".equals(st)) {
                        runOnUiThread(() -> { mGo.setEnabled(true);
                            show(Language.msg(R.string.l10n_onboarding_pairing_pending, r.deviceKey())); });
                    } else {
                        runOnUiThread(() -> { mGo.setEnabled(true);
                            show(Language.msg(R.string.l10n_onboarding_pairing_failed, String.valueOf(res.getOrDefault("error", res)))); });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> { mGo.setEnabled(true);
                        show(Language.msg(R.string.l10n_onboarding_pairing_failed, msg(e))); });
                }
            }
            public void err(String m) {
                runOnUiThread(() -> { mGo.setEnabled(true);
                    show(Language.msg(R.string.l10n_onboarding_connect_failed, m)); });
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        IntentResult r = IntentIntegrator.parseActivityResult(req, res, data);
        if (r != null && r.getContents() != null) {
            String[] invite = com.eurobuddha.maxima.cloud.AccountFiles.parseInvite(r.getContents());
            Language.setText(mAddr, invite[0]);
            Language.setText(mCode, invite[1]);
            toast(invite[1].isEmpty() ? tr(R.string.l10n_onboarding_address_scanned) : tr(R.string.l10n_onboarding_invite_scanned));
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    // ---- views ----
    private TextView h1(CharSequence s) {
        TextView t = new TextView(this); Language.setText(t, s); t.setTextColor(TEXT); t.setTextSize(22);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(10)); return t;
    }
    private TextView body(CharSequence s) {
        TextView t = new TextView(this); Language.setText(t, s); t.setTextColor(SUB); t.setTextSize(14);
        t.setPadding(0, dp(2), 0, dp(12)); return t;
    }
    private View card(CharSequence label, View inner) {
        LinearLayout w = new LinearLayout(this); w.setBackgroundColor(CARD);
        int q = dp(12); w.setPadding(q, q, q, q);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(8)); w.setLayoutParams(lp);
        w.setOrientation(LinearLayout.VERTICAL);
        TextView heading = body(label);
        if (inner.getId() == View.NO_ID) inner.setId(View.generateViewId());
        heading.setLabelFor(inner.getId());
        Language.description(inner, label);
        w.addView(heading); w.addView(inner); return w;
    }
    private EditText input(CharSequence hint, int type) {
        EditText e = new EditText(this); Language.hint(e, hint); e.setTextColor(TEXT);
        e.setTextSize(16); e.setHintTextColor(SUB); e.setInputType(type); e.setBackgroundColor(Color.TRANSPARENT); return e;
    }
    private Button accent(CharSequence s) {
        Button b = new Button(this); Language.setText(b, s); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(6)); b.setLayoutParams(lp); return b;
    }
    private Button ghost(CharSequence s) {
        Button b = new Button(this); Language.setText(b, s); b.setAllCaps(false);
        b.setTextColor(ACCENT); b.setBackgroundColor(Color.TRANSPARENT); return b;
    }
    private void show(CharSequence s) { mStatus.setVisibility(View.VISIBLE); Language.setText(mStatus, s); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static String msg(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
    }

    private String tr(int resource, Object... arguments) { return Language.text(this, resource, arguments); }

}
