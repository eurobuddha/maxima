package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Page;
import com.eurobuddha.maxima.app.ui.Qr;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * The cloud account's WATCH-ONLY wallet. The node holds only a device-supplied public address and
 * reads its balance through the hosted gateway — it has no keys and cannot spend (funds stay COLD
 * on the device). So this tab shows a balance + a receive address, never a Send. Data over
 * {@link ParlonsRemote#walletAddress()} + {@link ParlonsRemote#balance()}; the watch address is set
 * with {@link ParlonsRemote#setWatch(String)}.
 */
public final class CloudWalletPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mRoot;
    private volatile boolean mBusy;
    private long mLastLoad;
    private boolean mBuilt;

    private String mAddress = "";
    private String mConfirmed = "";
    private String mSendable = "";
    private String mError = "";

    public CloudWalletPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mRoot = zView.findViewById(R.id.wallet_container);
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return "Wallet";
    }

    @Override
    public void render() {
        if (mBusy) {
            return;
        }
        long now = System.currentTimeMillis();
        if (mBuilt && now - mLastLoad < 5000) {
            return;
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String addr = "";
                String confirmed = "";
                String sendable = "";
                String error = "";
                try {
                    JSONObject a = r.walletAddress();
                    addr = str(a, "address");
                    if (!addr.isEmpty()) {
                        JSONObject b = r.balance();
                        JSONObject bal = obj(b, "balance");
                        String[] cs = extract(bal);
                        confirmed = cs[0];
                        sendable = cs[1];
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fa = addr, fc = confirmed, fs = sendable, fe = error;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mAddress = fa;
                    mConfirmed = fc;
                    mSendable = fs;
                    mError = fe;
                    rebuild();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mError = m;
                    rebuild();
                });
            }
        });
    }

    private void rebuild() {
        mBuilt = true;
        Context c = mAct;
        mRoot.removeAllViews();

        if (mAddress.isEmpty()) {
            // No watch address yet — offer to set one (the device's cold receive address).
            mRoot.addView(PortalUi.section(c, "Wallet"));
            LinearLayout card = PortalUi.card(c);
            card.addView(PortalUi.title(c, "Watch-only wallet"));
            card.addView(PortalUi.gap(c, 6));
            card.addView(PortalUi.label(c, "Set your wallet's RECEIVE address and your cloud node will "
                    + "show its balance — read-only. Your spend keys never leave your device; the node "
                    + "can watch, never spend."));
            card.addView(PortalUi.gap(c, 12));
            EditText field = new EditText(c);
            field.setHint("Mx… receive address");
            field.setTextColor(c.getColor(R.color.ux_text));
            field.setHintTextColor(c.getColor(R.color.ux_subtext));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setTextSize(13);
            card.addView(field);
            card.addView(PortalUi.gap(c, 8));
            TextView set = PortalUi.button(c, "Set watch address");
            set.setOnClickListener(v -> setWatch(field.getText().toString().trim()));
            card.addView(set);
            mRoot.addView(card);
            if (!mError.isEmpty() && !mError.toLowerCase().contains("no watch")) {
                mRoot.addView(PortalUi.label(c, mError));
            }
            return;
        }

        // --- balance ---
        mRoot.addView(PortalUi.section(c, "Total balance"));
        LinearLayout bal = PortalUi.card(c);
        TextView big = new TextView(c);
        big.setText(mConfirmed.isEmpty() ? (mError.isEmpty() ? "…" : "unavailable") : mConfirmed);
        big.setTextColor(c.getColor(R.color.ux_text));
        big.setTextSize(34);
        big.setTypeface(big.getTypeface(), Typeface.BOLD);
        bal.addView(big);
        TextView unit = new TextView(c);
        unit.setText(mSendable.isEmpty() ? "MINIMA · watch-only" : mSendable + " sendable · watch-only");
        unit.setTextColor(c.getColor(R.color.ux_subtext));
        unit.setTextSize(13);
        bal.addView(unit);
        mRoot.addView(bal);

        // --- receive ---
        mRoot.addView(PortalUi.section(c, "Receive address"));
        LinearLayout rec = PortalUi.card(c);
        TextView val = PortalUi.value(c, mAddress);
        val.setTextSize(13);
        rec.addView(val);
        rec.addView(PortalUi.gap(c, 10));
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = PortalUi.ghost(c, "Copy");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = PortalUi.dp(c, 6);
        copy.setOnClickListener(v -> copy(mAddress));
        btns.addView(copy, lp1);
        TextView qr = PortalUi.ghost(c, "Show QR");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = PortalUi.dp(c, 6);
        qr.setOnClickListener(v -> showQr(mAddress));
        btns.addView(qr, lp2);
        rec.addView(btns);
        mRoot.addView(rec);

        if (!mError.isEmpty()) {
            mRoot.addView(PortalUi.label(c, "Balance note: " + mError));
        }
    }

    private void setWatch(String address) {
        if (address.isEmpty()) {
            mAct.toast("Enter a receive address");
            return;
        }
        mAct.toast("Setting…");
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.setWatch(address);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    if (err == null) {
                        mAct.toast("Watch address set");
                        mLastLoad = 0;
                        render();
                    } else {
                        mAct.toast("Could not set: " + err);
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast("Could not set: " + m));
            }
        });
    }

    /** Pull confirmed + sendable for Minima (tokenid 0x00) out of the gateway balance JSON. */
    private static String[] extract(JSONObject bal) {
        try {
            Object resp = bal.get("response");
            if (resp instanceof JSONArray) {
                JSONArray arr = (JSONArray) resp;
                for (Object o : arr) {
                    if (!(o instanceof JSONObject)) continue;
                    JSONObject t = (JSONObject) o;
                    String tid = str(t, "tokenid");
                    if (tid.isEmpty() || "0x00".equalsIgnoreCase(tid) || "0x00".equals(tid)) {
                        return new String[]{str(t, "confirmed"), str(t, "sendable")};
                    }
                }
                if (!arr.isEmpty() && arr.get(0) instanceof JSONObject) {
                    JSONObject t = (JSONObject) arr.get(0);
                    return new String[]{str(t, "confirmed"), str(t, "sendable")};
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{"", ""};
    }

    private void copy(String s) {
        if (s == null || s.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("address", s));   // full, never truncated
        mAct.toast("Address copied");
    }

    private void showQr(String s) {
        if (s == null || s.isEmpty()) return;
        int px = PortalUi.dp(mAct, 260);
        Bitmap bmp = Qr.encode(s, px);
        ImageView iv = new ImageView(mAct);
        iv.setImageBitmap(bmp);
        int pad = PortalUi.dp(mAct, 20);
        iv.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(mAct)
                .setTitle("Receive address")
                .setView(iv)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static JSONObject obj(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }
}
