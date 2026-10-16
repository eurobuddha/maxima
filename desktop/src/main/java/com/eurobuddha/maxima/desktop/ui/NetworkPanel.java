package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.net.ReachabilityManager;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Network tab — full parity with the phone: a connection hero with Auto-connect
 * (probe + live attach), transport stats, direct reachability (check / make me
 * reachable via the shared ReachabilityManager), a relay toggle (run a full relay
 * for others), the host list (add / connect / remove / reset), the location service
 * (MLS) address, and the event log.
 */
public final class NetworkPanel extends JPanel implements MaximaWindow.Tab, MaximaWindow.Responsive {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;

    private final JPanel mBody = new JPanel();
    private final DKit.WrapText mLog = new DKit.WrapText("");
    private String mSig = "";
    private JScrollPane mScroll;

    private JLabel mHeroBig;
    private JPanel mHeroPill;
    private JLabel mHeroSub;
    private DKit.HoverButton mAutoBtn;
    private boolean mConnecting;

    // Live labels updated in place every heartbeat — so the panel is only fully
    // rebuilt on a STRUCTURAL change (host list, direct on/off, relay on/off,
    // MLS mode). Rebuilding every tick is what snapped the scroll back.
    private JLabel mStatHosts, mStatContacts, mStatMailbox, mStatOutbox, mStatServices;
    private JLabel mDrDetail, mDrState, mRelayRoutes, mRelayRelayed;
    private final java.util.List<JPanel> mActionRows = new java.util.ArrayList<>();

    public NetworkPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);
        mBody.setLayout(new BoxLayout(mBody, BoxLayout.Y_AXIS));
        mBody.setOpaque(false);
        mBody.setBorder(new EmptyBorder(20, 22, 22, 22));
        mScroll = new JScrollPane(new DKit.ScrollableColumn(k.centered(mBody, 720)),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        mScroll.setBorder(null);
        mScroll.getViewport().setBackground(t.bg);
        mScroll.getVerticalScrollBar().setUnitIncrement(16);
        add(mScroll, BorderLayout.CENTER);
    }

    public String label() { return Language.text("nav.network"); }
    public JComponent view() { return this; }

    public void refresh() {
        MaximaNode n = node.node();
        if (n == null) { refreshJar(); return; }   // classic engine has no built-in pool
        int hosts = n.pool().activeCount();
        boolean directNow = node.reachState() == ReachabilityManager.State.ADVERTISED;
        boolean relayOn = node.relayRunning();
        // Cheap live bits every tick: hero, event log, counters, reach detail.
        // NONE of these trigger a rebuild — they update existing labels in place.
        if (!mConnecting) {
            updateHero(hosts);
        }
        mLog.setText(DesktopEventLog.asText(40));
        updateLive(n, hosts);

        // Structural signature ONLY — the things whose change alters the panel's
        // shape (which cards/fields exist). Per-tick numbers and the reachability
        // progress string are deliberately excluded, or the heartbeat rebuilds
        // every second and snaps the scroll position back.
        String sig = directNow + "|" + !node.reachAddress().isEmpty()
                + "|" + relayOn + "|" + (relayOn && node.relayStats() != null)
                + "|" + n.pool().activeHosts() + "|" + node.relayStore().get()
                + "|" + n.mlsAddress() + "|" + node.isStaticMls()
                + "|" + DesktopManualForward.enabled();
        if (sig.equals(mSig)) return;
        // Set mSig only AFTER a successful rebuild — if rebuild() throws, mSig
        // stays stale so the next heartbeat retries instead of freezing the tab.
        preserveScroll(() -> rebuild(n, hosts, directNow, relayOn));
        mSig = sig;
    }

    /** Classic (jar) engine network view — hosts, MLS/MAX#, host add, event log. */
    private void refreshJar() {
        int hosts = node.connectedCount();
        if (!mConnecting) updateHero(hosts);
        mLog.setText(DesktopEventLog.asText(40));
        String sig = "jar|" + hosts + "|" + node.mlsLocationAddress()
                + "|" + node.permanentAddress() + "|" + node.relayStore().get();
        if (sig.equals(mSig)) return;
        preserveScroll(this::rebuildJar);
        mSig = sig;   // only after a successful rebuild (see refresh())
    }

    /** Run a rebuild while holding the scroll position, so the heartbeat's
     *  removeAll() never snaps the Network tab back to the top. */
    private void preserveScroll(Runnable rebuild) {
        int sv = mScroll == null ? 0 : mScroll.getVerticalScrollBar().getValue();
        rebuild.run();
        if (mScroll != null) {
            final int keep = sv;
            javax.swing.SwingUtilities.invokeLater(() ->
                    mScroll.getVerticalScrollBar().setValue(keep));
        }
    }

    private void rebuildJar() {
        mBody.removeAll();
        mActionRows.clear();
        // The jar view doesn't own the built-in live labels — null them so a later
        // flip-back to built-in never has updateLive() touch detached orphans.
        mStatHosts = mStatContacts = mStatMailbox = mStatOutbox = mStatServices = null;
        mDrDetail = mDrState = mRelayRoutes = mRelayRelayed = null;
        mHeroPill = null; mHeroSub = null;
        int hosts = node.connectedCount();
        // hero
        DKit.RoundPanel hero = k.card();
        JPanel line = rowX();
        mHeroBig = new JLabel();
        Language.font(mHeroBig, t.extrabold(22f));
        line.add(mHeroBig);
        line.add(Box.createHorizontalGlue());
        hero.add(line);
        hero.add(k.vgap(6));
        JLabel classic = k.sub("");
        k.renderSub(classic, () -> Language.text("desktopNetwork.classic", "hosts", Language.text("nativeShell.hosts", "count", node.connectedCount())));
        hero.add(classic);
        mBody.add(hero);
        mBody.add(k.vgap(14));
        updateHero(hosts);

        // hosts (add via the jar's connectHost)
        mBody.add(sectionLabel(Language.msg("desktopNetwork.hosts")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel hostCard = k.card();
        try {
            for (String h : node.jarEngine().connectedHosts()) {
                hostCard.add(k.kvLine(h, Language.msg("node.connected")));
            }
        } catch (Exception ignored) { }
        hostCard.add(k.vgap(8));
        JTextField add = k.field(Language.msg("desktopNetwork.relayHint"));
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        hostCard.add(add);
        hostCard.add(k.vgap(8));
        DKit.HoverButton addBtn = k.primaryButton(Language.msg("nativeNetwork.addConnect"));
        addBtn.onClick(() -> {
            java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(add.getText());
            if (hs.isEmpty()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        Language.text("desktopNetwork.enterRelay"));
                return;
            }
            connectHost(hs.get(0));
        });
        JPanel ar = rowX();
        ar.add(addBtn);
        ar.add(Box.createHorizontalGlue());
        hostCard.add(ar);
        mActionRows.add(ar);
        mBody.add(hostCard);
        mBody.add(k.vgap(14));

        // MLS (same card as built-in — routes through node helpers)
        mBody.add(mlsCard());
        mBody.add(k.vgap(14));

        // event log
        mBody.add(sectionLabel(Language.msg("nativeNetwork.log")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel logCard = k.card();
        mLog.setForeground(t.subtext);
        mLog.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 10));
        mLog.setAlignmentX(Component.LEFT_ALIGNMENT);
        logCard.add(mLog);
        mBody.add(logCard);
        mBody.add(Box.createVerticalGlue());
        if (getWidth() > 0) onWidth(getWidth());
        mBody.revalidate();
        mBody.repaint();
    }

    private JComponent mlsCard() {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(sectionLabel(Language.msg("nativeNetwork.locationTitle")));
        col.add(k.vgap(8));
        DKit.RoundPanel mls = k.card();
        mls.add(sectionLabel(Language.msg("desktopNetwork.mode")));
        mls.add(k.sub(node.isStaticMls() ? Language.msg("desktopNetwork.static") : Language.msg("desktopNetwork.rotating")));
        String loc = node.mlsLocationAddress();
        if (loc != null && !loc.isEmpty()) {
            mls.add(k.vgap(8));
            mls.add(k.copyField(Language.msg("desktopNetwork.locationAddress"), loc, false));
        }
        String perm = node.permanentAddress();
        if (perm != null && !perm.isEmpty()) {
            mls.add(k.vgap(8));
            mls.add(k.copyField(Language.msg("desktopChat.permanent"), perm, false));
            mls.add(k.vgap(6));
            mls.add(k.sub(node.isStaticMls()
                    ? Language.msg("desktopNetwork.pinnedHelp")
                    : Language.msg("desktopNetwork.assignedHelp")));
        } else {
            mls.add(k.vgap(6));
            mls.add(k.sub(Language.msg("desktopNetwork.waitingMls")));
        }
        mls.add(k.vgap(10));
        DKit.HoverButton loc2 = k.ghostButton(Language.msg("nativeNetwork.locationSettings"));
        loc2.onClick(this::mlsSettings);
        JPanel lr = rowX();
        lr.add(loc2);
        lr.add(Box.createHorizontalGlue());
        mls.add(lr);
        col.add(mls);
        return col;
    }

    private void mlsSettings() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(16, 16, 16, 16));
        body.add(k.sub(Language.msg("desktopNetwork.pinHelp")));
        body.add(k.vgap(10));
        JTextField f = k.field("Mx…@host:port");
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        String cur = node.mlsLocationAddress();
        if (cur != null && cur.startsWith("Mx")) f.setText(cur);
        body.add(f);
        body.add(k.vgap(10));
        JLabel status = k.sub(" ");
        status.setAlignmentX(Component.LEFT_ALIGNMENT);

        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                Language.text("nativeNetwork.location"), java.awt.Dialog.ModalityType.MODELESS);

        Language.title(d, Language.msg("nativeNetwork.location"));

        DKit.HoverButton pin = k.primaryButton(Language.msg("nativeNetwork.pin"));
        pin.setAlignmentX(Component.LEFT_ALIGNMENT);
        pin.onClick(() -> {
            String m = f.getText().trim();
            if (!m.startsWith("Mx") || !m.contains("@") || !m.contains(":")) {
                k.setSub(status, Language.msg("nativeNetwork.mlsFormat")); return;
            }
            node.setStaticMls(m);
            DesktopEventLog.add("static MLS pinned");
            mSig = ""; refresh(); d.dispose();
        });
        body.add(pin);
        body.add(k.vgap(8));

        if (node.canRegisterPool()) {
            DKit.HoverButton pool = k.primaryButton(Language.msg("nativeNetwork.poolRegister"));
            pool.setAlignmentX(Component.LEFT_ALIGNMENT);
            pool.onClick(() -> {
                pool.setButtonEnabled(false);
                k.setSub(status, Language.msg("nativeNetwork.registering"));
                new Thread(() -> registerPool(status, d), "mls-pool").start();
            });
            body.add(pool);
            body.add(k.vgap(8));
        }

        DKit.HoverButton host = k.ghostButton(Language.msg("nativeNetwork.hostDirectory"));
        host.setAlignmentX(Component.LEFT_ALIGNMENT);
        host.onClick(() -> {
            node.setStaticMls("");
            DesktopEventLog.add("MLS: using host directory");
            mSig = ""; refresh(); d.dispose();
        });
        body.add(host);
        body.add(k.vgap(6));
        body.add(status);

        d.setContentPane(scrollBody(body));
        d.setSize(490, dialogHeight(node.canRegisterPool() ? 390 : 340));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    private void registerPool(JLabel status, JDialog d) {
        try {
            String json = httpGet("https://eurobuddha.com/staticmls/api.php?action=servers");
            java.util.regex.Matcher idm =
                    java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
            java.util.regex.Matcher pm =
                    java.util.regex.Pattern.compile("\"p2pidentity\":\"([^\"]+)\"").matcher(json);
            if (!idm.find() || !pm.find()) throw new Exception("pool list unavailable");
            boolean ok = node.registerWithPool("https://eurobuddha.com/staticmls/api.php",
                    idm.group(1), pm.group(1));
            javax.swing.SwingUtilities.invokeLater(() -> {
                k.setSub(status, ok ? Language.msg("desktopNetwork.registered")
                        : Language.msg("nativeNetwork.poolFailed"));
                if (ok) { mSig = ""; refresh(); d.dispose(); }
            });
        } catch (Exception e) {
            DesktopEventLog.add("pool register: " + e);
            javax.swing.SwingUtilities.invokeLater(() ->
                    k.renderSub(status, () -> Language.text("nativeNetwork.poolUnavailable", "error", Language.system(e.getMessage()))));
        }
    }

    private void manualForward() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(16, 16, 16, 16));
        body.add(k.sub(Language.msg("desktopNetwork.manualHelp")));
        body.add(k.vgap(10));
        String lan = DesktopManualForward.localIp();
        int curPort = DesktopManualForward.port();
        body.add(k.copyField(Language.msg("desktopNetwork.forwardComputer"),
                lan.isEmpty() ? "—" : lan + ":" + curPort, false));
        body.add(k.vgap(10));
        JTextField portF = k.field(Language.msg("nativeNetwork.port"));
        portF.setText(String.valueOf(curPort));
        portF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        portF.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(portF);
        body.add(k.vgap(8));
        JTextField ipF = k.field(Language.msg("nativeNetwork.publicAuto"));
        ipF.setText(DesktopManualForward.publicIp());
        ipF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        ipF.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(ipF);
        body.add(k.vgap(10));
        JLabel status = k.sub(" ");
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (ipF.getText().trim().isEmpty()) {
            new Thread(() -> {
                String ip = detectPublicIp();
                if (ip != null) javax.swing.SwingUtilities.invokeLater(() -> {
                    if (ipF.getText().trim().isEmpty()) ipF.setText(ip);
                });
            }, "detect-ip").start();
        }

        DKit.HoverButton enable = k.primaryButton(Language.msg("nativeNetwork.enableVerify"));
        enable.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit.HoverButton off = k.ghostButton(Language.msg("nativeNetwork.manualOffButton"));
        off.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(enable);
        body.add(k.vgap(8));
        body.add(off);
        body.add(k.vgap(6));
        body.add(status);

        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                Language.text("nativeNetwork.manualTitle"), java.awt.Dialog.ModalityType.MODELESS);
        Language.title(d, Language.msg("nativeNetwork.manualTitle"));
        enable.onClick(() -> {
            int p;
            try { p = Integer.parseInt(portF.getText().trim()); }
            catch (Exception e) { k.setSub(status, Language.msg("nativeNetwork.enterPort")); return; }
            if (p <= 0 || p >= 65536) { k.setSub(status, Language.msg("nativeNetwork.portRange")); return; }
            if (p == DesktopNode.RELAY_PORT) {
                k.setSub(status, Language.msg("nativeNetwork.reservedPort", "port", p)); return;
            }
            String ip = ipF.getText().trim();
            if (ip.isEmpty()) { k.setSub(status, Language.msg("desktopNetwork.enterPublic")); return; }
            DesktopManualForward.set(true, ip, p);
            node.applyManualForward();
            DesktopEventLog.add("manual port-forward enabled: " + ip + ":" + p);
            k.setSub(status, Language.msg("desktopNetwork.enabledVerify", "address", ip, "port", p));
            mSig = ""; refresh();
            javax.swing.Timer tm = new javax.swing.Timer(1600, e -> d.dispose());
            tm.setRepeats(false); tm.start();
        });
        off.onClick(() -> {
            DesktopManualForward.setEnabled(false);
            node.applyManualForward();
            DesktopEventLog.add("manual port-forward off");
            mSig = ""; refresh(); d.dispose();
        });
        d.setContentPane(scrollBody(body));
        d.setSize(490, dialogHeight(440));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    /** Find this machine's public IP so the user never has to look it up. */
    private static String detectPublicIp() {
        String[] urls = {"https://api.ipify.org", "https://icanhazip.com", "https://ifconfig.me/ip"};
        for (String u : urls) {
            try {
                String ip = httpGet(u).trim();
                if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return ip;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (java.io.InputStream in = c.getInputStream()) {
            return readAll(in);
        } catch (java.io.IOException e) {
            // Drain the error stream so the socket can be reused/closed, then rethrow
            // — else a non-2xx leaves the connection undrained under keep-alive.
            try (java.io.InputStream err = c.getErrorStream()) {
                if (err != null) readAll(err);
            } catch (Exception ignored) { }
            throw e;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) >= 0) { if (r > 0) bo.write(buf, 0, r); }
        return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void rebuild(MaximaNode n, int hosts, boolean directNow, boolean relayOn) {
        mBody.removeAll();
        mActionRows.clear();

        // ---- connection hero + Auto-connect ----
        DKit.RoundPanel hero = k.card();
        JPanel line = rowX();
        mHeroBig = new JLabel();
        Language.font(mHeroBig, t.extrabold(22f));
        line.add(mHeroBig);
        line.add(Box.createHorizontalGlue());
        mHeroPill = new JPanel();
        mHeroPill.setOpaque(false);
        mHeroPill.setLayout(new BoxLayout(mHeroPill, BoxLayout.X_AXIS));
        line.add(mHeroPill);
        hero.add(line);
        hero.add(k.vgap(6));
        mHeroSub = k.sub("");
        hero.add(mHeroSub);
        hero.add(k.vgap(12));
        mAutoBtn = k.primaryButton(Language.msg("nativeNetwork.auto"));
        mAutoBtn.onClick(this::autoConnect);
        JPanel ar = rowX();
        ar.add(mAutoBtn);
        ar.add(Box.createHorizontalGlue());
        hero.add(ar);
        mActionRows.add(ar);
        mBody.add(hero);
        mBody.add(k.vgap(14));
        updateHero(hosts);

        // ---- transport stats ----
        mBody.add(sectionLabel(Language.msg("nativeNetwork.transport")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel stats = k.card();
        mStatHosts = new JLabel(); mStatContacts = new JLabel();
        mStatMailbox = new JLabel(); mStatOutbox = new JLabel(); mStatServices = new JLabel();
        stats.add(statRow(Language.msg("nativeNetwork.hostsConnected"), Language.msg("nativeNetwork.independent"), mStatHosts));
        stats.add(k.divider());
        stats.add(statRow(Language.msg("nav.contacts"), Language.msg("nativeNetwork.contactsHelp"), mStatContacts));
        stats.add(k.divider());
        stats.add(statRow(Language.msg("nativeNetwork.mailbox"), Language.msg("nativeNetwork.mailboxHelp"), mStatMailbox));
        stats.add(k.divider());
        stats.add(statRow(Language.msg("nativeNetwork.outbox"), Language.msg("nativeNetwork.outboxHelp"), mStatOutbox));
        stats.add(k.divider());
        stats.add(statRow(Language.msg("nativeNetwork.services"), Language.msg("desktopNetwork.servicesHelp"), mStatServices));
        mBody.add(stats);
        mBody.add(k.vgap(14));

        // ---- direct reachability ----
        mBody.add(sectionLabel(Language.msg("nativeNetwork.directTitle")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel dr = k.card();
        JPanel drRow = rowX();
        JLabel drState = mDrState = Language.render(new JLabel(), () -> reachState(node.reachState()));
        Language.font(drState, t.semibold(14f));
        drState.setForeground(directNow ? t.success : t.text);
        drRow.add(drState);
        drRow.add(Box.createHorizontalGlue());
        drRow.add(k.pill(directNow ? Language.msg("nativeNetwork.on") : Language.msg("nativeNetwork.off"), directNow ? DKit.OK : DKit.NEUTRAL));
        dr.add(drRow);
        dr.add(k.vgap(6));
        mDrDetail = k.sub("");
        k.renderSub(mDrDetail, () -> reachDetail(node.reachDetail()));
        mDrDetail.setAlignmentX(Component.LEFT_ALIGNMENT);
        dr.add(mDrDetail);
        if (directNow && !node.reachAddress().isEmpty()) {
            dr.add(k.vgap(8));
            dr.add(k.copyField(Language.msg("nativeNetwork.public"), node.reachAddress(), false));
        }
        dr.add(k.vgap(10));
        DKit.HoverButton check = k.ghostButton(directNow ? Language.msg("desktopNetwork.recheck") : Language.msg("desktopNetwork.check"));
        check.onClick(() -> {
            DesktopEventLog.add("checking direct reachability…");
            node.checkReachability();
        });
        JPanel cr = rowX();
        cr.add(check);
        cr.add(Box.createHorizontalGlue());
        dr.add(cr);
        mActionRows.add(cr);
        // Manual port-forward — for routers with no UPnP (phone parity).
        dr.add(k.vgap(8));
        DKit.HoverButton mfBtn = k.ghostButton(DesktopManualForward.enabled()
                ? Language.msg("desktopNetwork.manualOn") : Language.msg("nativeNetwork.setupManual"));
        mfBtn.onClick(this::manualForward);
        JPanel mfr = rowX();
        mfr.add(mfBtn);
        mfr.add(Box.createHorizontalGlue());
        dr.add(mfr);
        mActionRows.add(mfr);
        mBody.add(dr);
        mBody.add(k.vgap(14));

        // ---- relay ----
        mBody.add(sectionLabel(Language.msg("nativeNetwork.help")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel relay = k.card();
        JPanel switchRow = rowX();
        JPanel sc = new JPanel();
        sc.setOpaque(false);
        sc.setLayout(new BoxLayout(sc, BoxLayout.Y_AXIS));
        JLabel st = Language.bind(new JLabel(), Language.msg("desktopNetwork.runRelay"));
        Language.font(st, t.semibold(13.5f));
        st.setForeground(t.text);
        st.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel sh = k.sub(relayOn ? Language.msg("desktopNetwork.carrying", "port", node.relayPort())
                : Language.msg("desktopNetwork.forwardOthers"));
        sh.setAlignmentX(Component.LEFT_ALIGNMENT);
        sc.add(st);
        sc.add(sh);
        switchRow.add(sc);
        switchRow.add(Box.createHorizontalGlue());
        DKit.Toggle relayToggle = k.toggle(relayOn, on -> {
            if (on) node.startRelay(); else node.stopRelay();
            mSig = "";
        });
        JPanel tw = new JPanel(new BorderLayout());
        tw.setOpaque(false);
        tw.add(relayToggle, BorderLayout.NORTH);
        switchRow.add(tw);
        relay.add(switchRow);
        mRelayRoutes = null; mRelayRelayed = null;
        RelayRuntime.Stats rs = node.relayStats();
        if (relayOn && rs != null) {
            relay.add(k.vgap(8));
            mRelayRoutes = new JLabel(String.valueOf(rs.routes));
            mRelayRelayed = new JLabel(String.valueOf(rs.relayed));
            relay.add(kvRow(Language.msg("desktopNetwork.clients"), mRelayRoutes));
            relay.add(kvRow(Language.msg("desktopNetwork.relayed"), mRelayRelayed));
        }
        // tiers
        relay.add(k.vgap(8));
        relay.add(tier(Language.msg("nativeNetwork.contributing"), Language.msg("nativeNetwork.mailboxLookups"), hosts > 0));
        relay.add(tier(Language.msg("nativeNetwork.directlyReachable"), Language.msg("nativeNetwork.peersReach"), directNow));
        relay.add(tier(Language.msg("nativeNetwork.relay"), Language.msg("nativeNetwork.relayTraffic"), relayOn));
        mBody.add(relay);
        mBody.add(k.vgap(14));

        // ---- hosts ----
        mBody.add(sectionLabel(Language.msg("nativeNetwork.hostsTitle")));
        mBody.add(k.vgap(8));
        DKit.RoundPanel hostCard = k.card();
        List<String> active = n.pool().activeHosts();
        List<String> configured = node.relayStore().get();
        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>(active);
        union.addAll(configured);
        boolean first = true;
        for (String h : union) {
            if (!first) hostCard.add(k.divider());
            hostCard.add(hostRow(h, active.contains(h)));
            first = false;
        }
        if (union.isEmpty()) {
            hostCard.add(k.sub(Language.msg("desktopNetwork.noHosts")));
        }
        hostCard.add(k.vgap(10));
        JTextField add = k.field(Language.msg("nativeNetwork.relayHint"));
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        hostCard.add(add);
        hostCard.add(k.vgap(8));
        JPanel hr = rowX();
        DKit.HoverButton addBtn = k.primaryButton(Language.msg("nativeNetwork.addConnect"));
        addBtn.onClick(() -> {
            // Typed host:port, a comma list, or the text of a relay's QR (parlons-relay:...).
            java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(add.getText());
            if (hs.isEmpty()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        Language.text("desktopNetwork.enterRelay"));
                return;
            }
            for (String hp : hs) {
                node.relayStore().add(hp);
            }
            connectHost(hs.get(0));
            add.setText("");
            mSig = "";
            refresh();
        });
        DKit.HoverButton reset = k.ghostButton(Language.msg("nativeNetwork.reset"));
        reset.onClick(() -> { node.relayStore().reset(); mSig = ""; refresh(); });
        // The compiled-in list is one seed source among several - never the only one.
        JPanel bi = rowX();
        JLabel biLabel = k.sub(node.relayStore().builtInEnabled()
                ? Language.msg("desktopNetwork.builtinOn")
                : Language.msg("desktopNetwork.builtinOff"));
        bi.add(biLabel);
        bi.add(Box.createRigidArea(new Dimension(10, 0)));
        bi.add(k.toggle(node.relayStore().builtInEnabled(), on -> {
            if (!node.relayStore().setBuiltInEnabled(on)) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        Language.text("desktopNetwork.addFirst"));
            }
            mSig = "";
            refresh();
        }));
        bi.add(Box.createHorizontalGlue());
        hostCard.add(k.vgap(8));
        hostCard.add(bi);
        hr.add(addBtn);
        hr.add(Box.createRigidArea(new Dimension(8, 0)));
        hr.add(reset);
        hr.add(Box.createHorizontalGlue());
        hostCard.add(hr);
        mActionRows.add(hr);
        mBody.add(hostCard);
        mBody.add(k.vgap(14));

        // ---- location service (MLS) — pin a static MLS, register with the
        //      eurobuddha pool, get a permanent MAX# address (full phone parity) ----
        mBody.add(mlsCard());
        mBody.add(k.vgap(14));

        // ---- event log ----
        JPanel logHead = rowX();
        logHead.add(sectionLabel(Language.msg("nativeNetwork.log")));
        logHead.add(Box.createHorizontalGlue());
        DKit.HoverButton clear = k.ghostButton(Language.msg("common.clear"));
        clear.onClick(() -> { DesktopEventLog.clear(); refresh(); });
        logHead.add(clear);
        mBody.add(logHead);
        mBody.add(k.vgap(8));
        DKit.RoundPanel logCard = k.card();
        mLog.setForeground(t.subtext);
        mLog.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 10));
        mLog.setAlignmentX(Component.LEFT_ALIGNMENT);
        logCard.add(mLog);
        mBody.add(logCard);

        mBody.add(Box.createVerticalGlue());
        if (getWidth() > 0) onWidth(getWidth());
        mBody.revalidate();
        mBody.repaint();
    }

    /** Refresh the per-tick numbers/detail in place — never a rebuild, so the
     *  scroll position is left completely alone. */
    private void updateLive(MaximaNode n, int hosts) {
        if (mStatHosts == null) return;   // not built yet
        mStatHosts.setText(String.valueOf(hosts));
        mStatContacts.setText(String.valueOf(n.contacts().size()));
        mStatMailbox.setText(String.valueOf(n.mailbox().totalItems()));
        mStatOutbox.setText(String.valueOf(n.outbox().size()));
        mStatServices.setText(String.valueOf(n.services().methods().size()));
        if (mDrDetail != null) k.renderSub(mDrDetail, () -> reachDetail(node.reachDetail()));
        if (mDrState != null) Language.render(mDrState, () -> reachState(node.reachState()));
        RelayRuntime.Stats rs = node.relayStats();
        if (rs != null && mRelayRoutes != null) mRelayRoutes.setText(String.valueOf(rs.routes));
        if (rs != null && mRelayRelayed != null) mRelayRelayed.setText(String.valueOf(rs.relayed));
    }

    /** A stat row whose value is a caller-owned label, so it can be updated in
     *  place by {@link #updateLive} without rebuilding the card. */
    private JComponent statRow(CharSequence title, CharSequence hint, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 2, 9, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel tl = k.sub(title);
        Language.font(tl, t.semibold(13f));
        tl.setForeground(t.text);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hl = k.sub(hint);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(tl);
        left.add(hl);
        row.add(left, BorderLayout.CENTER);
        Language.font(value, t.extrabold(18f));
        value.setForeground(t.text);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    /** A label:value line whose value label the caller keeps for in-place updates. */
    private JComponent kvRow(CharSequence label, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = k.sub(label);
        Language.font(l, t.font(12.5f));
        l.setForeground(t.subtext);
        Language.font(value, t.semibold(12.5f));
        value.setForeground(t.text);
        row.add(l, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private void updateHero(int hosts) {
        if (mHeroBig == null) return;
        boolean ok = hosts > 0;
        Language.bind(mHeroBig, mConnecting ? Language.msg("connection.connecting") : (ok ? Language.msg("notification.connected") : Language.msg("nativeNetwork.offline")));
        mHeroBig.setForeground(mConnecting ? t.text : (ok ? t.success : t.error));
        // mHeroPill / mHeroSub only exist on the built-in rebuild() path; the
        // classic (jar) rebuildJar() builds only mHeroBig. Guard so a jar-mode
        // heartbeat can't NPE here and leave the whole tab half-built.
        if (mHeroPill != null) {
            mHeroPill.removeAll();
            mHeroPill.add(k.pill(Language.msg("nativeShell.hosts", "count", hosts), ok ? DKit.OK : DKit.BAD));
            mHeroPill.revalidate();
            mHeroPill.repaint();
        }
        if (mHeroSub != null && !mConnecting) {
            k.setSub(mHeroSub, ok ? Language.msg("desktopNetwork.routes", "count", hosts)
                    : Language.msg("desktopNetwork.offlineHelp"));
        }
    }

    private void autoConnect() {
        if (DesktopConnectionFinder.isRunning()) return;
        mConnecting = true;
        mAutoBtn.setButtonEnabled(false);
        Language.bind(mAutoBtn, Language.msg("nativeNetwork.working"));
        DesktopConnectionFinder.run(node, node.relayStore(), new DesktopConnectionFinder.Listener() {
            public void onStep(String plain) { k.renderSub(mHeroSub, () -> Language.system(plain)); }
            public void onDone(int connectedHosts, boolean anyReachable) {
                mConnecting = false;
                mAutoBtn.setButtonEnabled(true);
                Language.bind(mAutoBtn, Language.msg("nativeNetwork.auto"));
                node.checkReachability();
                mSig = "";
                refresh();
            }
        });
    }

    /** One relay as a QR a phone can scan (its Network page adds it), plus the full text. */
    private void shareRelayQr(String hostPort) {
        String text = com.eurobuddha.maxima.core.session.SeedRelays.share(
                java.util.Collections.singletonList(hostPort));
        java.awt.image.BufferedImage img = DesktopQr.encode(text, 260, t.text.getRGB(), t.bg.getRGB());
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        if (img != null) {
            JLabel pic = new JLabel(new javax.swing.ImageIcon(img));
            pic.setAlignmentX(Component.CENTER_ALIGNMENT);
            body.add(pic);
        }
        JTextField copy = new JTextField(text);
        copy.setEditable(false);
        copy.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        body.add(Box.createRigidArea(new Dimension(0, 8)));
        body.add(copy);
        javax.swing.JOptionPane.showMessageDialog(this, body, Language.text("desktopNetwork.shareRelay", "host", hostPort),
                javax.swing.JOptionPane.PLAIN_MESSAGE);
    }

    private void connectHost(String hp) {
        new Thread(() -> {
            try {
                if (node.isJar()) {
                    node.jarEngine().connectHost(hp);           // classic engine
                } else if (node.node() != null) {
                    node.node().pool().addCandidate(hp);
                    node.node().pool().attachOne(hp, 15000);
                }
            } catch (Exception ignored) { }
            javax.swing.SwingUtilities.invokeLater(() -> { mSig = ""; refresh(); });
        }, "connect-host").start();
    }

    // ---- rows ----

    private JComponent stat(CharSequence title, CharSequence hint, String value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 2, 9, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel tl = k.sub(title);
        Language.font(tl, t.semibold(13f));
        tl.setForeground(t.text);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hl = k.sub(hint);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(tl);
        left.add(hl);
        row.add(left, BorderLayout.CENTER);
        JLabel v = new JLabel(value);
        Language.font(v, t.extrabold(18f));
        v.setForeground(t.text);
        row.add(v, BorderLayout.EAST);
        return row;
    }

    private JComponent tier(CharSequence title, CharSequence hint, boolean on) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 2, 6, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        JLabel dot = new JLabel("●");
        Language.font(dot, t.font(11f));
        dot.setForeground(on ? t.success : DKit.alpha(t.subtext, 120));
        row.add(dot, BorderLayout.WEST);
        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel tl = k.sub(title);
        Language.font(tl, t.semibold(12.5f));
        tl.setForeground(on ? t.text : t.subtext);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hl = k.sub(hint);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(tl);
        mid.add(hl);
        row.add(mid, BorderLayout.CENTER);
        if (on) {
            JLabel now = Language.bind(new JLabel(), Language.msg("nativeNetwork.now"));
            Language.font(now, t.bold(9.5f));
            now.setForeground(t.success);
            row.add(now, BorderLayout.EAST);
        }
        return row;
    }

    private JComponent hostRow(String hostPort, boolean connected) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 2, 9, 2));
        JLabel dot = new JLabel("●");
        Language.font(dot, t.font(10f));
        dot.setForeground(connected ? t.success : DKit.alpha(t.subtext, 110));
        row.add(dot, BorderLayout.WEST);
        String kind = DesktopRelayStore.isBuiltIn(hostPort) ? "desktopNetwork.builtIn"
                : node.relayStore().userSeeds().contains(hostPort) ? "desktopNetwork.yours" : "desktopNetwork.learned";
        JLabel h = k.sub("");
        k.renderSub(h, () -> hostPort + "   " + Language.text(kind));
        h.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        h.setForeground(t.text);
        row.add(h, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        DKit.HoverButton qr = k.ghostButton("QR");
        Language.font(qr, t.semibold(11f));
        qr.onClick(() -> shareRelayQr(hostPort));
        actions.add(qr);
        actions.add(Box.createRigidArea(new Dimension(6, 0)));
        if (!connected) {
            DKit.HoverButton c = k.ghostButton(Language.msg("nativeNetwork.connect"));
            Language.font(c, t.semibold(11f));
            c.onClick(() -> connectHost(hostPort));
            actions.add(c);
            actions.add(Box.createRigidArea(new Dimension(6, 0)));
        }
        DKit.HoverButton rm = k.dangerButton(Language.msg("common.remove"));
        Language.font(rm, t.semibold(11f));
        rm.onClick(() -> {
            com.eurobuddha.maxima.core.session.SeedRelays.Drop d = node.relayStore().remove(hostPort);
            if (d == com.eurobuddha.maxima.core.session.SeedRelays.Drop.REFUSED_LAST_SEED) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        Language.text("desktopNetwork.keepRelay"));
                return;
            }
            if (d == com.eurobuddha.maxima.core.session.SeedRelays.Drop.DROPPED_BUILTIN_BACK_ON) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        Language.text("desktopNetwork.lastRelay"));
            }
            mSig = "";
            refresh();
        });
        actions.add(rm);
        row.add(actions, BorderLayout.SOUTH);
        mActionRows.add(actions);
        return row;
    }

    private JScrollPane scrollBody(JPanel body) {
        JScrollPane scroll=new JScrollPane(new DKit.ScrollableColumn(body),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);scroll.getViewport().setBackground(t.card);return scroll;
    }
    private int dialogHeight(int normal) {
        return Math.min(java.awt.Toolkit.getDefaultToolkit().getScreenSize().height-80,
                normal*t.textPercent()/100);
    }

    private JLabel sectionLabel(CharSequence text) {
        JLabel label=k.sub(text);Language.font(label, t.semibold(11f));return label;
    }
    @Override public void onWidth(int width) {
        int available=Math.max(1,Math.min(720,width-44)-32);
        for(JPanel row:mActionRows) DKit.layoutActions(row,available);
    }
    static String reachState(ReachabilityManager.State state) {
        switch(state){
            case OFF: return Language.text("desktopNetwork.notAdvertised");
            case MAPPING: return Language.text("nativeNetwork.mapping");
            case PROBING: return Language.text("nativeNetwork.probing");
            case ADVERTISED: return Language.text("nativeNetwork.directlyReachable");
            default: throw new IllegalArgumentException("Unknown reachability state");
        }
    }
    /** Display-only adaptation of ReachabilityManager diagnostics; protocol values stay unchanged. */
    public static String reachDetail(String value) {
        if(value==null)return "";
        String[][] patterns={
            {"verifying (.+) \\(manual forward\\)…", "desktopNetwork.manualVerifying", "address"},
            {"manual forward: port ([0-9]+) is not open from outside — check the router rule", "desktopNetwork.manualNotOpen", "port"},
            {"reachable at (.+) \\(manual forward\\)", "desktopNetwork.manualReachable", "address"},
            {"verifying (.+) from outside…", "desktopNetwork.verifyOutside", "address"},
            {"reachable at (.+) via (.+)", "desktopNetwork.reachableVia", "address", "via"},
            {"lease renewed for (.+)", "desktopNetwork.leaseRenewed", "address"}
        };
        for(String[] pattern:patterns){
            java.util.regex.Matcher m=java.util.regex.Pattern.compile(pattern[0]).matcher(value);
            if(m.matches()){
                Object[] pairs=new Object[(pattern.length-2)*2];
                for(int i=2;i<pattern.length;i++){pairs[(i-2)*2]=pattern[i];pairs[(i-2)*2+1]=m.group(i-1);}
                return Language.text(pattern[1],pairs);
            }
        }
        return Language.system(value);
    }

    private JPanel rowX() {
        JPanel r = new JPanel();
        r.setOpaque(false);
        r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        return r;
    }

    private static JComponent holder(JComponent c) {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.add(c, BorderLayout.NORTH);
        return h;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
