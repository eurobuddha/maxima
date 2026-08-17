package com.eurobuddha.maxima.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.ChatActivity;
import com.eurobuddha.maxima.app.chat.Names;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;

import java.util.ArrayList;
import java.util.List;

/**
 * Who can reach you, and how you reach them.
 *
 * Your own address is at the top because handing it over is the single most
 * common thing you do here - on a network with no usernames and no directory
 * you can search, exchanging addresses IS the onboarding.
 */
public final class ContactsPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final TextView mAddress;
    private final TextView mAddressNote;
    private final EditText mNewContact;
    private final LinearLayout mContacts;
    private final TextView mContactsLabel;
    private final TextView mContactsEmpty;

    /** Only rebuild the contact rows when they actually changed. */
    private String mLastSignature = "";

    /**
     * How recently we must have heard from a contact to show them "online".
     * Matches the reference's 30-min contact-staleness threshold: a reachable
     * peer re-announces on the ~20-min Maxima loop, so 30 min keeps them green
     * with margin, and a peer gone quiet fades to grey after it.
     */
    private static final long ONLINE_MS = 30 * 60 * 1000L;

    public ContactsPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mAddress = zView.findViewById(R.id.address);
        mAddressNote = zView.findViewById(R.id.address_note);
        mNewContact = zView.findViewById(R.id.new_contact);
        mContacts = zView.findViewById(R.id.contacts_container);
        mContactsLabel = zView.findViewById(R.id.contacts_label);
        mContactsEmpty = zView.findViewById(R.id.contacts_empty);

        zView.findViewById(R.id.q_address).setOnClickListener(v -> Explain.show(mAct, "address"));
        zView.findViewById(R.id.q_contacts).setOnClickListener(v -> Explain.show(mAct, "contacts"));
        zView.findViewById(R.id.btn_copy).setOnClickListener(v -> copyAddress());
        zView.findViewById(R.id.btn_share).setOnClickListener(v -> shareAddress());
        zView.findViewById(R.id.btn_qr).setOnClickListener(v -> showQr());
        zView.findViewById(R.id.btn_add_contact).setOnClickListener(v -> addContact());
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
            mAddress.setText("starting…");
            return;
        }

        List<String> addrs = node.myAddresses();
        if (addrs.isEmpty()) {
            mAddress.setText("no address yet");
            mAddressNote.setText("You get an address as soon as a host accepts you. "
                    + "Check the Network tab if this does not clear.");
        } else {
            // ONE address, like classic's `contact` field. The rest are real
            // and usable, but a human copying an address wants one address.
            mAddress.setText(addrs.get(0));
            mAddressNote.setText(addrs.size() == 1
                    ? "Reachable through 1 host."
                    : "Also reachable through " + (addrs.size() - 1)
                    + " more host(s). Any of them works - that is multi-homing, "
                    + "and it is why one operator going down does not lose you.");
        }

        List<Contact> cs = node.contacts();
        StringBuilder sig = new StringBuilder();
        for (Contact c : cs) {
            sig.append(c.publicKey).append(c.name).append(c.primaryAddress())
                    // include the connectivity label so a row rebuilds when a
                    // contact crosses online -> "3h" -> etc., but not every tick
                    .append(statusLabel(c))
                    .append(peerPill(c)).append('|');   // repaint when the pill resolves
        }
        mContactsLabel.setText("CONTACTS  ·  " + cs.size());
        mContactsEmpty.setVisibility(cs.isEmpty() ? View.VISIBLE : View.GONE);
        if (sig.toString().equals(mLastSignature)) {
            return;
        }
        mLastSignature = sig.toString();

        mContacts.removeAllViews();
        for (Contact c : cs) {
            mContacts.addView(row(c));
        }
    }

    private View row(Contact zContact) {
        View v = LayoutInflater.from(mAct)
                .inflate(R.layout.item_conversation, mContacts, false);
        Avatars.apply(v.findViewById(R.id.conv_avatar), zContact.publicKey, zContact.name);
        ((TextView) v.findViewById(R.id.conv_name)).setText(zContact.name);
        String addr = zContact.primaryAddress();
        ((TextView) v.findViewById(R.id.conv_preview)).setText(
                addr == null ? "no address known - they have not replied yet"
                        : addr.substring(addr.indexOf('@') + 1));

        // Connectivity indicator, like classic: a coloured dot + recency, driven
        // by lastSeen. Green when we have heard from them within ONLINE_MS,
        // otherwise grey with how long ago (or "offline" if never heard).
        boolean online = zContact.lastSeen > 0
                && System.currentTimeMillis() - zContact.lastSeen < ONLINE_MS;
        TextView time = v.findViewById(R.id.conv_time);
        time.setText("● " + statusLabel(zContact));
        time.setTextColor(Ui.colour(mAct, online ? R.color.ux_success : R.color.ux_subtext));

        // Peer-software pill. classic Maxima and a stock Minima Core node are NOT
        // the same peer, so they get different pills: a full Core node advertises
        // a real chain tip -> "core" (Minima orange); a chainless classic-Maxima
        // peer with no extensions -> "classic" (muted); our own app -> none.
        TextView badge = v.findViewById(R.id.conv_badge);
        String pill = peerPill(zContact);
        if (pill != null) {
            badge.setText(pill);
            badge.setTextColor(Ui.colour(mAct,
                    pill.equals("core") ? R.color.ux_accent : R.color.ux_subtext));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }

        v.setOnClickListener(x -> {
            Intent i = new Intent(mAct, ChatActivity.class);
            i.putExtra(ChatActivity.EXTRA_CONVERSATION, zContact.publicKey);
            mAct.startActivity(i);
        });
        v.setOnLongClickListener(x -> {
            details(zContact);
            return true;
        });
        return v;
    }

    /**
     * The connectivity word next to the dot: "online" when heard from within
     * ONLINE_MS, else how long ago ("20m", "3h", "2d"), or "offline" if we have
     * never heard from them. lastSeen tracks the last accepted message from them
     * (chat, RPC, or the contact-ctrl refresh), so it fades as a peer goes quiet.
     */
    private String statusLabel(Contact zContact) {
        long ls = zContact.lastSeen;
        if (ls <= 0) {
            return "offline";
        }
        long d = System.currentTimeMillis() - ls;
        if (d < ONLINE_MS) {
            return "online";
        }
        long mins = d / 60000L;
        if (mins < 60) {
            return mins + "m";
        }
        long hours = mins / 60;
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }

    /**
     * The peer-software pill, or null for our own Maxima app. A full Minima Core
     * node advertises a real chain tip in its contact-ctrl (topblock/checkhash
     * != 0), which our chainless app and chainless classic-Maxima relays never
     * do - that is what tells a stock Core node apart from a classic peer.
     */
    private String peerPill(Contact c) {
        // A node running our maxima that declares itself a core/full node.
        if (c.kind != null && c.kind.equalsIgnoreCase("core")) {
            return "core";
        }
        // A stock Minima Core node advertises a real chain tip; our app and
        // chainless classic relays never do. Parse it as a NUMBER > 0 - a plain
        // "not 0x00" string check let a peer fake a "core" pill with junk.
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

    private void details(Contact zContact) {
        StringBuilder sb = new StringBuilder();
        sb.append(zContact.publicKey).append("\n\nAddresses\n");
        if (zContact.addresses.isEmpty()) {
            sb.append("  (none known)\n");
        }
        for (String a : zContact.addresses) {
            sb.append("  ").append(a).append('\n');
        }
        String pill = peerPill(zContact);
        sb.append("\nSoftware: ").append(
                "core".equals(pill) ? "Minima Core node (full chain) - classic Maxima"
                : "classic".equals(pill) ? "classic Maxima - no delivery receipts, no offline mailbox"
                : "Maxima app - extensions (receipts, mailbox, media)");
        new AlertDialog.Builder(mAct)
                .setTitle(zContact.name)
                .setMessage(sb.toString())
                .setNegativeButton("Remove", (d, w) -> new AlertDialog.Builder(mAct)
                        .setTitle("Remove " + zContact.name + "?")
                        .setMessage("They keep your address and can still write to you. "
                                + "This only removes them from your list.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d2, w2) -> {
                            MaximaNode n = MaximaService.node();
                            if (n != null) {
                                n.removeContact(zContact.publicKey);
                            }
                            mLastSignature = "";
                            render();
                        })
                        .show())
                .setPositiveButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------

    private String myPrimaryAddress() {
        MaximaNode node = MaximaService.node();
        return node == null || node.myAddresses().isEmpty() ? null : node.myAddresses().get(0);
    }

    private void copyAddress() {
        String a = myPrimaryAddress();
        if (a == null) {
            mAct.toast("No address yet - wait for a host");
            return;
        }
        ClipboardManager cm = mAct.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("maxima address", a));
        mAct.toast("Address copied");
    }

    private void shareAddress() {
        String a = myPrimaryAddress();
        if (a == null) {
            mAct.toast("No address yet");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, a);
        mAct.startActivity(Intent.createChooser(i, "Share your Maxima address"));
    }

    /** Show my address as a QR for a peer to scan, and offer to scan theirs. */
    private void showQr() {
        String a = myPrimaryAddress();
        if (a == null) {
            mAct.toast("No address yet");
            return;
        }
        int px = (int) (260 * mAct.getResources().getDisplayMetrics().density);
        android.graphics.Bitmap bmp = Qr.encode(a, px);
        LinearLayout box = new LinearLayout(mAct);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = (int) (16 * mAct.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        if (bmp != null) {
            android.widget.ImageView iv = new android.widget.ImageView(mAct);
            iv.setImageBitmap(bmp);
            box.addView(iv);
        }
        TextView t = new TextView(mAct);
        t.setText("Let a friend scan this to add you.");
        t.setTextColor(mAct.getResources().getColor(R.color.ux_subtext, mAct.getTheme()));
        int tp = (int) (10 * mAct.getResources().getDisplayMetrics().density);
        t.setPadding(0, tp, 0, 0);
        box.addView(t);

        new AlertDialog.Builder(mAct)
                .setTitle("Your Maxima QR")
                .setView(box)
                .setPositiveButton("Scan a code", (d, w) -> mAct.scanQr())
                .setNegativeButton("Close", null)
                .show();
    }

    /** Called by MainActivity with a scanned address; introduce to it. */
    public void onScanned(String zText) {
        if (zText == null) {
            return;
        }
        String addr = zText.trim();
        if (!addr.startsWith("Mx") && !addr.startsWith("MAX#")) {
            mAct.toast("Not a Maxima address");
            return;
        }
        mNewContact.setText(addr);
        addContact();
    }

    /**
     * Adding a contact means introducing ourselves to them; they reciprocate.
     * The same handshake classic Maxima uses, so it works with classic peers.
     */
    private void addContact() {
        String addr = mNewContact.getText().toString().trim();
        if (addr.isEmpty()) {
            mAct.toast("Paste their Mx…@host:port address");
            return;
        }
        MaximaNode node = MaximaService.node();
        if (node == null) {
            mAct.toast("Transport not running");
            return;
        }
        EventLog.add("introducing ourselves to " + addr.substring(Math.max(0, addr.indexOf('@'))));
        new Thread(() -> {
            try {
                node.introduce(addr, true);
                mAct.runOnUiThread(() -> {
                    mNewContact.setText("");
                    mAct.toast("Introduction sent - waiting for them to reply");
                    mLastSignature = "";
                    render();
                });
            } catch (Exception e) {
                EventLog.add("introduce failed: " + e.getMessage());
                mAct.runOnUiThread(() -> mAct.toast("Failed: " + e.getMessage()));
            }
        }, "introduce").start();
    }
}
