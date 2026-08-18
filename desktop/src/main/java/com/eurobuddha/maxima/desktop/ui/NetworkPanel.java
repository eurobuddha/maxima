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
public final class NetworkPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;

    private final JPanel mBody = new JPanel();
    private final DKit.WrapText mLog = new DKit.WrapText("");
    private String mSig = "";

    private JLabel mHeroBig;
    private JPanel mHeroPill;
    private JLabel mHeroSub;
    private DKit.HoverButton mAutoBtn;
    private boolean mConnecting;

    public NetworkPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);
        mBody.setLayout(new BoxLayout(mBody, BoxLayout.Y_AXIS));
        mBody.setOpaque(false);
        mBody.setBorder(new EmptyBorder(20, 22, 22, 22));
        JScrollPane sp = new JScrollPane(holder(k.centered(mBody, 720)),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(t.bg);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        add(sp, BorderLayout.CENTER);
    }

    public String label() { return "Network"; }
    public JComponent view() { return this; }

    public void refresh() {
        MaximaNode n = node.node();
        if (n == null) return;
        int hosts = n.pool().activeCount();
        boolean directNow = node.reachState() == ReachabilityManager.State.ADVERTISED;
        boolean relayOn = node.relayRunning();
        // Cheap live bits every tick: hero + event log.
        if (!mConnecting) {
            updateHero(hosts);
        }
        mLog.setText(DesktopEventLog.asText(40));

        String sig = hosts + "|" + n.contacts().size() + "|" + n.mailbox().totalItems()
                + "|" + n.outbox().size() + "|" + n.services().methods().size()
                + "|" + directNow + "|" + node.reachAddress() + "|" + node.reachDetail()
                + "|" + relayOn + "|" + n.pool().activeHosts() + "|" + node.relayStore().get()
                + "|" + n.mlsAddress();
        if (sig.equals(mSig)) return;
        mSig = sig;
        rebuild(n, hosts, directNow, relayOn);
    }

    private void rebuild(MaximaNode n, int hosts, boolean directNow, boolean relayOn) {
        mBody.removeAll();

        // ---- connection hero + Auto-connect ----
        DKit.RoundPanel hero = k.card();
        JPanel line = rowX();
        mHeroBig = new JLabel();
        mHeroBig.setFont(t.extrabold(22f));
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
        mAutoBtn = k.primaryButton("Auto-connect");
        mAutoBtn.onClick(this::autoConnect);
        JPanel ar = rowX();
        ar.add(mAutoBtn);
        ar.add(Box.createHorizontalGlue());
        hero.add(ar);
        mBody.add(hero);
        mBody.add(k.vgap(14));
        updateHero(hosts);

        // ---- transport stats ----
        mBody.add(k.sectionLabel("Transport"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel stats = k.card();
        stats.add(stat("Hosts connected", "Independent routes to you. More is safer.", String.valueOf(hosts)));
        stats.add(k.divider());
        stats.add(stat("Contacts", "People who can reach you and you them", String.valueOf(n.contacts().size())));
        stats.add(k.divider());
        stats.add(stat("Mailbox held for others", "Encrypted messages you store for offline peers",
                String.valueOf(n.mailbox().totalItems())));
        stats.add(k.divider());
        stats.add(stat("Your outbox", "Your messages waiting to be accepted by a host",
                String.valueOf(n.outbox().size())));
        stats.add(k.divider());
        stats.add(stat("Services you answer", "Requests this desktop can serve for others",
                String.valueOf(n.services().methods().size())));
        mBody.add(stats);
        mBody.add(k.vgap(14));

        // ---- direct reachability ----
        mBody.add(k.sectionLabel("Direct reachability · a public address when possible"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel dr = k.card();
        JPanel drRow = rowX();
        JLabel drState = new JLabel(directNow ? "Directly reachable"
                : (node.reachState() == ReachabilityManager.State.OFF ? "Not advertised"
                : cap(node.reachState().name().toLowerCase())));
        drState.setFont(t.semibold(14f));
        drState.setForeground(directNow ? t.success : t.text);
        drRow.add(drState);
        drRow.add(Box.createHorizontalGlue());
        drRow.add(k.pill(directNow ? "on" : "off", directNow ? DKit.OK : DKit.NEUTRAL));
        dr.add(drRow);
        dr.add(k.vgap(6));
        dr.add(k.sub(node.reachDetail()));
        if (directNow && !node.reachAddress().isEmpty()) {
            dr.add(k.vgap(8));
            dr.add(k.copyField("your public address", node.reachAddress(), false));
        }
        dr.add(k.vgap(10));
        DKit.HoverButton check = k.ghostButton(directNow ? "Re-check" : "Check / make me reachable");
        check.onClick(() -> {
            DesktopEventLog.add("checking direct reachability…");
            node.checkReachability();
        });
        JPanel cr = rowX();
        cr.add(check);
        cr.add(Box.createHorizontalGlue());
        dr.add(cr);
        mBody.add(dr);
        mBody.add(k.vgap(14));

        // ---- relay ----
        mBody.add(k.sectionLabel("Help the network"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel relay = k.card();
        JPanel switchRow = rowX();
        JPanel sc = new JPanel();
        sc.setOpaque(false);
        sc.setLayout(new BoxLayout(sc, BoxLayout.Y_AXIS));
        JLabel st = new JLabel("Run as a relay");
        st.setFont(t.semibold(13.5f));
        st.setForeground(t.text);
        st.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel sh = k.sub(relayOn ? "You're carrying other people's traffic on port " + node.relayPort()
                : "Forward other people's messages — grows the network");
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
        RelayRuntime.Stats rs = node.relayStats();
        if (relayOn && rs != null) {
            relay.add(k.vgap(8));
            relay.add(k.kvLine("Clients attached", String.valueOf(rs.routes)));
            relay.add(k.kvLine("Messages relayed", String.valueOf(rs.relayed)));
        }
        // tiers
        relay.add(k.vgap(8));
        relay.add(tier("Contributing", "mailbox & lookups", hosts > 0));
        relay.add(tier("Directly reachable", "peers reach you", directNow));
        relay.add(tier("Relay ⚡", "carrying others' traffic", relayOn));
        mBody.add(relay);
        mBody.add(k.vgap(14));

        // ---- hosts ----
        mBody.add(k.sectionLabel("Hosts · they relay your traffic"));
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
            hostCard.add(k.sub("No hosts yet."));
        }
        hostCard.add(k.vgap(10));
        JTextField add = k.field("add relay  host:port");
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        hostCard.add(add);
        hostCard.add(k.vgap(8));
        JPanel hr = rowX();
        DKit.HoverButton addBtn = k.primaryButton("Add & connect");
        addBtn.onClick(() -> {
            String hp = add.getText().trim();
            if (!DesktopRelayStore.isValid(hp)) {
                javax.swing.JOptionPane.showMessageDialog(this, "Enter host:port, e.g. 31.125.188.214:9501");
                return;
            }
            node.relayStore().add(hp);
            connectHost(hp);
            add.setText("");
            mSig = "";
            refresh();
        });
        DKit.HoverButton reset = k.ghostButton("Reset to defaults");
        reset.onClick(() -> { node.relayStore().reset(); mSig = ""; refresh(); });
        hr.add(addBtn);
        hr.add(Box.createRigidArea(new Dimension(8, 0)));
        hr.add(reset);
        hr.add(Box.createHorizontalGlue());
        hostCard.add(hr);
        mBody.add(hostCard);
        mBody.add(k.vgap(14));

        // ---- MLS ----
        mBody.add(k.sectionLabel("Location service · finds you when you move"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel mls = k.card();
        String mlsAddr = n.mlsAddress();
        if (mlsAddr == null || mlsAddr.isEmpty()) {
            mls.add(k.sub("None — no host has offered one yet."));
        } else {
            mls.add(k.copyField("location address", mlsAddr, false));
        }
        mBody.add(mls);
        mBody.add(k.vgap(14));

        // ---- event log ----
        JPanel logHead = rowX();
        logHead.add(k.sectionLabel("Event log"));
        logHead.add(Box.createHorizontalGlue());
        DKit.HoverButton clear = k.ghostButton("Clear");
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
        mBody.revalidate();
        mBody.repaint();
    }

    private void updateHero(int hosts) {
        if (mHeroBig == null) return;
        boolean ok = hosts > 0;
        mHeroBig.setText(mConnecting ? "Connecting…" : (ok ? "Connected" : "Offline"));
        mHeroBig.setForeground(mConnecting ? t.text : (ok ? t.success : t.error));
        mHeroPill.removeAll();
        mHeroPill.add(k.pill(hosts + (hosts == 1 ? " host" : " hosts"), ok ? DKit.OK : DKit.BAD));
        mHeroPill.revalidate();
        mHeroPill.repaint();
        if (!mConnecting) {
            mHeroSub.setText(ok ? "Reachable through " + hosts + (hosts == 1 ? " route." : " independent routes.")
                    : "Not connected — tap Auto-connect to fix.");
        }
    }

    private void autoConnect() {
        if (DesktopConnectionFinder.isRunning()) return;
        mConnecting = true;
        mAutoBtn.setButtonEnabled(false);
        mAutoBtn.setText("Working…");
        DesktopConnectionFinder.run(node, node.relayStore(), new DesktopConnectionFinder.Listener() {
            public void onStep(String plain) { mHeroSub.setText(plain); }
            public void onDone(int connectedHosts, boolean anyReachable) {
                mConnecting = false;
                mAutoBtn.setButtonEnabled(true);
                mAutoBtn.setText("Auto-connect");
                node.checkReachability();
                mSig = "";
                refresh();
            }
        });
    }

    private void connectHost(String hp) {
        new Thread(() -> {
            try {
                node.node().pool().addCandidate(hp);
                node.node().pool().attachOne(hp, 15000);
            } catch (Exception ignored) { }
            javax.swing.SwingUtilities.invokeLater(() -> { mSig = ""; refresh(); });
        }, "connect-host").start();
    }

    // ---- rows ----

    private JComponent stat(String title, String hint, String value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 2, 9, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel tl = new JLabel(title);
        tl.setFont(t.semibold(13f));
        tl.setForeground(t.text);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hl = k.sub(hint);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(tl);
        left.add(hl);
        row.add(left, BorderLayout.CENTER);
        JLabel v = new JLabel(value);
        v.setFont(t.extrabold(18f));
        v.setForeground(t.text);
        row.add(v, BorderLayout.EAST);
        return row;
    }

    private JComponent tier(String title, String hint, boolean on) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 2, 6, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        JLabel dot = new JLabel("●");
        dot.setFont(t.font(11f));
        dot.setForeground(on ? t.success : DKit.alpha(t.subtext, 120));
        row.add(dot, BorderLayout.WEST);
        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel tl = new JLabel(title);
        tl.setFont(t.semibold(12.5f));
        tl.setForeground(on ? t.text : t.subtext);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hl = k.sub(hint);
        hl.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(tl);
        mid.add(hl);
        row.add(mid, BorderLayout.CENTER);
        if (on) {
            JLabel now = new JLabel("NOW");
            now.setFont(t.bold(9.5f));
            now.setForeground(t.success);
            row.add(now, BorderLayout.EAST);
        }
        return row;
    }

    private JComponent hostRow(String hostPort, boolean connected) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 2, 9, 2));
        JLabel dot = new JLabel("●");
        dot.setFont(t.font(10f));
        dot.setForeground(connected ? t.success : DKit.alpha(t.subtext, 110));
        row.add(dot, BorderLayout.WEST);
        JLabel h = new JLabel(hostPort);
        h.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        h.setForeground(t.text);
        row.add(h, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        if (!connected) {
            DKit.HoverButton c = k.ghostButton("Connect");
            c.setFont(t.semibold(11f));
            c.onClick(() -> connectHost(hostPort));
            actions.add(c);
            actions.add(Box.createRigidArea(new Dimension(6, 0)));
        }
        DKit.HoverButton rm = k.dangerButton("Remove");
        rm.setFont(t.semibold(11f));
        rm.onClick(() -> { node.relayStore().remove(hostPort); mSig = ""; refresh(); });
        actions.add(rm);
        row.add(actions, BorderLayout.EAST);
        return row;
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
