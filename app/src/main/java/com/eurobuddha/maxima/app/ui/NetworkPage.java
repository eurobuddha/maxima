package com.eurobuddha.maxima.app.ui;

import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.maxima.app.AndroidContribution;
import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.MainActivity;
import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.MlsStore;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.RelayStore;
import com.eurobuddha.maxima.core.MaximaNode;

import java.util.List;

/**
 * Is it working, and what is it doing for other people.
 *
 * This is the only screen with raw figures on it, and every one of them carries
 * a "?" - a transport that shows you numbers you cannot interpret is just as
 * opaque as one that shows you nothing.
 */
public final class NetworkPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mStats;
    private final LinearLayout mHosts;
    private final LinearLayout mContrib;
    private final TextView mMls;
    private final TextView mContribution;
    private final TextView mLog;
    private final EditText mNewRelay;
    private final EditText mNewMls;

    public NetworkPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mStats = zView.findViewById(R.id.stats_container);
        mHosts = zView.findViewById(R.id.hosts_container);
        mContrib = zView.findViewById(R.id.contrib_container);
        mMls = zView.findViewById(R.id.mls);
        mContribution = zView.findViewById(R.id.contribution);
        mLog = zView.findViewById(R.id.log);
        mNewRelay = zView.findViewById(R.id.new_relay);
        mNewMls = zView.findViewById(R.id.new_mls);

        zView.findViewById(R.id.q_status).setOnClickListener(v -> Explain.show(mAct, "status"));
        zView.findViewById(R.id.q_hosts).setOnClickListener(v -> Explain.show(mAct, "hosts"));
        zView.findViewById(R.id.q_mls).setOnClickListener(v -> Explain.show(mAct, "mls"));
        zView.findViewById(R.id.q_contribution)
                .setOnClickListener(v -> Explain.show(mAct, "contribution"));
        zView.findViewById(R.id.q_log).setOnClickListener(v -> Explain.show(mAct, "log"));

        zView.findViewById(R.id.btn_add_relay).setOnClickListener(v -> addRelay());
        zView.findViewById(R.id.btn_relays).setOnClickListener(v -> manageRelays());
        zView.findViewById(R.id.btn_set_mls).setOnClickListener(v -> setMls());
        zView.findViewById(R.id.btn_clear_mls).setOnClickListener(v -> clearMls());
        zView.findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            EventLog.clear();
            render();
        });
        zView.findViewById(R.id.btn_contrib_toggle).setOnClickListener(v -> {
            boolean now = !AndroidContribution.isEnabled(mAct);
            AndroidContribution.setEnabled(mAct, now);
            EventLog.add("contribution " + (now ? "enabled" : "disabled"));
            render();
        });
        zView.findViewById(R.id.btn_contrib_metered).setOnClickListener(v -> {
            boolean now = !AndroidContribution.unmeteredOnly(mAct);
            AndroidContribution.setUnmeteredOnly(mAct, now);
            EventLog.add("heavy duties " + (now ? "wifi only" : "any network"));
            render();
        });
    }

    @Override
    public View view() {
        return mView;
    }

    @Override
    public CharSequence title() {
        return "Network";
    }

    @Override
    public void render() {
        MaximaNode node = MaximaService.node();
        mLog.setText(EventLog.asText(40));
        if (node == null) {
            return;
        }

        int hosts = node.pool().activeCount();
        mStats.removeAllViews();
        Ui.state(mAct, mStats, "Reachable",
                hosts > 0 ? "People can reach you right now"
                        : "Nobody can reach you until a host accepts you",
                hosts > 0 ? "YES" : "NO",
                Ui.colour(mAct, hosts > 0 ? R.color.ux_success : R.color.ux_error),
                "status");
        Ui.stat(mAct, mStats, "Hosts connected",
                "Independent routes to you. More is safer.",
                String.valueOf(hosts), "hosts");
        Ui.stat(mAct, mStats, "Contacts",
                "People who can reach you and you them",
                String.valueOf(node.contacts().size()), "contacts");
        Ui.stat(mAct, mStats, "Mailbox held for others",
                "Encrypted messages you are storing for offline peers",
                String.valueOf(node.mailbox().totalItems()), "mailbox");
        Ui.stat(mAct, mStats, "Your outbox",
                "Your messages still waiting to be accepted by a host",
                String.valueOf(node.outbox().size()), "outbox");
        Ui.stat(mAct, mStats, "Services you answer",
                "Requests this phone can serve for other people",
                String.valueOf(node.services().methods().size()), "services");

        // ---- hosts ----
        mHosts.removeAllViews();
        List<String> active = node.pool().activeHosts();
        for (String h : active) {
            Ui.mono(mAct, mHosts, "  connected   " + h, Ui.colour(mAct, R.color.ux_success));
        }
        for (String cand : RelayStore.get(mAct)) {
            if (!active.contains(cand)) {
                Ui.mono(mAct, mHosts, "  --          " + cand, Ui.colour(mAct, R.color.ux_subtext));
            }
        }
        if (active.isEmpty() && RelayStore.get(mAct).isEmpty()) {
            Ui.mono(mAct, mHosts, "  no hosts configured", Ui.colour(mAct, R.color.ux_error));
        }

        // ---- MLS ----
        String mls = node.mlsAddress();
        if (mls.isEmpty()) {
            mMls.setText("none - no host has offered one");
        } else {
            int at = mls.indexOf('@');
            mMls.setText((node.isStaticMls() ? "PINNED   " : "from host   ")
                    + mls.substring(0, Math.min(20, mls.length())) + "…"
                    + (at < 0 ? "" : mls.substring(at)));
        }

        // ---- contribution ----
        AndroidContribution pol = MaximaService.policy();
        if (pol != null) {
            String[] lines = pol.describe().split("\n");
            mContribution.setText(lines.length > 1 ? lines[1].trim() : pol.describe());

            mContrib.removeAllViews();
            boolean on = AndroidContribution.isEnabled(mAct);
            Ui.state(mAct, mContrib, "Contributing",
                    on ? "This phone is helping carry the network"
                            : "This phone is taking only",
                    on ? "ON" : "OFF",
                    Ui.colour(mAct, on ? R.color.ux_success : R.color.ux_subtext),
                    "contribution");
            Ui.stat(mAct, mContrib, "Witness + directory",
                    "Answering lookups and signing delivery evidence",
                    on ? "on" : "off", "witness");
            Ui.stat(mAct, mContrib, "Storage",
                    "Replicating small encrypted blobs for others",
                    on ? "on" : "off", "storage");
            Ui.stat(mAct, mContrib, "Network limit",
                    AndroidContribution.unmeteredOnly(mAct)
                            ? "Heavy duties pause on mobile data"
                            : "Heavy duties run on any network",
                    AndroidContribution.unmeteredOnly(mAct) ? "wifi" : "any", "gates");
            Ui.stat(mAct, mContrib, "Counters",
                    node.tier1().contributionSummary(), "", "counters");

            ((Button) mView.findViewById(R.id.btn_contrib_toggle))
                    .setText(on ? "Turn off" : "Turn ON");
            ((Button) mView.findViewById(R.id.btn_contrib_metered))
                    .setText(AndroidContribution.unmeteredOnly(mAct)
                            ? "Wifi only ✓" : "Any network");
        }
    }

    // ---------------------------------------------------------------

    private void addRelay() {
        String hp = mNewRelay.getText().toString().trim();
        if (!RelayStore.isValid(hp)) {
            mAct.toast("Enter host:port, e.g. 31.125.188.214:8001");
            return;
        }
        RelayStore.add(mAct, hp);
        mNewRelay.setText("");
        EventLog.add("host added: " + hp + " (restarting transport)");
        mAct.toast("Added. Restarting transport…");
        // The cleanest correct way to pick up a new host is to bounce.
        mAct.stopService(new Intent(mAct, com.eurobuddha.maxima.app.MaximaService.class));
        mView.postDelayed(() -> MaximaService.start(mAct), 1500);
    }

    private void manageRelays() {
        List<String> relays = RelayStore.get(mAct);
        String[] arr = relays.toArray(new String[0]);
        new AlertDialog.Builder(mAct)
                .setTitle("Hosts - tap to remove")
                .setItems(arr, (d, which) -> {
                    RelayStore.remove(mAct, arr[which]);
                    EventLog.add("host removed: " + arr[which]);
                    mAct.toast("Removed " + arr[which]);
                    render();
                })
                .setNeutralButton("Reset to defaults", (d, w) -> {
                    RelayStore.reset(mAct);
                    mAct.toast("Reset");
                    render();
                })
                .setPositiveButton("Close", null)
                .show();
    }

    /** Classic: maxextra action:staticmls host:… */
    private void setMls() {
        String m = mNewMls.getText().toString().trim();
        if (!m.startsWith("Mx") || !m.contains("@") || !m.contains(":")) {
            mAct.toast("Needs the form Mx…@host:port");
            return;
        }
        MaximaNode node = MaximaService.node();
        if (node == null) {
            mAct.toast("Transport not running");
            return;
        }
        node.setStaticMls(m);
        MlsStore.save(mAct, m);
        mNewMls.setText("");
        EventLog.add("static MLS pinned");
        new Thread(node::refreshContacts, "refresh-mls").start();
        mAct.toast("MLS pinned");
        render();
    }

    private void clearMls() {
        MaximaNode node = MaximaService.node();
        if (node != null) {
            node.setStaticMls("");
        }
        MlsStore.save(mAct, "");
        EventLog.add("static MLS cleared - will use whatever a host offers");
        mAct.toast("Using the host's directory");
        render();
    }
}
