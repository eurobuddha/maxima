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
        mKeyValue.setText(node == null ? "…" : node.identity().publicKeyHex());

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
        mPrivacy.addView(k.kv("Delivery receipts",
                "Always on — the second tick is the transport doing its job",
                "ON", k.col(R.color.ux_success)));

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
                .setTitle("Let " + appLabel(pkg) + " use Maxima?")
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
                    new AlertDialog.Builder(mAct)
                            .setTitle("Write these down, offline")
                            .setMessage(phrase == null ? "(none)" : phrase)
                            .setNeutralButton("What is this?",
                                    (d2, w2) -> Explain.show(mAct, "seed"))
                            .setPositiveButton("Copy", (d2, w2) -> {
                                ClipboardManager cm =
                                        mAct.getSystemService(ClipboardManager.class);
                                cm.setPrimaryClip(ClipData.newPlainText("seed", phrase));
                                mAct.toast("Copied — clear your clipboard afterwards");
                            })
                            .setNegativeButton("Close", null)
                            .show();
                })
                .show();
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
        mView.postDelayed(() -> {
            wipeDir(new java.io.File(mAct.getFilesDir(), "node"));
            wipeDir(new java.io.File(mAct.getFilesDir(), "chat"));
            MaximaService.start(mAct);
            mName.setText(SeedStore.displayName(mAct));
            mAct.toast("Identity restored — reconnecting");
        }, 1500);
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
        LinearLayout keyField = k.copyField("Identity key", "…", "Identity key copied");
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
        about.setText("Maxima is a comms layer, not a service. Your messages go from your device "
                + "to theirs, through whichever independent hosts are carrying traffic. No account, "
                + "no phone number, no server holding your history.");
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
