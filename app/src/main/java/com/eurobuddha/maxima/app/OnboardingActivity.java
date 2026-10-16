package com.eurobuddha.maxima.app;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import androidx.appcompat.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import com.eurobuddha.maxima.app.i18n.Language;
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
        Language.setText(title, "Parlons!");
        title.setTextColor(text);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        TextView blurb = new TextView(this);
        Language.setText(blurb, Language.msg(R.string.l10n_onboarding_identity_intro));
        blurb.setTextColor(sub);
        blurb.setTextSize(15);
        blurb.setGravity(Gravity.CENTER);
        blurb.setPadding(0, dp(14), 0, dp(36));

        Button create = primary(Language.msg(R.string.l10n_onboarding_create_identity));
        create.setOnClickListener(v -> createNew());

        Button restore = ghost(Language.msg(R.string.l10n_onboarding_restore_identity));
        restore.setOnClickListener(v -> promptRestore());

        root.addView(title);
        root.addView(blurb);
        root.addView(create, lp());
        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
        root.addView(restore, lp());

        Button language = ghost(Language.msg(R.string.l10n_language_change));
        language.setOnClickListener(v -> Language.choose(this, false));
        root.addView(new View(this), new LinearLayout.LayoutParams(1, dp(12)));
        root.addView(language, lp());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), scroll).setAppearanceLightStatusBars(
                androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5);
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
        Language.setText(tv, sb.toString().trim());
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(getColor(R.color.ux_text));
        tv.setTextSize(15);
        tv.setTextIsSelectable(true);
        int pad = dp(20);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        AlertDialog d = new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_onboarding_write_words))
                .setMessage(Language.msg(R.string.l10n_onboarding_words_warning))
                .setView(sv)
                .setCancelable(false)
                .setNeutralButton(Language.msg(R.string.l10n_common_copy), (di, w) -> copyWithAutoClear(zPhrase))
                .setPositiveButton(Language.msg(R.string.l10n_onboarding_saved_words), (di, w) -> proceed())
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
        d.show();
        // Copy must keep the words visible.
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> copyWithAutoClear(zPhrase));
    }

    // ---- restore ----------------------------------------------------------

    private void promptRestore() {
        EditText input = new EditText(this);
        Language.hint(input, Language.msg(R.string.l10n_native_dialog_enter_seed));
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(3);
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);
        android.widget.CheckBox anyPhrase = new android.widget.CheckBox(this);
        Language.setText(anyPhrase, Language.msg(R.string.l10n_native_dialog_custom_seed));
        android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        fields.addView(input);
        fields.addView(anyPhrase);

        AlertDialog restoreDialog = new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_onboarding_restore_identity))
                .setMessage(Language.join(Language.msg(R.string.l10n_onboarding_restore_help), Language.msg(R.string.l10n_security_key_use_warning)))
                .setView(fields)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_native_dialog_restore), (d, w) -> {
                    String phrase = input.getText().toString();
                    if (phrase.trim().isEmpty()) {
                        toast(tr(R.string.l10n_native_dialog_enter_phrase));
                        return;
                    }
                    try {
                        SeedStore.ImportResult r = SeedStore.importPhrase(this, phrase, anyPhrase.isChecked());
                        CharSequence warn = anyPhrase.isChecked() ? Language.msg(R.string.l10n_native_dialog_custom_restored)
                                : r.checksumValid ? ""
                                : Language.msg(R.string.l10n_onboarding_no_checksum);
                        new LanguageDialog(this)
                                .setTitle(Language.msg(R.string.l10n_native_dialog_restored))
                                .setCancelable(false)
                                .setMessage(Language.join(Language.msg(R.string.l10n_onboarding_restored_phone), warn))
                                .setPositiveButton(Language.msg(R.string.l10n_onboarding_start), (d2, w2) -> proceed())
                                .show();
                    } catch (Exception e) {
                        toast(tr(R.string.l10n_native_dialog_restore_failed, e.getMessage()));
                    }
                })
                .create();
        if (restoreDialog.getWindow() != null) {
            restoreDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        restoreDialog.show();
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
        toast(tr(R.string.l10n_native_dialog_clipboard_clear));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Exception ignored) {
            }
        }, 60_000);
    }

    // ---- tiny view helpers ------------------------------------------------

    private Button primary(CharSequence zLabel) {
        Button b = baseButton(zLabel);
        b.setTextColor(getColor(R.color.ux_on_accent));
        b.setBackground(pill(getColor(R.color.ux_accent)));
        return b;
    }

    private Button ghost(CharSequence zLabel) {
        Button b = baseButton(zLabel);
        b.setTextColor(getColor(R.color.ux_text));
        GradientDrawable g = pill(0x00000000);
        g.setStroke(dp(1), getColor(R.color.ux_divider));
        b.setBackground(g);
        return b;
    }

    private Button baseButton(CharSequence zLabel) {
        Button b = new Button(this);
        b.setAllCaps(false);
        Language.setText(b, zLabel);
        b.setTextSize(16);
        b.setMinHeight(dp(52));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        return p;
    }

    private int dp(int zDp) {
        return Math.round(zDp * getResources().getDisplayMetrics().density);
    }

    private void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_LONG).show();
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
    }

    private String tr(int resource, Object... arguments) { return Language.text(this, resource, arguments); }

}
