package com.eurobuddha.maxima.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * First-run gate. The app used to silently mint an identity on first launch, so a
 * user reinstalling on a new phone was quietly given a NEW identity with no chance
 * to restore. This screen — shown by {@link MainActivity} when
 * {@link SeedStore#hasIdentity} is false, and backstopped by the service's own
 * no-mint gate — offers the real choice: create a brand-new identity (and write
 * the words down), or restore an existing one from its seed phrase.
 *
 * On first run the transport was never started, so restore here needs no teardown/
 * wipe (unlike {@link IdentityRestore}) — it just imports the phrase and starts
 * fresh on it.
 */
public final class OnboardingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle zState) {
        super.onCreate(zState);

        // Seed derivation (create AND restore) needs SHA3-256, which Android's JDK
        // provider lacks. MainActivity installs it, but it gates to us BEFORE that
        // line runs, so we must install it here too — otherwise create/restore fail
        // with "SHA3-256 unavailable".
        Sha3Provider.install();

        // Already have an identity (race, or backed out after creating one) → app.
        if (SeedStore.hasIdentity(this)) {
            launchMain();
            return;
        }

        int bg   = getColor(R.color.ux_bg);
        int text = getColor(R.color.ux_text);
        int sub  = getColor(R.color.ux_subtext);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setGravity(Gravity.CENTER);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Parlons!");
        title.setTextColor(text);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        TextView blurb = new TextView(this);
        blurb.setText("Private, secure, community-run chat. Set up your identity — it lives "
                + "only on this device and is backed by a secret 24-word phrase.");
        blurb.setTextColor(sub);
        blurb.setTextSize(15);
        blurb.setGravity(Gravity.CENTER);
        blurb.setPadding(0, dp(14), 0, dp(36));

        Button create = primary("Create new identity");
        create.setOnClickListener(v -> createNew());

        Button restore = ghost("Restore from seed phrase");
        restore.setOnClickListener(v -> promptRestore());

        root.addView(title);
        root.addView(blurb);
        root.addView(create, lp());
        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
        root.addView(restore, lp());

        setContentView(root);
    }

    // ---- create new -------------------------------------------------------

    private void createNew() {
        SeedStore.createNewIdentity(this);      // generate + save (idempotent, race-safe)
        showSeedThenContinue(SeedStore.revealPhrase(this));
    }

    /** Show the 24 words to write down before continuing. FLAG_SECURE blocks
     *  screenshots; a Copy option clears the clipboard after 60s. */
    private void showSeedThenContinue(String zPhrase) {
        String[] words = zPhrase.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            sb.append(i + 1).append(". ").append(words[i]).append(i % 2 == 0 ? "\t\t" : "\n");
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString().trim());
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(getColor(R.color.ux_text));
        tv.setTextSize(15);
        tv.setTextIsSelectable(true);
        int pad = dp(20);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Write these 24 words down")
                .setMessage("This is the ONLY way to recover your identity and any funds. "
                        + "Keep it offline and private — anyone with these words is you.")
                .setView(sv)
                .setCancelable(false)
                .setNeutralButton("Copy", (di, w) -> copyWithAutoClear(zPhrase))
                .setPositiveButton("I've saved them", (di, w) -> proceed())
                .create();
        d.setOnShowListener(x -> {
            // Copy must NOT dismiss the dialog — keep the words visible.
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> copyWithAutoClear(zPhrase));
        });
        if (d.getWindow() != null) {
            d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
        d.show();
    }

    // ---- restore ----------------------------------------------------------

    private void promptRestore() {
        EditText input = new EditText(this);
        input.setHint("paste your 24 words, separated by spaces");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(3);
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Restore from seed phrase")
                .setMessage("Enter the 24 words from your other device to bring the SAME identity "
                        + "and address here." + IdentityRestore.KEYUSE_WARNING)
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> {
                    String phrase = input.getText().toString().trim();
                    if (phrase.isEmpty()) {
                        toast("Enter your phrase");
                        return;
                    }
                    try {
                        SeedStore.ImportResult r = SeedStore.importPhrase(this, phrase);
                        String warn = r.checksumValid ? ""
                                : "\n\nNote: no BIP39 checksum — normal for a phrase from a Minima "
                                + "node. Restored anyway.";
                        new AlertDialog.Builder(this)
                                .setTitle("Restored")
                                .setCancelable(false)
                                .setMessage("Your identity is restored on this phone." + warn)
                                .setPositiveButton("Start", (d2, w2) -> proceed())
                                .show();
                    } catch (Exception e) {
                        toast("Could not restore: " + e.getMessage());
                    }
                })
                .show();
    }

    // ---- shared -----------------------------------------------------------

    /** The service was never started on first run, so just start it on the seed
     *  we now hold and hand off to the main UI. */
    private void proceed() {
        MaximaService.start(this);
        launchMain();
    }

    private void launchMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void copyWithAutoClear(String zPhrase) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("seed", zPhrase));
        toast("Copied — clipboard clears in 60s");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Exception ignored) {
            }
        }, 60_000);
    }

    // ---- tiny view helpers ------------------------------------------------

    private Button primary(String zLabel) {
        Button b = baseButton(zLabel);
        b.setTextColor(getColor(R.color.ux_on_accent));
        b.setBackground(pill(getColor(R.color.ux_accent)));
        return b;
    }

    private Button ghost(String zLabel) {
        Button b = baseButton(zLabel);
        b.setTextColor(getColor(R.color.ux_text));
        GradientDrawable g = pill(0x00000000);
        g.setStroke(dp(1), getColor(R.color.ux_divider));
        b.setBackground(g);
        return b;
    }

    private Button baseButton(String zLabel) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(zLabel);
        b.setTextSize(16);
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable pill(int zFill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(zFill);
        g.setCornerRadius(dp(12));
        return g;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        return p;
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }

    private void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_LONG).show();
    }
}
