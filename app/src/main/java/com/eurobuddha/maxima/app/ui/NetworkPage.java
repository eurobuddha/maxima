package com.eurobuddha.maxima.app.ui;

import com.eurobuddha.maxima.app.i18n.LanguageDialog;

import android.content.Intent;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.SystemMessages;
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
    private LinearLayout mForward;
    private String mPublicIp = "";
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
        return com.eurobuddha.maxima.app.i18n.Language.msg(com.eurobuddha.maxima.app.R.string.l10n_nav_network);
    }

    @Override
    public void render() {
        String log = EventLog.asText(40);
        Language.setText(mLog, "(nothing yet)".equals(log) ? Language.msg(R.string.l10n_node_panel_empty) : log);
        MaximaNode node = MaximaService.node();
        com.eurobuddha.maxima.app.jar.JarEngine jarEngine = MaximaService.jar();
        if (node == null && jarEngine != null) {
            // Classic engine: classic maintains the hosts itself; the hero
            // reflects its live connection set, and the host list shows
            // connected + configured exactly like the old engine's.
            List<String> jactive = jarEngine.connectedHosts();
            int jhosts = jactive.size();
            Language.setText(mNodeSub, Language.msg(R.string.l10n_native_network_classic_subtitle, Language.count(R.plurals.l10n_native_shell_hosts, jhosts)));
            setPill(jhosts > 0 ? Language.msg(R.string.l10n_native_network_reachable) : Language.msg(R.string.l10n_native_network_offline), jhosts > 0 ? Kit.OK : Kit.BAD);
            String jarSig = "jar|" + jactive + "|" + RelayStore.get(mAct)
                    + "|" + jarEngine.isStaticMls()
                    + "|" + jarEngine.contacts().size();
            if (!jarSig.equals(mLastNetSig)) {
                mLastNetSig = jarSig;
                renderHostList(jactive);
                // Transport figures - the classic engine's honest numbers.
                mStats.removeAllViews();
                addStat(Language.msg(R.string.l10n_native_network_hosts_connected), Language.msg(R.string.l10n_native_network_classic_hosts),
                        String.valueOf(jhosts), 0, "hosts");
                addStat(Language.msg(R.string.l10n_nav_contacts), Language.msg(R.string.l10n_native_network_contacts_help),
                        String.valueOf(jarEngine.contacts().size()), 0, "contacts");
                // Contribution + direct reachability ride the old engine; on
                // classic they are simply not running - say so, don't sit blank.
                mContrib.removeAllViews();
                mContrib.addView(k.sub(Language.msg(R.string.l10n_native_network_classic_contribution)));
                mContribNote.setVisibility(View.GONE);
                mDirect.removeAllViews();
                mDirect.addView(k.sub(Language.msg(R.string.l10n_native_network_classic_direct)));
            }
            // ---- MLS (classic): rotating by default, static when pinned; a
            // static MLS mints a permanent MAX# address worth copying. ----
            if (mMlsText != null) {
                final String perm = jarEngine.permanentAddress();
                if (!perm.isEmpty()) {
                    Language.setText(mMlsText, Language.msg(R.string.l10n_native_network_static_mls, jarEngine.mlsHost(), perm));
                    mMlsText.setOnClickListener(x -> {
                        ((android.content.ClipboardManager) mAct.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE))
                                .setPrimaryClip(android.content.ClipData
                                        .newPlainText("maxima-max-address", perm));
                        mAct.toast(tr(R.string.l10n_native_network_static_copied));
                    });
                } else {
                    String mh = jarEngine.mlsHost();
                    Language.setText(mMlsText, mh.isEmpty()
                            ? Language.msg(R.string.l10n_native_network_rotating)
                            : Language.msg(R.string.l10n_native_network_rotating_mls, mh));
                    mMlsText.setOnClickListener(null);
                }
            }
            return;
        }
        if (node == null) {
            Language.setText(mNodeSub, Language.msg(R.string.l10n_native_contacts_starting));
            setPill(Language.msg(R.string.l10n_native_network_starting), Kit.WARN);
            return;
        }

        int hosts = node.pool().activeCount();
        DirectReachability dr = MaximaService.direct();
        boolean directNow = dr != null && dr.state() == DirectReachability.State.ADVERTISED;
        AndroidContribution pol0 = MaximaService.policy();
        boolean wifiNow = pol0 != null && pol0.isLocalNetwork();
        if (!ConnectionFinder.isRunning()) {
            Language.setText(mNodeSub, Language.msg(directNow ? R.string.l10n_native_network_via_direct : R.string.l10n_native_network_via_network,
                    Language.count(R.plurals.l10n_native_shell_hosts, hosts),
                    wifiNow ? "Wi-Fi" : Language.msg(R.string.l10n_native_network_mobile)));
        }
        setPill(hosts > 0 ? Language.msg(R.string.l10n_native_network_reachable) : Language.msg(R.string.l10n_native_network_offline), hosts > 0 ? Kit.OK : Kit.BAD);

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
        addStat(Language.msg(R.string.l10n_native_network_hosts_connected), Language.msg(R.string.l10n_native_network_independent),
                String.valueOf(hosts), 0, "hosts");
        addStat(Language.msg(R.string.l10n_nav_contacts), Language.msg(R.string.l10n_native_network_contacts_help),
                String.valueOf(node.contacts().size()), 0, "contacts");
        addStat(Language.msg(R.string.l10n_native_network_mailbox), Language.msg(R.string.l10n_native_network_mailbox_help),
                String.valueOf(node.mailbox().totalItems()), k.col(R.color.ux_subtext), "mailbox");
        addStat(Language.msg(R.string.l10n_native_network_outbox), Language.msg(R.string.l10n_native_network_outbox_help),
                String.valueOf(node.outbox().size()), k.col(R.color.ux_subtext), "outbox");
        addStat(Language.msg(R.string.l10n_native_network_services), Language.msg(R.string.l10n_native_network_services_help),
                String.valueOf(node.services().methods().size()), 0, "services");

        // ---- hosts ----
        renderHostList(node.pool().activeHosts());

        // ---- help the network (one switch, automatic tiers, suggest the fix) ----
        AndroidContribution pol = MaximaService.policy();
        mContrib.removeAllViews();
        mContribNote.setVisibility(View.GONE);
        if (pol != null) {
            boolean on = AndroidContribution.isEnabled(mAct);
            mContrib.addView(k.switchRow(Language.msg(R.string.l10n_native_network_help),
                    Language.msg(R.string.l10n_native_network_help_sub),
                    on, checked -> {
                        AndroidContribution.setEnabled(mAct, checked);
                        EventLog.add("help the network " + (checked ? "on" : "off"));
                        render();
                    }));

            RelayHost rh = MaximaService.relay();
            boolean relayOn = rh != null && rh.state() == RelayHost.State.RUNNING;
            mContrib.addView(tierRow(Language.msg(R.string.l10n_native_network_contributing), Language.msg(R.string.l10n_native_network_mailbox_lookups), on));
            mContrib.addView(tierRow(Language.msg(R.string.l10n_native_network_directly_reachable), Language.msg(R.string.l10n_native_network_peers_reach), directNow));
            mContrib.addView(tierRow(Language.msg(R.string.l10n_native_network_relay), Language.msg(R.string.l10n_native_network_relay_traffic), relayOn));

            View fix = helpFix(on, directNow, relayOn, dr, rh);
            if (fix != null) {
                mContrib.addView(fix);
            }

            mContrib.addView(k.divider());
            boolean wifi = AndroidContribution.unmeteredOnly(mAct);
            mContrib.addView(k.switchRow(Language.msg(R.string.l10n_native_network_wifi_only),
                    wifi ? Language.msg(R.string.l10n_native_network_heavy_pause)
                            : Language.msg(R.string.l10n_native_network_heavy_any),
                    wifi, checked -> {
                        AndroidContribution.setUnmeteredOnly(mAct, checked);
                        EventLog.add("heavy duties " + (checked ? "wifi only" : "any network"));
                        render();
                    }));
            mContrib.addView(k.kv(Language.msg(R.string.l10n_native_network_witness),
                    Language.msg(R.string.l10n_native_network_witness_help),
                    on ? Language.msg(R.string.l10n_native_network_on) : Language.msg(R.string.l10n_native_network_off), on ? k.col(R.color.ux_success) : k.col(R.color.ux_subtext)));
            mContrib.addView(k.kv(Language.msg(R.string.l10n_native_network_storage), Language.msg(R.string.l10n_native_network_storage_help),
                    on ? Language.msg(R.string.l10n_native_network_on) : Language.msg(R.string.l10n_native_network_off), on ? k.col(R.color.ux_success) : k.col(R.color.ux_subtext)));
            mContrib.addView(k.kv(Language.msg(R.string.l10n_native_network_counters), contributionSummary(node.tier1()), "", 0));
        } else {
            mContrib.addView(k.sub(Language.msg(R.string.l10n_native_network_starting_long)));
        }

        // ---- MLS / permanent address ----
        // PINNED (isStaticMls) = a FIXED permanent MAX# (MAX#<you>#<pool relay>): it
        // never changes and, when the anchor is an open-pool relay, resolves for
        // anyone — this is what to hand out as your contact. UNPINNED = the rotating
        // live location, which DRIFTS as you move networks; "Make this my permanent
        // address" in Location settings fixes it to a pool relay. Never label the
        // drifting location "permanent".
        String mls = node.mlsAddress();
        if (mls.isEmpty()) {
            Language.setText(mMlsText, Language.msg(R.string.l10n_native_network_no_mls));
            mMlsText.setOnClickListener(null);
        } else {
            final boolean pinned = node.isStaticMls();
            final String shown = pinned ? node.permanentAddress() : mls;
            Language.setText(mMlsText, shown);
            mMlsText.setOnClickListener(x -> {
                ((android.content.ClipboardManager) mAct.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText(
                                pinned ? "maxima-max" : "maxima-mls", shown));
                mAct.toast(pinned ? tr(R.string.l10n_native_network_permanent_copied)
                        : tr(R.string.l10n_native_network_location_copied));
            });
        }

        // ---- direct reachability ----
        renderDirect();
        renderForward();
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
            mHostList.addView(k.sub(Language.msg(R.string.l10n_native_network_no_hosts)));
        }
        int total = active.size() + countIdle(active, configured);
        if (total > shown) {
            TextView more = k.sub(Language.count(R.plurals.l10n_native_network_more_hosts, total - shown));
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

    /** LAN address, router, public address and one line per port with what to forward and
     *  whether it is reached - every value a copy field (RULE 1: whole, copyable). */
    private void renderForward() {
        if (mForward == null) {
            return;
        }
        mForward.removeAllViews();
        com.eurobuddha.maxima.app.direct.DirectReachability d = MaximaService.direct();
        RelayHost rh = MaximaService.relay();
        String lan = MaximaService.localIp();
        String gw = d == null ? "" : d.gatewayIp();
        String pub = mPublicIp;
        if (pub.isEmpty() && d != null && !d.publicAddress().isEmpty()) {
            String pa = d.publicAddress();
            pub = pa.contains(":") ? pa.substring(0, pa.lastIndexOf(':')) : pa;
        }
        if (pub.isEmpty()) {
            pub = ManualForward.publicIp(mAct);
        }
        if (pub.isEmpty()) {
            detectPublicIp(ip -> { if (ip != null && !ip.isEmpty()) { mPublicIp = ip; mAct.runOnUiThread(this::renderForward); } });
        }
        mForward.addView(k.copyField(Language.msg(R.string.l10n_native_network_lan), lan.isEmpty() ? "—" : lan, Language.msg(R.string.l10n_common_copied)));
        if (!gw.isEmpty()) {
            LinearLayout gwRow = k.copyField(Language.msg(R.string.l10n_node_router), "http://" + gw + "/", Language.msg(R.string.l10n_native_network_router_copied));
            mForward.addView(gwRow);
            TextView open = k.ghostButton(Language.msg(R.string.l10n_native_network_router_admin));
            open.setOnClickListener(v -> {
                try { mAct.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://" + gw + "/"))); }
                catch (Exception e) { mAct.toast(tr(R.string.l10n_native_network_no_browser)); }
            });
            mForward.addView(open);
        }
        mForward.addView(k.copyField(Language.msg(R.string.l10n_node_public_address), pub.isEmpty() ? Language.msg(R.string.l10n_native_network_finding) : pub, Language.msg(R.string.l10n_common_copied)));

        boolean relayOn = rh != null && rh.state() == RelayHost.State.RUNNING;
        String relayProof = rh == null ? "" : rh.portProof();
        mForward.addView(k.kvPill(Language.msg(R.string.l10n_native_network_relay_port, RelayHost.RELAY_PORT),
                relayOn ? ("open".equals(relayProof) ? Language.msg(R.string.l10n_native_network_relay_reached)
                        : "closed".equals(relayProof) ? Language.msg(R.string.l10n_native_network_not_reached, RelayHost.RELAY_PORT, lan.isEmpty() ? Language.msg(R.string.l10n_native_network_phone) : lan)
                        : Language.msg(R.string.l10n_native_network_testing_internet))
                        : Language.msg(R.string.l10n_native_network_relay_off),
                relayOn ? ("open".equals(relayProof) ? Language.msg(R.string.l10n_native_network_open) : "closed".equals(relayProof) ? Language.msg(R.string.l10n_native_network_closed) : Language.msg(R.string.l10n_native_network_testing)) : Language.msg(R.string.l10n_native_network_off),
                relayOn && "open".equals(relayProof) ? Kit.OK : Kit.NEUTRAL));
        if (relayOn) {
            mForward.addView(k.copyField(Language.msg(R.string.l10n_native_network_router_relay), Language.join("TCP ", RelayHost.RELAY_PORT, " → ", lan.isEmpty() ? Language.msg(R.string.l10n_native_network_phone) : lan, ":", RelayHost.RELAY_PORT), Language.msg(R.string.l10n_native_network_rule_copied)));
        }
        int directPort = ManualForward.enabled(mAct) ? ManualForward.port(mAct) : (d == null ? 0 : d.externalPort());
        boolean advertised = d != null && d.state() == com.eurobuddha.maxima.app.direct.DirectReachability.State.ADVERTISED;
        mForward.addView(k.kvPill(Language.msg(R.string.l10n_native_network_direct_port, directPort > 0 ? String.valueOf(directPort) : Language.msg(R.string.l10n_native_network_automatic)),
                advertised ? Language.msg(R.string.l10n_native_network_direct_reached)
                        : d == null ? Language.msg(R.string.l10n_native_contacts_starting) : reachDetail(d.detail()),
                advertised ? Language.msg(R.string.l10n_native_network_open) : (d == null ? "—" : SystemMessages.message(d.state().name().toLowerCase(java.util.Locale.ROOT))),
                advertised ? Kit.OK : Kit.NEUTRAL));
        if (directPort > 0 && !advertised) {
            mForward.addView(k.copyField(Language.msg(R.string.l10n_native_network_router_direct), Language.join("TCP ", directPort, " → ", lan.isEmpty() ? Language.msg(R.string.l10n_native_network_phone) : lan, ":", directPort), Language.msg(R.string.l10n_native_network_rule_copied)));
        }
        mForward.addView(k.sub(Language.msg(R.string.l10n_native_network_reservation, lan.isEmpty() ? Language.msg(R.string.l10n_native_network_phone) : lan)));
    }

    private void renderDirect() {
        com.eurobuddha.maxima.app.direct.DirectReachability d = MaximaService.direct();
        mDirect.removeAllViews();
        if (d == null) {
            mDirect.addView(k.kv(Language.msg(R.string.l10n_native_network_direct), Language.msg(R.string.l10n_native_contacts_starting), "—", k.col(R.color.ux_subtext)));
            return;
        }
        boolean advertised =
                d.state() == com.eurobuddha.maxima.app.direct.DirectReachability.State.ADVERTISED;
        mDirect.addView(k.kvPill(Language.msg(R.string.l10n_native_network_direct), reachDetail(d.detail()),
                advertised ? Language.msg(R.string.l10n_native_network_on) : SystemMessages.message(d.state().name().toLowerCase(java.util.Locale.ROOT)),
                advertised ? Kit.OK : Kit.NEUTRAL));
        if (advertised && !d.publicAddress().isEmpty()) {
            mDirect.addView(k.copyField(Language.msg(R.string.l10n_native_network_public), d.publicAddress(), Language.msg(R.string.l10n_native_contacts_address_copied)));
        }
        TextView mf = k.ghostButton(ManualForward.enabled(mAct)
                ? Language.msg(R.string.l10n_native_network_manual_on) : Language.msg(R.string.l10n_native_network_setup_manual));
        LinearLayout.LayoutParams mfp = new LinearLayout.LayoutParams(-1, -2);
        mfp.topMargin = k.dp(10);
        mf.setOnClickListener(v -> showManualForward());
        mDirect.addView(mf, mfp);
    }

    // ---------------------------------------------------------------
    // Host connect / manage
    // ---------------------------------------------------------------

    private void addRelay() {
        addRelayText(mNewRelay.getText().toString());
    }

    /** Typed host:port, a comma list, or the text of a relay's QR: add them all, connect the first. */
    private void addRelayText(String zText) {
        java.util.List<String> hosts = com.eurobuddha.maxima.core.session.SeedRelays.parse(zText);
        if (hosts.isEmpty()) {
            mAct.toast(tr(R.string.l10n_native_network_enter_host));
            return;
        }
        for (int i = 1; i < hosts.size(); i++) {
            RelayStore.add(mAct, hosts.get(i));   // the rest are seeds from now on
            EventLog.add("relay added: " + hosts.get(i));
        }
        connectHost(hosts.get(0), RelayStore.get(mAct).contains(hosts.get(0)));
    }

    /** Scan a relay's QR (parlons-relay:host:port,...) and add what it names. */
    private void scanRelayQr() {
        mAct.scanQr(text -> mView.post(() -> addRelayText(text)));
    }

    /** Show one relay as a QR another phone can scan, with the full text to copy. */
    private void shareRelayQr(String host) {
        String text = com.eurobuddha.maxima.core.session.SeedRelays.share(
                java.util.Collections.singletonList(host));
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView img = new android.widget.ImageView(mAct);
        int px = k.dp(220);
        img.setImageBitmap(Qr.encode(text, px));
        body.addView(img, new LinearLayout.LayoutParams(px, px));
        body.addView(k.sub(Language.msg(R.string.l10n_native_network_share_host, host)), k.mb(k.dp(8)));
        body.addView(k.copyField(Language.msg(R.string.l10n_native_network_share_text), text, Language.msg(R.string.l10n_common_copied)));
        k.sheet(Language.msg(R.string.l10n_native_network_share_relay), body);
    }

    /** Connect to (or add + connect to) an external host, with an up-front
     *  explanation and an after-the-fact confirmation of the relay + count. */
    private void connectHost(final String host, final boolean alreadyConfigured) {
        // Classic engine: adopt the host LIVE - no transport restart needed,
        // classic proves it with a mined check-connect and just uses it.
        final com.eurobuddha.maxima.app.jar.JarEngine jarEngine = MaximaService.jar();
        if (MaximaService.node() == null && jarEngine != null) {
            new LanguageDialog(mAct)
                    .setTitle(alreadyConfigured ? Language.msg(R.string.l10n_native_network_connect_title) : Language.msg(R.string.l10n_native_network_add_connect_title))
                    .setMessage(Language.msg(R.string.l10n_native_network_connect_classic, host))
                    .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                    .setPositiveButton(Language.msg(R.string.l10n_native_network_connect), (d, w) -> {
                        if (!alreadyConfigured) {
                            RelayStore.add(mAct, host);
                            mNewRelay.setText("");
                        }
                        EventLog.add("connecting to host: " + host + " (classic engine)");
                        mAct.toast(tr(R.string.l10n_native_network_connecting_host, host));
                        jarEngine.connectHost(host);
                        mView.postDelayed(() -> confirmConnected(host), 6000);
                    })
                    .show();
            return;
        }
        MaximaNode node = MaximaService.node();
        int now = node == null ? 0 : node.pool().activeCount();
        new LanguageDialog(mAct)
                .setTitle(alreadyConfigured ? Language.msg(R.string.l10n_native_network_connect_title) : Language.msg(R.string.l10n_native_network_add_connect_title))
                .setMessage(Language.count(R.plurals.l10n_native_network_connect_restart, now, host, now))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_native_network_connect), (d, w) -> {
                    if (!alreadyConfigured) {
                        RelayStore.add(mAct, host);
                        mNewRelay.setText("");
                    }
                    EventLog.add("connecting to host: " + host + " (restarting transport)");
                    mAct.toast(tr(R.string.l10n_native_network_connecting_host, host));
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
        new LanguageDialog(mAct)
                .setTitle(up ? Language.msg(R.string.l10n_notification_connected) : Language.msg(R.string.l10n_native_network_reconnecting))
                .setMessage(Language.join(up ? Language.msg(R.string.l10n_native_network_connected_host, host) : Language.msg(R.string.l10n_native_network_configured_host, host), "\n\n",
                        Language.count(R.plurals.l10n_native_network_connected_now, n)))
                .setPositiveButton(Language.msg(R.string.l10n_common_ok), null)
                .show();
    }

    private void manageHosts() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);

        EditText add = k.field(Language.msg(R.string.l10n_native_network_add_host_hint));
        body.addView(add, k.mb(k.dp(9)));
        TextView addBtn = k.primaryButton(Language.msg(R.string.l10n_native_network_add_connect));
        body.addView(addBtn, k.mb(k.dp(6)));
        final BottomSheetDialog[] box = new BottomSheetDialog[1];
        TextView scanBtn = k.ghostButton(Language.msg(R.string.l10n_native_settings_scan_relay));
        scanBtn.setOnClickListener(v -> {
            if (box[0] != null) {
                box[0].dismiss();
            }
            scanRelayQr();
        });
        body.addView(scanBtn, k.mb(k.dp(16)));

        // The compiled-in list is one seed source among several - never the only one.
        boolean builtIn = RelayStore.builtInEnabled(mAct);
        body.addView(k.switchRow(Language.msg(R.string.l10n_native_network_builtin),
                builtIn ? Language.msg(R.string.l10n_native_network_builtin_help)
                        : Language.msg(R.string.l10n_native_network_builtin_off),
                builtIn, on -> {
                    if (!RelayStore.setBuiltInEnabled(mAct, on)) {
                        mAct.toast(tr(R.string.l10n_native_dialog_add_relay_first));
                        if (box[0] != null) {
                            box[0].dismiss();
                        }
                        manageHosts();
                        return;
                    }
                    EventLog.add("built-in relay list " + (on ? "on" : "off"));
                    render();
                }));

        body.addView(k.sectionLabel(Language.msg(R.string.l10n_native_network_configured)));
        MaximaNode node = MaximaService.node();
        final List<String> active = node == null ? new java.util.ArrayList<>() : node.pool().activeHosts();
        List<String> hosts = RelayStore.get(mAct);
        if (hosts.isEmpty()) {
            body.addView(k.sub(Language.msg(R.string.l10n_native_network_none_configured)));
        }
        for (String h : hosts) {
            body.addView(manageRow(h, active.contains(h), () -> {
                if (box[0] != null) {
                    box[0].dismiss();
                }
            }));
        }

        TextView reset = k.ghostButton(Language.msg(R.string.l10n_native_network_reset));
        body.addView(reset, k.mb(0));
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) reset.getLayoutParams();
        if (rp != null) {
            rp.topMargin = k.dp(14);
        }

        box[0] = k.sheet(Language.msg(R.string.l10n_native_network_manage), body);
        addBtn.setOnClickListener(v -> {
            String text = add.getText().toString().trim();
            if (com.eurobuddha.maxima.core.session.SeedRelays.parse(text).isEmpty()) {
                mAct.toast(tr(R.string.l10n_native_dialog_relay_input_required));
                return;
            }
            box[0].dismiss();
            addRelayText(text);
        });
        reset.setOnClickListener(v -> {
            RelayStore.reset(mAct);
            mAct.toast(tr(R.string.l10n_native_network_reset));
            box[0].dismiss();
            render();
        });
    }

    private View manageRow(String host, boolean connected, Runnable dismiss) {
        LinearLayout row = new LinearLayout(mAct);
        boolean largeText = MainHeader.compact(mAct);
        row.setOrientation(largeText ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
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
        Language.setText(st, Language.msg(RelayStore.isBuiltIn(host) ? R.string.l10n_native_network_host_built_in : R.string.l10n_native_network_host_yours,
                connected ? Language.msg(R.string.l10n_notification_connected) : Language.msg(R.string.l10n_native_network_idle)));
        st.setTextSize(11);
        st.setTextColor(k.col(connected ? R.color.ux_success : R.color.ux_subtext));
        mid.addView(h);
        mid.addView(st);
        row.addView(mid, new LinearLayout.LayoutParams(largeText ? -1 : 0, -2, largeText ? 0f : 1f));
        LinearLayout actions = row;
        if (largeText) {
            actions = new LinearLayout(mAct);
            android.content.res.Configuration config = mAct.getResources().getConfiguration();
            // Three translated actions need their own lines when scaled text leaves little room.
            boolean stackActions = config.screenWidthDp < 260f * config.fontScale;
            actions.setOrientation(stackActions ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END);
            row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        }

        if (!connected) {
            TextView conn = new TextView(mAct);
            Language.setText(conn, Language.msg(R.string.l10n_native_network_connect));
            conn.setTextSize(12);
            conn.setTypeface(Typeface.DEFAULT_BOLD);
            conn.setTextColor(k.col(R.color.ux_text));
            conn.setPadding(k.dp(10), k.dp(6), k.dp(10), k.dp(6));
            conn.setBackground(k.ripple());
            conn.setOnClickListener(v -> {
                dismiss.run();
                connectHost(host, true);
            });
            actions.addView(conn);
        }

        TextView qr = new TextView(mAct);
        qr.setText("QR");
        qr.setTextSize(12);
        qr.setTypeface(Typeface.DEFAULT_BOLD);
        qr.setTextColor(k.col(R.color.ux_accent));
        qr.setPadding(k.dp(10), k.dp(6), k.dp(10), k.dp(6));
        qr.setBackground(k.ripple());
        qr.setOnClickListener(v -> {
            dismiss.run();
            shareRelayQr(host);
        });
        actions.addView(qr);

        TextView rm = new TextView(mAct);
        Language.setText(rm, Language.msg(R.string.l10n_common_remove));
        rm.setTextSize(12);
        rm.setTypeface(Typeface.DEFAULT_BOLD);
        rm.setTextColor(k.col(R.color.ux_error));
        rm.setPadding(k.dp(10), k.dp(6), k.dp(10), k.dp(6));
        rm.setBackground(k.ripple());
        rm.setOnClickListener(v -> {
            com.eurobuddha.maxima.core.session.SeedRelays.Drop d = RelayStore.remove(mAct, host);
            if (d == com.eurobuddha.maxima.core.session.SeedRelays.Drop.REFUSED_LAST_SEED) {
                mAct.toast(tr(R.string.l10n_native_dialog_keep_relay));
                return;
            }
            EventLog.add("host removed: " + host);
            mAct.toast(d == com.eurobuddha.maxima.core.session.SeedRelays.Drop.DROPPED_BUILTIN_BACK_ON
                    ? tr(R.string.l10n_native_network_removed_last, host)
                    : tr(R.string.l10n_native_network_removed_host, host));
            Language.setText(st, Language.msg(R.string.l10n_common_removed));
            st.setTextColor(k.col(R.color.ux_subtext));
            render();
        });
        actions.addView(rm);
        return row;
    }

    // ---------------------------------------------------------------
    // MLS
    // ---------------------------------------------------------------

    private void pinMls() {
        LinearLayout body = new LinearLayout(mAct);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(k.sub(Language.msg(R.string.l10n_native_network_pin_help)));
        EditText f = k.field("Mx…@host:port");
        body.addView(f, k.mb(k.dp(4)));
        TextView pin = k.primaryButton(Language.msg(R.string.l10n_native_network_pin));
        body.addView(pin, k.mb(k.dp(8)));
        final com.eurobuddha.maxima.app.jar.JarEngine jarEngine = MaximaService.jar();
        TextView pool = null;
        if (jarEngine != null) {
            // One tap: register with the eurobuddha staticMLS pool and pin it -
            // registration is what lets STRANGERS resolve your MAX# address.
            pool = k.primaryButton(Language.msg(R.string.l10n_native_network_pool_register));
            body.addView(pool, k.mb(k.dp(8)));
        }
        // Built-in engine: pin the best attached OPEN-POOL relay as a FIXED,
        // stranger-resolvable permanent anchor. No central registration needed -
        // the pool relay open-resolves. Only shown when a pool relay is attached.
        TextView poolBuiltin = null;
        if (jarEngine == null) {
            MaximaNode bn = MaximaService.node();
            if (bn != null && !bn.bestPoolMls().isEmpty()) {
                poolBuiltin = k.primaryButton(Language.msg(R.string.l10n_native_network_make_permanent));
                body.addView(poolBuiltin, k.mb(k.dp(8)));
            }
        }
        TextView clear = k.ghostButton(Language.msg(R.string.l10n_native_network_host_directory));
        body.addView(clear);
        BottomSheetDialog d = k.sheet(Language.msg(R.string.l10n_native_network_location), body);
        pin.setOnClickListener(v -> {
            String m = f.getText().toString().trim();
            if (!m.startsWith("Mx") || !m.contains("@") || !m.contains(":")) {
                mAct.toast(tr(R.string.l10n_native_network_mls_format));
                return;
            }
            if (jarEngine != null) {
                jarEngine.setStaticMls(m);
                MlsStore.save(mAct, m);
                mAct.toast(tr(R.string.l10n_native_network_mls_pinned));
                d.dismiss();
                mLastNetSig = "";
                render();
                return;
            }
            MaximaNode node = MaximaService.node();
            if (node == null) {
                mAct.toast(tr(R.string.l10n_native_chat_transport_stopped));
                return;
            }
            node.setStaticMls(m);
            MlsStore.save(mAct, m);
            EventLog.add("static MLS pinned");
            new Thread(node::refreshContacts, "refresh-mls").start();
            mAct.toast(tr(R.string.l10n_native_network_mls_pinned));
            d.dismiss();
            render();
        });
        if (pool != null) {
            pool.setOnClickListener(v -> {
                mAct.toast(tr(R.string.l10n_native_network_registering));
                new Thread(() -> {
                    try {
                        // First server in the pool list; the API is the source
                        // of truth for identities post-move.
                        String json = httpGet(
                                "https://eurobuddha.com/staticmls/api.php?action=servers");
                        java.util.regex.Matcher idm = java.util.regex.Pattern
                                .compile("\"id\":\"([^\"]+)\"").matcher(json);
                        java.util.regex.Matcher pm = java.util.regex.Pattern
                                .compile("\"p2pidentity\":\"([^\"]+)\"").matcher(json);
                        if (!idm.find() || !pm.find()) {
                            throw new Exception("pool list unavailable");
                        }
                        String sid = idm.group(1);
                        String ident = pm.group(1);
                        boolean ok = jarEngine.registerWithPool(
                                "https://eurobuddha.com/staticmls/api.php", sid, ident);
                        if (ok) {
                            MlsStore.save(mAct, ident);
                        }
                        mAct.runOnUiThread(() -> {
                            mAct.toast(ok ? tr(R.string.l10n_native_network_registered)
                                    : tr(R.string.l10n_native_network_pool_failed));
                            d.dismiss();
                            mLastNetSig = "";
                            render();
                        });
                    } catch (Exception e) {
                        EventLog.add("pool register: " + e);
                        mAct.runOnUiThread(() -> mAct.toast(tr(R.string.l10n_native_network_pool_unavailable, SystemMessages.text(mAct, e.getMessage()))));
                    }
                }, "mls-pool-register").start();
            });
        }
        if (poolBuiltin != null) {
            poolBuiltin.setOnClickListener(v -> {
                MaximaNode node = MaximaService.node();
                if (node == null) {
                    mAct.toast(tr(R.string.l10n_native_chat_transport_stopped));
                    return;
                }
                String anchor = node.bestPoolMls();
                if (anchor.isEmpty()) {
                    mAct.toast(tr(R.string.l10n_native_network_no_pool));
                    return;
                }
                node.setStaticMls(anchor);          // FIXED pin; does not rotate
                MlsStore.save(mAct, anchor);
                EventLog.add("permanent address pinned to pool relay " + anchor);
                new Thread(node::refreshContacts, "refresh-mls").start();
                mAct.toast(tr(R.string.l10n_native_network_permanent_set));
                d.dismiss();
                mLastNetSig = "";
                render();
            });
        }
        clear.setOnClickListener(v -> {
            if (jarEngine != null) {
                jarEngine.setStaticMls("");
            }
            MaximaNode node = MaximaService.node();
            if (node != null) {
                node.setStaticMls("");
            }
            MlsStore.save(mAct, "");
            EventLog.add("static MLS cleared");
            mAct.toast(tr(R.string.l10n_native_network_using_directory));
            d.dismiss();
            mLastNetSig = "";
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
        Language.setText(mAutoBtn, Language.msg(R.string.l10n_native_network_working));
        mAutoBtn.setClickable(false);
        ConnectionFinder.run(mAct, mAct, new ConnectionFinder.Listener() {
            @Override
            public void onStep(String zPlain) {
                Language.setText(mNodeSub, SystemMessages.message(zPlain));
            }

            @Override
            public void onDone(int zHosts, boolean zAny) {
                // Also try to go directly reachable now.
                DirectReachability d = MaximaService.direct();
                if (d != null) {
                    Language.setText(mNodeSub, Language.msg(R.string.l10n_native_network_opening_direct));
                    d.reprobe();
                }
                Language.setText(mAutoBtn, Language.msg(R.string.l10n_native_network_auto));
                mAutoBtn.setClickable(true);
                mAct.toast(zAny
                        ? Language.quantity(mAct, R.plurals.l10n_native_network_connected_through, zHosts, zHosts)
                        : tr(R.string.l10n_native_network_no_relay));
                render();
            }
        });
    }

    private View tierRow(CharSequence label, CharSequence sub, boolean on) {
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
        Language.setText(t, Language.join(label, "  ·  ", sub));
        t.setTextSize(13);
        t.setTextColor(k.col(on ? R.color.ux_text : R.color.ux_subtext));
        if (on) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (on) {
            TextView now = new TextView(mAct);
            Language.setText(now, Language.msg(R.string.l10n_native_network_now));
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
                return fixBlock(Language.msg(R.string.l10n_native_network_connect_wifi),
                        Language.msg(R.string.l10n_native_network_no_mobile_direct),
                        Language.msg(R.string.l10n_native_network_open_wifi), this::openWifi);
            }
            if (b == DirectReachability.Blocker.NEEDS_CHARGING) {
                return fixBlock(Language.msg(R.string.l10n_native_network_plug_direct),
                        Language.msg(R.string.l10n_native_network_auto_charging), null, null);
            }
            if (b == DirectReachability.Blocker.ROUTER_NO_PORT) {
                String ip = MaximaService.localIp();
                return fixBlock(Language.msg(R.string.l10n_native_network_router_no_port),
                        ip.isEmpty() ? Language.msg(R.string.l10n_native_network_router_manual) : Language.msg(R.string.l10n_native_network_router_manual_ip, ip),
                        Language.msg(R.string.l10n_native_network_setup_forward), this::showManualForward);
            }
            if (b == DirectReachability.Blocker.NEEDS_PUBLIC_IP) {
                return fixBlock(Language.msg(R.string.l10n_native_network_enter_public),
                        Language.msg(R.string.l10n_native_network_manual_public),
                        Language.msg(R.string.l10n_native_network_setup_forward), this::showManualForward);
            }
            return null;
        }
        if (!relayOn) {
            RelayHost.Blocker b = rh == null ? null : rh.blocker();
            if (b == RelayHost.Blocker.NEEDS_WIFI) {
                return fixBlock(Language.msg(R.string.l10n_native_network_relay_wifi),
                        Language.msg(R.string.l10n_native_network_mobile_client),
                        Language.msg(R.string.l10n_native_network_open_wifi), this::openWifi);
            }
            if (b == RelayHost.Blocker.NEEDS_CHARGING) {
                return fixBlock(Language.msg(R.string.l10n_native_network_plug_relay),
                        Language.msg(R.string.l10n_native_network_charging_relay), null, null);
            }
            if (b == RelayHost.Blocker.NEEDS_BATTERY) {
                int pct = MaximaService.policy() == null ? 0 : MaximaService.policy().batteryPercent();
                return fixBlock(Language.msg(R.string.l10n_native_network_charge_above, RelayHost.START_BATTERY),
                        Language.msg(R.string.l10n_native_network_charge_now, pct), null, null);
            }
            return null;
        }
        return fixBlock(Language.msg(R.string.l10n_native_network_relaying),
                Language.msg(R.string.l10n_native_network_relay_stops), null, null);
    }

    private View fixBlock(CharSequence title, CharSequence body, CharSequence actionLabel, Runnable action) {
        LinearLayout box = new LinearLayout(mAct);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.field_pill);
        box.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = k.dp(12);
        box.setLayoutParams(bp);
        TextView t = new TextView(mAct);
        Language.setText(t, title);
        t.setTextSize(13);
        t.setTypeface(k.manrope(Typeface.BOLD));
        t.setTextColor(k.col(R.color.ux_text));
        box.addView(t);
        TextView b = new TextView(mAct);
        Language.setText(b, body);
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
        body.addView(k.sub(Language.msg(R.string.l10n_native_network_manual_help)));
        body.addView(k.copyField(Language.msg(R.string.l10n_native_network_forward_phone),
                lan.isEmpty() ? "—" : lan + ":" + port, Language.msg(R.string.l10n_common_copied)));

        final EditText portF = k.field(Language.msg(R.string.l10n_native_network_port));
        portF.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portF.setText(String.valueOf(port));
        body.addView(portF, k.mb(k.dp(9)));
        LinearLayout.LayoutParams pfp = (LinearLayout.LayoutParams) portF.getLayoutParams();
        pfp.topMargin = k.dp(10);
        final EditText ipF = k.field(Language.msg(R.string.l10n_native_network_public_auto));
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

        TextView enable = k.primaryButton(Language.msg(R.string.l10n_native_network_enable_verify));
        body.addView(enable, k.mb(k.dp(8)));
        TextView off = k.ghostButton(Language.msg(R.string.l10n_native_network_manual_off_button));
        body.addView(off);

        BottomSheetDialog d = k.sheet(Language.msg(R.string.l10n_native_network_manual_title), body);
        enable.setOnClickListener(v -> {
            int p;
            try {
                p = Integer.parseInt(portF.getText().toString().trim());
            } catch (NumberFormatException e) {
                mAct.toast(tr(R.string.l10n_native_network_enter_port));
                return;
            }
            if (p <= 0 || p >= 65536) {
                mAct.toast(tr(R.string.l10n_native_network_port_range));
                return;
            }
            if (p == RelayHost.RELAY_PORT) {
                mAct.toast(tr(R.string.l10n_native_network_reserved_port, p));
                return;
            }
            final int port2 = p;
            String ip = ipF.getText().toString().trim();
            if (ip.isEmpty()) {
                mAct.toast(tr(R.string.l10n_native_network_finding_public));
                detectPublicIp(det -> {
                    if (det == null) {
                        mAct.toast(tr(R.string.l10n_native_network_public_failed));
                    } else {
                        enableManual(det, port2, d);
                    }
                });
                return;
            }
            if (!com.eurobuddha.maxima.core.portmap.PortMapper.isPublic(ip)) {
                mAct.toast(tr(R.string.l10n_native_network_invalid_public));
                return;
            }
            enableManual(ip, port2, d);
        });
        off.setOnClickListener(v -> {
            ManualForward.setEnabled(mAct, false);
            applyManualRebind();
            mAct.toast(tr(R.string.l10n_native_network_manual_off));
            d.dismiss();
            render();
        });
    }

    private void enableManual(String ip, int port, BottomSheetDialog d) {
        ManualForward.set(mAct, true, ip, port);
        applyManualRebind();
        d.dismiss();
        render();
        mAct.toast(tr(R.string.l10n_native_network_verifying, ip, port));
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
                new LanguageDialog(mAct)
                        .setTitle(Language.msg(R.string.l10n_native_network_verified))
                        .setMessage(Language.msg(R.string.l10n_native_network_verified_address, dr.publicAddress()))
                        .setPositiveButton(Language.msg(R.string.l10n_native_network_great), null)
                        .show();
                return;
            }
            DirectReachability.Blocker b = dr.blocker();
            boolean hardFail = b == DirectReachability.Blocker.ROUTER_NO_PORT
                    || b == DirectReachability.Blocker.NEEDS_PUBLIC_IP;
            if (tries[0]++ >= 15 || (hardFail && tries[0] > 4)) {
                mVerifying = false;
                render();
                new LanguageDialog(mAct)
                        .setTitle(Language.msg(R.string.l10n_native_network_verify_failed))
                        .setMessage(Language.msg(R.string.l10n_native_network_verify_help, ManualForward.port(mAct), MaximaService.localIp(), reachDetail(dr.detail())))
                        .setPositiveButton(Language.msg(R.string.l10n_common_ok), null)
                        .setNeutralButton(Language.msg(R.string.l10n_common_retry), (di, w) -> {
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

    /** Display-only adaptation of desktop NetworkPanel.reachDetail; addresses remain literal. */
    private static CharSequence reachDetail(String value) {
        if(value==null)return "";
        String[] patterns={
            "reachable at (.+) \\(manual forward\\)",
            "verifying (.+) from outside…",
            "reachable at (.+) via (.+)",
            "lease renewed for (.+)",
            "(.+) not reachable from outside — behind CGNAT, or the forward rule must point at Parlons port ([0-9]+)"
        };
        int[] resources={R.string.l10n_desktop_network_manual_reachable,
                R.string.l10n_desktop_network_verify_outside,R.string.l10n_desktop_network_reachable_via,
                R.string.l10n_desktop_network_lease_renewed,R.string.l10n_native_network_manual_unreachable};
        for(int i=0;i<patterns.length;i++){
            java.util.regex.Matcher m=java.util.regex.Pattern.compile(patterns[i]).matcher(value);
            if(m.matches()){
                Object[] arguments=new Object[m.groupCount()];
                for(int group=0;group<arguments.length;group++)arguments[group]=m.group(group+1);
                return Language.msg(resources[i],arguments);
            }
        }
        return SystemMessages.message(value);
    }

    /** Display the existing counters without changing their core/protocol summary. */
    private static CharSequence contributionSummary(com.eurobuddha.maxima.core.services.Tier1Services services) {
        long declined = services.declined();
        return Language.join(Language.msg(R.string.l10n_native_network_contribution_counts,
                services.mailHeld(), services.lookupsServed(), services.blobsStored(),
                services.receiptsSigned(), services.addressesShared()),
                declined > 0 ? Language.msg(R.string.l10n_native_network_declined_count, declined) : "");
    }

    private String tr(int resource, Object... args) { return Language.text(mAct, resource, args); }

    // Shared bindings update labels in place; retain the current views, drafts and pending actions.
    @Override public void languageChanged() {}

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
            mAct.toast(tr(R.string.l10n_native_network_wifi_failed));
        }
    }

    private void addStat(CharSequence key, CharSequence hint, String value, int colorOr0, String explainKey) {
        LinearLayout row = k.kv(key, hint, value, colorOr0);
        row.setBackground(k.ripple());
        row.setOnClickListener(v -> Explain.show(mAct, explainKey));
        mStats.addView(row);
    }

    private void setPill(CharSequence text, int kind) {
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
        Language.setText(hn, Language.msg(R.string.l10n_native_network_connection));
        hn.setTextSize(16);
        hn.setTypeface(k.manrope(Typeface.BOLD));
        hn.setTextColor(k.col(R.color.ux_text));
        mNodeSub = k.sub("…");
        hcol.addView(hn);
        hcol.addView(mNodeSub);
        hrow.addView(hcol, new LinearLayout.LayoutParams(0, -2, 1f));
        mPillHolder = new LinearLayout(mAct);
        mPillHolder.setGravity(Gravity.CENTER);
        boolean compact = MainHeader.compact(mAct);
        if (!compact) hrow.addView(mPillHolder);
        hero.addView(hrow);
        if (compact) {
            mPillHolder.setGravity(Gravity.END);
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-1, -2);
            badge.topMargin = k.dp(6);
            hero.addView(mPillHolder, badge);
        }
        mAutoBtn = k.primaryButton(Language.msg(R.string.l10n_native_network_auto));
        mAutoBtn.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);
        mAutoBtn.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_FULL);
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-1, -2);
        abp.topMargin = k.dp(12);
        mAutoBtn.setOnClickListener(v -> autoConnect());
        hero.addView(mAutoBtn, abp);
        mBox.addView(hero, k.mb(k.dp(4)));

        // Transport.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_network_transport)));
        LinearLayout tCard = k.card();
        mStats = new LinearLayout(mAct);
        mStats.setOrientation(LinearLayout.VERTICAL);
        tCard.addView(mStats);
        mBox.addView(tCard, k.mb(k.dp(4)));

        // Hosts.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_network_hosts_title)));
        LinearLayout hCard = k.card();
        mHostList = new LinearLayout(mAct);
        mHostList.setOrientation(LinearLayout.VERTICAL);
        hCard.addView(mHostList);
        mNewRelay = k.field(Language.msg(R.string.l10n_native_network_relay_hint));
        hCard.addView(mNewRelay, k.mb(k.dp(9)));
        LinearLayout.LayoutParams nrp = (LinearLayout.LayoutParams) mNewRelay.getLayoutParams();
        nrp.topMargin = k.dp(10);
        LinearLayout hbtns = new LinearLayout(mAct);
        boolean largeText = MainHeader.compact(mAct);
        hbtns.setOrientation(largeText ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        TextView addBtn = k.primaryButton(Language.msg(R.string.l10n_native_network_add_connect));
        TextView manageBtn = k.ghostButton(Language.msg(R.string.l10n_native_network_manage_button));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(largeText ? -1 : 0, -2, largeText ? 0f : 1f);
        l.rightMargin = largeText ? 0 : k.dp(5);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(largeText ? -1 : 0, -2, largeText ? 0f : 1f);
        r.leftMargin = largeText ? 0 : k.dp(5);
        r.topMargin = largeText ? k.dp(8) : 0;
        hbtns.addView(addBtn, l);
        hbtns.addView(manageBtn, r);
        hCard.addView(hbtns);
        addBtn.setOnClickListener(v -> addRelay());
        manageBtn.setOnClickListener(v -> manageHosts());
        mBox.addView(hCard, k.mb(k.dp(4)));

        // Help the network.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_network_help)));
        LinearLayout cCard = k.card();
        mContribNote = k.sub("");
        mContribNote.setPadding(0, k.dp(2), 0, k.dp(6));
        cCard.addView(mContribNote);
        mContrib = new LinearLayout(mAct);
        mContrib.setOrientation(LinearLayout.VERTICAL);
        cCard.addView(mContrib);
        mBox.addView(cCard, k.mb(k.dp(4)));

        // What to type into the router: this phone's address, the router, the ports.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_network_forward_title)));
        LinearLayout fCard = k.card();
        mForward = new LinearLayout(mAct);
        mForward.setOrientation(LinearLayout.VERTICAL);
        fCard.addView(mForward);
        mBox.addView(fCard, k.mb(k.dp(4)));

        // Direct reachability (above Location service).
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_network_direct_title)));
        LinearLayout dCard = k.card();
        mDirect = new LinearLayout(mAct);
        mDirect.setOrientation(LinearLayout.VERTICAL);
        dCard.addView(mDirect);
        mBox.addView(dCard, k.mb(k.dp(4)));

        // Location service.
        mBox.addView(k.sectionLabel(Language.msg(R.string.l10n_native_network_location_title)));
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
        TextView mlsBtn = k.ghostButton(Language.msg(R.string.l10n_native_network_location_settings));
        mCard.addView(mlsBtn);
        mlsBtn.setOnClickListener(v -> pinMls());
        mBox.addView(mCard, k.mb(k.dp(4)));

        // Event log.
        LinearLayout logHead = new LinearLayout(mAct);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView logLabel = k.sectionLabel(Language.msg(R.string.l10n_native_network_log));
        logHead.addView(logLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clr = new TextView(mAct);
        Language.setText(clr, Language.msg(R.string.l10n_common_clear));
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
