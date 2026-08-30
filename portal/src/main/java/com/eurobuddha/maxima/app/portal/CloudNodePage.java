package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The Node tab — the reason a VPS account beats a phone: it is ALWAYS ON, it holds your one account
 * for every device, and it IS a network relay. Replaces Parlons' local "Network" tab (transport
 * telemetry a portal has no business showing) with the account node's superpowers, over
 * {@link ParlonsRemote#nodeStatus()} + {@link ParlonsRemote#devices()}. Paired devices can be
 * approved / revoked here, and a fresh bootstrap code minted.
 */
public final class CloudNodePage implements Page {

    private static final class Dev {
        String key;
        String label;
        long pairedAt;
    }

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mRoot;
    private volatile boolean mBusy;
    private long mLastLoad;
    private boolean mBuilt;

    // status snapshot
    private String mName = "";
    private String mPermanent = "";
    private long mUptime;
    private String mVersion = "";
    private int mHosts;
    private boolean mMailboxHeld;
    private boolean mRelayOn;
    private int mMeshPeers;
    private boolean mStatusOk;
    private final List<Dev> mAuthorized = new ArrayList<>();
    private final List<String> mPending = new ArrayList<>();

    public CloudNodePage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mRoot = zView.findViewById(R.id.network_root);
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return "Node";
    }

    @Override
    public void render() {
        if (mBusy) {
            return;
        }
        long now = System.currentTimeMillis();
        if (mBuilt && now - mLastLoad < 4000) {
            return;
        }
        if (!mBuilt) {
            // Paint the last-known node status instantly while the live fetch attaches.
            String cached = CloudSession.cached(mAct, "nodestatus");
            if (!cached.isEmpty()) {
                try {
                    Object o = new org.minima.utils.json.parser.JSONParser().parse(cached);
                    if (o instanceof JSONObject) {
                        applyStatus((JSONObject) o);
                        rebuild();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                boolean statusOk = false;
                String name = mName, permanent = mPermanent, version = mVersion;
                long uptime = mUptime;
                int hosts = mHosts, mesh = mMeshPeers;
                boolean mailbox = mMailboxHeld, relay = mRelayOn;
                List<Dev> auth = new ArrayList<>();
                List<String> pend = new ArrayList<>();
                try {
                    JSONObject s = r.nodeStatus();
                    if (bool(s, "ok")) {
                        statusOk = true;
                        name = str(s, "name");
                        permanent = str(s, "permanent");
                        version = str(s, "version");
                        uptime = lng(s, "uptime");
                        hosts = (int) lng(s, "hosts");
                        mailbox = bool(s, "mailboxHeld");
                        relay = bool(s, "relayOn");
                        mesh = (int) lng(s, "meshPeers");
                        CloudSession.cache(mAct, "nodestatus", s.toString());
                    }
                } catch (Exception e) {
                    // Older node without the node.status RPC — fall back to ping for identity.
                    try {
                        JSONObject p = r.ping();
                        name = str(p, "name");
                        permanent = str(p, "permanent");
                    } catch (Exception ignored) {
                    }
                }
                try {
                    JSONObject d = r.devices();
                    JSONArray a = (JSONArray) d.get("authorized");
                    if (a != null) {
                        for (Object o : a) {
                            JSONObject jd = (JSONObject) o;
                            Dev dev = new Dev();
                            dev.key = str(jd, "key");
                            dev.label = str(jd, "label");
                            dev.pairedAt = lng(jd, "pairedAt");
                            auth.add(dev);
                        }
                    }
                    JSONArray pn = (JSONArray) d.get("pending");
                    if (pn != null) {
                        for (Object o : pn) {
                            pend.add(String.valueOf(o));
                        }
                    }
                } catch (Exception ignored) {
                }

                final boolean fok = statusOk;
                final String fn = name, fp = permanent, fv = version;
                final long fu = uptime;
                final int fh = hosts, fm = mesh;
                final boolean fmail = mailbox, frelay = relay;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mStatusOk = fok;
                    mName = fn;
                    mPermanent = fp;
                    mVersion = fv;
                    mUptime = fu;
                    mHosts = fh;
                    mMailboxHeld = fmail;
                    mRelayOn = frelay;
                    mMeshPeers = fm;
                    mAuthorized.clear();
                    mAuthorized.addAll(auth);
                    mPending.clear();
                    mPending.addAll(pend);
                    rebuild();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    if (!mBuilt) {
                        mRoot.removeAllViews();
                        mRoot.addView(PortalUi.label(mAct, "Can't reach your node.\n" + m));
                        mBuilt = true;
                    }
                });
            }
        });
    }

    /** Populate the status snapshot fields from a (cached) node.status JSON. */
    private void applyStatus(JSONObject s) {
        mStatusOk = bool(s, "ok");
        mName = str(s, "name");
        mPermanent = str(s, "permanent");
        mVersion = str(s, "version");
        mUptime = lng(s, "uptime");
        mHosts = (int) lng(s, "hosts");
        mMailboxHeld = bool(s, "mailboxHeld");
        mRelayOn = bool(s, "relayOn");
        mMeshPeers = (int) lng(s, "meshPeers");
    }

    private void rebuild() {
        mBuilt = true;
        Context c = mAct;
        mRoot.removeAllViews();

        // --- your identity: the account's name, editable (answers "where is my identity?") ---
        LinearLayout ident = PortalUi.card(c);
        LinearLayout identRow = new LinearLayout(c);
        identRow.setOrientation(LinearLayout.HORIZONTAL);
        identRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identCol = new LinearLayout(c);
        identCol.setOrientation(LinearLayout.VERTICAL);
        identCol.addView(PortalUi.label(c, "Your name"));
        TextView identName = new TextView(c);
        identName.setText(mName.isEmpty() ? "(not set)" : mName);
        identName.setTextColor(c.getColor(R.color.ux_text));
        identName.setTextSize(18);
        identName.setTypeface(identName.getTypeface(), Typeface.BOLD);
        identCol.addView(identName);
        identCol.addView(PortalUi.label(c, "How you appear in your contacts' lists — on every device."));
        identRow.addView(identCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView edit = PortalUi.ghost(c, "Edit");
        edit.setOnClickListener(v -> promptSetName());
        identRow.addView(edit);
        ident.addView(identRow);
        mRoot.addView(ident);

        // --- always-on header ---
        LinearLayout head = PortalUi.card(c);
        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(c);
        int ds = PortalUi.dp(c, 9);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
        dlp.rightMargin = PortalUi.dp(c, 8);
        dot.setLayoutParams(dlp);
        dot.setBackgroundResource(R.drawable.status_dot);
        dot.getBackground().mutate().setTint(c.getColor(mHosts > 0 ? R.color.ux_success : R.color.ux_error));
        titleRow.addView(dot);
        TextView t = PortalUi.title(c, mHosts > 0 ? "Always on — online" : "Node unreachable");
        titleRow.addView(t);
        head.addView(titleRow);
        head.addView(PortalUi.gap(c, 4));
        String sub = (mName.isEmpty() ? "Your account" : mName)
                + (mVersion.isEmpty() ? "" : "  ·  parlons-cloud " + mVersion);
        head.addView(PortalUi.label(c, sub));
        if (mStatusOk) {
            head.addView(PortalUi.gap(c, 8));
            head.addView(PortalUi.kv(c, "Uptime", uptime(mUptime)));
            head.addView(PortalUi.kv(c, "Relays attached", String.valueOf(mHosts)));
            head.addView(PortalUi.kv(c, "Mailbox held", mMailboxHeld ? "yes" : "no"));
        } else {
            head.addView(PortalUi.gap(c, 6));
            head.addView(PortalUi.label(c, "Update the node to parlons-cloud 0.4.1+ for full status."));
        }
        mRoot.addView(head);

        // --- superpowers ---
        mRoot.addView(PortalUi.section(c, "Why a VPS node is king"));
        LinearLayout powers = PortalUi.card(c);
        powers.addView(power(c, "Always on",
                "Your account never goes dark — messages arrive even with every phone asleep."));
        powers.addView(divider(c, powers));
        powers.addView(power(c, "One account, every device",
                mAuthorized.size() + " device" + (mAuthorized.size() == 1 ? "" : "s")
                        + " paired — each drives this same account."));
        powers.addView(divider(c, powers));
        powers.addView(power(c, "You are a relay",
                mRelayOn ? ("Carrying traffic for the network"
                        + (mMeshPeers > 0 ? " · mesh: " + mMeshPeers + " peers" : ""))
                        : "Relay off"));
        mRoot.addView(powers);

        // --- permanent address ---
        mRoot.addView(PortalUi.section(c, "Permanent address"));
        LinearLayout addr = PortalUi.card(c);
        addr.addView(PortalUi.label(c, "The address that never changes — reaches you on any device."));
        addr.addView(PortalUi.gap(c, 8));
        TextView val = PortalUi.value(c, mPermanent.isEmpty() ? "(resolving…)" : mPermanent);
        val.setTextSize(13);
        addr.addView(val);
        addr.addView(PortalUi.gap(c, 10));
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = PortalUi.ghost(c, "Copy");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = PortalUi.dp(c, 6);
        copy.setOnClickListener(v -> copy(mPermanent));
        btns.addView(copy, lp1);
        TextView qr = PortalUi.ghost(c, "Show QR");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = PortalUi.dp(c, 6);
        qr.setOnClickListener(v -> showQr(mPermanent));
        btns.addView(qr, lp2);
        addr.addView(btns);
        mRoot.addView(addr);

        // --- paired devices ---
        mRoot.addView(PortalUi.section(c, "Paired devices (" + mAuthorized.size() + ")"));
        LinearLayout devs = PortalUi.card(c);
        if (mAuthorized.isEmpty()) {
            devs.addView(PortalUi.label(c, "No devices paired."));
        } else {
            for (int i = 0; i < mAuthorized.size(); i++) {
                if (i > 0) devs.addView(divider(c, devs));
                devs.addView(deviceRow(c, mAuthorized.get(i)));
            }
        }
        mRoot.addView(devs);

        if (!mPending.isEmpty()) {
            mRoot.addView(PortalUi.section(c, "Pending approval (" + mPending.size() + ")"));
            LinearLayout pend = PortalUi.card(c);
            for (int i = 0; i < mPending.size(); i++) {
                if (i > 0) pend.addView(divider(c, pend));
                pend.addView(pendingRow(c, mPending.get(i)));
            }
            mRoot.addView(pend);
        }

        TextView newCode = PortalUi.ghost(c, "New pairing code");
        newCode.setOnClickListener(v -> newCode());
        LinearLayout.LayoutParams nclp = PortalUi.matchWrap(c);
        nclp.bottomMargin = PortalUi.dp(c, 24);
        newCode.setLayoutParams(nclp);
        mRoot.addView(newCode);
    }

    private View power(Context c, String title, String desc) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pv = PortalUi.dp(c, 8);
        col.setPadding(0, pv, 0, pv);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(c.getColor(R.color.ux_text));
        t.setTextSize(15);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        col.addView(t);
        TextView d = new TextView(c);
        d.setText(desc);
        d.setTextColor(c.getColor(R.color.ux_subtext));
        d.setTextSize(13);
        col.addView(d);
        return col;
    }

    private View deviceRow(Context c, Dev dev) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = PortalUi.dp(c, 8);
        row.setPadding(0, pv, 0, pv);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(c);
        nm.setText(dev.label.isEmpty() ? "device" : dev.label);
        nm.setTextColor(c.getColor(R.color.ux_text));
        nm.setTextSize(15);
        col.addView(nm);
        boolean isThisDevice = sameKey(dev.key, CloudSession.deviceKeyOrEmpty(mAct));
        TextView sub = new TextView(c);
        sub.setText(isThisDevice ? "this device" : "paired");
        sub.setTextColor(c.getColor(isThisDevice ? R.color.ux_success : R.color.ux_subtext));
        sub.setTextSize(12);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView revoke = PortalUi.ghost(c, "Revoke");
        revoke.setTextColor(c.getColor(R.color.ux_error));
        revoke.setOnClickListener(v -> confirmRevoke(dev));
        row.addView(revoke);
        return row;
    }

    private View pendingRow(Context c, String key) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = PortalUi.dp(c, 8);
        row.setPadding(0, pv, 0, pv);
        TextView k = new TextView(c);
        k.setText("A device wants to pair");
        k.setTextColor(c.getColor(R.color.ux_text));
        k.setTextSize(15);
        row.addView(k, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView approve = PortalUi.button(c, "Approve");
        approve.setOnClickListener(v -> approve(key));
        row.addView(approve);
        return row;
    }

    private void confirmRevoke(Dev dev) {
        new AlertDialog.Builder(mAct)
                .setTitle("Revoke this device?")
                .setMessage((dev.label.isEmpty() ? "This device" : dev.label)
                        + " will lose access to your account. Your identity is NOT changed — other "
                        + "devices keep working. This can't be undone (they'd need a new code to re-pair).")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Revoke", (d, w) -> revoke(dev.key))
                .show();
    }

    /** Set the account's display name — the node re-announces it to every contact.
     *  Public so MainActivity's overflow menu ("Set account name") can open it too. */
    public void promptSetName() {
        final android.widget.EditText field = new android.widget.EditText(mAct);
        field.setText(mName);
        field.setHint("e.g. eurobuddha");
        field.setSingleLine(true);
        int pad = PortalUi.dp(mAct, 20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(mAct);
        wrap.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(mAct)
                .setTitle("Your name")
                .setMessage("Shown to your contacts, and re-announced to all of them now.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) {
                        mAct.toast("Name can't be empty");
                        return;
                    }
                    act(r -> r.setName(name), "Name set — announcing to your contacts…",
                            "Could not set name");
                })
                .show();
        field.requestFocus();
    }

    private void approve(String key) {
        act(r -> r.approve(key), "Approved", "Could not approve");
    }

    private void revoke(String key) {
        act(r -> r.revoke(key), "Revoked", "Could not revoke");
    }

    private void newCode() {
        mAct.toast("Minting…");
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String note = null, error = null;
                try {
                    JSONObject res = r.newCode();
                    if (bool(res, "ok")) {
                        note = str(res, "note");
                    } else {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fnote = note, ferr = error;
                mAct.runOnUiThread(() -> {
                    if (ferr == null) {
                        new AlertDialog.Builder(mAct)
                                .setTitle("New pairing code minted")
                                .setMessage((fnote == null || fnote.isEmpty())
                                        ? "A fresh one-time code was written to the node's pair-code.txt. "
                                        + "Read it over ssh, then enter it on the new device."
                                        : fnote)
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        mAct.toast(ferr);
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast(m));
            }
        });
    }

    private interface Call {
        JSONObject run(ParlonsRemote r) throws Exception;
    }

    private void act(Call call, String okMsg, String failMsg) {
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = call.run(r);
                    if (!bool(res, "ok")) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = error;
                mAct.runOnUiThread(() -> {
                    if (err == null) {
                        mAct.toast(okMsg);
                        mLastLoad = 0;
                        render();
                    } else {
                        mAct.toast(failMsg + ": " + err);
                    }
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast(failMsg + ": " + m));
            }
        });
    }

    private View divider(Context c, LinearLayout parent) {
        View div = new View(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, PortalUi.dp(c, 1));
        div.setLayoutParams(dlp);
        div.setBackgroundColor(c.getColor(R.color.ux_divider));
        return div;
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
                .setTitle("Permanent address")
                .setView(iv)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String uptime(long ms) {
        if (ms <= 0) return "just started";
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    private static boolean sameKey(String a, String b) {
        return a != null && !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static boolean bool(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Boolean && (Boolean) v;
    }
}
