package com.eurobuddha.maxima.app.i18n;

import android.os.Bundle;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import android.content.res.Configuration;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import com.eurobuddha.maxima.app.R;

/** Debug-only fixture: real resources and chat layout, no identity, node, relay or account. */
public final class LanguagePreviewActivity extends AppCompatActivity {
    private final String instance = java.util.UUID.randomUUID().toString();
    private Configuration initial;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ("onboarding".equals(getIntent().getStringExtra("preview"))) {
            String screen = getPackageName().equals("com.eurobuddha.maxima.app")
                    ? "com.eurobuddha.maxima.app.OnboardingActivity"
                    : "com.eurobuddha.maxima.app.portal.OnboardingActivity";
            startActivity(new android.content.Intent().setClassName(this, screen));
            finish();
            return;
        }
        initial = new Configuration(getResources().getConfiguration());
        android.util.Log.i("ParlonsLanguageFixture", "create " + instance + " " + initial);
        setContentView(R.layout.activity_chat);
        ((TextView)findViewById(R.id.chat_title)).setText("Chats");
        ((TextView)findViewById(R.id.chat_subtitle)).setText(instance);
        ((EditText)findViewById(R.id.chat_input)).setText("Draft 中文 Привет");
        findViewById(R.id.btn_chat_info).setOnClickListener(v -> Language.choose(this, false));
        LinearLayout root = findViewById(R.id.chat_root);
        final View appbar = findViewById(R.id.chat_appbar);
        final View composer = findViewById(R.id.chat_composer);
        final int barTop = appbar.getPaddingTop();          // XML base
        final int compBottom = composer.getPaddingBottom();  // XML base
        // Extend the dark app bar UP into the status bar, and keep the composer
        // above the nav bar / keyboard - so the header colour fills the top like
        // WhatsApp, not a pale strip above it.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, 0);
            appbar.setPadding(appbar.getPaddingLeft(), barTop + bars.top,
                    appbar.getPaddingRight(), appbar.getPaddingBottom());
            composer.setPadding(composer.getPaddingLeft(), composer.getPaddingTop(),
                    composer.getPaddingRight(), compBottom + Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(getColor(R.color.ux_header));
        androidx.core.view.WindowInsetsControllerCompat wic =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), root);
        if (wic != null) {
            wic.setAppearanceLightStatusBars(false);   // dark header → light status icons
        }


        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * getResources().getDisplayMetrics().density);
        labels.setPadding(padding,padding,padding,padding);
        int[] strings = {R.string.l10n_language_description, R.string.l10n_native_settings_background_help,
                R.string.l10n_hosting_chat_access, R.string.l10n_password_mismatch};
        for (int resource : strings) {
            TextView label = new TextView(this);label.setTextSize(16); label.setPadding(0,0,0,padding);
            Language.setText(label,Language.msg(resource)); labels.addView(label);
        }
        TextView plural = new TextView(this);plural.setTextSize(16);
        Language.setText(plural,Language.count(R.plurals.l10n_connection_relays,2));labels.addView(plural);
        TextView parameter = new TextView(this);parameter.setTextSize(16);
        Language.setText(parameter,Language.msg(R.string.l10n_hosting_invite_help,"Chats"));labels.addView(parameter);
        root.addView(labels,1);Language.apply(root);
    }
    @Override public void onConfigurationChanged(Configuration config) {
        android.util.Log.i("ParlonsLanguageFixture", "configuration " + instance + " diff=" + Integer.toHexString(initial.diff(config)) + " " + config);
        super.onConfigurationChanged(config); Language.configurationChanged(this);
    }
    @Override protected void onDestroy() {
        android.util.Log.i("ParlonsLanguageFixture", "destroy " + instance + " changing=" + isChangingConfigurations() + " flags=" + Integer.toHexString(getChangingConfigurations()));
        super.onDestroy();
    }
}
