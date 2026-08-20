package com.eurobuddha.maxima.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
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
import com.eurobuddha.maxima.app.ManualForward;
import com.eurobuddha.maxima.app.MlsStore;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.RelayStore;
import com.eurobuddha.maxima.app.direct.DirectReachability;
import com.eurobuddha.maxima.app.net.ConnectionFinder;
import com.eurobuddha.maxima.app.relay.RelayHost;
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
    private TextView mAutoBtn;
    private boolean mVerifying;
    /** Signature of the state the heavy sections render, to skip idle rebuilds. */
    private String mLastNetSig;

    /** The host activity is still usable (not finishing/destroyed) — guards every
     *  delayed dialog/render posted from a background or timer callback. */
    private boolean alive() {
        return !mAct.isFinishing() && !mAct.isDestroyed();
    }

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
        com.eurobuddha.maxima.app.jar.JarEngine jarEngine = MaximaService.jar();
        if (node == null && jarEngine != null) {
            // Classic engine: classic maintains the hosts itself; the hero
            // reflects its live connection set, and the host list shows
            // connected + configured exactly like the old engine's.
            List<String> jactive = jarEngine.connectedHosts();
            int jhosts = jactive.size();
            mNodeSub.setText(jhosts + (jhosts == 1 ? " host" : " hosts")
                    + " · classic engine");
            setPill(jhosts > 0 ? "Reachable" : "Offline", jhosts > 0 ? Kit.OK : Kit.BAD);
            String jarSig = "jar|" + jactive + "|" + RelayStore.get(mAct);
            if (!jarSig.equals(mLastNetSig)) {
                mLastNetSig = jarSig;
                renderHostList(jactive);
            }
            return;
        }
        if (node == null) {
            mNodeSub.setText("starting…");
            setPill("Starting", Kit.WARN);
            return;
        }

        int hosts = node.pool().activeCount();
        DirectReachability dr = MaximaService.direct();
        boolean directNow = dr != null && dr.state() == DirectReachability.State.ADVERTISED;
        AndroidContribution pol0 = MaximaService.policy();
        boolean wifiNow = pol0 != null && pol0.isLocalNetwork();
        if (!ConnectionFinder.isRunning()) {
            StringBuilder sb = new StringBuilder();
            sb.append(hosts).append(hosts == 1 ? " host" : " hosts");
            sb.append(" · ").append(wifiNow ? "Wi-Fi" : "mobile data");
            if (directNow) {
                sb.append(" · directly reachable");
            }
            mNodeSub.setText(sb.toString());
        }
        setPill(hosts > 0 ? "Reachable" : "Offline", hosts > 0 ? Kit.OK : Kit.BAD);

        // The 2s heartbeat calls render() constantly. The cheap live bits above
        // (log, pill, subtitle) refresh every tick, but the sections below all
        // removeAllViews() + rebuild — churn that flickers and drops taps. Skip
        // that heavy rebuild unless the state it depends on actually changed.
        RelayHost relay0 = MaximaService.relay();
        boolean relayOn0 = relay0 != null && relay0.state() == RelayHost.State.RUNNING;
        AndroidContribution polSig = MaximaService.policy();
        String heavySig = hosts
                + "|" + node.contacts().size()
                + "|" + node.mailbox().totalItems()
                + "|" + node.outbox().size()
                + "|" + node.services().methods().size()
                + "|" + directNow + "|" + relayOn0
                + "|" + (polSig != null && AndroidContribution.isEnabled(mAct))
                + "|" + AndroidContribution.unmeteredOnly(mAct)
                + "|" + node.pool().activeHosts()
                + "|" + RelayStore.get(mAct)
                + "|" + node.mlsAddress()
                + "|" + (dr == null ? "-" : dr.blocker() + ":" + dr.suggestedPort()
                        + ":" + dr.state() + ":" + dr.publicAddress())
                + "|" + (relay0 == null ? "-" : relay0.blocker())
                + "|" + node.tier1().contributionSummary();
        if (heavySig.equals(mLastNetSig)) {
            return;
        }
        mLastNetSig = heavySig;

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
        renderHostList(node.pool().activeHosts());

        // ---- help the network (one switch, automatic tiers, suggest the fix) ----
        AndroidContribution pol = MaximaService.policy();
        mContrib.removeAllViews();
        mContribNote.setVisibility(View.GONE);
        if (pol != null) {
            boolean on = AndroidContribution.isEnabled(mAct);
            mContrib.addView(k.switchRow("Help the network",
                    "Automatically does the most it safely can",
                    on, checked -> {
                        AndroidContribution.setEnabled(mAct, checked);
                        EventLog.add("help the network " + (checked ? "on" : "off"));
                        render();
                    }));

            RelayHost rh = MaximaService.relay();
            boolean relayOn = rh != null && rh.state() == RelayHost.State.RUNNING;
            mContrib.addView(tierRow("Contributing", "mailbox & lookups", on));
            mContrib.addView(tierRow("Directly reachable", "peers reach you", directNow));
            mContrib.addView(tierRow("Relay ⚡", "carrying others' traffic", relayOn));

            View fix = helpFix(on, directNow, relayOn, dr, rh);
            if (fix != null) {
                mContrib.addView(fix);
            }

            mContrib.addView(k.divider());
            boolean wifi = AndroidContribution.unmeteredOnly(mAct);
            mContrib.addView(k.switchRow("Wi-Fi only",
                    wifi ? "Heavy duties pause on mobile data"
                            : "Heavy duties run on any network",
                    wifi, checked -> {
                        AndroidContribution.setUnmeteredOnly(mAct, checked);
                        EventLog.add("heavy duties " + (checked ? "wifi only" : "any network"));
                        render();
                    }));
            mContrib.addView(k.kv("Witness + directory",
                    "Answering lookups and signing delivery evidence",
                    on ? "on" : "off", on ? k.col(R.color.ux_success) : k.col(R.color.ux_subtext)));
            mContrib.addView(k.kv("Storage", "Replicating small encrypted blobs for others",
                    on ? "on" : "off", on ? k.col(R.color.ux_success) : k.col(R.color.ux_subtext)));
            mContrib.addView(k.kv("Counters", node.tier1().contributionSummary(), "", 0));
        } else {
            mContrib.addView(k.sub("Starting…"));
        }

        // ---- MLS ----
        String mls = node.mlsAddress();
        if (mls.isEmpty()) {
            mMlsText.setText("None — no host has offered one yet.");
            mMlsText.setOnClickListener(null);
        } else {
            mMlsText.setText(mls);
            final String mlsVal = mls;
            mMlsText.setOnClickListener(x -> {
                ((android.content.ClipboardManager) mAct.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("maxima-mls", mlsVal));
                mAct.toast("Location address copied");
            });
        }

        // ---- direct reachability ----
        renderDirect();
    }

    private void renderHostList(List<String> active) {
        mHostList.removeAllViews();
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
        TextView mf = k.ghostButton(ManualForward.enabled(mAct)
                ? "Manual forward · on" : "Set up manual port-forward");
        LinearLayout.LayoutParams mfp = new LinearLayout.LayoutParams(-1, -2);
        mfp.topMargin = k.dp(10);
        mf.setOnClickListener(v -> showManualForward());
        mDirect.addView(mf, mfp);
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
        // Classic engine: adopt the host LIVE - no transport restart needed,
        // classic proves it with a mined check-connect and just uses it.
        final com.eurobuddha.maxima.app.jar.JarEngine jarEngine = MaximaService.jar();
        if (MaximaService.node() == null && jarEngine != null) {
            new AlertDialog.Builder(mAct)
                    .setTitle(alreadyConfigured ? "Connect to this host?" : "Add and connect?")
                    .setMessage(host + "\n\nThe classic engine will connect and prove this host "
                            + "with a mined check-connect. No restart needed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Connect", (d, w) -> {
                        if (!alreadyConfigured) {
                            RelayStore.add(mAct, host);
                            mNewRelay.setText("");
                        }
                        EventLog.add("connecting to host: " + host + " (classic engine)");
                        mAct.toast("Connecting to " + host + "…");
                        jarEngine.connectHost(host);
                        mView.postDelayed(() -> confirmConnected(host), 6000);
                    })
                    .show();
            return;
        }
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
        if (!alive()) {
            return;
        }
        MaximaNode node = MaximaService.node();
        com.eurobuddha.maxima.app.jar.JarEngine jarEngine = MaximaService.jar();
        int n;
        boolean up;
        if (node == null && jarEngine != null) {
            java.util.List<String> jh = jarEngine.connectedHosts();
            n = jh.size();
            up = jh.contains(host);
        } else {
            n = node == null ? 0 : node.pool().activeCount();
            up = node != null && node.pool().activeHosts().contains(host);
        }
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

    // ---------------------------------------------------------------
    // Auto-connect + Help-the-network
    // ---------------------------------------------------------------

    private void autoConnect() {
        if (ConnectionFinder.isRunning()) {
            return;
        }
        mAutoBtn.setText("Working…");
        mAutoBtn.setClickable(false);
        ConnectionFinder.run(mAct, mAct, new ConnectionFinder.Listener() {
            @Override
            public void onStep(String zPlain) {
                mNodeSub.setText(zPlain);
            }

            @Override
            public void onDone(int zHosts, boolean zAny) {
                // Also try to go directly reachable now.
                DirectReachability d = MaximaService.direct();
                if (d != null) {
                    mNodeSub.setText("Opening a direct port…");
                    d.reprobe();
                }
                mAutoBtn.setText("Auto-connect");
                mAutoBtn.setClickable(true);
                mAct.toast(zAny
                        ? "Connected through " + zHosts + (zHosts == 1 ? " host" : " hosts")
                        : "No relay reachable right now");
                render();
            }
        });
    }

    private View tierRow(String label, String sub, boolean on) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, k.dp(8), 0, k.dp(8));
        View dot = new View(mAct);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(k.col(on ? R.color.ux_success : R.color.ux_subtext));
        if (!on) {
            g.setAlpha(110);
        }
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(k.dp(8), k.dp(8));
        dlp.rightMargin = k.dp(11);
        row.addView(dot, dlp);
        TextView t = new TextView(mAct);
        t.setText(label + "  ·  " + sub);
        t.setTextSize(13);
        t.setTextColor(k.col(on ? R.color.ux_text : R.color.ux_subtext));
        if (on) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (on) {
            TextView now = new TextView(mAct);
            now.setText("NOW");
            now.setTextSize(9);
            now.setTypeface(Typeface.DEFAULT_BOLD);
            now.setLetterSpacing(0.06f);
            now.setTextColor(k.col(R.color.ux_success));
            row.addView(now);
        }
        return row;
    }

    /** The diagnose-and-suggest-the-fix block, or null when nothing to suggest. */
    private View helpFix(boolean on, boolean directOn, boolean relayOn,
                         DirectReachability d, RelayHost rh) {
        if (!on) {
            return null;   // the switch itself is the action
        }
        if (!directOn) {
            DirectReachability.Blocker b = d == null ? null : d.blocker();
            int port = d == null ? -1 : d.suggestedPort();
            if (b == DirectReachability.Blocker.NEEDS_WIFI) {
                return fixBlock("Connect to Wi-Fi to become reachable",
                        "Direct isn't possible on mobile/carrier networks.",
                        "Open Wi-Fi settings", this::openWifi);
            }
            if (b == DirectReachability.Blocker.NEEDS_CHARGING) {
                return fixBlock("Plug in to go directly reachable",
                        "It'll start on its own once charging.", null, null);
            }
            if (b == DirectReachability.Blocker.ROUTER_NO_PORT) {
                String ip = MaximaService.localIp();
                return fixBlock("Your router didn't open a port",
                        "Enable UPnP/NAT-PMP in your router, or forward a port by hand"
                                + (ip.isEmpty() ? "." : " to " + ip + " (this phone)."),
                        "Set up manual forward", this::showManualForward);
            }
            if (b == DirectReachability.Blocker.NEEDS_PUBLIC_IP) {
                return fixBlock("Enter your public IP",
                        "Manual forward is on — add your public IP so Maxima can advertise it.",
                        "Set up manual forward", this::showManualForward);
            }
            return null;
        }
        if (!relayOn) {
            RelayHost.Blocker b = rh == null ? null : rh.blocker();
            if (b == RelayHost.Blocker.NEEDS_WIFI) {
                return fixBlock("Relay needs Wi-Fi",
                        "On mobile data the phone stays a client.",
                        "Open Wi-Fi settings", this::openWifi);
            }
            if (b == RelayHost.Blocker.NEEDS_CHARGING) {
                return fixBlock("Plug in to relay for others",
                        "The phone forwards traffic once it's charging.", null, null);
            }
            if (b == RelayHost.Blocker.NEEDS_BATTERY) {
                int pct = MaximaService.policy() == null ? 0 : MaximaService.policy().batteryPercent();
                return fixBlock("Charge above " + RelayHost.START_BATTERY + "% to relay",
                        "Now " + pct + "%. It'll start on its own.", null, null);
            }
            return null;
        }
        return fixBlock("You're relaying for others",
                "Others' traffic runs through your phone. It stands down when you unplug.", null, null);
    }

    private View fixBlock(String title, String body, String actionLabel, Runnable action) {
        LinearLayout box = new LinearLayout(mAct);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.field_pill);
        box.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = k.dp(12);
        box.setLayoutParams(bp);
        TextView t = new TextView(mAct);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(k.manrope(Typeface.BOLD));
        t.setTextColor(k.col(R.color.ux_text));
        box.addView(t);
        TextView b = new TextView(mAct);
        b.setText(body);
        b.setTextSize(12);
        b.setTextColor(k.col(R.color.ux_subtext));
        b.setPadding(0, k.dp(3), 0, 0);
        box.addView(b);
        if (actionLabel != null && action != null) {
            TextView btn = k.ghostButton(actionLabel);
            LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-2, -2);
            abp.topMargin = k.dp(10);
            btn.setOnClickListener(v -> action.run());
            box.addView(btn, abp);
        }
        return box;
    }

    private void showManualForward() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        String lan = MaximaService.localIp();
        int port = ManualForward.port(mAct);
        body.addView(k.sub("If your router won't open a port automatically, add a rule on it that "
                + "forwards a TCP port to this phone — then enter your public IP so Maxima can prove "
                + "and advertise it. The port is pinned so the rule survives restarts."));
        body.addView(k.copyField("Forward to this phone",
                lan.isEmpty() ? "—" : lan + ":" + port, "Copied"));

        final EditText portF = k.field("Port to forward");
        portF.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portF.setText(String.valueOf(port));
        body.addView(portF, k.mb(k.dp(9)));
        LinearLayout.LayoutParams pfp = (LinearLayout.LayoutParams) portF.getLayoutParams();
        pfp.topMargin = k.dp(10);
        final EditText ipF = k.field("Your public IP (found automatically)");
        ipF.setText(ManualForward.publicIp(mAct));
        body.addView(ipF, k.mb(k.dp(9)));
        // Find the public IP for the user so they never have to look it up.
        if (ipF.getText().toString().trim().isEmpty()) {
            detectPublicIp(ip -> {
                if (ip != null && ipF.getText().toString().trim().isEmpty()) {
                    ipF.setText(ip);
                }
            });
        }

        TextView enable = k.primaryButton("Enable & verify");
        body.addView(enable, k.mb(k.dp(8)));
        TextView off = k.ghostButton("Turn manual off");
        body.addView(off);

        BottomSheetDialog d = k.sheet("Manual port-forward", body);
        enable.setOnClickListener(v -> {
            int p;
            try {
                p = Integer.parseInt(portF.getText().toString().trim());
            } catch (NumberFormatException e) {
                mAct.toast("Enter a port");
                return;
            }
            if (p <= 0 || p >= 65536) {
                mAct.toast("Port must be 1–65535");
                return;
            }
            if (p == RelayHost.RELAY_PORT) {
                mAct.toast("Port " + p + " is reserved for relay mode — pick another");
                return;
            }
            final int port2 = p;
            String ip = ipF.getText().toString().trim();
            if (ip.isEmpty()) {
                mAct.toast("Finding your public IP…");
                detectPublicIp(det -> {
                    if (det == null) {
                        mAct.toast("Couldn't find your public IP — enter it manually");
                    } else {
                        enableManual(det, port2, d);
                    }
                });
                return;
            }
            if (!com.eurobuddha.maxima.core.portmap.PortMapper.isPublic(ip)) {
                mAct.toast("That doesn't look like a public IP");
                return;
            }
            enableManual(ip, port2, d);
        });
        off.setOnClickListener(v -> {
            ManualForward.setEnabled(mAct, false);
            applyManualRebind();
            mAct.toast("Manual forward off");
            d.dismiss();
            render();
        });
    }

    private void enableManual(String ip, int port, BottomSheetDialog d) {
        ManualForward.set(mAct, true, ip, port);
        applyManualRebind();
        d.dismiss();
        render();
        mAct.toast("Verifying " + ip + ":" + port + "…");
        verifyManual();
    }

    /** Rebind the direct listener to the pinned/ephemeral port IMMEDIATELY (not on
     *  the 60s heartbeat), then re-prove — all off the main thread. */
    private void applyManualRebind() {
        new Thread(() -> {
            MaximaNode n = MaximaService.node();
            if (n != null) {
                n.stopDirect();
                n.startDirect(ManualForward.enabled(mAct) ? ManualForward.port(mAct) : 0);
            }
            DirectReachability dr = MaximaService.direct();
            if (dr != null) {
                dr.reprobe();
            }
        }, "manual-rebind").start();
    }

    /** Poll the reachability state after a manual enable and report the result. */
    private void verifyManual() {
        final DirectReachability dr = MaximaService.direct();
        if (dr == null || mVerifying) {
            return;
        }
        mVerifying = true;
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!alive()) {
                mVerifying = false;
                return;
            }
            if (dr.state() == DirectReachability.State.ADVERTISED) {
                mVerifying = false;
                render();
                new AlertDialog.Builder(mAct)
                        .setTitle("You're directly reachable")
                        .setMessage("Verified — others can now reach you at " + dr.publicAddress()
                                + ", no relay in between.")
                        .setPositiveButton("Great", null)
                        .show();
                return;
            }
            DirectReachability.Blocker b = dr.blocker();
            boolean hardFail = b == DirectReachability.Blocker.ROUTER_NO_PORT
                    || b == DirectReachability.Blocker.NEEDS_PUBLIC_IP;
            if (tries[0]++ >= 15 || (hardFail && tries[0] > 4)) {
                mVerifying = false;
                render();
                new AlertDialog.Builder(mAct)
                        .setTitle("Couldn't verify")
                        .setMessage("Not reachable from outside yet. Check your router forwards TCP "
                                + ManualForward.port(mAct) + " to " + MaximaService.localIp()
                                + " (this phone's current LAN IP), and that the public IP is right.\n\n"
                                + "If the rule looks correct and it still fails, your internet provider "
                                + "is probably using carrier-grade NAT (a public IP shared across many "
                                + "homes) — no port-forward can open that, and you'll stay connected "
                                + "through relays.\n\n(" + dr.detail() + ")")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Try again", (di, w) -> {
                            applyManualRebind();
                            verifyManual();
                        })
                        .show();
                return;
            }
            mView.postDelayed(poll[0], 1000);
        };
        mView.postDelayed(poll[0], 2000);
    }

    private interface IpCallback {
        void onIp(String zIp);
    }

    /** Discover this phone's PUBLIC IP via an outbound echo service, off-main. */
    private void detectPublicIp(IpCallback zCb) {
        new Thread(() -> {
            String ip = httpGet("https://api.ipify.org");
            if (!looksLikeIp(ip)) {
                ip = httpGet("https://icanhazip.com");
            }
            final String out = looksLikeIp(ip) ? ip.trim() : null;
            mAct.runOnUiThread(() -> zCb.onIp(out));
        }, "detect-public-ip").start();
    }

    private String httpGet(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()))) {
                String line = r.readLine();
                return line == null ? null : line.trim();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private boolean looksLikeIp(String s) {
        return s != null && s.trim().matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    private void openWifi() {
        try {
            mAct.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            mAct.toast("Couldn't open Wi-Fi settings");
        }
    }

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
        hn.setText("Connection");
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
        mAutoBtn = k.primaryButton("Auto-connect");
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-1, -2);
        abp.topMargin = k.dp(12);
        mAutoBtn.setOnClickListener(v -> autoConnect());
        hero.addView(mAutoBtn, abp);
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

        // Help the network.
        mBox.addView(k.sectionLabel("Help the network"));
        LinearLayout cCard = k.card();
        mContribNote = k.sub("");
        mContribNote.setPadding(0, k.dp(2), 0, k.dp(6));
        cCard.addView(mContribNote);
        mContrib = new LinearLayout(mAct);
        mContrib.setOrientation(LinearLayout.VERTICAL);
        cCard.addView(mContrib);
        mBox.addView(cCard, k.mb(k.dp(4)));

        // Direct reachability (above Location service).
        mBox.addView(k.sectionLabel("Direct reachability · a public address when possible"));
        LinearLayout dCard = k.card();
        mDirect = new LinearLayout(mAct);
        mDirect.setOrientation(LinearLayout.VERTICAL);
        dCard.addView(mDirect);
        mBox.addView(dCard, k.mb(k.dp(4)));

        // Location service.
        mBox.addView(k.sectionLabel("Location service · finds you when you move"));
        LinearLayout mCard = k.card();
        mMlsText = new TextView(mAct);
        mMlsText.setTextSize(12);
        mMlsText.setTypeface(Typeface.MONOSPACE);
        mMlsText.setTextColor(k.col(R.color.ux_text));
        mMlsText.setBackgroundResource(R.drawable.field_pill);
        int mtp = k.dp(12);
        mMlsText.setPadding(mtp, mtp, mtp, mtp);
        mMlsText.setClickable(true);
        LinearLayout.LayoutParams mtlp = new LinearLayout.LayoutParams(-1, -2);
        mtlp.bottomMargin = k.dp(8);
        mCard.addView(mMlsText, mtlp);
        TextView mlsBtn = k.ghostButton("Location settings");
        mCard.addView(mlsBtn);
        mlsBtn.setOnClickListener(v -> pinMls());
        mBox.addView(mCard, k.mb(k.dp(4)));

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
