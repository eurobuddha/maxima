package com.eurobuddha.maxima.app.portal.wallet;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.Set;

/** Settings: theme picker (all four Design modes), hidden-tokens manager, pairing status, about. */
public final class SettingsDialog {

    private SettingsDialog() {}

    public static void show(final WalletActivity act) {
        final android.app.Dialog[] dlgRef = new android.app.Dialog[1];
        final Sheet sheet = Sheet.create(act, "Settings");
        LinearLayout box = sheet.body();

        // ---- amount display ----
        box.addView(section(act, "DECIMAL PLACES"));
        TextView dnote = new TextView(act);
        dnote.setText("Minima carries up to 44 decimal places. Lists are trimmed to this many; "
                + "detail sheets and transaction confirmations always show the exact figure.");
        dnote.setTextColor(Design.dim());
        dnote.setTextSize(11f);
        box.addView(dnote);

        RadioGroup dgroup = new RadioGroup(act);
        final int[] places = {2, 4, 8, Format.FULL};
        for (final int d : places) {
            RadioButton rb = new RadioButton(act);
            rb.setText(Format.label(d));
            rb.setTextColor(Design.text());
            rb.setTextSize(13f);
            rb.setChecked(Format.decimals() == d);
            rb.setOnClickListener(v -> {
                Format.setDecimals(act, d);
                act.refreshAll();           // repaint every list with the new precision
            });
            dgroup.addView(rb);
        }
        box.addView(dgroup);

        // ---- hidden tokens ----
        box.addView(section(act, "HIDDEN TOKENS"));
        Set<String> hidden = HiddenTokens.all(act);
        if (hidden.isEmpty()) {
            TextView none = new TextView(act);
            none.setText("None. Hide a token from its detail sheet on the Balances tab.");
            none.setTextColor(Design.dim());
            none.setTextSize(12f);
            box.addView(none);
        } else {
            for (final String tid : hidden) {
                LinearLayout r = new LinearLayout(act);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(0, dp(act, 5), 0, dp(act, 5));
                TextView t = new TextView(act);
                String name = tid;
                for (TokenBalance b : act.balances()) {
                    if (b.tokenid.equals(tid)) { name = b.name + "  ·  " + Util.shorten(tid); break; }
                }
                if (name.equals(tid)) name = Util.shorten(tid);
                t.setText(name);
                t.setTextColor(Design.text());
                t.setTextSize(12f);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView un = new TextView(act);
                un.setText("UNHIDE");
                un.setTextColor(Design.accent());
                un.setTextSize(10f);
                un.setLetterSpacing(0.08f);
                un.setPadding(dp(act, 8), dp(act, 4), 0, dp(act, 4));
                un.setOnClickListener(v -> {
                    HiddenTokens.unhide(act, tid);
                    r.setAlpha(0.3f);
                    un.setText("UNHIDDEN");
                    act.refreshAll();
                });
                r.addView(un);
                box.addView(r);
            }
        }

        // ---- node ----
        box.addView(section(act, "NODE"));
        NodeApi node = act.node();
        TextView n = new TextView(act);
        boolean paired = node != null && node.isEnabled();
        long ok = node == null ? 0 : node.lastOkMs();
        String last = ok <= 0 ? "never" : ((System.currentTimeMillis() - ok) / 1000) + "s ago";
        n.setText((paired ? "✓ This device is paired to the account (its Parlons Node signs)" : "◦ Not paired — pair this device on the Node tab")
                + "\nLast node reply: " + last
                + "\nIPC: broadcast Intent · bounded paging · large replies via file hand-off");
        n.setTextColor(paired ? Design.success() : Design.amber());
        n.setTextSize(12f);
        box.addView(n);

        // ---- about ----
        box.addView(section(act, "ABOUT"));
        TextView a = new TextView(act);
        String ver;
        try { ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Exception e) { ver = "?"; }
        a.setText("Parlons Cloud wallet v" + ver + " — your node's wallet, on your phone.\n"
                + "Tokens · NFTs · State NFT locked editions.\n"
                + "Java + classic Views · node access only via minimaapi broadcast IPC.");
        a.setTextColor(Design.dim());
        a.setTextSize(12f);
        box.addView(a);

        sheet.action("Close", Sheet.Style.SECONDARY, null);
        dlgRef[0] = sheet.show();
    }

    private static TextView section(WalletActivity act, String label) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(act, 14), 0, dp(act, 6));
        return t;
    }

    private static int dp(WalletActivity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
