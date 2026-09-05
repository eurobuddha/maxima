package com.eurobuddha.maxima.app.portal.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

/**
 * Full-granularity modal for a single UTXO: every field the node reports — coinid, address
 * (0x + Mx), amounts (token + raw Minima-scale), created block / age / confirmations, MMR entry,
 * store-state and spendability flags — plus every state variable, with an inline image preview
 * for embedded StateNFT art ({@code [base64]} at port 1). Every value is tap-to-copy.
 */
public final class CoinDetailDialog {

    /** Bracketed base64 — the StateNFT embedded-image shape for state values. */
    private static final Pattern EMBED = Pattern.compile("^\\[[A-Za-z0-9+/=]*\\]$");

    private CoinDetailDialog() {}

    public static void show(WalletActivity act, Coin c) {
        final Sheet sheet = Sheet.create(act, Util.tidyAmount(c.amount) + "  " + c.tokenName);
        LinearLayout box = sheet.body();

        // status chips line
        TextView chips = new TextView(act);
        StringBuilder st = new StringBuilder(c.sendable ? "SENDABLE" : "LOCKED / TRACK-ONLY");
        if (c.storestate) st.append("  ·  STORES STATE");
        if (c.spent) st.append("  ·  SPENT");
        chips.setText(st.toString());
        chips.setTextColor(c.sendable ? Design.success() : Design.amber());
        chips.setTextSize(10f);
        chips.setLetterSpacing(0.08f);
        chips.setPadding(0, dp(act, 2), 0, dp(act, 8));
        box.addView(chips);

        addCopyKv(act, box, "Coin ID", c.coinid);
        addCopyKv(act, box, "Address", c.address);
        if (c.miniaddress != null && !c.miniaddress.isEmpty() && !c.miniaddress.equals(c.address)) {
            addCopyKv(act, box, "Mx address", c.miniaddress);
        }
        addCopyKv(act, box, "Token ID", c.tokenid);
        if (!Util.isMinima(c.tokenid) && c.rawAmount != null && !c.rawAmount.equals(c.amount)) {
            addCopyKv(act, box, "Raw amount (Minima scale)", c.rawAmount);
        }
        if (c.created != null && !c.created.isEmpty()) {
            long age = c.ageBlocks(act.chainBlock());
            String detail = c.created + (age >= 0 ? "   (" + age + " blocks ago)" : "");
            addCopyKv(act, box, "Created block", detail);
        }
        if (c.mmrentry != null && !c.mmrentry.isEmpty()) {
            addCopyKv(act, box, "MMR entry", c.mmrentry);
        }

        // ---- state variables ----
        if (!c.state.isEmpty()) {
            TextView sl = new TextView(act);
            sl.setText("STATE VARIABLES (" + c.state.size() + ")");
            sl.setTextColor(Design.dim());
            sl.setTextSize(11f);
            sl.setLetterSpacing(0.1f);
            sl.setPadding(0, dp(act, 14), 0, dp(act, 4));
            box.addView(sl);

            for (String[] s : c.state) {
                String port = s[0], data = s[1] == null ? "" : s[1];
                boolean embedded = EMBED.matcher(data).matches() && data.length() > 8;
                String shown = data.length() > 120 ? data.substring(0, 117) + "…" : data;
                addCopyKv(act, box, "Port " + port + (embedded ? "  ·  embedded image" : ""), shown, data);

                if (embedded) {
                    // Render the on-chain art inline (strip the [] wrapper → data URI).
                    String b64 = data.substring(1, data.length() - 1);
                    ImageView iv = new ImageView(act);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(act, 120), dp(act, 120));
                    lp.topMargin = dp(act, 4); lp.bottomMargin = dp(act, 6);
                    lp.gravity = Gravity.CENTER_HORIZONTAL;
                    iv.setLayoutParams(lp);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    ImageLoader.loadOver(act, "data:image/jpeg;base64," + b64, iv, null);
                    box.addView(iv);
                }
            }
        }

        // StateNFT unit at one of my addresses → offer Transfer / Bury (detected via the token script).
        final LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(act, 12), 0, 0);
        box.addView(actions);

        sheet.action("Close", Sheet.Style.SECONDARY, null);
        final android.app.Dialog dlg = sheet.show();

        if (!Util.isMinima(c.tokenid) && Util.isValidHexId(c.tokenid)
                && !c.state.isEmpty() && isMine(act, c)) {
            act.node().cmd("tokens tokenid:" + c.tokenid, new NodeApi.Cb() {
                @Override public void onResult(org.json.JSONObject json) {
                    Object resp = json.opt("response");
                    org.json.JSONObject tok = resp instanceof org.json.JSONObject
                            ? (org.json.JSONObject) resp
                            : (resp instanceof org.json.JSONArray && ((org.json.JSONArray) resp).length() > 0
                                ? ((org.json.JSONArray) resp).optJSONObject(0) : null);
                    if (tok == null) return;
                    if (!StateNft.isStateNftScript(tok.optString("script", ""))) return;
                    // the tokens-command entry IS the token record (name at top level)
                    StateNft.Meta meta = StateNft.parseMeta(c.tokenid, tok);
                    String collection = meta == null || meta.name == null || meta.name.isEmpty()
                            ? c.tokenName : meta.name;
                    String idx = StateNft.stamped(c.raw);
                    String display = collection + (idx != null ? " #" + idx : "");
                    actions.addView(actionBtn(act, "Transfer", false,
                            v -> { dlg.dismiss(); StateNftActions.transferDialog(act, c.tokenid, display, c.raw); }));
                    actions.addView(actionBtn(act, "Bury", true,
                            v -> { dlg.dismiss(); StateNftActions.buryDialog(act, c.tokenid, collection, display, c.raw); }));
                }
                @Override public void onError(String message) { /* no actions offered */ }
            });
        }
    }

    private static boolean isMine(WalletActivity act, Coin c) {
        for (String[] a : act.myAddresses()) {
            if (c.address.equals(a[0]) || c.address.equals(a[1])
                    || (c.miniaddress != null && c.miniaddress.equals(a[1]))) return true;
        }
        return false;
    }

    private static android.widget.TextView actionBtn(WalletActivity act, String label, boolean danger,
                                                     android.view.View.OnClickListener click) {
        android.widget.TextView b = new android.widget.TextView(act);
        b.setText(label);
        b.setTextSize(13f);
        b.setTypeface(Design.typefaceBold());
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(act, 10), 0, dp(act, 10));
        b.setTextColor(danger ? Design.red() : Design.onAccent());
        b.setBackgroundColor(danger ? Design.redSoft() : Design.accent());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (danger) lp.leftMargin = dp(act, 8);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    /** Label + monospace value row; tapping copies the full value to the clipboard. */
    private static void addCopyKv(WalletActivity act, LinearLayout box, String label, String shown) {
        addCopyKv(act, box, label, shown, shown);
    }

    private static void addCopyKv(WalletActivity act, LinearLayout box, String label,
                                  String shown, String full) {
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Design.dim());
        l.setTextSize(11f);
        l.setPadding(0, dp(act, 8), 0, dp(act, 1));
        box.addView(l);

        TextView v = new TextView(act);
        v.setText(shown);
        v.setTextColor(Design.text());
        v.setTextSize(12f);
        v.setTypeface(Typeface.MONOSPACE);
        box.addView(v);
        v.setOnClickListener(x -> copy(act, label, full));
    }

    static void copy(Context ctx, String label, String value) {
        if (value == null || value.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(ctx, label + " copied", Toast.LENGTH_SHORT).show();
    }

    private static int dp(WalletActivity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
