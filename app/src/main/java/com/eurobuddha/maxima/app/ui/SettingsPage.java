package com.eurobuddha.maxima.app.ui;

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
        return "Settings";
    }

    @Override
    public void render() {
        MaximaNode node = MaximaService.node();
        // Either engine answers this - the jar's identity key shows the same way.
        com.eurobuddha.maxima.core.ChatPort port = MaximaService.port();
        mKeyValue.setText(port == null ? "…" : port.publicKeyHex());

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
        mPrivacy.addView(k.switchRow("Read receipts",
                rr ? "Contacts are told when you read their message"
                        : "Contacts see delivery only, never when you read",
                rr, checked -> {
                    ChatPrefs.setReadReceipts(mAct, checked);
                    EventLog.add("read receipts " + (checked ? "on" : "off"));
                    render();
                }));
        mPrivacy.addView(k.divider());
        boolean snd = ChatPrefs.messageSound(mAct);
        mPrivacy.addView(k.switchRow("Message sound",
                snd ? "Pssst! when a message arrives"
                        : "Silent - notifications still appear and vibrate",
                snd, checked -> {
                    ChatPrefs.setMessageSound(mAct, checked);
                    EventLog.add("message sound " + (checked ? "on" : "off"));
                    render();
                }));
        mPrivacy.addView(k.divider());
        mPrivacy.addView(k.kv("Delivery receipts",
                "Always on — the second tick is the transport doing its job",
                "ON", k.col(R.color.ux_success)));
        mPrivacy.addView(k.divider());
        boolean lockOn = com.eurobuddha.maxima.app.AppLock.isEnabled(mAct);
        mPrivacy.addView(k.switchRow("App lock",
                lockOn ? "Fingerprint or device PIN to open Parlons"
                        : (com.eurobuddha.maxima.app.AppLock.isAvailable(mAct)
                                ? "Require fingerprint or device PIN to open Parlons"
                                : "Set a screen lock on this device to use this"),
                lockOn, checked -> toggleAppLock(checked)));
        mPrivacy.addView(k.divider());
        boolean share = ChatPrefs.allowScreenShare(mAct);
        mPrivacy.addView(k.switchRow("Allow screen sharing",
                share ? "Screen can be mirrored, cast or screenshotted (needed for demos)"
                        : "Screen is hidden from mirroring/casting when App lock is on",
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
        mPower.addView(k.switchRow("Run as core node",
                isCore ? "Shown to contacts as an always-on core node"
                        : "Shown to contacts as an ordinary phone",
                isCore, checked -> {
                    ChatPrefs.setNodeKind(mAct, checked ? "core" : "");
                    EventLog.add("node type: " + (checked ? "core" : "phone"));
                    render();
                }));
        mPower.addView(k.divider());
        boolean jarOn = MaximaService.jarMode(mAct);
        mPower.addView(k.switchRow("Classic engine",
                jarOn ? "Routing runs on classic Maxima (maxima.jar)"
                        : "Routing runs on the built-in engine - flip for classic Maxima",
                jarOn, checked -> {
                    mAct.getSharedPreferences("maxima_relays",
                            android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("engine_jar", checked).apply();
                    EventLog.add("engine switch: "
                            + (checked ? "classic (jar)" : "built-in") + " - restarting");
                    android.widget.Toast.makeText(mAct,
                            "Switching engine - Parlons restarts in a moment",
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
        mPower.addView(k.kvPill("Battery exemption",
                exempt ? "Android will let the connection stay up"
                        : "Android may kill the connection — expect missed messages",
                exempt ? "granted" : "not set", exempt ? Kit.OK : Kit.WARN));
        if (!exempt) {
            TextView fix = k.ghostButton("Fix battery settings");
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
            mApps.addView(appRow(appLabel(pkg), pkg + " wants access", "pending", Kit.WARN,
                    () -> approveSheet(pkg)));
            firstApp = false;
        }
        for (String pkg : approved) {
            if (!firstApp) {
                mApps.addView(k.divider());
            }
            mApps.addView(appRow(appLabel(pkg), "Approved — tap to revoke", "allowed", Kit.OK,
                    () -> revokeDialog(pkg)));
            firstApp = false;
        }
        if (pending.isEmpty() && approved.isEmpty()) {
            mApps.addView(k.kv("None yet",
                    "No other app on this phone is using Maxima as its transport", "0", 0));
        }
    }

    private View appRow(String label, String hint, String pill, int kind, Runnable onTap) {
        LinearLayout row = k.kvPill(label, hint, pill, kind);
        row.setBackground(k.ripple());
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    private void approveSheet(String pkg) {
        new AlertDialog.Builder(mAct)
                .setTitle("Let " + appLabel(pkg) + " use Parlons?")
                .setMessage(pkg + " wants to send and receive messages through your Maxima "
                        + "identity, on its own application channel.")
                .setNegativeButton("Deny", (d, w) -> {
                    MaximaApiReceiver.deny(mAct, pkg);
                    render();
                })
                .setPositiveButton("Approve", (d, w) -> {
                    MaximaApiReceiver.approve(mAct, pkg);
                    render();
                })
                .show();
    }

    private void revokeDialog(String pkg) {
        new AlertDialog.Builder(mAct)
                .setTitle("Revoke " + pkg + "?")
                .setMessage("It will no longer be able to send or receive through your identity.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Revoke", (d, w) -> {
                    MaximaApiReceiver.revoke(mAct, pkg);
                    render();
                })
                .show();
    }

    private void toggleAppLock(boolean zWant) {
        if (zWant && !com.eurobuddha.maxima.app.AppLock.isAvailable(mAct)) {
            mAct.toast("Set a fingerprint or screen lock on this device first");
            render();
            return;
        }
        com.eurobuddha.maxima.app.AppLock.authenticate(mAct,
                zWant ? "Turn on app lock" : "Turn off app lock",
                "Confirm it's you",
                new com.eurobuddha.maxima.app.AppLock.Callback() {
                    public void onSuccess() {
                        com.eurobuddha.maxima.app.AppLock.setEnabled(mAct, zWant);
                        mAct.toast(zWant ? "App lock on" : "App lock off");
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
            mAct.toast("Enter a name");
            return;
        }
        SeedStore.setDisplayName(mAct, n);
        MaximaNode node = MaximaService.node();
        if (node != null) {
            node.setName(n);
            EventLog.add("name set to \"" + n + "\" - telling contacts");
            new Thread(node::refreshContacts, "refresh-name").start();
        }
        mAct.toast("Name set to " + n);
    }

    private void showPhrase() {
        new AlertDialog.Builder(mAct)
                .setTitle("Show seed phrase?")
                .setMessage("These 24 words ARE your identity and a spendable Minima wallet seed. "
                        + "Anyone who sees them controls both. Never share, screenshot, or type "
                        + "them into another app.\n\nMake sure nobody is looking.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reveal", (d, w) -> {
                    String phrase = SeedStore.revealPhrase(mAct);
                    AlertDialog reveal = new AlertDialog.Builder(mAct)
                            .setTitle("Write these down, offline")
                            .setMessage(phrase == null ? "(none)" : phrase)
                            .setNeutralButton("What is this?",
                                    (d2, w2) -> Explain.show(mAct, "seed"))
                            .setPositiveButton("Copy", (d2, w2) -> copySeedToClipboard(phrase))
                            .setNegativeButton("Close", null)
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
        mAct.toast("Copied — will auto-clear in 60s");
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
        input.setHint("paste your 24 words, separated by spaces");
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = k.dp(16);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(mAct)
                .setTitle("Restore a seed phrase")
                .setMessage("This REPLACES the identity on this phone with the one your words "
                        + "derive. Any contacts and chats stored here are cleared and the transport "
                        + "restarts. Make sure these are the right words.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> {
                    String phrase = input.getText().toString().trim();
                    if (phrase.isEmpty()) {
                        mAct.toast("Enter your phrase");
                        return;
                    }
                    try {
                        SeedStore.ImportResult r = SeedStore.importPhrase(mAct, phrase);
                        String warn = r.checksumValid ? ""
                                : "\n\nNote: no BIP39 checksum — normal for a phrase generated by "
                                + "a Minima node. Restored anyway.";
                        new AlertDialog.Builder(mAct)
                                .setTitle("Restored")
                                .setCancelable(false)
                                .setMessage("Your identity is restored." + warn
                                        + "\n\nRestarting the transport with it now.")
                                .setPositiveButton("OK", (d2, w2) -> applyRestoredIdentity())
                                .show();
                    } catch (Exception e) {
                        mAct.toast("Could not restore: " + e.getMessage());
                    }
                })
                .show();
    }

    private void applyRestoredIdentity() {
        mAct.stopService(new Intent(mAct, MaximaService.class));
        // Do NOT wipe on a fixed delay: onDestroy flushes node + chat state to
        // the very dirs we clear, so wiping before teardown finishes lets the old
        // identity be re-persisted on top of the restore. Wait for the service to
        // publish node()==null (its last teardown act), then wipe + restart.
        waitForTeardownThenRestore(0);
    }

    private void waitForTeardownThenRestore(int zAttempt) {
        boolean down = MaximaService.node() == null;
        if (down || zAttempt >= 40) {   // hard cap ~10s so we never hang
            wipeDir(new java.io.File(mAct.getFilesDir(), "node"));
            wipeDir(new java.io.File(mAct.getFilesDir(), "chat"));
            MaximaService.start(mAct);
            mName.setText(SeedStore.displayName(mAct));
            mAct.toast(down ? "Identity restored — reconnecting"
                    : "Identity restored — reconnecting (forced)");
            return;
        }
        mView.postDelayed(() -> waitForTeardownThenRestore(zAttempt + 1), 250);
    }

    private static void wipeDir(java.io.File zDir) {
        if (zDir == null || !zDir.exists()) {
            return;
        }
        java.io.File[] fs = zDir.listFiles();
        if (fs != null) {
            for (java.io.File f : fs) {
                if (f.isDirectory()) {
                    wipeDir(f);
                } else {
                    f.delete();
                }
            }
        }
        zDir.delete();
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
            mAct.toast("Could not open battery settings");
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
        // Your name.
        mBox.addView(k.sectionLabel("Your name"));
        LinearLayout nameCard = k.card();
        LinearLayout nameRow = new LinearLayout(mAct);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.setPadding(0, k.dp(6), 0, k.dp(6));
        mName = new EditText(mAct);
        mName.setText(SeedStore.displayName(mAct));
        mName.setSingleLine(true);
        mName.setBackgroundResource(R.drawable.field_pill);
        mName.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12));
        mName.setTextColor(k.col(R.color.ux_text));
        mName.setTextSize(14);
        mName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        nameRow.addView(mName, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView setBtn = k.primaryButton("Set");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = k.dp(9);
        nameRow.addView(setBtn, sp);
        setBtn.setOnClickListener(v -> setName());
        nameCard.addView(nameRow);
        LinearLayout keyField = k.copyField("maxima publickey", "…", "maxima publickey copied", false);
        mKeyValue = (TextView) keyField.getChildAt(1);
        nameCard.addView(keyField);
        mBox.addView(nameCard, k.mb(k.dp(4)));

        // Privacy.
        mBox.addView(k.sectionLabel("Privacy"));
        LinearLayout privCard = k.card();
        mPrivacy = new LinearLayout(mAct);
        mPrivacy.setOrientation(LinearLayout.VERTICAL);
        privCard.addView(mPrivacy);
        mBox.addView(privCard, k.mb(k.dp(4)));

        // Appearance (segmented).
        mBox.addView(k.sectionLabel("Appearance"));
        LinearLayout appCard = k.card();
        LinearLayout seg = new LinearLayout(mAct);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundResource(R.drawable.seg_track);
        int s4 = k.dp(4);
        seg.setPadding(s4, s4, s4, s4);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-1, -2);
        segLp.topMargin = k.dp(6);
        segLp.bottomMargin = k.dp(6);
        String[] labels = {"System", "Light", "Dark"};
        String[] values = {"system", "light", "dark"};
        for (int i = 0; i < 3; i++) {
            final String val = values[i];
            TextView s = new TextView(mAct);
            s.setText(labels[i]);
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
        mBox.addView(k.sectionLabel("Node & power"));
        LinearLayout powerCard = k.card();
        mPower = new LinearLayout(mAct);
        mPower.setOrientation(LinearLayout.VERTICAL);
        powerCard.addView(mPower);
        mBox.addView(powerCard, k.mb(k.dp(4)));

        // Keys & security.
        mBox.addView(k.sectionLabel("Keys & security"));
        LinearLayout secCard = k.card();
        secCard.addView(k.kv("Seed phrase",
                "24 words that are your identity AND a Minima wallet seed", "24 words",
                k.col(R.color.ux_subtext)));
        TextView show = k.dangerButton("Show seed phrase");
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-1, -2);
        shp.topMargin = k.dp(6);
        shp.bottomMargin = k.dp(8);
        secCard.addView(show, shp);
        show.setOnClickListener(v -> showPhrase());
        TextView restore = k.ghostButton("Restore a seed phrase");
        secCard.addView(restore);
        restore.setOnClickListener(v -> restore());
        mBox.addView(secCard, k.mb(k.dp(4)));

        // Apps (IPC).
        LinearLayout appsHead = new LinearLayout(mAct);
        appsHead.setOrientation(LinearLayout.HORIZONTAL);
        appsHead.setGravity(Gravity.CENTER_VERTICAL);
        appsHead.addView(k.sectionLabel("Apps using Maxima"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView q = new TextView(mAct);
        q.setText("?");
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
        mBox.addView(k.sectionLabel("About"));
        LinearLayout aboutCard = k.card();
        TextView about = new TextView(mAct);
        about.setText("Parlons is private, secure chat with nobody in the middle. Your messages go "
                + "straight from your device to theirs, end to end, through whichever independent "
                + "hosts are carrying traffic — no account, no phone number, no server holding your "
                + "history. Your keys and your wallet never leave this phone.\n\n"
                + "Powered by Maxima · Minima");
        about.setTextSize(12);
        about.setLineSpacing(k.dp(2), 1f);
        about.setTextColor(k.col(R.color.ux_subtext));
        about.setPadding(0, k.dp(4), 0, k.dp(2));
        aboutCard.addView(about);
        TextView ver = new TextView(mAct);
        ver.setText("Version " + appVersion());
        ver.setTextSize(12);
        ver.setTypeface(k.manrope(Typeface.BOLD));
        ver.setTextColor(k.col(R.color.ux_text));
        ver.setPadding(0, k.dp(8), 0, k.dp(2));
        aboutCard.addView(ver);
        mBox.addView(aboutCard, k.mb(k.dp(12)));
    }
}
