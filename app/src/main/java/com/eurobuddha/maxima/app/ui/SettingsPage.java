package com.eurobuddha.maxima.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
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

/** Who you are, and what you are allowing. */
public final class SettingsPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final EditText mName;
    private final TextView mKey;
    private final LinearLayout mPrivacy;
    private final LinearLayout mSecurity;
    private final LinearLayout mApps;
    private final TextView mAbout;

    public SettingsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mName = zView.findViewById(R.id.name_field);
        mKey = zView.findViewById(R.id.identity_key);
        mPrivacy = zView.findViewById(R.id.settings_privacy);
        mSecurity = zView.findViewById(R.id.settings_security);
        mApps = zView.findViewById(R.id.apps_container);
        mAbout = zView.findViewById(R.id.about);

        mName.setText(SeedStore.displayName(mAct));
        zView.findViewById(R.id.q_name).setOnClickListener(v -> Explain.show(mAct, "name"));
        zView.findViewById(R.id.q_ipc).setOnClickListener(v -> Explain.show(mAct, "ipc"));
        zView.findViewById(R.id.btn_set_name).setOnClickListener(v -> setName());
        zView.findViewById(R.id.btn_phrase).setOnClickListener(v -> showPhrase());
        zView.findViewById(R.id.btn_restore).setOnClickListener(v -> restore());
        zView.findViewById(R.id.btn_battery).setOnClickListener(v -> requestBatteryExemption());

        mAbout.setText("Maxima is a comms layer, not a service. There is no company "
                + "in the middle: your messages go from your device to theirs, "
                + "through whichever independent hosts are carrying traffic.\n\n"
                + "It speaks the same protocol as Minima's classic Maxima, so a "
                + "classic node can relay for you and you can message classic "
                + "users - they just will not send you delivery receipts, because "
                + "classic cannot.\n\n"
                + "No account, no phone number, no server holding your history.");
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
        mKey.setText(node == null ? "" : node.identity().publicKeyHex());

        mPrivacy.removeAllViews();
        boolean rr = ChatPrefs.readReceipts(mAct);
        Ui.toggle(mAct, mPrivacy, "Read receipts",
                rr ? "Contacts are told when you read their message"
                        : "Contacts see delivery only, never when you read",
                rr ? "ON" : "off", "readreceipts", () -> {
                    boolean now = !ChatPrefs.readReceipts(mAct);
                    ChatPrefs.setReadReceipts(mAct, now);
                    EventLog.add("read receipts " + (now ? "on" : "off"));
                    render();
                });
        Ui.stat(mAct, mPrivacy, "Delivery receipts",
                "Always on. The second tick is the transport doing its job.",
                "ON", "ticks");

        mSecurity.removeAllViews();
        boolean exempt = isBatteryExempt();
        Ui.state(mAct, mSecurity, "Battery exemption",
                exempt ? "Android will let the connection stay up"
                        : "Android may kill the connection - expect missed messages",
                exempt ? "granted" : "NOT SET",
                Ui.colour(mAct, exempt ? R.color.ux_success : R.color.ux_pending),
                "battery");
        Ui.stat(mAct, mSecurity, "Seed phrase",
                "24 words that are your identity AND a Minima wallet seed",
                "24 words", "seed");

        mApps.removeAllViews();
        Set<String> pending = MaximaApiReceiver.pendingPackages(mAct);
        Set<String> approved = MaximaApiReceiver.approvedPackages(mAct);

        // Requests first: an app asked to use Maxima as its transport and is
        // waiting on the user. Approval is ALWAYS an explicit user action here -
        // REGISTER only records the ask.
        for (String pkg : pending) {
            Ui.toggle(mAct, mApps, appLabel(pkg) + " — WANTS ACCESS",
                    "Tap to approve or deny " + pkg, "pending", "ipc",
                    () -> new AlertDialog.Builder(mAct)
                            .setTitle("Let " + appLabel(pkg) + " use Maxima?")
                            .setMessage(pkg + " wants to send and receive messages "
                                    + "through your Maxima identity, on its own "
                                    + "application channel.")
                            .setNegativeButton("Deny", (d, w) -> {
                                MaximaApiReceiver.deny(mAct, pkg);
                                render();
                            })
                            .setPositiveButton("Approve", (d, w) -> {
                                MaximaApiReceiver.approve(mAct, pkg);
                                render();
                            })
                            .show());
        }

        if (approved.isEmpty() && pending.isEmpty()) {
            Ui.stat(mAct, mApps, "None yet",
                    "No other app on this phone is using Maxima as its transport",
                    "0", "ipc");
        } else {
            for (String pkg : approved) {
                Ui.toggle(mAct, mApps, appLabel(pkg), "Approved - tap to revoke", "allowed", "ipc",
                        () -> new AlertDialog.Builder(mAct)
                                .setTitle("Revoke " + pkg + "?")
                                .setMessage("It will no longer be able to send or receive "
                                        + "through your identity.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Revoke", (d, w) -> {
                                    MaximaApiReceiver.revoke(mAct, pkg);
                                    render();
                                })
                                .show());
            }
        }
    }

    /** The human app name for a package, falling back to the package id. */
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

    /** Classic: maxima action:setname. Contacts hold the old one until told. */
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

    /**
     * The phrase is a spendable wallet seed, so showing it is a deliberate act
     * with a warning attached, not a casual screen.
     */
    private void showPhrase() {
        new AlertDialog.Builder(mAct)
                .setTitle("Show seed phrase?")
                .setMessage("This phrase is ALSO a Minima wallet seed. Anyone who sees it "
                        + "can restore your identity AND spend any funds sent to it.\n\n"
                        + "Make sure nobody is looking.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Show", (d, w) -> {
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
                                mAct.toast("Copied - clear your clipboard afterwards");
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

    /**
     * Restore an existing Minima seed phrase, replacing this install's identity.
     *
     * This is the path back from a reinstall: the seed lives Keystore-encrypted
     * on-device and does NOT survive uninstall, so the 24 words are the only
     * portable backup. Pasting them here reproduces the exact same Maxima
     * identity (and the same address a Minima node would derive), so contacts
     * can reach you again.
     */
    private void restore() {
        final EditText input = new EditText(mAct);
        input.setHint("paste your 24 words, separated by spaces");
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = Ui.dp(mAct, 16);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(mAct)
                .setTitle("Restore a seed phrase")
                .setMessage("This REPLACES the identity on this phone with the one "
                        + "your words derive. Any contacts and chats stored here now "
                        + "are cleared - your restored identity re-establishes its own. "
                        + "Make sure these are the right words.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> {
                    String phrase = input.getText().toString().trim();
                    if (phrase.isEmpty()) {
                        mAct.toast("Enter your phrase");
                        return;
                    }
                    try {
                        // Validates every word (throws on an unknown one) and
                        // saves the phrase encrypted. Checksum is a warning only:
                        // a node-generated phrase legitimately has none.
                        SeedStore.ImportResult r = SeedStore.importPhrase(mAct, phrase);
                        String warn = r.checksumValid ? ""
                                : "\n\nNote: no BIP39 checksum - normal for a phrase "
                                + "generated by a Minima node. Restored anyway.";
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

    /**
     * Restart the service so it reloads the restored seed, wiping the old
     * identity's local node/chat state in the gap so nothing carries over.
     */
    private void applyRestoredIdentity() {
        mAct.stopService(new Intent(mAct, MaximaService.class));
        // Give onDestroy time to flush and release its files before we wipe,
        // then start fresh - onCreate reads the newly-stored phrase.
        mSecurity.postDelayed(() -> {
            wipeDir(new java.io.File(mAct.getFilesDir(), "node"));
            wipeDir(new java.io.File(mAct.getFilesDir(), "chat"));
            MaximaService.start(mAct);
            mName.setText(SeedStore.displayName(mAct));
            mAct.toast("Identity restored - reconnecting");
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
}
