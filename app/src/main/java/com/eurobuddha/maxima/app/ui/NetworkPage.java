package com.eurobuddha.maxima.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * Is it working, and what is it doing for other people — in the greyscale design
 * language. A node status hero (with the Minima mark and a reachability pill),
 * live transport figures, a scrollable host list you can connect to / manage,
 * real contribution switches, MLS, direct reachability, and the event log.
 *
 * Heavy actions (adding/connecting a host restarts the transport) always confirm
 * with an explanation first, and report which relay and how many hosts are
 * connected afterwards.
 */
public final class NetworkPage implements Page {

    private final MainActivity mAct;
    private final View mView;
    private final LinearLayout mBox;
    private final Kit k;

    private TextView mNodeSub;
    private LinearLayout mPillHolder;
    private LinearLayout mStats;
    private LinearLayout mHostList;
    private EditText mNewRelay;
    private LinearLayout mContrib;
    private TextView mContribNote;
    private LinearLayout mDirect;
    private TextView mMlsText;
    private TextView mLog;

    public NetworkPage(MainActivity zAct, View zView) {
        mAct = zAct;
        mView = zView;
        mBox = zView.findViewById(R.id.network_root);
        k = new Kit(zAct);
        buildUi();
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
        mLog.setText(EventLog.asText(40));
        MaximaNode node = MaximaService.node();
        if (node == null) {
            mNodeSub.setText("starting…");
            setPill("Starting", Kit.WARN);
            return;
        }

        int hosts = node.pool().activeCount();
        mNodeSub.setText(hosts + (hosts == 1 ? " connected host" : " connected hosts"));
        setPill(hosts > 0 ? "Reachable" : "Offline", hosts > 0 ? Kit.OK : Kit.BAD);

        // ---- transport figures ----
        mStats.removeAllViews();
        addStat("Hosts connected", "Independent routes to you. More is safer.",
                String.valueOf(hosts), 0, "hosts");
        addStat("Contacts", "People who can reach you and you them",
                String.valueOf(node.contacts().size()), 0, "contacts");
        addStat("Mailbox held for others", "Encrypted messages you store for offline peers",
                String.valueOf(node.mailbox().totalItems()), k.col(R.color.ux_subtext), "mailbox");
        addStat("Your outbox", "Your messages waiting to be accepted by a host",
                String.valueOf(node.outbox().size()), k.col(R.color.ux_subtext), "outbox");
        addStat("Services you answer", "Requests this phone can serve for others",
                String.valueOf(node.services().methods().size()), 0, "services");

        // ---- hosts ----
        renderHostList(node);

        // ---- contribution ----
        AndroidContribution pol = MaximaService.policy();
        mContrib.removeAllViews();
        if (pol != null) {
            String[] lines = pol.describe().split("\n");
            mContribNote.setText(lines.length > 1 ? lines[1].trim() : pol.describe());
            boolean on = AndroidContribution.isEnabled(mAct);
            mContrib.addView(k.switchRow("Contributing",
                    on ? "This phone is helping carry the network"
                            : "This phone is taking only",
                    on, checked -> {
                        AndroidContribution.setEnabled(mAct, checked);
                        EventLog.add("contribution " + (checked ? "enabled" : "disabled"));
                        render();
                    }));
            boolean wifi = AndroidContribution.unmeteredOnly(mAct);
            mContrib.addView(k.switchRow("Wi-Fi only",
                    wifi ? "Heavy duties pause on mobile data"
                            : "Heavy duties run on any network",
                    wifi, checked -> {
                        AndroidContribution.setUnmeteredOnly(mAct, checked);
                        EventLog.add("heavy duties " + (checked ? "wifi only" : "any network"));
                        render();
                    }));
            mContrib.addView(k.divider());
            mContrib.addView(k.kv("Witness + directory",
                    "Answering lookups and signing delivery evidence",
                    on ? "on" : "off", on ? k.col(R.color.ux_success) : k.col(R.color.ux_subtext)));
            mContrib.addView(k.kv("Storage", "Replicating small encrypted blobs for others",
                    on ? "on" : "off", on ? k.col(R.color.ux_success) : k.col(R.color.ux_subtext)));
            mContrib.addView(k.kv("Counters", node.tier1().contributionSummary(), "", 0));
        } else {
            mContrib.addView(k.sub("Contribution policy starting…"));
        }

        // ---- MLS ----
        String mls = node.mlsAddress();
        if (mls.isEmpty()) {
            mMlsText.setText("None — no host has offered one yet.");
        } else {
            mMlsText.setText((node.isStaticMls() ? "Pinned:  " : "From host:  ") + mls);
        }

        // ---- direct reachability ----
        renderDirect();
    }

    private void renderHostList(MaximaNode node) {
        mHostList.removeAllViews();
        List<String> active = node.pool().activeHosts();
        List<String> configured = RelayStore.get(mAct);
        int shown = 0;
        for (String h : active) {
            if (shown > 0) {
                mHostList.addView(k.divider());
            }
            mHostList.addView(k.hostLine(h, true));
            shown++;
        }
        for (String c : configured) {
            if (active.contains(c)) {
                continue;
            }
            if (shown >= 6) {
                break;
            }
            if (shown > 0) {
                mHostList.addView(k.divider());
            }
            mHostList.addView(k.hostLine(c, false));
            shown++;
        }
        if (shown == 0) {
            mHostList.addView(k.sub("No hosts configured. Add one below to get online."));
        }
        int total = active.size() + countIdle(active, configured);
        if (total > shown) {
            TextView more = k.sub("+ " + (total - shown) + " more in Manage");
            more.setPadding(0, k.dp(8), 0, 0);
            mHostList.addView(more);
        }
    }

    private int countIdle(List<String> active, List<String> configured) {
        int n = 0;
        for (String c : configured) {
            if (!active.contains(c)) {
                n++;
            }
        }
        return n;
    }

    private void renderDirect() {
        com.eurobuddha.maxima.app.direct.DirectReachability d = MaximaService.direct();
        mDirect.removeAllViews();
        if (d == null) {
            mDirect.addView(k.kv("Direct reachability", "starting…", "—", k.col(R.color.ux_subtext)));
            return;
        }
        boolean advertised =
                d.state() == com.eurobuddha.maxima.app.direct.DirectReachability.State.ADVERTISED;
        mDirect.addView(k.kvPill("Direct reachability", d.detail(),
                advertised ? "on" : d.state().name().toLowerCase(),
                advertised ? Kit.OK : Kit.NEUTRAL));
        if (advertised && !d.publicAddress().isEmpty()) {
            mDirect.addView(k.copyField("Public address", d.publicAddress(), "Address copied"));
        }
    }

    // ---------------------------------------------------------------
    // Host connect / manage
    // ---------------------------------------------------------------

    private void addRelay() {
        final String hp = mNewRelay.getText().toString().trim();
        if (!RelayStore.isValid(hp)) {
            mAct.toast("Enter host:port, e.g. 31.125.188.214:8001");
            return;
        }
        connectHost(hp, false);
    }

    /** Connect to (or add + connect to) an external host, with an up-front
     *  explanation and an after-the-fact confirmation of the relay + count. */
    private void connectHost(final String host, final boolean alreadyConfigured) {
        MaximaNode node = MaximaService.node();
        int now = node == null ? 0 : node.pool().activeCount();
        new AlertDialog.Builder(mAct)
                .setTitle(alreadyConfigured ? "Connect to this host?" : "Add and connect?")
                .setMessage(host + "\n\nMaxima will restart its transport to connect to this "
                        + "relay. Your chats and wallet are unaffected. You're currently connected "
                        + "to " + now + " host" + (now == 1 ? "" : "s") + ".")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (d, w) -> {
                    if (!alreadyConfigured) {
                        RelayStore.add(mAct, host);
                        mNewRelay.setText("");
                    }
                    EventLog.add("connecting to host: " + host + " (restarting transport)");
                    mAct.toast("Connecting to " + host + "…");
                    mAct.stopService(new Intent(mAct, MaximaService.class));
                    mView.postDelayed(() -> MaximaService.start(mAct), 1500);
                    // Report the relay + connected count once it has settled.
                    mView.postDelayed(() -> confirmConnected(host), 6000);
                })
                .show();
    }

    private void confirmConnected(String host) {
        MaximaNode node = MaximaService.node();
        int n = node == null ? 0 : node.pool().activeCount();
        boolean up = node != null && node.pool().activeHosts().contains(host);
        render();
        new AlertDialog.Builder(mAct)
                .setTitle(up ? "Connected" : "Reconnecting")
                .setMessage((up ? host + " is connected.\n\n" : host + " is configured and will "
                        + "connect once it accepts you.\n\n")
                        + "You're now connected to " + n + " host" + (n == 1 ? "" : "s") + ".")
                .setPositiveButton("OK", null)
                .show();
    }

    private void manageHosts() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        EditText add = k.field("Add a relay  host:port");
        body.addView(add, k.mb(k.dp(9)));
        TextView addBtn = k.primaryButton("Add & connect");
        body.addView(addBtn, k.mb(k.dp(16)));

        body.addView(k.sectionLabel("Configured hosts"));
        MaximaNode node = MaximaService.node();
        final List<String> active = node == null ? new java.util.ArrayList<>() : node.pool().activeHosts();
        List<String> hosts = RelayStore.get(mAct);
        final BottomSheetDialog[] box = new BottomSheetDialog[1];
        if (hosts.isEmpty()) {
            body.addView(k.sub("None configured."));
        }
        for (String h : hosts) {
            body.addView(manageRow(h, active.contains(h), () -> {
                if (box[0] != null) {
                    box[0].dismiss();
                }
            }));
        }

        TextView reset = k.ghostButton("Reset to defaults");
        body.addView(reset, k.mb(0));
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) reset.getLayoutParams();
        if (rp != null) {
            rp.topMargin = k.dp(14);
        }

        box[0] = k.sheet("Manage hosts", body);
        addBtn.setOnClickListener(v -> {
            String hp = add.getText().toString().trim();
            if (!RelayStore.isValid(hp)) {
                mAct.toast("Enter host:port");
                return;
            }
            box[0].dismiss();
            connectHost(hp, false);
        });
        reset.setOnClickListener(v -> {
            RelayStore.reset(mAct);
            mAct.toast("Reset to defaults");
            box[0].dismiss();
            render();
        });
    }

    private View manageRow(String host, boolean connected, Runnable dismiss) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, k.dp(10), 0, k.dp(10));

        LinearLayout mid = new LinearLayout(mAct);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(mAct);
        h.setText(host);
        h.setTypeface(Typeface.MONOSPACE);
        h.setTextSize(12);
        h.setTextColor(k.col(R.color.ux_text));
        TextView st = new TextView(mAct);
        st.setText(connected ? "Connected" : "Idle");
        st.setTextSize(11);
        st.setTextColor(k.col(connected ? R.color.ux_success : R.color.ux_subtext));
        mid.addView(h);
        mid.addView(st);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!connected) {
            TextView conn = new TextView(mAct);
            conn.setText("Connect");
            conn.setTextSize(12);
            conn.setTypeface(Typeface.DEFAULT_BOLD);
            conn.setTextColor(k.col(R.color.ux_text));
            conn.setPadding(k.dp(10), k.dp(6), k.dp(10), k.dp(6));
            conn.setBackground(k.ripple());
            conn.setOnClickListener(v -> {
                dismiss.run();
                connectHost(host, true);
            });
            row.addView(conn);
        }

        TextView rm = new TextView(mAct);
        rm.setText("Remove");
        rm.setTextSize(12);
        rm.setTypeface(Typeface.DEFAULT_BOLD);
        rm.setTextColor(k.col(R.color.ux_error));
        rm.setPadding(k.dp(10), k.dp(6), k.dp(10), k.dp(6));
        rm.setBackground(k.ripple());
        rm.setOnClickListener(v -> {
            RelayStore.remove(mAct, host);
            EventLog.add("host removed: " + host);
            mAct.toast("Removed " + host);
            st.setText("Removed");
            st.setTextColor(k.col(R.color.ux_subtext));
            render();
        });
        row.addView(rm);
        return row;
    }

    // ---------------------------------------------------------------
    // MLS
    // ---------------------------------------------------------------

    private void pinMls() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(k.sub("Pin a static Location Service so contacts can always find you when "
                + "you move networks. Leave it to the host otherwise."));
        EditText f = k.field("Mx…@host:port");
        body.addView(f, k.mb(k.dp(4)));
        TextView pin = k.primaryButton("Pin this MLS");
        body.addView(pin, k.mb(k.dp(8)));
        TextView clear = k.ghostButton("Use the host's directory");
        body.addView(clear);
        BottomSheetDialog d = k.sheet("Location service", body);
        pin.setOnClickListener(v -> {
            String m = f.getText().toString().trim();
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
            EventLog.add("static MLS pinned");
            new Thread(node::refreshContacts, "refresh-mls").start();
            mAct.toast("MLS pinned");
            d.dismiss();
            render();
        });
        clear.setOnClickListener(v -> {
            MaximaNode node = MaximaService.node();
            if (node != null) {
                node.setStaticMls("");
            }
            MlsStore.save(mAct, "");
            EventLog.add("static MLS cleared");
            mAct.toast("Using the host's directory");
            d.dismiss();
            render();
        });
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private void addStat(String key, String hint, String value, int colorOr0, String explainKey) {
        LinearLayout row = k.kv(key, hint, value, colorOr0);
        row.setBackground(k.ripple());
        row.setOnClickListener(v -> Explain.show(mAct, explainKey));
        mStats.addView(row);
    }

    private void setPill(String text, int kind) {
        mPillHolder.removeAllViews();
        mPillHolder.addView(k.statusPill(text, kind));
    }

    private void buildUi() {
        // Node status hero.
        LinearLayout hero = k.cardTight();
        LinearLayout hrow = new LinearLayout(mAct);
        hrow.setOrientation(LinearLayout.HORIZONTAL);
        hrow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(mAct);
        mark.setBackgroundResource(R.drawable.coin_minima_bg);
        mark.setImageResource(R.drawable.ic_minima_mark);
        mark.setColorFilter(Color.WHITE);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int mp = k.dp(12);
        mark.setPadding(mp, mp, mp, mp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(k.dp(46), k.dp(46));
        mlp.rightMargin = k.dp(13);
        hrow.addView(mark, mlp);
        LinearLayout hcol = new LinearLayout(mAct);
        hcol.setOrientation(LinearLayout.VERTICAL);
        TextView hn = new TextView(mAct);
        hn.setText("Your node");
        hn.setTextSize(16);
        hn.setTypeface(k.manrope(Typeface.BOLD));
        hn.setTextColor(k.col(R.color.ux_text));
        mNodeSub = k.sub("…");
        hcol.addView(hn);
        hcol.addView(mNodeSub);
        hrow.addView(hcol, new LinearLayout.LayoutParams(0, -2, 1f));
        mPillHolder = new LinearLayout(mAct);
        mPillHolder.setGravity(Gravity.CENTER);
        hrow.addView(mPillHolder);
        hero.addView(hrow);
        hero.setOnClickListener(v -> Explain.show(mAct, "status"));
        mBox.addView(hero, k.mb(k.dp(4)));

        // Transport.
        mBox.addView(k.sectionLabel("Transport"));
        LinearLayout tCard = k.card();
        mStats = new LinearLayout(mAct);
        mStats.setOrientation(LinearLayout.VERTICAL);
        tCard.addView(mStats);
        mBox.addView(tCard, k.mb(k.dp(4)));

        // Hosts.
        mBox.addView(k.sectionLabel("Hosts · they relay your traffic"));
        LinearLayout hCard = k.card();
        mHostList = new LinearLayout(mAct);
        mHostList.setOrientation(LinearLayout.VERTICAL);
        hCard.addView(mHostList);
        mNewRelay = k.field("add relay  host:port");
        hCard.addView(mNewRelay, k.mb(k.dp(9)));
        LinearLayout.LayoutParams nrp = (LinearLayout.LayoutParams) mNewRelay.getLayoutParams();
        nrp.topMargin = k.dp(10);
        LinearLayout hbtns = new LinearLayout(mAct);
        hbtns.setOrientation(LinearLayout.HORIZONTAL);
        TextView addBtn = k.primaryButton("Add & connect");
        TextView manageBtn = k.ghostButton("Manage");
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
        l.rightMargin = k.dp(5);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, -2, 1f);
        r.leftMargin = k.dp(5);
        hbtns.addView(addBtn, l);
        hbtns.addView(manageBtn, r);
        hCard.addView(hbtns);
        addBtn.setOnClickListener(v -> addRelay());
        manageBtn.setOnClickListener(v -> manageHosts());
        mBox.addView(hCard, k.mb(k.dp(4)));

        // Contribution.
        mBox.addView(k.sectionLabel("What this phone gives back"));
        LinearLayout cCard = k.card();
        mContribNote = k.sub("");
        mContribNote.setPadding(0, k.dp(2), 0, k.dp(6));
        cCard.addView(mContribNote);
        mContrib = new LinearLayout(mAct);
        mContrib.setOrientation(LinearLayout.VERTICAL);
        cCard.addView(mContrib);
        mBox.addView(cCard, k.mb(k.dp(4)));

        // Location service.
        mBox.addView(k.sectionLabel("Location service · finds you when you move"));
        LinearLayout mCard = k.card();
        mMlsText = new TextView(mAct);
        mMlsText.setTextSize(12);
        mMlsText.setTextColor(k.col(R.color.ux_text));
        mMlsText.setPadding(0, k.dp(4), 0, k.dp(4));
        mCard.addView(mMlsText);
        TextView mlsBtn = k.ghostButton("Location settings");
        mCard.addView(mlsBtn);
        mlsBtn.setOnClickListener(v -> pinMls());
        mBox.addView(mCard, k.mb(k.dp(4)));

        // Direct reachability.
        mBox.addView(k.sectionLabel("Direct reachability · a public address when possible"));
        LinearLayout dCard = k.card();
        mDirect = new LinearLayout(mAct);
        mDirect.setOrientation(LinearLayout.VERTICAL);
        dCard.addView(mDirect);
        mBox.addView(dCard, k.mb(k.dp(4)));

        // Event log.
        LinearLayout logHead = new LinearLayout(mAct);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView logLabel = k.sectionLabel("Event log");
        logHead.addView(logLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clr = new TextView(mAct);
        clr.setText("Clear");
        clr.setTextSize(12);
        clr.setTypeface(Typeface.DEFAULT_BOLD);
        clr.setTextColor(k.col(R.color.ux_subtext));
        clr.setPadding(k.dp(8), k.dp(4), k.dp(4), k.dp(4));
        clr.setBackground(k.ripple());
        clr.setOnClickListener(v -> {
            EventLog.clear();
            render();
        });
        logHead.addView(clr);
        mBox.addView(logHead);
        LinearLayout logCard = k.card();
        mLog = new TextView(mAct);
        mLog.setTypeface(Typeface.MONOSPACE);
        mLog.setTextSize(10.5f);
        mLog.setTextColor(k.col(R.color.ux_subtext));
        mLog.setLineSpacing(k.dp(2), 1f);
        logCard.addView(mLog);
        mBox.addView(logCard, k.mb(k.dp(12)));
    }
}
