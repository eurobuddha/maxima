package com.eurobuddha.maxima.app.portal;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Avatars;
import com.eurobuddha.maxima.app.ui.Page;
import com.eurobuddha.maxima.app.ui.Qr;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The cloud account's contacts. Shows the ACCOUNT's permanent address to share (full + QR — this is
 * the one address that reaches you on every paired device), the contact list (tap to chat), and an
 * add-by-address form. Data over {@link ParlonsRemote#ping()} + {@link ParlonsRemote#contacts()};
 * add over {@link ParlonsRemote#addContact(String)}.
 */
public final class CloudContactsPage implements Page {

    private static final class C {
        String key;
        String name;
        String address;
    }

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mRoot;
    private EditText mAddField;
    private String mPermanent = "";
    private final List<C> mContacts = new ArrayList<>();
    private volatile boolean mBusy;
    private long mLastLoad;
    private boolean mBuilt;

    public CloudContactsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mRoot = zView.findViewById(R.id.contacts_root);
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return "Contacts";
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
            // Paint the last-known contacts instantly while the live fetch attaches.
            String cached = CloudSession.cached(mAct, "contacts");
            if (!cached.isEmpty()) {
                try {
                    Object o = new org.minima.utils.json.parser.JSONParser().parse(cached);
                    if (o instanceof JSONObject) {
                        JSONObject j = (JSONObject) o;
                        mPermanent = str(j, "permanent");
                        mContacts.clear();
                        mContacts.addAll(parseContacts(j));
                        rebuild();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        mBusy = true;
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String perm = mPermanent;
                List<C> got = new ArrayList<>();
                try {
                    JSONObject ping = r.ping();
                    perm = str(ping, "permanent");
                    JSONObject res = r.contacts();
                    got = parseContacts(res);
                    res.put("permanent", perm);
                    CloudSession.cache(mAct, "contacts", res.toString());
                } catch (Exception ignored) {
                }
                final String permanent = perm;
                final List<C> fgot = got;
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    mPermanent = permanent;
                    mContacts.clear();
                    mContacts.addAll(fgot);
                    rebuild();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> {
                    mLastLoad = System.currentTimeMillis();
                    mBusy = false;
                    if (!mBuilt) {
                        mRoot.removeAllViews();
                        mRoot.addView(PortalUi.label(mAct, "Can't reach your account.\n" + m));
                        mBuilt = true;
                    }
                });
            }
        });
    }

    private static List<C> parseContacts(JSONObject res) {
        List<C> got = new ArrayList<>();
        JSONArray arr = (JSONArray) res.get("contacts");
        if (arr != null) {
            for (Object o : arr) {
                JSONObject c = (JSONObject) o;
                C x = new C();
                x.key = str(c, "key");
                x.name = str(c, "name");
                x.address = str(c, "address");
                got.add(x);
            }
        }
        return got;
    }

    /** Called by MainActivity after a QR scan resolves to an address. */
    public void onScanned(String zContents) {
        if (mAddField != null && zContents != null) {
            mAddField.setText(zContents.trim());
        }
    }

    private void rebuild() {
        mBuilt = true;
        mRoot.removeAllViews();
        Context c = mAct;

        // --- your account address (the always-reachable one) ---
        mRoot.addView(PortalUi.section(c, "Your account address"));
        LinearLayout addr = PortalUi.card(c);
        TextView hint = PortalUi.label(c, "Share this so people can reach you — it stays the same on every device.");
        addr.addView(hint);
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
        copy.setOnClickListener(v -> copyToClipboard(mPermanent));
        btns.addView(copy, lp1);
        TextView qr = PortalUi.ghost(c, "Show QR");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = PortalUi.dp(c, 6);
        qr.setOnClickListener(v -> showQr(mPermanent));
        btns.addView(qr, lp2);
        addr.addView(btns);
        mRoot.addView(addr);

        // --- contacts ---
        mRoot.addView(PortalUi.section(c, "Contacts (" + mContacts.size() + ")"));
        if (mContacts.isEmpty()) {
            LinearLayout empty = PortalUi.card(c);
            empty.addView(PortalUi.label(c, "No contacts yet. Add someone by their address below."));
            mRoot.addView(empty);
        } else {
            LinearLayout list = PortalUi.card(c);
            for (int i = 0; i < mContacts.size(); i++) {
                if (i > 0) {
                    View div = new View(c);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, PortalUi.dp(c, 1));
                    div.setLayoutParams(dlp);
                    div.setBackgroundColor(c.getColor(R.color.ux_divider));
                    list.addView(div);
                }
                list.addView(contactRow(c, mContacts.get(i)));
            }
            mRoot.addView(list);
        }

        // --- add a contact ---
        mRoot.addView(PortalUi.section(c, "Add a contact"));
        LinearLayout add = PortalUi.card(c);
        mAddField = new EditText(c);
        mAddField.setHint("Mx…@host:port address");
        mAddField.setTextColor(c.getColor(R.color.ux_text));
        mAddField.setHintTextColor(c.getColor(R.color.ux_subtext));
        mAddField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mAddField.setTextSize(13);
        add.addView(mAddField);
        add.addView(PortalUi.gap(c, 8));
        LinearLayout addBtns = new LinearLayout(c);
        addBtns.setOrientation(LinearLayout.HORIZONTAL);
        TextView scan = PortalUi.ghost(c, "Scan QR");
        LinearLayout.LayoutParams alp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        alp1.rightMargin = PortalUi.dp(c, 6);
        scan.setOnClickListener(v -> mAct.scanIntoContacts());
        addBtns.addView(scan, alp1);
        TextView doAdd = PortalUi.button(c, "Add");
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        alp2.leftMargin = PortalUi.dp(c, 6);
        doAdd.setOnClickListener(v -> addContact(mAddField.getText().toString().trim()));
        addBtns.addView(doAdd, alp2);
        add.addView(addBtns);
        mRoot.addView(add);
    }

    private View contactRow(Context c, C contact) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = PortalUi.dp(c, 8);
        row.setPadding(0, pv, 0, pv);
        row.setClickable(true);
        row.setBackgroundResource(R.color.ux_card);

        TextView av = new TextView(c);
        int sz = PortalUi.dp(c, 40);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(sz, sz);
        alp.rightMargin = PortalUi.dp(c, 12);
        av.setLayoutParams(alp);
        Avatars.apply(av, contact.key, contact.name);
        row.addView(av);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(c);
        nm.setText(contact.name.isEmpty() ? contact.key : contact.name);
        nm.setTextColor(c.getColor(R.color.ux_text));
        nm.setTextSize(16);
        col.addView(nm);
        TextView sub = new TextView(c);
        sub.setText(contact.address.isEmpty() ? "reachable via your account" : "tap to chat");
        sub.setTextColor(c.getColor(R.color.ux_subtext));
        sub.setTextSize(12);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            Intent i = new Intent(mAct, CloudChatActivity.class);
            i.putExtra(CloudChatActivity.EXTRA_PEER, contact.key);
            i.putExtra(CloudChatActivity.EXTRA_NAME, contact.name);
            mAct.startActivity(i);
        });
        row.setOnLongClickListener(v -> {
            detailSheet(contact);
            return true;
        });
        return row;
    }

    /** Full contact detail — software, last seen, key, all addresses, payment address, and
     *  Message / Rename / Reconnect / Remove (parlons.contacts.info + .remove). */
    private void detailSheet(C contact) {
        CloudSession.connectInteractive(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                JSONObject info = null;
                try {
                    info = r.contactInfo(contact.key);
                } catch (Exception ignored) {
                }
                final JSONObject fi = info;
                mAct.runOnUiThread(() -> showDetail(contact, fi));
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> showDetail(contact, null));
            }
        });
    }

    private void showDetail(C contact, JSONObject info) {
        Context c = mAct;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = PortalUi.dp(c, 20);
        box.setPadding(pad, PortalUi.dp(c, 8), pad, 0);
        String nm = contact.name.isEmpty() ? contact.key : contact.name;
        box.addView(PortalUi.title(c, nm));
        if (info != null && bool(info, "ok")) {
            String kind = str(info, "kind");
            box.addView(PortalUi.kv(c, "Software", bool(info, "classic") ? "Classic Maxima"
                    : kind.isEmpty() ? "Parlons" : kind));
            long ls = lng(info, "lastSeen");
            box.addView(PortalUi.kv(c, "Last seen", presence(ls)));
            box.addView(PortalUi.gap(c, 8));
            box.addView(PortalUi.label(c, "Public key"));
            TextView k = PortalUi.value(c, str(info, "key"));
            k.setTextSize(11);
            box.addView(k);
            JSONArray addrs = (JSONArray) info.get("addresses");
            if (addrs != null && !addrs.isEmpty()) {
                box.addView(PortalUi.gap(c, 8));
                box.addView(PortalUi.label(c, "Addresses"));
                for (Object o : addrs) {
                    TextView a = PortalUi.value(c, String.valueOf(o));
                    a.setTextSize(11);
                    box.addView(a);
                }
            }
            String wallet = str(info, "wallet");
            if (!wallet.isEmpty()) {
                box.addView(PortalUi.gap(c, 8));
                box.addView(PortalUi.label(c, "Payment address (MINIMA)"));
                TextView w = PortalUi.value(c, wallet);
                w.setTextSize(11);
                box.addView(w);
            }
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(box);
        new AlertDialog.Builder(c)
                .setView(scroll)
                .setPositiveButton("Message", (d, w) -> {
                    Intent i = new Intent(mAct, CloudChatActivity.class);
                    i.putExtra(CloudChatActivity.EXTRA_PEER, contact.key);
                    i.putExtra(CloudChatActivity.EXTRA_NAME, contact.name);
                    mAct.startActivity(i);
                })
                .setNeutralButton("More", (d, w) -> moreMenu(contact))
                .setNegativeButton("Close", null)
                .show();
    }

    private void moreMenu(C contact) {
        new AlertDialog.Builder(mAct)
                .setItems(new CharSequence[]{"Rename", "Reconnect now", "Remove contact"},
                        (d, which) -> {
                            if (which == 0) {
                                promptRename(contact);
                            } else if (which == 1) {
                                reconnect(contact);
                            } else {
                                confirmRemove(contact);
                            }
                        })
                .show();
    }

    private void confirmRemove(C contact) {
        String nm = contact.name.isEmpty() ? contact.key : contact.name;
        new AlertDialog.Builder(mAct)
                .setTitle("Remove " + nm + "?")
                .setMessage("They stay able to write to you and keep your address — this just "
                        + "removes them from your list.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    CloudSession.connect(mAct, new CloudSession.Cb() {
                        public void ok(ParlonsRemote r) {
                            String error = null;
                            try {
                                JSONObject res = r.removeContact(contact.key);
                                Object ok = res.get("ok");
                                if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                                    error = String.valueOf(res.get("error"));
                                }
                            } catch (Exception e) {
                                error = e.getMessage() == null ? e.toString() : e.getMessage();
                            }
                            final String err = error;
                            mAct.runOnUiThread(() -> {
                                mAct.toast(err == null ? "Removed" : "Failed: " + err);
                                mLastLoad = 0;
                                render();
                            });
                        }
                        public void err(String m) {
                            mAct.runOnUiThread(() -> mAct.toast("Failed: " + m));
                        }
                    });
                })
                .show();
    }

    private static String presence(long lastSeen) {
        if (lastSeen <= 0) {
            return "never";
        }
        long ago = System.currentTimeMillis() - lastSeen;
        if (ago < 30 * 60_000L) {
            return "online now";
        }
        long m = ago / 60_000, h = m / 60, d = h / 24;
        if (d > 0) return d + "d ago";
        if (h > 0) return h + "h ago";
        return m + "m ago";
    }

    private static long lng(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static boolean bool(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Boolean && (Boolean) v;
    }

    /** Force the account to re-resolve this contact's current address from their directory. */
    private void reconnect(C contact) {
        mAct.toast("Reconnecting…");
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String msg;
                try {
                    JSONObject res = r.resolveContact(contact.key);
                    Object ok = res.get("ok");
                    if (ok instanceof Boolean && (Boolean) ok) {
                        Object up = res.get("updated");
                        msg = (up instanceof Boolean && (Boolean) up)
                                ? "Fresh address found — reconnected"
                                : "Address unchanged — they may just be offline";
                    } else {
                        msg = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    msg = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fmsg = msg;
                mAct.runOnUiThread(() -> {
                    mAct.toast(fmsg);
                    mLastLoad = 0;
                    render();
                });
            }
            public void err(String m) {
                mAct.runOnUiThread(() -> mAct.toast(m));
            }
        });
    }

    /** Long-press → rename: a local display-name override stored on the account. */
    private void promptRename(C contact) {
        final EditText field = new EditText(mAct);
        field.setText(contact.name);
        field.setHint("Name for this contact");
        field.setSingleLine(true);
        int pad = PortalUi.dp(mAct, 20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(mAct);
        wrap.setPadding(pad, PortalUi.dp(mAct, 8), pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(mAct)
                .setTitle("Rename contact")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) {
                        mAct.toast("Name can't be empty");
                        return;
                    }
                    CloudSession.connect(mAct, new CloudSession.Cb() {
                        public void ok(ParlonsRemote r) {
                            String error = null;
                            try {
                                JSONObject res = r.renameContact(contact.key, name);
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
                                    mAct.toast("Renamed");
                                    mLastLoad = 0;
                                    render();
                                } else {
                                    mAct.toast("Could not rename: " + err);
                                }
                            });
                        }
                        public void err(String m) {
                            mAct.runOnUiThread(() -> mAct.toast("Could not rename: " + m));
                        }
                    });
                })
                .show();
        field.requestFocus();
    }

    private void addContact(String address) {
        if (address.isEmpty()) {
            mAct.toast("Paste or scan an address first");
            return;
        }
        // Mirror the app's introduceAddr guard: a Parlons address is Mx…@host:port or a MAX#…
        // permanent address. Reject anything else up-front and KEEP the field so it can be fixed.
        if (!address.startsWith("Mx") && !address.startsWith("MAX#")) {
            mAct.toast("That doesn't look like a Parlons address — it should start with Mx… or MAX#…");
            return;
        }
        mAct.toast("Adding…");
        CloudSession.connect(mAct, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                String error = null;
                try {
                    JSONObject res = r.addContact(address);
                    Object ok = res.get("ok");
                    if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                        error = String.valueOf(res.get("error"));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String err = friendlyAddError(error);
                mAct.runOnUiThread(() -> {
                    if (err == null) {
                        mAct.toast("Contact added");
                        if (mAddField != null) {
                            mAddField.setText("");
                        }
                        // The node's introduce handshake is async — the contact may not be in the
                        // store on the first read. Refresh now AND again shortly so it appears.
                        mLastLoad = 0;
                        render();
                        mRoot.postDelayed(() -> { mLastLoad = 0; render(); }, 2500);
                    } else {
                        // Keep the field populated so the user can retry / correct it.
                        mAct.toast("Could not add: " + err);
                    }
                });
            }
            public void err(String m) {
                final String fm = friendlyAddError(m);
                mAct.runOnUiThread(() -> mAct.toast("Could not add: " + fm));
            }
        });
    }

    /** Map the node's introduce/resolve exceptions to plain English — no raw Java in a toast. */
    private static String friendlyAddError(String raw) {
        if (raw == null) {
            return null;
        }
        String r = raw.toLowerCase();
        if (r.contains("could not resolve") || r.contains("no record")) {
            return "that account isn't reachable right now — check they're online, or re-scan their address";
        }
        if (r.contains("no reachable address") || r.contains("unreachable")) {
            return "none of their addresses answered — they may be offline";
        }
        if (r.contains("timeout") || r.contains("timed out")) {
            return "the network didn't answer in time — try again";
        }
        // Strip a leading exception class name (java.lang.IllegalStateException: …).
        int colon = raw.indexOf(": ");
        if (colon > 0 && raw.substring(0, colon).matches("[a-zA-Z0-9_.$]+(Exception|Error)")) {
            return raw.substring(colon + 2);
        }
        return raw;
    }

    private void copyToClipboard(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("address", s));   // full, never truncated
        mAct.toast("Address copied");
    }

    private void showQr(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        int px = PortalUi.dp(mAct, 260);
        Bitmap bmp = Qr.encode(s, px);
        ImageView iv = new ImageView(mAct);
        iv.setImageBitmap(bmp);
        int pad = PortalUi.dp(mAct, 20);
        iv.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(mAct)
                .setTitle("Your account address")
                .setView(iv)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
