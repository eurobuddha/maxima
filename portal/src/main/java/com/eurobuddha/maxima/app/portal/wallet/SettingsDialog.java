package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.R;
import static com.eurobuddha.maxima.app.i18n.Language.msg;

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
        final Sheet sheet = Sheet.create(act, msg(R.string.l10n_nav_settings));
        LinearLayout box = sheet.body();

        // ---- amount display ----
        box.addView(section(act, msg(R.string.l10n_node_wallet_decimals)));
        TextView dnote = new TextView(act);
        Language.setText(dnote, msg(R.string.l10n_node_wallet_precision_help));
        dnote.setTextColor(Design.dim());
        dnote.setTextSize(11f);
        box.addView(dnote);

        RadioGroup dgroup = new RadioGroup(act);
        final int[] places = {2, 4, 8, Format.FULL};
        for (final int d : places) {
            RadioButton rb = new RadioButton(act);
            Language.setText(rb, d == Format.FULL ? msg(R.string.l10n_node_wallet_full_precision) : Language.count(R.plurals.l10n_node_wallet_decimal_places, d));
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
        box.addView(section(act, msg(R.string.l10n_node_wallet_hidden_tokens)));
        Set<String> hidden = HiddenTokens.all(act);
        if (hidden.isEmpty()) {
            TextView none = new TextView(act);
            Language.setText(none, msg(R.string.l10n_node_wallet_none_hidden));
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
                Language.setText(t, name);
                t.setTextColor(Design.text());
                t.setTextSize(12f);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView un = new TextView(act);
                Language.setText(un, msg(R.string.l10n_node_wallet_unhide));
                un.setTextColor(Design.accent());
                un.setTextSize(10f);
                un.setLetterSpacing(0.08f);
                un.setPadding(dp(act, 8), dp(act, 4), 0, dp(act, 4));
                un.setOnClickListener(v -> {
                    HiddenTokens.unhide(act, tid);
                    r.setAlpha(0.3f);
                    Language.setText(un, msg(R.string.l10n_node_wallet_unhidden));
                    act.refreshAll();
                });
                r.addView(un);
                box.addView(r);
            }
        }

        // ---- node ----
        box.addView(section(act, msg(R.string.l10n_nav_node)));
        NodeApi node = act.node();
        TextView n = new TextView(act);
        boolean paired = node != null && node.isEnabled();
        long ok = node == null ? 0 : node.lastOkMs();
        CharSequence last = ok <= 0 ? msg(R.string.l10n_ios_never_seen) : Language.since(ok);
        Language.setText(n, Language.join(paired ? msg(R.string.l10n_node_wallet_paired) : msg(R.string.l10n_node_wallet_unpaired),
                "\n", msg(R.string.l10n_node_wallet_last_reply, last)));
        n.setTextColor(paired ? Design.success() : Design.amber());
        n.setTextSize(12f);
        box.addView(n);

        // ---- about ----
        box.addView(section(act, msg(R.string.l10n_native_settings_about)));
        TextView a = new TextView(act);
        String ver;
        try { ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Exception e) { ver = "?"; }
        Language.setText(a, msg(R.string.l10n_node_wallet_about, ver));
        a.setTextColor(Design.dim());
        a.setTextSize(12f);
        box.addView(a);

        sheet.action(msg(R.string.l10n_language_close), Sheet.Style.SECONDARY, null);
        dlgRef[0] = sheet.show();
    }

    private static TextView section(WalletActivity act, CharSequence label) {
        TextView t = new TextView(act);
        Language.setText(t, label);
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
