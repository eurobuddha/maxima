package com.eurobuddha.maxima.app.ui;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import com.eurobuddha.maxima.app.i18n.Language;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.ChatPrefs;
import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.IdentityRestore;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.SeedStore;
import com.eurobuddha.maxima.app.ipc.MaximaApiReceiver;
import com.eurobuddha.maxima.core.MaximaNode;

import java.util.Set;

/** Who you are, and what you are allowing — greyscale, on the shared {@link Kit}. */
public final class SettingsPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mBox;
    private final Kit k;

    private EditText mName;
    private TextView mKeyValue;
    private LinearLayout mPrivacy;
    private final TextView[] mAppearSeg = new TextView[3];
    private LinearLayout mPower;
    private LinearLayout mApps;
    /** Signature of the state the heavy sections render, to skip idle rebuilds. */
    private String mLastSig;

    public SettingsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mBox = zView.findViewById(R.id.settings_root);
        k = new Kit(zAct);
        buildUi();
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return com.eurobuddha.maxima.app.i18n.Language.msg(com.eurobuddha.maxima.app.R.string.l10n_nav_settings);
    }

    @Override
    public void render() {
        MaximaNode node = MaximaService.node();
        // Either engine answers this - the jar's identity key shows the same way.
        com.eurobuddha.maxima.core.ChatPort port = MaximaService.port();
        Language.setText(mKeyValue, port == null ? "…" : port.publicKeyHex());

        // The 2s heartbeat calls this repeatedly. Only the key field above is
        // cheap to refresh every tick; the sections below removeAllViews() +
        // rebuild, so skip that churn unless their inputs actually changed.
        String heavySig = (port == null ? "-" : port.publicKeyHex())
                + "|" + ChatPrefs.readReceipts(mAct)
                + "|" + ChatPrefs.messageSound(mAct)
                + "|" + com.eurobuddha.maxima.app.AppLock.isEnabled(mAct)
                + "|" + com.eurobuddha.maxima.app.AppLock.isAvailable(mAct)
                + "|" + ChatPrefs.appearance(mAct)
                + "|" + ChatPrefs.nodeKind(mAct)
                + "|" + com.eurobuddha.maxima.app.call.IncomingCallScreen.canShowFullScreen(mAct)
                + "|" + isBatteryExempt()
                + "|" + MaximaApiReceiver.pendingPackages(mAct)
                + "|" + MaximaApiReceiver.approvedPackages(mAct);
        if (heavySig.equals(mLastSig)) {
            return;
        }
        mLastSig = heavySig;

        // Privacy.
        mPrivacy.removeAllViews();
        boolean rr = ChatPrefs.readReceipts(mAct);
        mPrivacy.addView(k.switchRow(Language.msg(R.string.l10n_settings_read_receipts),
                rr ? Language.msg(R.string.l10n_native_settings_receipts_on)
                        : Language.msg(R.string.l10n_native_settings_receipts_off),
                rr, checked -> {
                    ChatPrefs.setReadReceipts(mAct, checked);
                    EventLog.add("read receipts " + (checked ? "on" : "off"));
                    render();
                }));
        mPrivacy.addView(k.divider());
        boolean snd = ChatPrefs.messageSound(mAct);
        mPrivacy.addView(k.switchRow(Language.msg(R.string.l10n_native_settings_sound),
                snd ? Language.msg(R.string.l10n_native_settings_sound_on)
                        : Language.msg(R.string.l10n_native_settings_sound_off),
                snd, checked -> {
                    ChatPrefs.setMessageSound(mAct, checked);
                    EventLog.add("message sound " + (checked ? "on" : "off"));
                    render();
                }));
        mPrivacy.addView(k.divider());
        boolean fullScreen = com.eurobuddha.maxima.app.call.IncomingCallScreen.canShowFullScreen(mAct);
        LinearLayout calls = k.kv(Language.msg(R.string.l10n_native_settings_incoming),
                fullScreen ? Language.msg(R.string.l10n_native_settings_incoming_allowed)
                        : Language.msg(R.string.l10n_native_settings_incoming_permission),
                fullScreen ? Language.msg(R.string.l10n_native_settings_settings_button) : Language.msg(R.string.l10n_native_settings_allow), 0);
        calls.setOnClickListener(v ->
                com.eurobuddha.maxima.app.call.IncomingCallScreen.openCallSettings(mAct));
        mPrivacy.addView(calls);
        mPrivacy.addView(k.divider());
        mPrivacy.addView(k.kv(Language.msg(R.string.l10n_native_settings_delivery),
                Language.msg(R.string.l10n_native_settings_delivery_help),
                Language.msg(R.string.l10n_native_settings_on), k.col(R.color.ux_success)));
        mPrivacy.addView(k.divider());
        boolean lockOn = com.eurobuddha.maxima.app.AppLock.isEnabled(mAct);
        mPrivacy.addView(k.switchRow(Language.msg(R.string.l10n_native_settings_lock),
                lockOn ? Language.msg(R.string.l10n_native_settings_lock_on)
                        : (com.eurobuddha.maxima.app.AppLock.isAvailable(mAct)
                                ? Language.msg(R.string.l10n_native_settings_lock_available)
                                : Language.msg(R.string.l10n_native_settings_lock_unavailable)),
                lockOn, checked -> toggleAppLock(checked)));
        mPrivacy.addView(k.divider());
        boolean share = ChatPrefs.allowScreenShare(mAct);
        mPrivacy.addView(k.switchRow(Language.msg(R.string.l10n_native_settings_screen_share),
                share ? Language.msg(R.string.l10n_native_settings_screen_share_on)
                        : Language.msg(R.string.l10n_native_settings_screen_share_off),
                share, checked -> {
                    ChatPrefs.setAllowScreenShare(mAct, checked);
                    EventLog.add("screen sharing " + (checked ? "allowed" : "blocked"));
                    // Re-apply FLAG_SECURE immediately for the live window.
                    if (checked) {
                        mAct.getWindow().clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    } else if (com.eurobuddha.maxima.app.AppLock.isEnabled(mAct)) {
                        mAct.getWindow().addFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    }
                    render();
                }));

        // Appearance segmented highlight.
        setAppearSeg(ChatPrefs.appearance(mAct));

        // Node & power.
        mPower.removeAllViews();
        boolean isCore = "core".equals(ChatPrefs.nodeKind(mAct));
        mPower.addView(k.switchRow(Language.msg(R.string.l10n_native_settings_core),
                isCore ? Language.msg(R.string.l10n_native_settings_core_on)
                        : Language.msg(R.string.l10n_native_settings_core_off),
                isCore, checked -> {
                    ChatPrefs.setNodeKind(mAct, checked ? "core" : "");
                    EventLog.add("node type: " + (checked ? "core" : "phone"));
                    render();
                }));
        mPower.addView(k.divider());
        boolean jarOn = MaximaService.jarMode(mAct);
        mPower.addView(k.switchRow(Language.msg(R.string.l10n_native_settings_classic),
                jarOn ? Language.msg(R.string.l10n_native_settings_classic_on)
                        : Language.msg(R.string.l10n_native_settings_classic_off),
                jarOn, checked -> {
                    if (!checked) {
                        // Leaving classic: carry its book across BEFORE the
                        // restart, or contacts added on the jar vanish.
                        com.eurobuddha.maxima.app.jar.JarEngine j = MaximaService.jar();
                        if (j != null) {
                            int x = com.eurobuddha.maxima.app.jar.JarMigration
                                    .exportContacts(mAct, j);
                            EventLog.add("engine sync: " + x
                                    + " contact(s) carried to the built-in book");
                        }
                    }
                    mAct.getSharedPreferences("maxima_relays",
                            android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("engine_jar", checked)
                            .putBoolean("engine_flip_announce", checked).apply();
                    EventLog.add("engine switch: "
                            + (checked ? "classic (jar)" : "built-in") + " - restarting");
                    android.widget.Toast.makeText(mAct,
                            tr(R.string.l10n_native_dialog_switch_engine),
                            android.widget.Toast.LENGTH_SHORT).show();
                    // The engine is wired at service creation; a clean process
                    // restart is the only honest way to swap it. Identity and
                    // contacts carry over (one-time migration, already proven).
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> {
                        android.content.Intent i = new android.content.Intent(
                                mAct, com.eurobuddha.maxima.app.MainActivity.class);
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        mAct.startActivity(i);
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }, 600);
                }));
        mPower.addView(k.divider());
        boolean exempt = isBatteryExempt();
        mPower.addView(k.kvPill(Language.msg(R.string.l10n_native_settings_battery),
                exempt ? Language.msg(R.string.l10n_native_settings_battery_on)
                        : Language.msg(R.string.l10n_native_settings_battery_off),
                exempt ? Language.msg(R.string.l10n_native_settings_granted) : Language.msg(R.string.l10n_native_settings_not_set), exempt ? Kit.OK : Kit.WARN));
        if (!exempt) {
            TextView fix = k.ghostButton(Language.msg(R.string.l10n_native_settings_battery_fix));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
            fp.topMargin = k.dp(10);
            mPower.addView(fix, fp);
            fix.setOnClickListener(v -> requestBatteryExemption());
        }

        // Apps (IPC).
        mApps.removeAllViews();
        Set<String> pending = MaximaApiReceiver.pendingPackages(mAct);
        Set<String> approved = MaximaApiReceiver.approvedPackages(mAct);
        boolean firstApp = true;
        for (String pkg : pending) {
            if (!firstApp) {
                mApps.addView(k.divider());
            }
            mApps.addView(appRow(appLabel(pkg), Language.msg(R.string.l10n_native_settings_app_wants_access, pkg), Language.msg(R.string.l10n_native_settings_app_pending), Kit.WARN,
                    () -> approveSheet(pkg)));
            firstApp = false;
        }
        for (String pkg : approved) {
            if (!firstApp) {
                mApps.addView(k.divider());
            }
            mApps.addView(appRow(appLabel(pkg), Language.msg(R.string.l10n_native_settings_app_approved), Language.msg(R.string.l10n_native_settings_app_allowed), Kit.OK,
                    () -> revokeDialog(pkg)));
            firstApp = false;
        }
        if (pending.isEmpty() && approved.isEmpty()) {
            mApps.addView(k.kv(Language.msg(R.string.l10n_native_settings_no_apps),
                    Language.msg(R.string.l10n_native_settings_no_apps_help), "0", 0));
        }
    }

    private View appRow(String label, CharSequence hint, CharSequence pill, int kind, Runnable onTap) {
        LinearLayout row = k.kvPill(label, hint, pill, kind);
        row.setBackground(k.ripple());
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    private void approveSheet(String pkg) {
        new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_dialog_approve_title, appLabel(pkg)))
                .setMessage(Language.msg(R.string.l10n_native_dialog_approve_help, pkg))
                .setNegativeButton(Language.msg(R.string.l10n_native_dialog_deny), (d, w) -> {
                    MaximaApiReceiver.deny(mAct, pkg);
                    render();
                })
                .setPositiveButton(Language.msg(R.string.l10n_common_approve), (d, w) -> {
                    MaximaApiReceiver.approve(mAct, pkg);
                    render();
                })
                .show();
    }

    private void revokeDialog(String pkg) {
        new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_dialog_revoke_title, pkg))
                .setMessage(Language.msg(R.string.l10n_native_dialog_revoke_help))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_common_revoke), (d, w) -> {
                    MaximaApiReceiver.revoke(mAct, pkg);
                    render();
                })
                .show();
    }

    private void toggleAppLock(boolean zWant) {
        if (zWant && !com.eurobuddha.maxima.app.AppLock.isAvailable(mAct)) {
            mAct.toast(tr(R.string.l10n_native_dialog_lock_setup));
            render();
            return;
        }
        com.eurobuddha.maxima.app.AppLock.authenticate(mAct,
                zWant ? tr(R.string.l10n_native_dialog_lock_enable) : tr(R.string.l10n_native_dialog_lock_disable),
                tr(R.string.l10n_native_dialog_confirm_you),
                new com.eurobuddha.maxima.app.AppLock.Callback() {
                    public void onSuccess() {
                        com.eurobuddha.maxima.app.AppLock.setEnabled(mAct, zWant);
                        mAct.toast(zWant ? tr(R.string.l10n_native_dialog_lock_enabled) : tr(R.string.l10n_native_dialog_lock_disabled));
                        render();
                    }

                    public void onError(String zMessage) {
                        mAct.toast(zMessage);
                        render();
                    }

                    public void onCancelled() {
                        render();
                    }
                });
    }

    private String appVersion() {
        try {
            return mAct.getPackageManager()
                    .getPackageInfo(mAct.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    mAct.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence l = mAct.getPackageManager().getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    private void setName() {
        String n = mName.getText().toString().trim();
        if (n.isEmpty()) {
            mAct.toast(tr(R.string.l10n_native_dialog_name_required));
            return;
        }
        SeedStore.setDisplayName(mAct, n);
        MaximaNode node = MaximaService.node();
        if (node != null) {
            node.setName(n);
            EventLog.add("name set to \"" + n + "\" - telling contacts");
            new Thread(node::refreshContacts, "refresh-name").start();
        }
        mAct.toast(tr(R.string.l10n_native_dialog_name_set, n));
    }

    private void showPhrase() {
        new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_dialog_show_seed_question))
                .setMessage(Language.msg(R.string.l10n_native_dialog_seed_warning))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_native_dialog_reveal), (d, w) -> {
                    String phrase = SeedStore.revealPhrase(mAct);
                    AlertDialog reveal = new LanguageDialog(mAct)
                            .setTitle(Language.msg(R.string.l10n_native_dialog_write_offline))
                            .setMessage(phrase == null ? Language.msg(R.string.l10n_native_dialog_none) : phrase)
                            .setNeutralButton(Language.msg(R.string.l10n_native_dialog_explain),
                                    (d2, w2) -> Explain.show(mAct, "seed"))
                            .setPositiveButton(Language.msg(R.string.l10n_common_copy), (d2, w2) -> copySeedToClipboard(phrase))
                            .setNegativeButton(Language.msg(R.string.l10n_language_close), null)
                            .create();
                    // The seed must never land in a screenshot or the recents thumbnail.
                    if (reveal.getWindow() != null) {
                        reveal.getWindow().addFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    }
                    reveal.show();
                })
                .show();
    }

    /** Copy the seed marked SENSITIVE, and auto-clear it after 60s if still ours. */
    private void copySeedToClipboard(String zPhrase) {
        if (zPhrase == null || zPhrase.isEmpty()) {
            return;
        }
        ClipboardManager cm = mAct.getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText("seed", zPhrase);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        mAct.toast(tr(R.string.l10n_native_dialog_seed_copied));
        mAct.getWindow().getDecorView().postDelayed(() -> {
            try {
                ClipData cur = cm.getPrimaryClip();
                if (cur != null && cur.getItemCount() > 0
                        && zPhrase.contentEquals(cur.getItemAt(0).coerceToText(mAct))) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        cm.clearPrimaryClip();
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                }
            } catch (Exception ignored) {
            }
        }, 60_000);
    }

    private boolean isBatteryExempt() {
        try {
            PowerManager pm = mAct.getSystemService(PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(mAct.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void restore() {
        final EditText input = new EditText(mAct);
        Language.hint(input, Language.msg(R.string.l10n_native_dialog_enter_seed));
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = k.dp(16);
        input.setPadding(pad, pad, pad, pad);
        android.widget.CheckBox anyPhrase = new android.widget.CheckBox(mAct);
        Language.setText(anyPhrase, Language.msg(R.string.l10n_native_dialog_custom_seed));
        android.widget.LinearLayout fields = new android.widget.LinearLayout(mAct);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        fields.addView(input);
        fields.addView(anyPhrase);

        AlertDialog restoreDialog = new LanguageDialog(mAct)
                .setTitle(Language.msg(R.string.l10n_native_settings_restore_seed))
                .setMessage(Language.join(Language.msg(R.string.l10n_native_dialog_restore_intro), Language.msg(R.string.l10n_security_key_use_warning)))
                .setView(fields)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_native_dialog_restore), (d, w) -> {
                    String phrase = input.getText().toString();
                    if (phrase.trim().isEmpty()) {
                        mAct.toast(tr(R.string.l10n_native_dialog_enter_phrase));
                        return;
                    }
                    try {
                        SeedStore.ImportResult r = SeedStore.importPhrase(mAct, phrase, anyPhrase.isChecked());
                        CharSequence warn = anyPhrase.isChecked() ? Language.msg(R.string.l10n_native_dialog_custom_restored)
                                : r.checksumValid ? ""
                                : Language.msg(R.string.l10n_native_dialog_no_checksum);
                        new LanguageDialog(mAct)
                                .setTitle(Language.msg(R.string.l10n_native_dialog_restored))
                                .setCancelable(false)
                                .setMessage(Language.join(Language.msg(R.string.l10n_native_dialog_identity_restored), warn,
                                        Language.msg(R.string.l10n_native_dialog_restart_transport)))
                                .setPositiveButton(Language.msg(R.string.l10n_native_dialog_ok), (d2, w2) -> applyRestoredIdentity())
                                .show();
                    } catch (Exception e) {
                        mAct.toast(tr(R.string.l10n_native_dialog_restore_failed, e.getMessage()));
                    }
                })
                .create();
        if (restoreDialog.getWindow() != null) {
            restoreDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        restoreDialog.show();
    }

    private void applyRestoredIdentity() {
        IdentityRestore.applyWithTeardown(mAct, mView, forced -> {
            Language.setText(mName, SeedStore.displayName(mAct));
            mAct.toast(forced ? tr(R.string.l10n_native_dialog_restore_forced)
                    : tr(R.string.l10n_native_dialog_restore_reconnecting));
        });
    }

    private void launchBackup(String zMode) {
        android.content.Intent i = new android.content.Intent(mAct,
                com.eurobuddha.maxima.app.backup.BackupActivity.class);
        i.putExtra(com.eurobuddha.maxima.app.backup.BackupActivity.EXTRA_MODE, zMode);
        mAct.startActivity(i);
    }

    private void requestBatteryExemption() {
        if (isBatteryExempt()) {
            Explain.show(mAct, "battery");
            return;
        }
        try {
            mAct.startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + mAct.getPackageName())));
        } catch (Exception e) {
            mAct.toast(tr(R.string.l10n_native_dialog_battery_failed));
        }
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private void setAppearSeg(String appearance) {
        int on = "light".equals(appearance) ? 1 : "dark".equals(appearance) ? 2 : 0;
        for (int i = 0; i < 3; i++) {
            boolean sel = i == on;
            mAppearSeg[i].setBackgroundResource(sel ? R.drawable.seg_thumb : 0);
            mAppearSeg[i].setTextColor(k.col(sel ? R.color.ux_text : R.color.ux_subtext));
        }
    }

    private void chooseAppearance(String value) {
        ChatPrefs.setAppearance(mAct, value);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(ChatPrefs.nightMode(mAct));
        setAppearSeg(value);
    }

    private void buildUi() {
        LinearLayout languageCard = k.card();
        TextView languageTitle = k.sub("");
        com.eurobuddha.maxima.app.i18n.Language.setText(languageTitle,
                com.eurobuddha.maxima.app.i18n.Language.msg(R.string.l10n_language_description));
        languageCard.addView(languageTitle);
        TextView languageButton = k.ghostButton("English · 简体中文 · Русский");
        languageButton.setOnClickListener(v -> com.eurobuddha.maxima.app.i18n.Language.choose(mAct, false));
        com.eurobuddha.maxima.app.i18n.Language.description(languageButton,
                com.eurobuddha.maxima.app.i18n.Language.msg(R.string.l10n_language_change));
        languageCard.addView(languageButton);
        mBox.addView(languageCard);

        // Your name.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_settings_your_name)));
        LinearLayout nameCard = k.card();
        LinearLayout nameRow = new LinearLayout(mAct);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.setPadding(0, k.dp(6), 0, k.dp(6));
        mName = new EditText(mAct);
        Language.setText(mName, SeedStore.displayName(mAct));
        mName.setSingleLine(true);
        mName.setBackgroundResource(R.drawable.field_pill);
        mName.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12));
        mName.setTextColor(k.col(R.color.ux_text));
        mName.setTextSize(14);
        mName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        nameRow.addView(mName, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView setBtn = k.primaryButton(Language.msg(R.string.l10n_native_settings_set));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = k.dp(9);
        nameRow.addView(setBtn, sp);
        setBtn.setOnClickListener(v -> setName());
        nameCard.addView(nameRow);
        LinearLayout keyField = k.copyField("maxima publickey", "…", Language.msg(R.string.l10n_native_settings_public_key_copied), false);
        mKeyValue = (TextView) keyField.getChildAt(1);
        nameCard.addView(keyField);
        mBox.addView(nameCard, k.mb(k.dp(4)));

        // Privacy.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_settings_privacy)));
        LinearLayout privCard = k.card();
        mPrivacy = new LinearLayout(mAct);
        mPrivacy.setOrientation(LinearLayout.VERTICAL);
        privCard.addView(mPrivacy);
        mBox.addView(privCard, k.mb(k.dp(4)));

        // Appearance (segmented).
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_settings_appearance)));
        LinearLayout appCard = k.card();
        LinearLayout seg = new LinearLayout(mAct);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundResource(R.drawable.seg_track);
        int s4 = k.dp(4);
        seg.setPadding(s4, s4, s4, s4);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-1, -2);
        segLp.topMargin = k.dp(6);
        segLp.bottomMargin = k.dp(6);
        int[] labels = {R.string.l10n_theme_system_label, R.string.l10n_theme_light_label, R.string.l10n_theme_dark_label};
        String[] values = {"system", "light", "dark"};
        for (int i = 0; i < 3; i++) {
            final String val = values[i];
            TextView s = new TextView(mAct);
            Language.setText(s, Language.msg(labels[i]));
            s.setGravity(Gravity.CENTER);
            s.setTextSize(13);
            s.setTypeface(Typeface.DEFAULT_BOLD);
            s.setPadding(0, k.dp(8), 0, k.dp(8));
            s.setOnClickListener(v -> chooseAppearance(val));
            mAppearSeg[i] = s;
            seg.addView(s, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        appCard.addView(seg, segLp);
        mBox.addView(appCard, k.mb(k.dp(4)));

        // Node & power.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_settings_node_power)));
        LinearLayout powerCard = k.card();
        mPower = new LinearLayout(mAct);
        mPower.setOrientation(LinearLayout.VERTICAL);
        powerCard.addView(mPower);
        mBox.addView(powerCard, k.mb(k.dp(4)));

        // Keys & security.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_settings_keys_security)));
        LinearLayout secCard = k.card();
        secCard.addView(k.kv(Language.msg(R.string.l10n_native_settings_seed),
                Language.msg(R.string.l10n_native_settings_seed_help), Language.msg(R.string.l10n_native_settings_seed_words),
                k.col(R.color.ux_subtext)));
        TextView show = k.dangerButton(Language.msg(R.string.l10n_native_settings_show_seed));
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-1, -2);
        shp.topMargin = k.dp(6);
        shp.bottomMargin = k.dp(8);
        secCard.addView(show, shp);
        show.setOnClickListener(v -> showPhrase());
        TextView restore = k.ghostButton(Language.msg(R.string.l10n_native_settings_restore_seed));
        secCard.addView(restore);
        restore.setOnClickListener(v -> restore());

        TextView backup = k.ghostButton(Language.msg(R.string.l10n_native_settings_backup_identity));
        LinearLayout.LayoutParams bkp = new LinearLayout.LayoutParams(-1, -2);
        bkp.topMargin = k.dp(8);
        secCard.addView(backup, bkp);
        backup.setOnClickListener(v -> launchBackup("export"));
        TextView restoreBk = k.ghostButton(Language.msg(R.string.l10n_native_settings_restore_backup));
        secCard.addView(restoreBk);
        restoreBk.setOnClickListener(v -> launchBackup("import"));

        mBox.addView(secCard, k.mb(k.dp(4)));

        // Apps (IPC).
        LinearLayout appsHead = new LinearLayout(mAct);
        appsHead.setOrientation(LinearLayout.HORIZONTAL);
        appsHead.setGravity(Gravity.CENTER_VERTICAL);
        appsHead.addView(k.sectionLabel(Language.msg(R.string.l10n_native_settings_apps)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView q = new TextView(mAct);
        Language.setText(q, "?");
        q.setTextSize(13);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setTextColor(k.col(R.color.ux_subtext));
        q.setPadding(k.dp(10), k.dp(4), k.dp(6), k.dp(4));
        q.setBackground(k.ripple());
        q.setOnClickListener(v -> Explain.show(mAct, "ipc"));
        appsHead.addView(q);
        mBox.addView(appsHead);
        LinearLayout appsCard = k.card();
        mApps = new LinearLayout(mAct);
        mApps.setOrientation(LinearLayout.VERTICAL);
        appsCard.addView(mApps);
        mBox.addView(appsCard, k.mb(k.dp(4)));

        // About.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_settings_about)));
        LinearLayout aboutCard = k.card();
        TextView about = new TextView(mAct);
        Language.setText(about, Language.msg(R.string.l10n_native_settings_about_text));
        about.setTextSize(12);
        about.setLineSpacing(k.dp(2), 1f);
        about.setTextColor(k.col(R.color.ux_subtext));
        about.setPadding(0, k.dp(4), 0, k.dp(2));
        aboutCard.addView(about);
        TextView ver = new TextView(mAct);
        Language.setText(ver, Language.msg(R.string.l10n_native_dialog_version, appVersion()));
        ver.setTextSize(12);
        ver.setTypeface(k.manrope(Typeface.BOLD));
        ver.setTextColor(k.col(R.color.ux_text));
        ver.setPadding(0, k.dp(8), 0, k.dp(2));
        aboutCard.addView(ver);
        mBox.addView(aboutCard, k.mb(k.dp(12)));
    }
    private String tr(int resource, Object... arguments) {
        return Language.text(mAct, resource, arguments);
    }

}
