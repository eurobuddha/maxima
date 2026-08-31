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

    private Switch mReceipts;
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
        TextView notif = PortalUi.title(c, "Notification settings");
        notif.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        });
        dev.addView(notif);
        dev.addView(PortalUi.label(c, "Sounds, vibration and channels — handled by Android."));
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
