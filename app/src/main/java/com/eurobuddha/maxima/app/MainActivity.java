package com.eurobuddha.maxima.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole UI, on one screen.
 *
 * Scope is deliberately narrow: everything needed to TEST the transport on a
 * real handset and nothing else. You must be able to see whether you are
 * attached, hand someone your address, add theirs, send a message, and read
 * what happened when it does not work. Without those five things the app is a
 * black box and a soak test tells you nothing.
 */
public final class MainActivity extends AppCompatActivity {

    private TextView mStatus;
    private TextView mAddress;
    private TextView mLog;
    private TextView mContribution;
    private Spinner mContactPicker;
    private EditText mMessage;
    private EditText mNewContact;
    private EditText mNewRelay;
    private EditText mNewMls;
    private EditText mNameField;
    private TextView mHosts;
    private TextView mMls;
    private TextView mAddressNote;
    private boolean mNameTouched;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Contact> mContacts = new ArrayList<>();

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            render();
            mHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // targetSdk 35 forces edge-to-edge, so without this the top of the
        // screen sits under the status bar and the bottom under the navigation
        // bar - half the UI unreachable.
        View root = findViewById(R.id.root_scroll);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mStatus = findViewById(R.id.status);
        mAddress = findViewById(R.id.address);
        mLog = findViewById(R.id.log);
        mContribution = findViewById(R.id.contribution);
        mContactPicker = findViewById(R.id.contact_picker);
        mMessage = findViewById(R.id.message);
        mNewContact = findViewById(R.id.new_contact);
        mNewRelay = findViewById(R.id.new_relay);
        mNewMls = findViewById(R.id.new_mls);
        mNameField = findViewById(R.id.name_field);
        mHosts = findViewById(R.id.hosts);
        mMls = findViewById(R.id.mls);
        mAddressNote = findViewById(R.id.address_note);
        mNameField.setText(SeedStore.displayName(this));

        Sha3Provider.install();
        requestBatteryExemption();
        MaximaService.start(this);

        findViewById(R.id.btn_copy).setOnClickListener(v -> copyAddress());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareAddress());
        findViewById(R.id.btn_add_contact).setOnClickListener(v -> addContact());
        findViewById(R.id.btn_send).setOnClickListener(v -> send());
        findViewById(R.id.btn_add_relay).setOnClickListener(v -> addRelay());
        findViewById(R.id.btn_relays).setOnClickListener(v -> showRelays());
        findViewById(R.id.btn_phrase).setOnClickListener(v -> showPhrase());
        findViewById(R.id.btn_contrib_toggle).setOnClickListener(v -> {
            boolean now = !AndroidContribution.isEnabled(this);
            AndroidContribution.setEnabled(this, now);
            EventLog.add("contribution " + (now ? "enabled" : "disabled"));
            render();
        });
        findViewById(R.id.btn_contrib_metered).setOnClickListener(v -> {
            boolean now = !AndroidContribution.unmeteredOnly(this);
            AndroidContribution.setUnmeteredOnly(this, now);
            EventLog.add("heavy duties " + (now ? "wifi only" : "any network"));
            render();
        });
        findViewById(R.id.btn_set_name).setOnClickListener(v -> setName());
        findViewById(R.id.btn_set_mls).setOnClickListener(v -> setMls());
        findViewById(R.id.btn_clear_mls).setOnClickListener(v -> clearMls());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            EventLog.clear();
            render();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRefresh);
    }

    // ---------------------------------------------------------------

    private void render() {
        MaximaNode node = MaximaService.node();

        if (node == null) {
            mStatus.setText("Transport starting...");
            mAddress.setText("");
            mLog.setText(EventLog.asText(40));
            return;
        }

        int relays = node.pool().activeCount();
        StringBuilder s = new StringBuilder();
        s.append(relays > 0 ? "CONNECTED" : "NOT CONNECTED").append("   ");
        s.append(relays).append(" relay(s)");
        s.append("   contacts ").append(node.contacts().size());
        s.append("   mailbox ").append(node.mailbox().totalItems());
        s.append("\nservices ").append(node.services().methods().size());
        s.append("   outbox ").append(node.outbox().size());
        mStatus.setText(s.toString());

        // ONE address, like classic's `contact` field. The others are real and
        // usable, but a human copying an address should get one address.
        List<String> addrs = node.myAddresses();
        if (addrs.isEmpty()) {
            mAddress.setText("(none yet - not attached to a host)");
            mAddressNote.setText("");
        } else {
            mAddress.setText(addrs.get(0));
            mAddressNote.setText(addrs.size() == 1
                    ? "reachable via 1 host"
                    : "also reachable via " + (addrs.size() - 1) + " more host(s) - "
                    + "any of them works, this is multi-homing");
        }

        StringBuilder hb = new StringBuilder();
        List<String> hosts = node.pool().activeHosts();
        if (hosts.isEmpty()) {
            hb.append("(none connected)");
        } else {
            for (String h : hosts) {
                hb.append("  connected  ").append(h).append('\n');
            }
        }
        for (String cand : RelayStore.get(this)) {
            if (!hosts.contains(cand)) {
                hb.append("  --         ").append(cand).append('\n');
            }
        }
        mHosts.setText(hb.toString().trim());

        String mls = node.mlsAddress();
        mMls.setText(mls.isEmpty()
                ? "(none - no host has offered one)"
                : (node.isStaticMls() ? "PINNED   " : "from host  ")
                + mls.substring(0, Math.min(24, mls.length())) + "..."
                + mls.substring(mls.indexOf('@') < 0 ? mls.length() : mls.indexOf('@')));

        // Repopulate the contact picker only when it actually changed.
        List<Contact> cs = node.contacts();
        if (cs.size() != mContacts.size()) {
            mContacts.clear();
            mContacts.addAll(cs);
            List<String> labels = new ArrayList<>();
            for (Contact c : mContacts) {
                labels.add(c.name + "  " + c.publicKey.substring(0, 14) + "...");
            }
            if (labels.isEmpty()) {
                labels.add("(no contacts yet)");
            }
            ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, labels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mContactPicker.setAdapter(ad);
        }

        AndroidContribution pol = MaximaService.policy();
        if (pol != null) {
            mContribution.setText(pol.describe()
                    + "\n  " + node.tier1().contributionSummary());
            ((Button) findViewById(R.id.btn_contrib_toggle))
                    .setText(AndroidContribution.isEnabled(this) ? "Turn off" : "Turn ON");
            ((Button) findViewById(R.id.btn_contrib_metered))
                    .setText(AndroidContribution.unmeteredOnly(this)
                            ? "Wifi only \u2713" : "Any network");
        }

        mLog.setText(EventLog.asText(40));
    }

    private String myPrimaryAddress() {
        MaximaNode node = MaximaService.node();
        if (node == null || node.myAddresses().isEmpty()) {
            return null;
        }
        return node.myAddresses().get(0);
    }

    private void copyAddress() {
        String a = myPrimaryAddress();
        if (a == null) {
            toast("No address yet - wait until a relay connects");
            return;
        }
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("maxima address", a));
        toast("Address copied");
    }

    private void shareAddress() {
        String a = myPrimaryAddress();
        if (a == null) {
            toast("No address yet");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, a);
        startActivity(Intent.createChooser(i, "Share your Maxima address"));
    }

    /**
     * Adding a contact means introducing ourselves to them. They reciprocate,
     * and only then do we have a two-way link - the same handshake classic uses.
     */
    private void addContact() {
        String addr = mNewContact.getText().toString().trim();
        if (addr.isEmpty()) {
            toast("Paste their Mx...@host:port address");
            return;
        }
        MaximaNode node = MaximaService.node();
        if (node == null) {
            toast("Transport not running");
            return;
        }
        EventLog.add("introducing ourselves to " + addr.substring(Math.max(0, addr.indexOf('@'))));
        new Thread(() -> {
            try {
                node.introduce(addr, true);
                runOnUiThread(() -> {
                    mNewContact.setText("");
                    toast("Introduction sent - waiting for them to reply");
                });
            } catch (Exception e) {
                EventLog.add("introduce failed: " + e.getMessage());
                runOnUiThread(() -> toast("Failed: " + e.getMessage()));
            }
        }, "introduce").start();
    }

    private void send() {
        String text = mMessage.getText().toString().trim();
        if (text.isEmpty()) {
            toast("Type a message");
            return;
        }
        int pos = mContactPicker.getSelectedItemPosition();
        if (mContacts.isEmpty() || pos < 0 || pos >= mContacts.size()) {
            toast("No contact selected");
            return;
        }
        Contact c = mContacts.get(pos);
        String addr = c.primaryAddress();
        if (addr == null) {
            toast("No known address for " + c.name);
            return;
        }
        new Thread(() -> {
            String err = MaximaService.sendChat(addr, text);
            runOnUiThread(() -> {
                if (err == null) {
                    mMessage.setText("");
                    toast("Sent");
                } else {
                    toast("Failed: " + err);
                }
            });
        }, "send").start();
    }

    private void addRelay() {
        String hp = mNewRelay.getText().toString().trim();
        if (!RelayStore.isValid(hp)) {
            toast("Enter host:port, e.g. 31.125.188.214:8001");
            return;
        }
        RelayStore.add(this, hp);
        mNewRelay.setText("");
        EventLog.add("relay added: " + hp + " (restarting transport)");
        toast("Added. Restarting transport...");
        // Simplest correct way to pick up a new relay: bounce the service.
        stopService(new Intent(this, MaximaService.class));
        mHandler.postDelayed(() -> MaximaService.start(this), 1500);
    }

    private void showRelays() {
        List<String> relays = RelayStore.get(this);
        String[] arr = relays.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Relays (tap to remove)")
                .setItems(arr, (d, which) -> {
                    RelayStore.remove(this, arr[which]);
                    EventLog.add("relay removed: " + arr[which]);
                    toast("Removed " + arr[which]);
                })
                .setNeutralButton("Reset to defaults", (d, w) -> {
                    RelayStore.reset(this);
                    toast("Reset");
                })
                .setPositiveButton("Close", null)
                .show();
    }

    /**
     * The phrase is a spendable Minima wallet seed, so showing it is a
     * deliberate act with a warning attached, not a casual screen.
     */
    private void showPhrase() {
        new AlertDialog.Builder(this)
                .setTitle("Show seed phrase?")
                .setMessage("This phrase is ALSO a Minima wallet seed. Anyone who sees it "
                        + "can restore your identity AND spend any funds sent to it.\n\n"
                        + "Make sure nobody is looking.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Show", (d, w) -> {
                    String phrase = SeedStore.revealPhrase(this);
                    new AlertDialog.Builder(this)
                            .setTitle("Seed phrase - write it down")
                            .setMessage(phrase == null ? "(none)" : phrase)
                            .setPositiveButton("Copy", (d2, w2) -> {
                                ClipboardManager cm = getSystemService(ClipboardManager.class);
                                cm.setPrimaryClip(ClipData.newPlainText("seed", phrase));
                                toast("Copied - clear your clipboard afterwards");
                            })
                            .setNegativeButton("Close", null)
                            .show();
                })
                .show();
    }

    /** Classic: maxima action:setname. Contacts see this. */
    private void setName() {
        String n = mNameField.getText().toString().trim();
        if (n.isEmpty()) {
            toast("Enter a name");
            return;
        }
        SeedStore.setDisplayName(this, n);
        MaximaNode node = MaximaService.node();
        if (node != null) {
            node.setName(n);
            EventLog.add("name set to \"" + n + "\" - telling contacts");
            // Contacts hold the old name until we tell them, same as classic's
            // refresh on setname.
            new Thread(node::refreshContacts, "refresh-name").start();
        }
        toast("Name set to " + n);
    }

    /** Classic: maxextra action:staticmls host:... */
    private void setMls() {
        String m = mNewMls.getText().toString().trim();
        if (!m.startsWith("Mx") || !m.contains("@") || !m.contains(":")) {
            toast("Needs the form Mx...@host:port");
            return;
        }
        MaximaNode node = MaximaService.node();
        if (node == null) {
            toast("Transport not running");
            return;
        }
        node.setStaticMls(m);
        MlsStore.save(this, m);
        mNewMls.setText("");
        EventLog.add("static MLS pinned");
        new Thread(node::refreshContacts, "refresh-mls").start();
        toast("MLS pinned");
    }

    private void clearMls() {
        MaximaNode node = MaximaService.node();
        if (node != null) {
            node.setStaticMls("");
        }
        MlsStore.save(this, "");
        EventLog.add("static MLS cleared - will use whatever a host offers");
        toast("Cleared");
    }

    private void requestBatteryExemption() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {
        }
    }

    private void toast(String zMsg) {
        Toast.makeText(this, zMsg, Toast.LENGTH_SHORT).show();
    }
}
