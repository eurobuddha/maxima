package com.eurobuddha.maxima.app.portal;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.i18n.SystemMessages;

import com.eurobuddha.maxima.app.i18n.Language;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONObject;

/**
 * Portal settings — the account-level switches that live ON THE NODE (they follow the account to
 * every device), plus device-local bits. Parlons' Settings vocabulary, cloud-backed.
 */
public final class CloudSettingsActivity extends AppCompatActivity {

    /** The next scanned QR lands here (a relay QR from the Relays sheet). */
    private java.util.function.Consumer<String> mScanSink;
    private final androidx.activity.result.ActivityResultLauncher<
            com.journeyapps.barcodescanner.ScanOptions> mScanLauncher =
            registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), result -> {
                java.util.function.Consumer<String> sink = mScanSink;
                mScanSink = null;
                if (result != null && result.getContents() != null && sink != null) {
                    sink.accept(result.getContents());
                }
            });

    private void scanRelayQr(java.util.function.Consumer<String> zSink) {
        mScanSink = zSink;
        com.journeyapps.barcodescanner.ScanOptions o = new com.journeyapps.barcodescanner.ScanOptions();
        o.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        o.setPrompt("Scan a relay QR");
        o.setBeepEnabled(false);
        o.setOrientationLocked(true);
        mScanLauncher.launch(o);
    }

    /** The seed relays of THIS phone: list (yours / built-in), add, scan, drop, the switch. */
    private void showRelays() {
        final Context c = this;
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = PortalUi.dp(c, 16);
        body.setPadding(pad, pad, pad, pad);
        final androidx.appcompat.app.AlertDialog[] dlg = new androidx.appcompat.app.AlertDialog[1];

        for (final String h : PortalRelayStore.get(c)) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView t = new TextView(c);
            Language.setText(t, Language.join(h, "  · ", Language.msg(com.eurobuddha.maxima.core.session.SeedRelays.isBuiltIn(h)
                    ? R.string.l10n_node_builtin : R.string.l10n_desktop_network_yours)));
            t.setTextColor(getColor(R.color.ux_text));
            t.setTextSize(12);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView rm = PortalUi.ghost(c, Language.msg(R.string.l10n_native_settings_drop));
            rm.setTextColor(getColor(R.color.ux_error));
            rm.setOnClickListener(v -> {
                com.eurobuddha.maxima.core.session.SeedRelays.Drop d = PortalRelayStore.remove(c, h);
                if (d == com.eurobuddha.maxima.core.session.SeedRelays.Drop.REFUSED_LAST_SEED) {
                    android.widget.Toast.makeText(c, tr(R.string.l10n_native_dialog_keep_relay),
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                if (d == com.eurobuddha.maxima.core.session.SeedRelays.Drop.DROPPED_BUILTIN_BACK_ON) {
                    android.widget.Toast.makeText(c, tr(R.string.l10n_native_dialog_last_relay),
                            android.widget.Toast.LENGTH_LONG).show();
                }
                if (dlg[0] != null) dlg[0].dismiss();
                showRelays();
            });
            row.addView(rm);
            body.addView(row);
        }
        if (PortalRelayStore.get(c).isEmpty()) {
            body.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_no_relays)));
        }

        body.addView(PortalUi.gap(c, 10));
        final android.widget.EditText add = new android.widget.EditText(c);
        Language.hint(add, Language.msg(R.string.l10n_native_dialog_relay_input));
        add.setSingleLine(true);
        add.setTextSize(13);
        add.setTextColor(getColor(R.color.ux_text));
        add.setHintTextColor(getColor(R.color.ux_subtext));
        body.addView(add);
        TextView addBtn = PortalUi.button(c, Language.msg(R.string.l10n_common_add));
        addBtn.setOnClickListener(v -> {
            java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(add.getText().toString());
            if (hs.isEmpty()) {
                android.widget.Toast.makeText(c, tr(R.string.l10n_native_dialog_relay_input_required),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            for (String h : hs) PortalRelayStore.add(c, h);
            if (dlg[0] != null) dlg[0].dismiss();
            showRelays();
        });
        body.addView(addBtn);
        TextView scan = PortalUi.ghost(c, Language.msg(R.string.l10n_native_settings_scan_relay));
        scan.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            scanRelayQr(text -> {
                java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(text);
                if (hs.isEmpty()) {
                    android.widget.Toast.makeText(c, tr(R.string.l10n_native_dialog_not_relay_qr), android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    for (String h : hs) PortalRelayStore.add(c, h);
                    android.widget.Toast.makeText(c, Language.count(R.plurals.l10n_ios_relays_added, hs.size()), android.widget.Toast.LENGTH_SHORT).show();
                }
                showRelays();
            });
        });
        body.addView(scan);

        body.addView(PortalUi.gap(c, 10));
        LinearLayout sw = new LinearLayout(c);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView swLabel = PortalUi.label(c, Language.msg(R.string.l10n_native_settings_use_builtins));
        sw.addView(swLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        androidx.appcompat.widget.SwitchCompat toggle = new androidx.appcompat.widget.SwitchCompat(c);
        toggle.setChecked(PortalRelayStore.builtInEnabled(c));
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (!PortalRelayStore.setBuiltInEnabled(c, on)) {
                android.widget.Toast.makeText(c, tr(R.string.l10n_native_dialog_add_relay_first),
                        android.widget.Toast.LENGTH_LONG).show();
                b.setChecked(true);
            }
        });
        sw.addView(toggle);
        body.addView(sw);
        body.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_relay_apply)));

        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(body);
        dlg[0] = new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_native_settings_relay_sources))
                .setView(scroll)
                .setPositiveButton(Language.msg(R.string.l10n_common_done), null)
                .setNeutralButton(Language.msg(R.string.l10n_native_dialog_reset), (d, w) -> {
                    PortalRelayStore.reset(c);
                    android.widget.Toast.makeText(c, tr(R.string.l10n_native_dialog_builtin_reset), android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private Switch mReceipts;
    private Switch mAppLock;
    private Switch mScreenShare;
    private Switch mMsgSound;
    private volatile boolean mApplying;   // guard: programmatic set must not fire the RPC

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Context c = this;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.ux_bg));

        // Simple app bar in the parity chrome.
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getColor(R.color.ux_header));
        int pad = PortalUi.dp(c, 16);
        bar.setPadding(pad, pad, pad, pad);
        TextView back = new TextView(c);
        Language.setText(back, "‹");
        back.setTextSize(26);
        back.setTextColor(getColor(R.color.ux_on_header));
        back.setPadding(0, 0, PortalUi.dp(c, 18), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(c);
        Language.setText(title, Language.msg(R.string.l10n_nav_settings));
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.ux_on_header));
        bar.addView(title);
        root.addView(bar);

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int bp = PortalUi.dp(c, 14);
        body.setPadding(bp, bp, bp, bp);

        LinearLayout languageCard = PortalUi.card(c);
        TextView languageHelp = PortalUi.label(c, "");
        com.eurobuddha.maxima.app.i18n.Language.setText(languageHelp,
                com.eurobuddha.maxima.app.i18n.Language.msg(R.string.l10n_language_description));
        languageCard.addView(languageHelp);
        TextView languageButton = PortalUi.ghost(c, "English · 简体中文 · Русский");
        com.eurobuddha.maxima.app.i18n.Language.description(languageButton,
                com.eurobuddha.maxima.app.i18n.Language.msg(R.string.l10n_language_change));
        languageButton.setOnClickListener(v -> com.eurobuddha.maxima.app.i18n.Language.choose(this, false));
        languageCard.addView(languageButton);
        body.addView(languageCard);

        // --- account settings (stored on the node — follow you to every device) ---
        body.addView(PortalUi.section(c, Language.msg(R.string.l10n_contacts_owner)));
        LinearLayout acc = PortalUi.card(c);
        LinearLayout rrRow = new LinearLayout(c);
        rrRow.setOrientation(LinearLayout.HORIZONTAL);
        rrRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout rrCol = new LinearLayout(c);
        rrCol.setOrientation(LinearLayout.VERTICAL);
        TextView rrT = PortalUi.title(c, Language.msg(R.string.l10n_native_settings_send_receipts));
        rrCol.addView(rrT);
        rrCol.addView(PortalUi.label(c,
                Language.msg(R.string.l10n_native_settings_cloud_receipts)));
        rrRow.addView(rrCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mReceipts = new Switch(c);
        mReceipts.setEnabled(false);   // until the current value loads
        mReceipts.setOnCheckedChangeListener((v, on) -> {
            if (!mApplying) {
                applyReceipts(on);
            }
        });
        rrRow.addView(mReceipts);
        acc.addView(rrRow);
        body.addView(acc);

        // --- privacy & security: app lock + screen-share (device-local, mirrors the phone app) ---
        body.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_settings_privacy_security)));
        LinearLayout sec = PortalUi.card(c);
        boolean lockAvail = com.eurobuddha.maxima.app.AppLock.isAvailable(c);

        LinearLayout lockRow = new LinearLayout(c);
        lockRow.setOrientation(LinearLayout.HORIZONTAL);
        lockRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout lockCol = new LinearLayout(c);
        lockCol.setOrientation(LinearLayout.VERTICAL);
        lockCol.addView(PortalUi.title(c, Language.msg(R.string.l10n_native_settings_lock)));
        lockCol.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_cloud_lock)));
        lockRow.addView(lockCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mAppLock = new Switch(c);
        mAppLock.setChecked(com.eurobuddha.maxima.app.AppLock.isEnabled(c));
        mAppLock.setEnabled(lockAvail);
        lockRow.addView(mAppLock);
        sec.addView(lockRow);
        if (!lockAvail) {
            sec.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_cloud_lock_unavailable)));
        }

        sec.addView(PortalUi.gap(c, 12));

        LinearLayout ssRow = new LinearLayout(c);
        ssRow.setOrientation(LinearLayout.HORIZONTAL);
        ssRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ssCol = new LinearLayout(c);
        ssCol.setOrientation(LinearLayout.VERTICAL);
        ssCol.addView(PortalUi.title(c, Language.msg(R.string.l10n_native_settings_screen_share)));
        ssCol.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_cloud_screen_share)));
        ssRow.addView(ssCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mScreenShare = new Switch(c);
        mScreenShare.setChecked(com.eurobuddha.maxima.app.AppLock.allowScreenShare(c));
        mScreenShare.setEnabled(com.eurobuddha.maxima.app.AppLock.isEnabled(c));
        mScreenShare.setOnCheckedChangeListener((v, on) ->
                com.eurobuddha.maxima.app.AppLock.setAllowScreenShare(c, on));
        ssRow.addView(mScreenShare);
        sec.addView(ssRow);
        // Wire the lock switch last so it can flip the screen-share row's enabled state.
        mAppLock.setOnCheckedChangeListener((v, on) -> {
            com.eurobuddha.maxima.app.AppLock.setEnabled(c, on);
            mScreenShare.setEnabled(on);
        });
        body.addView(sec);

        // --- notifications: the Parlons "pssst" sound (device-local) ---
        body.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_settings_notifications)));
        LinearLayout notifCard = PortalUi.card(c);
        LinearLayout sndRow = new LinearLayout(c);
        sndRow.setOrientation(LinearLayout.HORIZONTAL);
        sndRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sndCol = new LinearLayout(c);
        sndCol.setOrientation(LinearLayout.VERTICAL);
        sndCol.addView(PortalUi.title(c, Language.msg(R.string.l10n_native_settings_sound)));
        sndCol.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_cloud_sound)));
        sndRow.addView(sndCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mMsgSound = new Switch(c);
        mMsgSound.setChecked(PortalNotifier.messageSound(c));
        mMsgSound.setOnCheckedChangeListener((v, on) -> PortalNotifier.setMessageSound(c, on));
        sndRow.addView(mMsgSound);
        notifCard.addView(sndRow);
        notifCard.addView(PortalUi.gap(c, 12));
        TextView calls = PortalUi.ghost(c, Language.msg(R.string.l10n_native_settings_incoming_settings));
        calls.setOnClickListener(v -> PortalIncomingCall.openCallSettings(this));
        notifCard.addView(calls);
        notifCard.addView(PortalUi.label(c,
                Language.msg(R.string.l10n_native_settings_incoming_help)));
        body.addView(notifCard);

        // --- keys & backup: the identity lifecycle, app-parity discipline ---
        body.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_settings_keys_backup)));
        LinearLayout keys = PortalUi.card(c);
        keys.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_cloud_backup_help)));
        keys.addView(PortalUi.gap(c, 10));
        TextView showSeed = PortalUi.ghost(c, Language.msg(R.string.l10n_native_settings_show_seed));
        showSeed.setTextColor(c.getColor(R.color.ux_error));
        showSeed.setOnClickListener(v -> confirmShowSeed());
        keys.addView(showSeed);
        keys.addView(PortalUi.gap(c, 8));
        TextView backup = PortalUi.button(c, Language.msg(R.string.l10n_native_settings_backup_account));
        backup.setOnClickListener(v -> promptBackupPassphrase());
        keys.addView(backup);
        keys.addView(PortalUi.gap(c, 8));
        keys.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_cloud_bundle)));
        keys.addView(PortalUi.gap(c, 10));
        TextView restore = PortalUi.ghost(c, Language.msg(R.string.l10n_native_settings_import_identity));
        restore.setOnClickListener(v -> startActivity(new Intent(this, CloudIdentityImportActivity.class)));
        keys.addView(restore);
        keys.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_cloud_restore)));
        body.addView(keys);

        // --- Minima Core on this phone — the same "link to core" path phone users know.
        //     A full node on the phone is your own chain view + an independent wallet home
        //     (resync your wallet to a new seed there; the cloud seed stays your identity). ---
        body.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_settings_core_phone)));
        LinearLayout core = PortalUi.card(c);
        boolean coreInstalled = com.eurobuddha.maxima.app.wallet.WalletPublisher.coreInstalled(this);
        if (coreInstalled) {
            core.addView(PortalUi.title(c, Language.msg(R.string.l10n_native_settings_core_installed)));
            core.addView(PortalUi.label(c,
                    Language.msg(R.string.l10n_native_settings_core_installed_help)));
            TextView openCore = PortalUi.ghost(c, Language.msg(R.string.l10n_native_settings_open_core));
            openCore.setOnClickListener(v -> {
                Intent i = getPackageManager()
                        .getLaunchIntentForPackage("com.eurobuddha.minimacore");
                if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.minimablock");
                if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.eurobuddha.pandamonium");
                if (i != null) {
                    startActivity(i);
                }
            });
            LinearLayout.LayoutParams oclp = PortalUi.matchWrap(c);
            oclp.topMargin = PortalUi.dp(c, 10);
            openCore.setLayoutParams(oclp);
            core.addView(openCore);
        } else {
            core.addView(PortalUi.title(c, Language.msg(R.string.l10n_native_settings_run_core)));
            core.addView(PortalUi.label(c,
                    Language.msg(R.string.l10n_native_settings_run_core_help)));
            TextView getCore = PortalUi.button(c, Language.msg(R.string.l10n_native_settings_get_core));
            getCore.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                            "https://github.com/eurobuddha/minima-core-apks/releases/tag/mirrors"))));
            LinearLayout.LayoutParams gclp = PortalUi.matchWrap(c);
            gclp.topMargin = PortalUi.dp(c, 10);
            getCore.setLayoutParams(gclp);
            core.addView(getCore);
        }
        body.addView(core);

        // --- this device ---
        body.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_settings_this_device)));
        LinearLayout dev = PortalUi.card(c);
        TextView relays = PortalUi.title(c, Language.msg(R.string.l10n_native_settings_relay_sources));
        relays.setOnClickListener(v -> showRelays());
        dev.addView(relays);
        dev.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_relay_sources_help)));
        dev.addView(PortalUi.gap(c, 12));
        TextView notif = PortalUi.title(c, Language.msg(R.string.l10n_native_settings_notification_settings));
        notif.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        });
        dev.addView(notif);
        dev.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_notification_help)));

        // Battery exemption — the portal must stay live on the push channel; battery optimisation
        // can kill the foreground service. Only offer it when NOT already exempt.
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (!exempt) {
            dev.addView(PortalUi.gap(c, 12));
            TextView batt = PortalUi.title(c, Language.msg(R.string.l10n_native_settings_background));
            batt.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    // Some OEMs block the direct request — fall back to the settings list.
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
            });
            dev.addView(batt);
            dev.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_background_help)));
        }
        body.addView(dev);

        // --- about ---
        body.addView(PortalUi.section(c, Language.msg(R.string.l10n_native_settings_about)));
        LinearLayout about = PortalUi.card(c);
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.addView(PortalUi.kv(c, Language.msg(R.string.l10n_native_settings_portal_version), "v" + ver));
        about.addView(PortalUi.gap(c, 8));
        about.addView(PortalUi.label(c, Language.msg(R.string.l10n_native_settings_address_help)));
        TextView addr = PortalUi.value(c, CloudSession.account(this));   // full, never truncated
        addr.setTextSize(12);
        about.addView(addr);
        body.addView(about);

        ScrollView scroll = new ScrollView(c);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // Edge-to-edge: bar into the status bar, body above the nav bar.
        final int barTop = bar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            bar.setPadding(bar.getPaddingLeft(), barTop + bars.top,
                    bar.getPaddingRight(), bar.getPaddingBottom());
            return WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(getColor(R.color.ux_header));

        loadSettings();
    }

    // ==================================================================
    // Identity lifecycle — seed reveal + encrypted backup (app parity)
    // ==================================================================

    private void confirmShowSeed() {
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_native_dialog_show_seed_question))
                .setMessage(Language.msg(R.string.l10n_native_dialog_cloud_seed_warning))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_native_dialog_show), (d, w) -> fetchAndShowSeed())
                .show();
    }

    private void fetchAndShowSeed() {
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(com.eurobuddha.maxima.cloud.ParlonsRemote r) {
                String phrase = null, error = null;
                try {
                    org.minima.utils.json.JSONObject res = r.revealSeed();
                    Object okv = res.get("ok");
                    if (okv instanceof Boolean && (Boolean) okv) {
                        phrase = String.valueOf(res.get("phrase"));
                    } else {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fp = phrase, fe = error;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (fp == null || fp.isEmpty()) {
                        Toast.makeText(CloudSettingsActivity.this,
                                tr(R.string.l10n_native_dialog_fetch_seed_failed, fe), Toast.LENGTH_LONG).show();
                        return;
                    }
                    showSeedDialog(fp);
                });
            }
            public void err(String m) {
                runOnUiThread(() -> Toast.makeText(CloudSettingsActivity.this,
                        tr(R.string.l10n_native_dialog_node_failed, m), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** FLAG_SECURE numbered reveal + sensitive 60s-self-clearing copy — the app's exact rules. */
    private void showSeedDialog(String zPhrase) {
        String[] words = zPhrase.trim().split("\\s+");
        StringBuilder left = new StringBuilder(), right = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            (i < (words.length + 1) / 2 ? left : right)
                    .append(i + 1).append(". ").append(words[i]).append('\n');
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = PortalUi.dp(this, 20);
        row.setPadding(pad, PortalUi.dp(this, 10), pad, 0);
        for (String col : new String[]{left.toString(), right.toString()}) {
            TextView t = new TextView(this);
            Language.setText(t, col.trim());
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setTextSize(14);
            t.setTextIsSelectable(true);
            t.setTextColor(getColor(R.color.ux_text));
            row.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        androidx.appcompat.app.AlertDialog d = new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_native_dialog_your_words))
                .setView(row)
                .setPositiveButton(Language.msg(R.string.l10n_language_close), null)
                .setNeutralButton(Language.msg(R.string.l10n_common_copy), null)
                .create();
        // FLAG_SECURE BEFORE show() — no capturable first frame (the app's discipline).
        if (d.getWindow() != null) {
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        d.show();
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> copySeed(zPhrase));   // does NOT dismiss
    }

    private void copySeed(String zPhrase) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip =
                android.content.ClipData.newPlainText("seed", zPhrase);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, tr(R.string.l10n_native_dialog_clipboard_clear), Toast.LENGTH_SHORT).show();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.content.ClipData cur = cm.getPrimaryClip();
                if (cur != null && cur.getItemCount() > 0
                        && zPhrase.contentEquals(cur.getItemAt(0).coerceToText(this))) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
                }
            } catch (Exception ignored) {
            }
        }, 60_000);
    }

    private byte[] mPendingBackup;   // blob awaiting the SAF location

    private final androidx.activity.result.ActivityResultLauncher<String> mSaveBackup =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts
                            .CreateDocument("application/octet-stream"),
                    uri -> {
                        byte[] blob = mPendingBackup;
                        mPendingBackup = null;
                        if (uri == null) {
                            return;   // user cancelled the picker
                        }
                        if (blob == null) {
                            Toast.makeText(this, tr(R.string.l10n_native_dialog_backup_expired),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        try (java.io.OutputStream os =
                                     getContentResolver().openOutputStream(uri)) {
                            os.write(blob);
                            Toast.makeText(this, tr(R.string.l10n_native_dialog_backup_saved), Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(this, tr(R.string.l10n_native_dialog_backup_failed, e.getMessage()),
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    private void promptBackupPassphrase() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = PortalUi.dp(this, 20);
        box.setPadding(pad, PortalUi.dp(this, 8), pad, 0);
        final android.widget.EditText p1 = new android.widget.EditText(this);
        Language.hint(p1, Language.msg(R.string.l10n_native_dialog_backup_passphrase));
        p1.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p1);
        final android.widget.EditText p2 = new android.widget.EditText(this);
        Language.hint(p2, Language.msg(R.string.l10n_native_dialog_repeat_passphrase));
        p2.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p2);
        new LanguageDialog(this)
                .setTitle(Language.msg(R.string.l10n_native_dialog_backup_account))
                .setMessage(Language.msg(R.string.l10n_native_dialog_backup_warning))
                .setView(box)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_native_dialog_create_backup), (d, w) -> {
                    String a = p1.getText().toString();
                    String b = p2.getText().toString();
                    if (a.length() < 6) {
                        Toast.makeText(this, tr(R.string.l10n_native_dialog_six_characters),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!a.equals(b)) {
                        Toast.makeText(this, tr(R.string.l10n_native_dialog_passphrase_mismatch),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    exportBackup(a);
                })
                .show();
    }

    private void exportBackup(final String zPassphrase) {
        Toast.makeText(this, tr(R.string.l10n_native_dialog_building_backup), Toast.LENGTH_SHORT).show();
        CloudSession.connectInteractive(this, new CloudSession.Cb() {
            public void ok(com.eurobuddha.maxima.cloud.ParlonsRemote r) {
                byte[] blob = null;
                String error = null;
                try {
                    org.minima.utils.json.JSONObject res = r.backupExport(zPassphrase);
                    Object okv = res.get("ok");
                    if (okv instanceof Boolean && (Boolean) okv) {
                        blob = java.util.Base64.getDecoder()
                                .decode(String.valueOf(res.get("blob")));
                    } else {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final byte[] fb = blob;
                final String fe = error;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (fb == null) {
                        Toast.makeText(CloudSettingsActivity.this,
                                tr(R.string.l10n_native_dialog_backup_failed, fe), Toast.LENGTH_LONG).show();
                        return;
                    }
                    mPendingBackup = fb;
                    mSaveBackup.launch("parlons-cloud-backup.pbk");
                });
            }
            public void err(String m) {
                runOnUiThread(() -> Toast.makeText(CloudSettingsActivity.this,
                        tr(R.string.l10n_native_dialog_node_failed, m), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadSettings() {
        CloudSession.connect(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                Boolean rr = null;
                try {
                    JSONObject res = r.settings();
                    Object v = res.get("readReceipts");
                    if (v instanceof Boolean) {
                        rr = (Boolean) v;
                    }
                } catch (Exception ignored) {
                }
                final Boolean frr = rr;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (frr != null) {
                        mApplying = true;
                        mReceipts.setChecked(frr);
                        mApplying = false;
                        mReceipts.setEnabled(true);
                    }
                });
            }
            public void err(String m) {
            }
        });
    }

    private void applyReceipts(final boolean zOn) {
        CloudSession.connect(this, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.setReadReceipts(zOn);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (err != null) {
                        mApplying = true;
                        mReceipts.setChecked(!zOn);   // roll the switch back — be honest
                        mApplying = false;
                        Toast.makeText(CloudSettingsActivity.this,
                                tr(R.string.l10n_native_chat_operation_failed, SystemMessages.text(CloudSettingsActivity.this, err)), Toast.LENGTH_LONG).show();
                    }
                });
            }
            public void err(String m) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    mApplying = true;
                    mReceipts.setChecked(!zOn);
                    mApplying = false;
                    Toast.makeText(CloudSettingsActivity.this,
                            tr(R.string.l10n_native_dialog_node_failed, m), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
    }

    private String tr(int resource, Object... arguments) {
        return Language.text(this, resource, arguments);
    }

}
