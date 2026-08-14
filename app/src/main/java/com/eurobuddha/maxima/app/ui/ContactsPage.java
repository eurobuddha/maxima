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
            sig.append(c.publicKey).append(c.name).append(c.primaryAddress()).append('|');
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
        ((TextView) v.findViewById(R.id.conv_avatar)).setText(Ui.initial(zContact.name));
        ((TextView) v.findViewById(R.id.conv_name)).setText(zContact.name);
        String addr = zContact.primaryAddress();
        ((TextView) v.findViewById(R.id.conv_preview)).setText(
                addr == null ? "no address known - they have not replied yet"
                        : addr.substring(addr.indexOf('@') + 1));
        ((TextView) v.findViewById(R.id.conv_time)).setText(
                zContact.isClassic() ? "classic" : "");

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

    private void details(Contact zContact) {
        StringBuilder sb = new StringBuilder();
        sb.append(zContact.publicKey).append("\n\nAddresses\n");
        if (zContact.addresses.isEmpty()) {
            sb.append("  (none known)\n");
        }
        for (String a : zContact.addresses) {
            sb.append("  ").append(a).append('\n');
        }
        sb.append("\nSoftware: ")
                .append(zContact.isClassic() ? "classic Maxima - no delivery receipts, "
                        + "no offline mailbox" : "Maxima with extensions");
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
