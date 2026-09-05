package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

/**
 * Receive tab — the old-wallet receive experience: a QR (tap to copy), the Mx address with its
 * 0x form, a live {@code checkaddress} safety report ("belongs to this node / safe to use"),
 * NEXT-address rotation, and the full default-address pool with tap-to-show.
 */
public class ReceiveView extends BaseView {

    private final TextView address, safety, listLabel;
    private final ImageView qr;
    private final LinearLayout list;

    /** The address currently displayed in the QR (Mx form). */
    private String shownAddr = "";

    public ReceiveView(WalletActivity a) {
        super(a, R.layout.view_receive);
        address = find(R.id.rcvAddress);
        qr = find(R.id.rcvQr);
        safety = find(R.id.rcvSafety);
        listLabel = find(R.id.rcvListLabel);
        list = find(R.id.rcvList);

        Button copy = find(R.id.rcvCopy);
        copy.setOnClickListener(v -> copyAddress());
        qr.setOnClickListener(v -> copyAddress());          // tap QR to copy, like the old wallet
        address.setOnClickListener(v -> copyAddress());
        Button refreshBtn = find(R.id.rcvRefresh);
        refreshBtn.setOnClickListener(v -> fetchFresh());
        refreshBtn.setTextColor(Design.accent());
        root.setBackgroundColor(Design.bg());
        address.setBackgroundColor(Design.surface());
        address.setTextColor(Design.text());
        copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        copy.setTextColor(Design.onAccent());
        refresh();
    }

    /** Repaints from the cached address + address pool; never hits the node (onShown fetches). */
    @Override
    public void refresh() {
        String addr = shownAddr.isEmpty() ? act.defaultAddress() : shownAddr;
        if (addr == null || addr.isEmpty()) {
            address.setText("Fetching address…");
            qr.setImageBitmap(null);
        } else if (!addr.equals(shownAddr) || qr.getDrawable() == null) {
            showAddress(addr);
        }
        renderList();
    }

    /** getaddress is fetched lazily when the tab is opened. */
    @Override
    public void onShown() {
        if (shownAddr.isEmpty() && (act.defaultAddress() == null || act.defaultAddress().isEmpty())) {
            fetchFresh();
        }
    }

    /** Asks the node for a getaddress (rotates through the default pool), then shows it. */
    private void fetchFresh() {
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (!addr.isEmpty()) {
                    act.setDefaultAddress(addr);
                    showAddress(addr);
                }
            }
            @Override public void onError(String message) {
                if (!NodeApi.ERR_NOT_ENABLED.equals(message)) {
                    Toast.makeText(act, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Display one address: label, QR, and its safety report. */
    private void showAddress(String addr) {
        shownAddr = addr;
        String hex = hexFor(addr);
        address.setText(hex.isEmpty() || hex.equals(addr) ? addr : addr + "\n" + hex);
        renderQr(addr);
        checkSafety(addr);
    }

    /** The node vouches for its own address: belongs to this node + safe to use. */
    private void checkSafety(final String addr) {
        safety.setVisibility(View.GONE);
        act.node().cmd("checkaddress address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!addr.equals(shownAddr)) return;   // stale reply for a rotated address
                JSONObject r = json.optJSONObject("response");
                if (r == null) return;
                boolean mine = r.optBoolean("relevant", r.optBoolean("belongs", false));
                boolean simple = r.optBoolean("simple", false);
                StringBuilder s = new StringBuilder();
                s.append(mine ? "✓ Belongs to this node" : "◦ Not recognised as this node's address");
                if (simple) s.append("   ·   ✓ Safe to use");
                safety.setText(s.toString());
                safety.setTextColor(mine ? Design.success() : Design.amber());
                safety.setBackgroundColor(Design.surface());
                safety.setVisibility(View.VISIBLE);
            }
            @Override public void onError(String message) { /* report is best-effort */ }
        });
    }

    /** All wallet addresses from the scripts pool — tap to swap the QR to that address. */
    private void renderList() {
        list.removeAllViews();
        java.util.List<String[]> addrs = act.myAddresses();
        if (addrs.isEmpty()) { listLabel.setVisibility(View.GONE); return; }
        listLabel.setVisibility(View.VISIBLE);
        int n = Math.min(addrs.size(), 64);
        for (int i = 0; i < n; i++) {
            final String mini = addrs.get(i)[1];
            final String hex = addrs.get(i)[0];
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            boolean active = mini.equals(shownAddr) || hex.equals(shownAddr);
            r.setBackgroundColor(active ? Design.accentSoft() : Design.surface());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            r.setLayoutParams(lp);

            TextView t = new TextView(act);
            // The FULL address, never shortened. Util.shorten cut this to 8+…+6 = 15 characters,
            // which is not enough to tell two of your own addresses apart, let alone verify one
            // against a counterparty. There is room for the whole thing, and where there isn't it
            // wraps rather than hiding characters.
            t.setText(mini);
            t.setTextColor(active ? Design.accent() : Design.text());
            t.setTextSize(11f);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setEllipsize(null);
            t.setSingleLine(false);
            t.setMaxLines(3);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                // An address is one unbroken token; the default strategies try to keep it whole and
                // then clip. SIMPLE + no hyphenation wraps it at a character boundary instead.
                t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            }
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (active) {
                TextView chip = new TextView(act);
                chip.setText("SHOWING");
                chip.setTextColor(Design.accent());
                chip.setTextSize(9f);
                chip.setLetterSpacing(0.1f);
                r.addView(chip);
            }
            r.setOnClickListener(v -> { showAddress(mini); renderList(); });
            // Tap swaps the QR, so copying needs its own gesture. Copies the Mx form — the one you
            // hand to someone else.
            r.setOnLongClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Minima address", mini));
                    Toast.makeText(act, "Address copied", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            list.addView(r);
        }
    }

    /** The 0x form of a shown Mx address, from the wallet's address pool ("" if unknown). */
    private String hexFor(String mini) {
        for (String[] a : act.myAddresses()) if (a[1].equals(mini)) return a[0];
        return "";
    }

    /** Encodes the address to a QR bitmap off the UI thread; tag guards against stale results. */
    private void renderQr(final String text) {
        qr.setTag(text);   // remembers which address this bitmap is for
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                int size = 480;
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                    }
                }
            } catch (Exception e) {
                bmp = null;
            }
            final Bitmap result = bmp;
            act.runOnUiThread(() -> {
                // Only apply if the address hasn't changed since this encode started.
                if (text.equals(qr.getTag())) qr.setImageBitmap(result);
            });
        }).start();
    }

    /** Copies the displayed address to the clipboard and toasts confirmation. */
    private void copyAddress() {
        String addr = shownAddr.isEmpty() ? act.defaultAddress() : shownAddr;
        if (addr == null || addr.isEmpty()) return;
        CoinDetailDialog.copy(act, "Address", addr);
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
