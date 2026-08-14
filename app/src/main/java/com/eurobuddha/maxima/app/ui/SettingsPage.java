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
        Set<String> approved = MaximaApiReceiver.approvedPackages(mAct);
        if (approved.isEmpty()) {
            Ui.stat(mAct, mApps, "None yet",
                    "No other app on this phone is using Maxima as its transport",
                    "0", "ipc");
        } else {
            for (String pkg : approved) {
                Ui.toggle(mAct, mApps, pkg, "Approved - tap to revoke", "allowed", "ipc",
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
