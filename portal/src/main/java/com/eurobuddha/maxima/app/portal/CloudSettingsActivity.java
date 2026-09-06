package com.eurobuddha.maxima.app.portal;

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
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];

        for (final String h : PortalRelayStore.get(c)) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView t = new TextView(c);
            t.setText(h + (com.eurobuddha.maxima.core.session.SeedRelays.isBuiltIn(h)
                    ? "  · built-in" : "  · yours"));
            t.setTextColor(getColor(R.color.ux_text));
            t.setTextSize(12);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView rm = PortalUi.ghost(c, "Drop");
            rm.setTextColor(getColor(R.color.ux_error));
            rm.setOnClickListener(v -> {
                if (PortalRelayStore.remove(c, h)) {
                    android.widget.Toast.makeText(c, "That was your last relay - the built-in list is back on",
                            android.widget.Toast.LENGTH_LONG).show();
                }
                if (dlg[0] != null) dlg[0].dismiss();
                showRelays();
            });
            row.addView(rm);
            body.addView(row);
        }
        if (PortalRelayStore.get(c).isEmpty()) {
            body.addView(PortalUi.label(c, "No relays configured."));
        }

        body.addView(PortalUi.gap(c, 10));
        final android.widget.EditText add = new android.widget.EditText(c);
        add.setHint("host:port, or paste a relay QR text");
        add.setSingleLine(true);
        add.setTextSize(13);
        add.setTextColor(getColor(R.color.ux_text));
        add.setHintTextColor(getColor(R.color.ux_subtext));
        body.addView(add);
        TextView addBtn = PortalUi.button(c, "Add");
        addBtn.setOnClickListener(v -> {
            java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(add.getText().toString());
            if (hs.isEmpty()) {
                android.widget.Toast.makeText(c, "Enter host:port, or paste a relay QR text",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            for (String h : hs) PortalRelayStore.add(c, h);
            if (dlg[0] != null) dlg[0].dismiss();
            showRelays();
        });
        body.addView(addBtn);
        TextView scan = PortalUi.ghost(c, "Scan a relay QR");
        scan.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            scanRelayQr(text -> {
                java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(text);
                if (hs.isEmpty()) {
                    android.widget.Toast.makeText(c, "That QR is not a relay", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    for (String h : hs) PortalRelayStore.add(c, h);
                    android.widget.Toast.makeText(c, "Added " + hs.size() + " relay(s)", android.widget.Toast.LENGTH_SHORT).show();
                }
                showRelays();
            });
        });
        body.addView(scan);

        body.addView(PortalUi.gap(c, 10));
        LinearLayout sw = new LinearLayout(c);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView swLabel = PortalUi.label(c, "Use built-in relays (the list shipped with the app)");
        sw.addView(swLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        androidx.appcompat.widget.SwitchCompat toggle = new androidx.appcompat.widget.SwitchCompat(c);
        toggle.setChecked(PortalRelayStore.builtInEnabled(c));
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (!PortalRelayStore.setBuiltInEnabled(c, on)) {
                android.widget.Toast.makeText(c, "Add a relay of your own first - the phone needs somewhere to start",
                        android.widget.Toast.LENGTH_LONG).show();
                b.setChecked(true);
            }
        });
        sw.addView(toggle);
        body.addView(sw);
        body.addView(PortalUi.label(c, "Changes apply on the next reconnect. Relays this phone has "
                + "already learned from the network are kept either way."));

        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(body);
        dlg[0] = new android.app.AlertDialog.Builder(this)
                .setTitle("Relays this phone starts from")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", (d, w) -> {
                    PortalRelayStore.reset(c);
                    android.widget.Toast.makeText(c, "Back to the built-in list", android.widget.Toast.LENGTH_SHORT).show();
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
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(getColor(R.color.ux_on_header));
        back.setPadding(0, 0, PortalUi.dp(c, 18), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(c);
        title.setText("Settings");
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.ux_on_header));
        bar.addView(title);
        root.addView(bar);

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int bp = PortalUi.dp(c, 14);
        body.setPadding(bp, bp, bp, bp);

        // --- account settings (stored on the node — follow you to every device) ---
        body.addView(PortalUi.section(c, "Your account"));
        LinearLayout acc = PortalUi.card(c);
        LinearLayout rrRow = new LinearLayout(c);
        rrRow.setOrientation(LinearLayout.HORIZONTAL);
        rrRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout rrCol = new LinearLayout(c);
        rrCol.setOrientation(LinearLayout.VERTICAL);
        TextView rrT = PortalUi.title(c, "Send read receipts");
        rrCol.addView(rrT);
        rrCol.addView(PortalUi.label(c,
                "Let people see when you've read their messages. Set on your node — applies "
                        + "from every device."));
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
        body.addView(PortalUi.section(c, "Privacy & security"));
        LinearLayout sec = PortalUi.card(c);
        boolean lockAvail = com.eurobuddha.maxima.app.AppLock.isAvailable(c);

        LinearLayout lockRow = new LinearLayout(c);
        lockRow.setOrientation(LinearLayout.HORIZONTAL);
        lockRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout lockCol = new LinearLayout(c);
        lockCol.setOrientation(LinearLayout.VERTICAL);
        lockCol.addView(PortalUi.title(c, "App lock"));
        lockCol.addView(PortalUi.label(c, "Require your fingerprint or device PIN to open "
                + "Parlons Cloud."));
        lockRow.addView(lockCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mAppLock = new Switch(c);
        mAppLock.setChecked(com.eurobuddha.maxima.app.AppLock.isEnabled(c));
        mAppLock.setEnabled(lockAvail);
        lockRow.addView(mAppLock);
        sec.addView(lockRow);
        if (!lockAvail) {
            sec.addView(PortalUi.label(c, "Set up a fingerprint or screen lock in your phone's "
                    + "settings first."));
        }

        sec.addView(PortalUi.gap(c, 12));

        LinearLayout ssRow = new LinearLayout(c);
        ssRow.setOrientation(LinearLayout.HORIZONTAL);
        ssRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ssCol = new LinearLayout(c);
        ssCol.setOrientation(LinearLayout.VERTICAL);
        ssCol.addView(PortalUi.title(c, "Allow screen sharing"));
        ssCol.addView(PortalUi.label(c, "On by default. Turn off to hide the app from "
                + "screenshots and screen recording while it's locked."));
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
        body.addView(PortalUi.section(c, "Notifications"));
        LinearLayout notifCard = PortalUi.card(c);
        LinearLayout sndRow = new LinearLayout(c);
        sndRow.setOrientation(LinearLayout.HORIZONTAL);
        sndRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sndCol = new LinearLayout(c);
        sndCol.setOrientation(LinearLayout.VERTICAL);
        sndCol.addView(PortalUi.title(c, "Message sound"));
        sndCol.addView(PortalUi.label(c, "Play the Parlons chime for new messages. Off keeps the "
                + "notification but stays silent (it still vibrates)."));
        sndRow.addView(sndCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mMsgSound = new Switch(c);
        mMsgSound.setChecked(PortalNotifier.messageSound(c));
        mMsgSound.setOnCheckedChangeListener((v, on) -> PortalNotifier.setMessageSound(c, on));
        sndRow.addView(mMsgSound);
        notifCard.addView(sndRow);
        body.addView(notifCard);

        // --- keys & backup: the identity lifecycle, app-parity discipline ---
        body.addView(PortalUi.section(c, "Keys & backup"));
        LinearLayout keys = PortalUi.card(c);
        keys.addView(PortalUi.label(c, "The 24 words on your node are your identity AND a "
                + "spendable Minima wallet seed. Back them up like money."));
        keys.addView(PortalUi.gap(c, 10));
        TextView showSeed = PortalUi.ghost(c, "Show seed phrase");
        showSeed.setTextColor(c.getColor(R.color.ux_error));
        showSeed.setOnClickListener(v -> confirmShowSeed());
        keys.addView(showSeed);
        keys.addView(PortalUi.gap(c, 8));
        TextView backup = PortalUi.button(c, "Back up account…");
        backup.setOnClickListener(v -> promptBackupPassphrase());
        keys.addView(backup);
        keys.addView(PortalUi.gap(c, 8));
        keys.addView(PortalUi.label(c, "Encrypted with your passphrase before it leaves the "
                + "node. The portable account bundle: identity, paired devices, settings, "
                + "contacts and chat history (same .pbk format the phone app reads)."));
        keys.addView(PortalUi.gap(c, 10));
        keys.addView(PortalUi.label(c, "Move this account to ANY node or server with the same "
                + "MAX#: on the new host run\n"
                + "java -jar parlons-cloud.jar --restore backup.pbk\n"
                + "or, on a Parlons Node, java -Dparlons.restore=backup.pbk -jar parlons-node.jar\n"
                + "with the old host stopped for good (one identity, one live account). Your "
                + "paired devices reconnect on their own."));
        body.addView(keys);

        // --- Minima Core on this phone — the same "link to core" path phone users know.
        //     A full node on the phone is your own chain view + an independent wallet home
        //     (resync your wallet to a new seed there; the cloud seed stays your identity). ---
        body.addView(PortalUi.section(c, "Minima Core on this phone"));
        LinearLayout core = PortalUi.card(c);
        boolean coreInstalled;
        try {
            getPackageManager().getPackageInfo("org.minimarex.minimacore", 0);
            coreInstalled = true;
        } catch (Exception e) {
            coreInstalled = false;
        }
        if (coreInstalled) {
            core.addView(PortalUi.title(c, "Minima Core is installed ✓"));
            core.addView(PortalUi.label(c,
                    "Your phone runs a full Minima node — your own chain view, and the home "
                            + "for a wallet independent of your cloud identity."));
            TextView openCore = PortalUi.ghost(c, "Open Minima Core");
            openCore.setOnClickListener(v -> {
                Intent i = getPackageManager()
                        .getLaunchIntentForPackage("org.minimarex.minimacore");
                if (i != null) {
                    startActivity(i);
                }
            });
            LinearLayout.LayoutParams oclp = PortalUi.matchWrap(c);
            oclp.topMargin = PortalUi.dp(c, 10);
            openCore.setLayoutParams(oclp);
            core.addView(openCore);
        } else {
            core.addView(PortalUi.title(c, "Run a full node on this phone"));
            core.addView(PortalUi.label(c,
                    "Install Minima Core to run a full Minima node right here — your own view "
                            + "of the chain, and a wallet you can resync to a fresh seed, fully "
                            + "independent of your cloud identity. Strengthens the network too."));
            TextView getCore = PortalUi.button(c, "Get Minima Core");
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
        body.addView(PortalUi.section(c, "This device"));
        LinearLayout dev = PortalUi.card(c);
        TextView relays = PortalUi.title(c, "Relays this phone starts from");
        relays.setOnClickListener(v -> showRelays());
        dev.addView(relays);
        dev.addView(PortalUi.label(c, "Where this phone first looks for your account. Add your "
                + "own (type, paste, or scan a relay QR); the built-in list can be switched off."));
        dev.addView(PortalUi.gap(c, 12));
        TextView notif = PortalUi.title(c, "Notification settings");
        notif.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        });
        dev.addView(notif);
        dev.addView(PortalUi.label(c, "Sounds, vibration and channels — handled by Android."));

        // Battery exemption — the portal must stay live on the push channel; battery optimisation
        // can kill the foreground service. Only offer it when NOT already exempt.
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (!exempt) {
            dev.addView(PortalUi.gap(c, 12));
            TextView batt = PortalUi.title(c, "Keep running in the background");
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
            dev.addView(PortalUi.label(c, "Let Parlons Cloud stay connected so messages and calls "
                    + "arrive instantly, even when the screen is off."));
        }
        body.addView(dev);

        // --- about ---
        body.addView(PortalUi.section(c, "About"));
        LinearLayout about = PortalUi.card(c);
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.addView(PortalUi.kv(c, "Portal version", "v" + ver));
        about.addView(PortalUi.gap(c, 8));
        about.addView(PortalUi.label(c, "Your account address (share it — it never changes):"));
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
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Show seed phrase?")
                .setMessage("These 24 words ARE your account and its wallet. Make sure nobody "
                        + "is looking. They travel to this phone over the encrypted channel.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Show", (d, w) -> fetchAndShowSeed())
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
                                "Couldn't fetch the seed: " + fe, Toast.LENGTH_LONG).show();
                        return;
                    }
                    showSeedDialog(fp);
                });
            }
            public void err(String m) {
                runOnUiThread(() -> Toast.makeText(CloudSettingsActivity.this,
                        "Couldn't reach your node: " + m, Toast.LENGTH_LONG).show());
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
            t.setText(col.trim());
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setTextSize(14);
            t.setTextIsSelectable(true);
            t.setTextColor(getColor(R.color.ux_text));
            row.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        androidx.appcompat.app.AlertDialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Your 24 words")
                .setView(row)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy", null)
                .create();
        // FLAG_SECURE BEFORE show() — no capturable first frame (the app's discipline).
        if (d.getWindow() != null) {
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        d.setOnShowListener(x -> d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> copySeed(zPhrase)));   // does NOT dismiss
        d.show();
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
        Toast.makeText(this, "Copied — clipboard clears in 60s", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(this, "Backup expired — export again",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        try (java.io.OutputStream os =
                                     getContentResolver().openOutputStream(uri)) {
                            os.write(blob);
                            Toast.makeText(this, "Backup saved", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "Backup failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    private void promptBackupPassphrase() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = PortalUi.dp(this, 20);
        box.setPadding(pad, PortalUi.dp(this, 8), pad, 0);
        final android.widget.EditText p1 = new android.widget.EditText(this);
        p1.setHint("Passphrase (min 6 characters)");
        p1.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p1);
        final android.widget.EditText p2 = new android.widget.EditText(this);
        p2.setHint("Repeat passphrase");
        p2.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p2);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Back up account")
                .setMessage("Choose a passphrase for the backup file. It is NOT recoverable "
                        + "if you lose it.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create backup", (d, w) -> {
                    String a = p1.getText().toString();
                    String b = p2.getText().toString();
                    if (a.length() < 6) {
                        Toast.makeText(this, "Use at least 6 characters",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!a.equals(b)) {
                        Toast.makeText(this, "Passphrases don't match",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    exportBackup(a);
                })
                .show();
    }

    private void exportBackup(final String zPassphrase) {
        Toast.makeText(this, "Building encrypted backup…", Toast.LENGTH_SHORT).show();
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
                                "Backup failed: " + fe, Toast.LENGTH_LONG).show();
                        return;
                    }
                    mPendingBackup = fb;
                    mSaveBackup.launch("parlons-cloud-backup.pbk");
                });
            }
            public void err(String m) {
                runOnUiThread(() -> Toast.makeText(CloudSettingsActivity.this,
                        "Couldn't reach your node: " + m, Toast.LENGTH_LONG).show());
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
                                "Couldn't change it: " + err, Toast.LENGTH_LONG).show();
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
                            "Couldn't reach your node: " + m, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
