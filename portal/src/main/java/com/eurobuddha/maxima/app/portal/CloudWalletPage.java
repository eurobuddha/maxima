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
import com.eurobuddha.maxima.cloud.CloudWallet;
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
    private JSONArray mTokens;            // non-Minima tokens (from walletTokens)
    private int mUses = -1;               // one-time-signature counter (from walletUses)
    private int mMaxUses = CloudWallet.MAX_USES;

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
        // Never a blank tab: paint a loading card synchronously BEFORE the (multi-RPC) round-trip,
        // so the first frame after tab-select is always populated even while balance+tokens+uses
        // are still in flight.
        if (!mBuilt) {
            mRoot.removeAllViews();
            mRoot.addView(PortalUi.section(mAct, "Wallet"));
            LinearLayout loading = PortalUi.card(mAct);
            loading.addView(PortalUi.label(mAct, "Opening your account wallet…"));
            mRoot.addView(loading);
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String addr = "";
                String confirmed = "";
                String sendable = "";
                String error = "";
                JSONArray tokens = null;
                int uses = -1, maxUses = CloudWallet.MAX_USES;
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
                // The account's OWN wallet (not a device watch address) also has a token list and
                // the one-time-signature counter — read them too, best-effort (a miss just omits
                // the card, never blocks the balance).
                try {
                    JSONObject t = r.walletTokens();
                    if (t.get("ok") == Boolean.TRUE) {
                        tokens = nonMinima(obj(t, "balance"));
                    }
                } catch (Exception ignored) {
                }
                try {
                    JSONObject u = r.walletUses(0);
                    if (u.get("ok") == Boolean.TRUE) {
                        uses = num(u, "uses");
                        int mx = num(u, "max");
                        if (mx > 0) maxUses = mx;
                    }
                } catch (Exception ignored) {
                }
                final String fa = addr, fc = confirmed, fs = sendable, fe = error;
                final JSONArray ft = tokens;
                final int fu = uses, fm = maxUses;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mAddress = fa;
                    mConfirmed = fc;
                    mSendable = fs;
                    mError = fe;
                    mTokens = ft;
                    mUses = fu;
                    mMaxUses = fm;
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
        boolean first = !mBuilt;
        mBuilt = true;
        Context c = mAct;
        // Never a blank screen: while the account address is still deriving on the node and no
        // watch address is set, show a loading card instead of nothing.
        if (mAddress.isEmpty() && mError.isEmpty()) {
            mRoot.removeAllViews();
            mRoot.addView(PortalUi.section(c, "Wallet"));
            LinearLayout card = PortalUi.card(c);
            card.addView(PortalUi.label(c, first ? "Opening your account wallet…" : "Loading…"));
            mRoot.addView(card);
            return;
        }
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
        // Honest custody line: the ACCOUNT's own wallet signs on the node; a device-set watch
        // address is the read-only mode.
        unit.setText(mSendable.isEmpty() ? "MINIMA · your account's wallet"
                : mSendable + " sendable · keys live on your cloud node");
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

        // --- other tokens held (only for the account's own wallet, not a device watch address) ---
        if (mTokens != null && !mTokens.isEmpty()) {
            mRoot.addView(PortalUi.section(c, "Tokens"));
            LinearLayout tc = PortalUi.card(c);
            for (int i = 0; i < mTokens.size(); i++) {
                if (!(mTokens.get(i) instanceof JSONObject)) continue;
                JSONObject t = (JSONObject) mTokens.get(i);
                String name = tokenName(t);
                String amt = str(t, "confirmed");
                String tid = str(t, "tokenid");
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                int pv = PortalUi.dp(c, 8);
                row.setPadding(0, pv, 0, pv);
                LinearLayout left = new LinearLayout(c);
                left.setOrientation(LinearLayout.VERTICAL);
                TextView nm = new TextView(c);
                nm.setText(name);
                nm.setTextColor(c.getColor(R.color.ux_text));
                nm.setTextSize(15);
                nm.setTypeface(nm.getTypeface(), Typeface.BOLD);
                left.addView(nm);
                TextView id = new TextView(c);
                id.setText(tid);                                   // full token id, never truncated
                id.setTextColor(c.getColor(R.color.ux_subtext));
                id.setTextSize(10);
                id.setTypeface(Typeface.MONOSPACE);
                left.addView(id);
                row.addView(left, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView val2 = new TextView(c);
                val2.setText(amt);
                val2.setTextColor(c.getColor(R.color.ux_text));
                val2.setTextSize(15);
                val2.setGravity(android.view.Gravity.END);
                row.addView(val2);
                final String ftid = tid;
                row.setOnClickListener(v -> copy(ftid, "Token id copied"));
                tc.addView(row);
            }
            mRoot.addView(tc);
        }

        // --- key uses (the sacred one-time-signature counter) ---
        if (mUses >= 0) {
            mRoot.addView(PortalUi.section(c, "Signing key"));
            LinearLayout kc = PortalUi.card(c);
            TextView u = new TextView(c);
            u.setText(mUses + " / " + mMaxUses + " signatures used");
            u.setTextColor(c.getColor(R.color.ux_text));
            u.setTextSize(16);
            u.setTypeface(u.getTypeface(), Typeface.BOLD);
            kc.addView(u);
            kc.addView(PortalUi.gap(c, 4));
            kc.addView(PortalUi.label(c, "Each key signs a fixed number of times. When it runs low, "
                    + "detach to a fresh wallet. Only raise this if you've signed with this seed on "
                    + "ANOTHER device — a wrong count can reuse a one-time key and leak it."));
            kc.addView(PortalUi.gap(c, 8));
            TextView adj = PortalUi.ghost(c, "Set signatures used…");
            adj.setOnClickListener(v -> usesSheet());
            kc.addView(adj);
            mRoot.addView(kc);
        }

        // --- send from the account wallet (also powers the wallet-detach sweep) ---
        mRoot.addView(PortalUi.section(c, "Send"));
        LinearLayout sendCard = PortalUi.card(c);
        TextView send = PortalUi.button(c, "Send MINIMA");
        send.setOnClickListener(v -> sendSheet());
        sendCard.addView(send);
        sendCard.addView(PortalUi.gap(c, 8));
        TextView detach = PortalUi.ghost(c, "Detach wallet to this phone…");
        detach.setOnClickListener(v -> detachFlow());
        sendCard.addView(detach);
        sendCard.addView(PortalUi.gap(c, 6));
        sendCard.addView(PortalUi.label(c, "Detach = move ALL funds to a wallet whose seed "
                + "lives on this phone (Minima Core). Your cloud identity stays; the VPS "
                + "goes cold and keeps a watch-only view."));
        mRoot.addView(sendCard);

        if (!mError.isEmpty()) {
            mRoot.addView(PortalUi.label(c, "Balance note: " + mError));
        }
    }

    /** Send from the ACCOUNT wallet to any Minima address — one confirm naming amount+address
     *  in full (this signs and broadcasts a real transaction). */
    private void sendSheet() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(mAct);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = PortalUi.dp(mAct, 20);
        box.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        final EditText addr = new EditText(mAct);
        addr.setHint("Mx… or 0x… address");
        addr.setSingleLine(true);
        box.addView(addr);
        final EditText amt = new EditText(mAct);
        amt.setHint("Amount (MINIMA)");
        amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(amt);
        new AlertDialog.Builder(mAct)
                .setTitle("Send MINIMA")
                .setMessage("Signed on your cloud node with one guarded key use.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> confirmSend(
                        addr.getText().toString().trim(), amt.getText().toString().trim()))
                .show();
    }

    private void confirmSend(String zTo, String zAmount) {
        if (zTo.isEmpty() || zAmount.isEmpty()) {
            mAct.toast("Address and amount needed");
            return;
        }
        new AlertDialog.Builder(mAct)
                .setTitle("Send " + zAmount + " MINIMA?")
                .setMessage("To:\n" + zTo + "\n\nThis signs and broadcasts a real transaction "
                        + "and cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign & send", (d, w) -> doWalletSend(zTo, zAmount, null))
                .show();
    }

    private volatile boolean mSending;   // double-tap latch

    /** Send from the account wallet. If zWatchOnSuccess is non-null, the watch is repointed to
     *  it ONLY after the walletsent push for this send arrives (the detach sweep) — never
     *  unconditionally, so a failed sweep can't hide the account's real balance. */
    private void doWalletSend(String zTo, String zAmount, final String zWatchOnSuccess) {
        if (mSending) {
            mAct.toast("A send is already in progress…");
            return;
        }
        mSending = true;
        mAct.toast("Building on your node…");
        final String pid = java.util.UUID.randomUUID().toString();
        final PortalHub.Listener[] holder = new PortalHub.Listener[1];
        if (zWatchOnSuccess != null) {
            // Arm a one-shot listener: flip the watch only when THIS send confirms. Match on
            // pid for BOTH outcomes so an unrelated device's send can't disarm us, and so a
            // send-RPC failure can tear it down.
            holder[0] = ev -> {
                String type = String.valueOf(ev.get("type"));
                boolean mine = pid.equals(String.valueOf(ev.get("pid")));
                if ("walletsent".equals(type) && mine) {
                    PortalHub.remove(holder[0]);
                    mAct.runOnUiThread(() -> setWatch(zWatchOnSuccess));
                } else if ("walletfail".equals(type) && mine) {
                    PortalHub.remove(holder[0]);
                }
            };
            PortalHub.add(holder[0]);
        }
        CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.walletSend(zTo, zAmount, pid);
                    Object okv = res.get("ok");
                    if (!(okv instanceof Boolean) || !((Boolean) okv)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    mSending = false;
                    mAct.toast(err == null
                            ? "Signing & broadcasting — watch the balance" : "Send failed: " + err);
                    mLastLoad = 0;
                    render();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> {
                    mSending = false;
                    mAct.toast("Send failed: " + m);
                });
            }
        });
    }

    /** The resync flow (user decision: the new seed lives ON THE DEVICE): sweep everything to
     *  a Minima Core wallet on this phone, then watch that address from the cloud. */
    private void detachFlow() {
        boolean coreInstalled;
        try {
            mAct.getPackageManager().getPackageInfo("org.minimarex.minimacore", 0);
            coreInstalled = true;
        } catch (Exception e) {
            coreInstalled = false;
        }
        if (!coreInstalled) {
            new AlertDialog.Builder(mAct)
                    .setTitle("Minima Core needed")
                    .setMessage("The detached wallet lives in Minima Core on this phone — its "
                            + "seed never touches the VPS. Install it first (Settings → Minima "
                            + "Core on this phone), create a wallet there, then come back.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(mAct);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = PortalUi.dp(mAct, 20);
        box.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        final EditText addr = new EditText(mAct);
        addr.setHint("Mx… address from Minima Core");
        addr.setSingleLine(true);
        box.addView(addr);
        new AlertDialog.Builder(mAct)
                .setTitle("Detach wallet to this phone")
                .setMessage("1. In Minima Core: Wallet → Receive → copy your address.\n"
                        + "2. Paste it below. ALL sendable funds sweep to it in one "
                        + "transaction, and the cloud switches to watching that address.\n\n"
                        + "Your cloud IDENTITY is untouched — chats, calls and contacts keep "
                        + "working exactly as before.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> confirmDetach(
                        addr.getText().toString().trim()))
                .show();
    }

    private void confirmDetach(String zTo) {
        if (!zTo.matches("Mx[0-9A-Z]+") && !zTo.matches("0x[0-9A-Fa-f]{64}")) {
            mAct.toast("That doesn't look like a full Minima address");
            return;
        }
        String amount = mSendable.isEmpty() ? "" : mSendable;
        if (amount.isEmpty() || "0".equals(amount)) {
            mAct.toast("Nothing sendable right now — sync first or wait for confirmations");
            return;
        }
        final String amt = amount;
        new AlertDialog.Builder(mAct)
                .setTitle("Sweep " + amt + " MINIMA?")
                .setMessage("Everything sendable goes to:\n" + zTo + "\n\nThe cloud then "
                        + "watches this address read-only. This signs a real transaction and "
                        + "cannot be undone.")
                .setNegativeButton("Cancel", null)
                // Watch flips ONLY after the sweep's walletsent push — a failed sweep must not
                // hide the account's real balance behind an empty watch address.
                .setPositiveButton("Sweep & detach", (d, w) -> doWalletSend(zTo, amt, zTo))
                .show();
    }

    /** Raise-only adjust of the one-time-signature counter (for a seed signed elsewhere).
     *  Double-guarded: naming the danger, then confirming the exact new value. */
    private void usesSheet() {
        final EditText field = new EditText(mAct);
        field.setHint("New used count (> " + mUses + ")");
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        int pad = PortalUi.dp(mAct, 20);
        field.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        new AlertDialog.Builder(mAct)
                .setTitle("Set signatures used")
                .setMessage("ONLY if this seed has signed transactions on another device. The "
                        + "counter can only go UP — setting it too low risks reusing a key and "
                        + "leaking it. Current: " + mUses + ".")
                .setView(field)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> {
                    String s = field.getText().toString().trim();
                    int to;
                    try {
                        to = Integer.parseInt(s);
                    } catch (Exception e) {
                        mAct.toast("Enter a whole number");
                        return;
                    }
                    if (to <= mUses) {
                        mAct.toast("Must be higher than the current " + mUses);
                        return;
                    }
                    confirmUses(to);
                })
                .show();
    }

    private void confirmUses(final int zTo) {
        new AlertDialog.Builder(mAct)
                .setTitle("Raise to " + zTo + "?")
                .setMessage("This permanently marks " + zTo + " signatures as used and cannot be "
                        + "undone. Do this only to match a seed already used elsewhere.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Raise counter", (d, w) -> doRaiseUses(zTo))
                .show();
    }

    private void doRaiseUses(final int zTo) {
        mAct.toast("Updating counter…");
        CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.walletUses(zTo);
                    if (res.get("ok") != Boolean.TRUE) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    mAct.toast(err == null ? "Counter raised" : "Could not raise: " + err);
                    mLastLoad = 0;
                    render();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast("Could not raise: " + m));
            }
        });
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

    /** All tokens EXCEPT Minima (0x00) from the gateway balance JSON — the "Tokens" section. */
    private static JSONArray nonMinima(JSONObject bal) {
        JSONArray out = new JSONArray();
        try {
            Object resp = bal.get("response");
            if (resp instanceof JSONArray) {
                for (Object o : (JSONArray) resp) {
                    if (!(o instanceof JSONObject)) continue;
                    String tid = str((JSONObject) o, "tokenid");
                    if (tid.isEmpty() || "0x00".equalsIgnoreCase(tid)) continue;
                    out.add(o);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A token's display name — the "token" field may be a bare string or a {name:…} object. */
    private static String tokenName(JSONObject t) {
        Object tok = t.get("token");
        if (tok instanceof JSONObject) {
            Object n = ((JSONObject) tok).get("name");
            if (n != null && !String.valueOf(n).isEmpty()) return String.valueOf(n);
        } else if (tok != null && !String.valueOf(tok).isEmpty()) {
            return String.valueOf(tok);
        }
        return "Token";
    }

    private static int num(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    private void copy(String s) {
        copy(s, "Address copied");
    }

    private void copy(String s, String toast) {
        if (s == null || s.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("value", s));   // full, never truncated
        mAct.toast(toast);
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
