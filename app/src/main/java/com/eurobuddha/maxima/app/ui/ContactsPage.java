package com.eurobuddha.maxima.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.ChatActivity;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Who can reach you, and how you reach them — in the greyscale design language.
 *
 * A Maxima address is {@code Mx<base32 pubkey>@host:port} ≈ 273 characters, far
 * too long to read, so this screen is built so you never have to: you SHARE your
 * address (QR + one-tap copy of the complete string), and each contact's full
 * address lives in their card, copyable whole. Nothing is ever shown truncated
 * without the complete value one tap away.
 *
 * When we hand out our own address the appended host is always an IP, never a
 * domain (see {@link #toIpForm}).
 */
public final class ContactsPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mBox;

    private TextView mHostInfo;
    private EditText mSearch;
    private TextView mCountLabel;
    private LinearLayout mList;

    private String mSearchQuery = "";
    private String mLastSignature = "";

    private static final long ONLINE_MS = 30 * 60 * 1000L;

    public ContactsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mBox = zView.findViewById(R.id.contacts_root);
        buildUi();
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
        MaximaNode node = MaximaService.node();
        if (node == null) {
            mHostInfo.setText("starting…");
            return;
        }
        List<String> addrs = node.myAddresses();
        mHostInfo.setText(addrs.isEmpty()
                ? "No address yet — waiting for a host"
                : "Reachable through " + addrs.size()
                        + (addrs.size() == 1 ? " host" : " hosts"));

        List<Contact> cs = node.contacts();
        StringBuilder sig = new StringBuilder();
        for (Contact c : cs) {
            sig.append(c.publicKey).append(c.name).append(c.primaryAddress())
                    .append(statusLabel(c)).append(peerPill(c)).append('|');
        }
        if (sig.toString().equals(mLastSignature)) {
            return;
        }
        mLastSignature = sig.toString();
        rebuildList(cs);
    }

    // ---------------------------------------------------------------
    // List
    // ---------------------------------------------------------------

    private void rebuildList(List<Contact> all) {
        mList.removeAllViews();
        List<Contact> cs = filtered(all);
        mCountLabel.setText("CONTACTS · " + all.size());
        if (cs.isEmpty()) {
            mList.addView(sub(all.isEmpty()
                    ? "Nobody yet — share your address or scan a friend's to get started."
                    : "No contact matches \"" + mSearchQuery.trim() + "\"."));
            return;
        }
        boolean first = true;
        for (Contact c : cs) {
            if (!first) {
                mList.addView(divider());
            }
            mList.addView(contactRow(c));
            first = false;
        }
    }

    private List<Contact> filtered(List<Contact> all) {
        String q = mSearchQuery.trim().toLowerCase();
        if (q.isEmpty()) {
            return all;
        }
        List<Contact> out = new ArrayList<>();
        for (Contact c : all) {
            if ((c.name != null && c.name.toLowerCase().contains(q))
                    || (c.publicKey != null && c.publicKey.toLowerCase().contains(q))) {
                out.add(c);
            }
        }
        return out;
    }

    private View contactRow(Contact c) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vp = dp(11);
        row.setPadding(dp(4), vp, dp(4), vp);
        row.setClickable(true);
        row.setForeground(ripple());

        TextView av = new TextView(mAct);
        av.setGravity(Gravity.CENTER);
        av.setTextSize(17);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        Avatars.apply(av, c.publicKey, c.name);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(46), dp(46));
        ap.rightMargin = dp(12);
        row.addView(av, ap);

        LinearLayout mid = new LinearLayout(mAct);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(mAct);
        name.setText(c.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(col(R.color.ux_text));
        boolean online = c.lastSeen > 0 && System.currentTimeMillis() - c.lastSeen < ONLINE_MS;
        TextView pr = new TextView(mAct);
        pr.setText("● " + presenceText(c));
        pr.setTextSize(11.5f);
        pr.setSingleLine(true);
        pr.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pr.setTextColor(col(online ? R.color.ux_success : R.color.ux_subtext));
        mid.addView(name);
        mid.addView(pr);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        String pill = peerPill(c);
        if (pill != null) {
            TextView badge = new TextView(mAct);
            badge.setText(pill.toUpperCase());
            badge.setTextSize(9);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setLetterSpacing(0.08f);
            boolean core = pill.equals("core");
            badge.setBackgroundResource(core ? R.drawable.chip_on : R.drawable.chip_off);
            badge.setTextColor(col(core ? R.color.ux_on_accent : R.color.ux_subtext));
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.rightMargin = dp(4);
            row.addView(badge, bp);
        }

        ImageView info = new ImageView(mAct);
        info.setImageResource(R.drawable.ic_more);
        info.setColorFilter(col(R.color.ux_subtext));
        info.setBackground(ripple());
        int ip = dp(7);
        info.setPadding(ip, ip, ip, ip);
        info.setOnClickListener(x -> showContactCard(c));
        row.addView(info, new LinearLayout.LayoutParams(dp(32), dp(32)));

        // Tap → chat; long-press or the ⋯ button → contact card.
        row.setOnClickListener(x -> openChat(c));
        row.setOnLongClickListener(x -> {
            showContactCard(c);
            return true;
        });
        return row;
    }

    private void openChat(Contact c) {
        Intent i = new Intent(mAct, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_CONVERSATION, c.publicKey);
        mAct.startActivity(i);
    }

    // ---------------------------------------------------------------
    // Contact card sheet
    // ---------------------------------------------------------------

    private void showContactCard(Contact c) {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView av = new TextView(mAct);
        av.setGravity(Gravity.CENTER);
        av.setTextSize(20);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        Avatars.apply(av, c.publicKey, c.name);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(52), dp(52));
        ap.rightMargin = dp(13);
        head.addView(av, ap);
        LinearLayout hn = new LinearLayout(mAct);
        hn.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(mAct);
        nm.setText(c.name);
        nm.setTextSize(18);
        nm.setTypeface(manrope(Typeface.BOLD));
        nm.setTextColor(col(R.color.ux_text));
        boolean online = c.lastSeen > 0 && System.currentTimeMillis() - c.lastSeen < ONLINE_MS;
        TextView pr = new TextView(mAct);
        pr.setText("● " + presenceText(c));
        pr.setTextSize(12);
        pr.setTextColor(col(online ? R.color.ux_success : R.color.ux_subtext));
        hn.addView(nm);
        hn.addView(pr);
        head.addView(hn);
        body.addView(head, marginBottom(dp(16)));

        TextView msg = primaryButton("Message");
        body.addView(msg, marginBottom(dp(4)));

        String addr = c.primaryAddress();
        if (addr != null) {
            body.addView(copyField("Parlons address", addr, "Address copied"));
            // RULE 1: every host address must be copyable in full, not just
            // counted. Tap to reveal the rest as their own copy fields.
            if (c.addresses != null && c.addresses.size() > 1) {
                final LinearLayout extras = new LinearLayout(mAct);
                extras.setOrientation(LinearLayout.VERTICAL);
                extras.setVisibility(View.GONE);
                final int n = c.addresses.size() - 1;
                final TextView more = ghostButton("Show " + n + " more host"
                        + (n == 1 ? "" : "s"));
                more.setPadding(dp(2), dp(8), 0, 0);
                more.setOnClickListener(v -> {
                    if (extras.getChildCount() == 0) {
                        int idx = 0;
                        for (String a : c.addresses) {
                            if (a != null && !a.equals(addr)) {
                                extras.addView(copyField("Host " + (++idx), a, "Address copied"));
                            }
                        }
                    }
                    extras.setVisibility(View.VISIBLE);
                    more.setVisibility(View.GONE);
                });
                body.addView(more);
                body.addView(extras);
            }
        } else {
            TextView none = sub("No address known yet — they have not replied to your introduction.");
            none.setPadding(0, dp(12), 0, 0);
            body.addView(none);
        }

        if (c.minimaAddress != null && !c.minimaAddress.isEmpty()) {
            body.addView(copyField("Payment address (MINIMA)", c.minimaAddress, "Payment address copied"));
        }

        LinearLayout meta = new LinearLayout(mAct);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(0, dp(16), 0, 0);
        meta.addView(kv("Software", softwareLabel(c)));
        meta.addView(kv("Last seen", online ? "Online now" : lastSeenText(c)));
        body.addView(meta);

        body.addView(divider(), marginTB(dp(16)));
        TextView remove = ghostButton("Remove contact");
        remove.setTextColor(col(R.color.ux_error));
        body.addView(remove);

        BottomSheetDialog d = sheet(c.name, body);
        msg.setOnClickListener(v -> {
            d.dismiss();
            openChat(c);
        });
        remove.setOnClickListener(v -> confirmRemove(c, d));
    }

    private void confirmRemove(Contact c, BottomSheetDialog sheet) {
        new AlertDialog.Builder(mAct)
                .setTitle("Remove " + c.name + "?")
                .setMessage("They keep your address and can still write to you. "
                        + "This only removes them from your list.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    MaximaNode n = MaximaService.node();
                    if (n != null) {
                        n.removeContact(c.publicKey);
                    }
                    mLastSignature = "";
                    render();
                    sheet.dismiss();
                })
                .show();
    }

    // ---------------------------------------------------------------
    // My address + add sheets
    // ---------------------------------------------------------------

    private void showMyAddressSheet() {
        String raw = pickShareAddrRaw();
        if (raw == null) {
            mAct.toast("No address yet — wait for a host");
            return;
        }
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        final ImageView qr = new ImageView(mAct);
        int qp = dp(12);
        qr.setPadding(qp, qp, qp, qp);
        qr.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(210), dp(210));
        qlp.bottomMargin = dp(14);
        body.addView(qr, qlp);

        final LinearLayout copyHolder = new LinearLayout(mAct);
        copyHolder.setOrientation(LinearLayout.VERTICAL);
        body.addView(copyHolder, new LinearLayout.LayoutParams(-1, -2));

        final TextView shareBtn = primaryButton("Share…");
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2);
        sbp.topMargin = dp(12);
        body.addView(shareBtn, sbp);

        int hosts = 1;
        MaximaNode node = MaximaService.node();
        if (node != null) {
            hosts = Math.max(1, node.myAddresses().size());
        }
        TextView note = sub("Let a friend scan the QR, or share it. Reachable through "
                + hosts + (hosts == 1 ? " host." : " hosts — any of them works."));
        note.setPadding(dp(2), dp(10), dp(2), 0);
        body.addView(note);

        sheet("Share my address", body);

        // Resolve host → IP off-main, then fill QR + copy field + share.
        new Thread(() -> {
            final String ipAddr = toIpForm(raw);
            mAct.runOnUiThread(() -> {
                if (ipAddr == null) {
                    // Couldn't turn a domain host into an IP — never share the
                    // domain form. Ask the user to retry once online.
                    copyHolder.removeAllViews();
                    TextView warn = sub("Couldn't resolve a shareable IP address. "
                            + "Check your connection and reopen this to try again.");
                    warn.setPadding(dp(2), dp(6), dp(2), 0);
                    copyHolder.addView(warn);
                    shareBtn.setEnabled(false);
                    shareBtn.setAlpha(0.4f);
                    return;
                }
                final Bitmap bmp = Qr.encode(ipAddr, dp(200));
                if (bmp != null) {
                    qr.setImageBitmap(bmp);
                }
                copyHolder.removeAllViews();
                copyHolder.addView(copyField("Your address", ipAddr, "Address copied"));
                shareBtn.setOnClickListener(v -> shareText(ipAddr));
            });
        }, "resolve-share-addr").start();
    }

    private void showAddSheet() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView scan = primaryButton("Scan their QR");
        body.addView(scan, marginBottom(dp(2)));

        TextView or = sub("OR PASTE");
        or.setGravity(Gravity.CENTER);
        or.setLetterSpacing(0.1f);
        or.setPadding(0, dp(14), 0, dp(10));
        body.addView(or);

        final EditText paste = new EditText(mAct);
        paste.setHint("Mx…@host:port");
        paste.setBackgroundResource(R.drawable.field_pill);
        paste.setPadding(dp(14), dp(12), dp(14), dp(12));
        paste.setTextColor(col(R.color.ux_text));
        paste.setHintTextColor(col(R.color.ux_subtext));
        paste.setTextSize(13);
        paste.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(paste, marginBottom(dp(11)));

        TextView intro = ghostButton("Introduce myself");
        body.addView(intro);

        TextView note = sub("They'll appear in your list once they reply to the introduction.");
        note.setPadding(dp(2), dp(12), dp(2), 0);
        body.addView(note);

        BottomSheetDialog d = sheet("Add a contact", body);
        scan.setOnClickListener(v -> {
            d.dismiss();
            mAct.scanQr(this::onScanned);
        });
        intro.setOnClickListener(v -> {
            if (introduceAddr(paste.getText().toString())) {
                d.dismiss();
            }
        });
    }

    /** Called by MainActivity with a scanned address; introduce to it. */
    public void onScanned(String zText) {
        introduceAddr(zText);
    }

    /** Validate + fire the introduce handshake. Returns true if accepted. */
    private boolean introduceAddr(String zText) {
        if (zText == null) {
            return false;
        }
        final String addr = zText.trim();
        if (addr.isEmpty()) {
            mAct.toast("Paste their Mx…@host:port address");
            return false;
        }
        if (!addr.startsWith("Mx") && !addr.startsWith("MAX#")) {
            mAct.toast("Not a Parlons address");
            return false;
        }
        final MaximaNode node = MaximaService.node();
        if (node == null) {
            mAct.toast("Transport not running");
            return false;
        }
        EventLog.add("introducing ourselves to " + addr.substring(Math.max(0, addr.indexOf('@'))));
        new Thread(() -> {
            try {
                node.introduce(addr, true);
                mAct.runOnUiThread(() -> {
                    mAct.toast("Introduction sent — waiting for them to reply");
                    mLastSignature = "";
                    render();
                });
            } catch (Exception e) {
                EventLog.add("introduce failed: " + e.getMessage());
                mAct.runOnUiThread(() -> mAct.toast("Failed: " + e.getMessage()));
            }
        }, "introduce").start();
        return true;
    }

    private void shareText(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        mAct.startActivity(Intent.createChooser(i, "Share your Parlons address"));
    }

    // ---------------------------------------------------------------
    // Address helpers — appended host is ALWAYS an IP, never a domain.
    // ---------------------------------------------------------------

    /** Prefer an address whose host is already an IP; else the first one. */
    private String pickShareAddrRaw() {
        MaximaNode node = MaximaService.node();
        if (node == null) {
            return null;
        }
        List<String> addrs = node.myAddresses();
        for (String a : addrs) {
            if (isIp(hostOf(a))) {
                return a;
            }
        }
        return addrs.isEmpty() ? null : addrs.get(0);
    }

    /** {@code Mx…@host:port} → {@code Mx…@<ip>:port}, resolving a domain via DNS.
     *  Call off-main. Returns null rather than ever handing out a domain form:
     *  the rule is that OUR shared address always carries an IP host, never a
     *  domain (a domain can be repointed or shared across hosts). A malformed
     *  input with no '@' has no host to leak and is returned unchanged. */
    private String toIpForm(String addr) {
        int at = addr == null ? -1 : addr.lastIndexOf('@');
        if (at < 0) {
            return addr;
        }
        String mx = addr.substring(0, at);
        String hp = addr.substring(at + 1);
        // Bracketed IPv6 literal ([2001:db8::1]:port): the host itself contains
        // colons, so never split on the last colon.
        if (hp.startsWith("[")) {
            return addr;
        }
        int colon = hp.lastIndexOf(':');
        String host = colon < 0 ? hp : hp.substring(0, colon);
        String port = colon < 0 ? "" : hp.substring(colon);
        if (isIp(host)) {
            return addr;
        }
        try {
            java.net.InetAddress ia = java.net.InetAddress.getByName(host);
            return mx + "@" + ia.getHostAddress() + port;
        } catch (Exception e) {
            // Can't produce an IP — refuse to share a domain-based address.
            return null;
        }
    }

    private String hostOf(String addr) {
        int at = addr.lastIndexOf('@');
        if (at < 0) {
            return "";
        }
        String hp = addr.substring(at + 1);
        // Bracketed IPv6 literal: host is what's inside the brackets.
        if (hp.startsWith("[")) {
            int close = hp.indexOf(']');
            return close > 1 ? hp.substring(1, close) : hp;
        }
        int colon = hp.lastIndexOf(':');
        return colon < 0 ? hp : hp.substring(0, colon);
    }

    /** True for an IPv4 dotted-quad or an IPv6 literal (colon-bearing) host. */
    private boolean isIp(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        return host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    // ---------------------------------------------------------------
    // Presence / pill labels
    // ---------------------------------------------------------------

    private String presenceText(Contact c) {
        long ls = c.lastSeen;
        if (ls <= 0) {
            return "offline";
        }
        long d = System.currentTimeMillis() - ls;
        if (d < ONLINE_MS) {
            return "online";
        }
        return "last seen " + ago(d);
    }

    private String lastSeenText(Contact c) {
        if (c.lastSeen <= 0) {
            return "never";
        }
        return ago(System.currentTimeMillis() - c.lastSeen) + " ago";
    }

    private String ago(long d) {
        long mins = d / 60000L;
        if (mins < 1) {
            return "moments";
        }
        if (mins < 60) {
            return mins + "m";
        }
        long hours = mins / 60;
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }

    private String statusLabel(Contact c) {
        long ls = c.lastSeen;
        if (ls <= 0) {
            return "offline";
        }
        long d = System.currentTimeMillis() - ls;
        return d < ONLINE_MS ? "online" : ago(d);
    }

    private String peerPill(Contact c) {
        if (c.kind != null && c.kind.equalsIgnoreCase("core")) {
            return "core";
        }
        boolean hasChain = false;
        try {
            hasChain = c.topBlock != null && Long.parseLong(c.topBlock.trim()) > 0;
        } catch (NumberFormatException ignored) {
            hasChain = false;
        }
        if (hasChain) {
            return "core";
        }
        if (c.isClassic()) {
            return "classic";
        }
        return null;
    }

    private String softwareLabel(Contact c) {
        String pill = peerPill(c);
        if ("core".equals(pill)) {
            return "Minima Core";
        }
        if ("classic".equals(pill)) {
            return "Classic Maxima";
        }
        return "Parlons app";
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private void buildUi() {
        // Identity card.
        LinearLayout id = card();
        LinearLayout idrow = new LinearLayout(mAct);
        idrow.setOrientation(LinearLayout.HORIZONTAL);
        idrow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ico = new ImageView(mAct);
        ico.setImageResource(R.drawable.ic_qr);
        // The disc is always dark, so the glyph is always white (not ux_on_accent,
        // which would invert to dark in dark mode and vanish).
        ico.setColorFilter(0xFFFFFFFF);
        ico.setBackgroundResource(R.drawable.coin_minima_bg);
        int ip = dp(11);
        ico.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams iconlp = new LinearLayout.LayoutParams(dp(46), dp(46));
        iconlp.rightMargin = dp(13);
        idrow.addView(ico, iconlp);
        LinearLayout idcol = new LinearLayout(mAct);
        idcol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(mAct);
        title.setText("Your Parlons address");
        title.setTextSize(16);
        title.setTypeface(manrope(Typeface.BOLD));
        title.setTextColor(col(R.color.ux_text));
        mHostInfo = sub("…");
        idcol.addView(title);
        idcol.addView(mHostInfo);
        idrow.addView(idcol, new LinearLayout.LayoutParams(0, -2, 1f));
        id.addView(idrow);

        LinearLayout actions = new LinearLayout(mAct);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-1, -2);
        acp.topMargin = dp(14);
        TextView share = primaryButton("Share my address");
        TextView scan = ghostButton("Scan");
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 2f);
        l.rightMargin = dp(5);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, -2, 1f);
        r.leftMargin = dp(5);
        actions.addView(share, l);
        actions.addView(scan, r);
        id.addView(actions, acp);
        share.setOnClickListener(v -> showMyAddressSheet());
        scan.setOnClickListener(v -> mAct.scanQr(this::onScanned));
        mBox.addView(id, marginBottom(dp(4)));

        // Search field.
        LinearLayout searchRow = new LinearLayout(mAct);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackgroundResource(R.drawable.field_pill);
        searchRow.setPadding(dp(13), dp(2), dp(13), dp(2));
        ImageView si = new ImageView(mAct);
        si.setImageResource(R.drawable.ic_search);
        si.setColorFilter(col(R.color.ux_subtext));
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(dp(18), dp(18));
        silp.rightMargin = dp(9);
        searchRow.addView(si, silp);
        mSearch = new EditText(mAct);
        mSearch.setHint("Search contacts…");
        mSearch.setBackground(null);
        mSearch.setTextColor(col(R.color.ux_text));
        mSearch.setHintTextColor(col(R.color.ux_subtext));
        mSearch.setTextSize(14);
        mSearch.setSingleLine(true);
        mSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                mSearchQuery = e.toString();
                MaximaNode node = MaximaService.node();
                rebuildList(node == null ? new ArrayList<>() : node.contacts());
            }
        });
        searchRow.addView(mSearch, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = dp(14);
        mBox.addView(searchRow, srp);

        // Section header: CONTACTS · N   + Add
        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(18), dp(4), dp(9));
        mCountLabel = new TextView(mAct);
        mCountLabel.setText("CONTACTS");
        mCountLabel.setTextSize(10);
        mCountLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mCountLabel.setLetterSpacing(0.15f);
        mCountLabel.setTextColor(col(R.color.ux_subtext));
        head.addView(mCountLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = new TextView(mAct);
        add.setText("+ Add");
        add.setTextSize(12.5f);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setTextColor(col(R.color.ux_text));
        add.setPadding(dp(8), dp(4), dp(4), dp(4));
        add.setClickable(true);
        add.setBackground(ripple());
        add.setOnClickListener(v -> showAddSheet());
        head.addView(add);
        mBox.addView(head);

        // List card.
        LinearLayout listCard = card();
        mList = new LinearLayout(mAct);
        mList.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(mList);
        mBox.addView(listCard, marginBottom(dp(12)));
    }

    // ---------------------------------------------------------------
    // Sheet + widget helpers
    // ---------------------------------------------------------------

    private BottomSheetDialog sheet(String titleText, View content) {
        LinearLayout root = new LinearLayout(mAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.sheet_bg);
        root.setPadding(dp(20), dp(8), dp(20), dp(24));

        View grab = new View(mAct);
        grab.setBackgroundResource(R.drawable.grab_handle);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(10);
        root.addView(grab, gp);

        TextView t = new TextView(mAct);
        t.setText(titleText);
        t.setTextSize(17);
        t.setTypeface(manrope(Typeface.BOLD));
        t.setTextColor(col(R.color.ux_text));
        t.setPadding(0, 0, 0, dp(14));
        root.addView(t);

        ScrollView sv = new ScrollView(mAct);
        sv.addView(content);
        root.addView(sv);

        BottomSheetDialog d = new BottomSheetDialog(mAct);
        d.setContentView(root);
        View bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) {
            bs.setBackgroundColor(Color.TRANSPARENT);
        }
        d.show();
        return d;
    }

    private LinearLayout kv(String key, String value) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView k = new TextView(mAct);
        k.setText(key.toUpperCase());
        k.setTextSize(10);
        k.setLetterSpacing(0.1f);
        k.setTextColor(col(R.color.ux_subtext));
        TextView v = new TextView(mAct);
        v.setText(value);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.END);
        v.setTextColor(col(R.color.ux_text));
        row.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.4f));
        return row;
    }

    private LinearLayout copyField(String labelText, String value, String toastMsg) {
        LinearLayout box = new LinearLayout(mAct);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.field_pill);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(12);
        box.setLayoutParams(bp);

        LinearLayout head = new LinearLayout(mAct);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(mAct);
        l.setText(labelText.toUpperCase());
        l.setTextSize(10);
        l.setLetterSpacing(0.12f);
        l.setTextColor(col(R.color.ux_subtext));
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView cp = new ImageView(mAct);
        cp.setImageResource(R.drawable.ic_copy);
        cp.setColorFilter(col(R.color.ux_subtext));
        head.addView(cp, new LinearLayout.LayoutParams(dp(15), dp(15)));
        box.addView(head);

        TextView v = new TextView(mAct);
        v.setText(value);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(12);
        v.setPadding(0, dp(7), 0, 0);
        v.setTextColor(col(R.color.ux_text));
        v.setTextIsSelectable(true);
        box.addView(v);

        box.setOnClickListener(view -> {
            ((ClipboardManager) mAct.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("maxima", value));
            mAct.toast(toastMsg);
        });
        return box;
    }

    private View divider() {
        View d = new View(mAct);
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        d.setBackgroundColor(col(R.color.ux_divider));
        return d;
    }

    private android.graphics.drawable.Drawable ripple() {
        android.util.TypedValue tv = new android.util.TypedValue();
        mAct.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return androidx.core.content.ContextCompat.getDrawable(mAct, tv.resourceId);
    }

    private TextView primaryButton(String text) {
        TextView b = new TextView(mAct);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setTypeface(manrope(Typeface.BOLD));
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setTextColor(col(R.color.ux_on_accent));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView ghostButton(String text) {
        TextView b = new TextView(mAct);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setTypeface(manrope(Typeface.BOLD));
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setTextColor(col(R.color.ux_text));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(mAct);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout.LayoutParams marginBottom(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px;
        return lp;
    }

    private LinearLayout.LayoutParams marginTB(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px;
        lp.bottomMargin = px;
        return lp;
    }

    private TextView sub(String t) {
        TextView v = new TextView(mAct);
        v.setText(t);
        v.setTextSize(12);
        v.setTextColor(col(R.color.ux_subtext));
        return v;
    }

    private Typeface manrope(int style) {
        try {
            Typeface base = androidx.core.content.res.ResourcesCompat.getFont(mAct, R.font.manrope);
            return Typeface.create(base, style);
        } catch (Exception e) {
            return Typeface.defaultFromStyle(style);
        }
    }

    private int col(int res) {
        return mAct.getResources().getColor(res, mAct.getTheme());
    }

    private int dp(float v) {
        return (int) (v * mAct.getResources().getDisplayMetrics().density);
    }
}
