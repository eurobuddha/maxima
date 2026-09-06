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
public final class NetworkPanel extends JPanel implements MaximaWindow.Tab {

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
    private JLabel mDrDetail, mRelayRoutes, mRelayRelayed;

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

    public String label() { return "Network"; }
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
        // The jar view doesn't own the built-in live labels — null them so a later
        // flip-back to built-in never has updateLive() touch detached orphans.
        mStatHosts = mStatContacts = mStatMailbox = mStatOutbox = mStatServices = null;
        mDrDetail = mRelayRoutes = mRelayRelayed = null;
        mHeroPill = null; mHeroSub = null;
        int hosts = node.connectedCount();
        // hero
        DKit.RoundPanel hero = k.card();
        JPanel line = rowX();
        mHeroBig = new JLabel();
        mHeroBig.setFont(t.extrabold(22f));
        line.add(mHeroBig);
        line.add(Box.createHorizontalGlue());
        hero.add(line);
        hero.add(k.vgap(6));
        hero.add(k.sub(hosts + " host" + (hosts == 1 ? "" : "s")
                + " · classic Maxima engine (self-healing routing)"));
        mBody.add(hero);
        mBody.add(k.vgap(14));
        updateHero(hosts);

        // hosts (add via the jar's connectHost)
        mBody.add(k.sectionLabel("Hosts"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel hostCard = k.card();
        try {
            for (String h : node.jarEngine().connectedHosts()) {
                hostCard.add(k.kvLine(h, "connected"));
            }
        } catch (Exception ignored) { }
        hostCard.add(k.vgap(8));
        JTextField add = k.field("relay host:port");
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        hostCard.add(add);
        hostCard.add(k.vgap(8));
        DKit.HoverButton addBtn = k.primaryButton("Add & connect");
        addBtn.onClick(() -> {
            java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(add.getText());
            if (hs.isEmpty()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "Enter host:port, e.g. 31.125.188.214:9501 - or paste a relay QR text");
                return;
            }
            connectHost(hs.get(0));
        });
        JPanel ar = rowX();
        ar.add(addBtn);
        ar.add(Box.createHorizontalGlue());
        hostCard.add(ar);
        mBody.add(hostCard);
        mBody.add(k.vgap(14));

        // MLS (same card as built-in — routes through node helpers)
        mBody.add(mlsCard());
        mBody.add(k.vgap(14));

        // event log
        mBody.add(k.sectionLabel("Event log"));
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

    private JComponent mlsCard() {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(k.sectionLabel("Location service · finds you when you move"));
        col.add(k.vgap(8));
        DKit.RoundPanel mls = k.card();
        mls.add(k.kvLine("Mode", node.isStaticMls() ? "static (pinned)" : "rotating (host-assigned)"));
        String loc = node.mlsLocationAddress();
        if (loc != null && !loc.isEmpty()) {
            mls.add(k.vgap(8));
            mls.add(k.copyField("location address", loc, false));
        }
        String perm = node.permanentAddress();
        if (perm != null && !perm.isEmpty()) {
            mls.add(k.vgap(8));
            mls.add(k.copyField("permanent MAX# address", perm, false));
            mls.add(k.vgap(6));
            mls.add(k.sub(node.isStaticMls()
                    ? "Fixed to your pinned directory — this address never changes, "
                      + "even as you roam between hosts."
                    : "Host-assigned and usable right now. Pin a static MLS below to keep "
                      + "THIS exact address when your host changes."));
        } else {
            mls.add(k.vgap(6));
            mls.add(k.sub("Your MAX# appears as soon as a host offers you a directory. "
                    + "Pin a static MLS to fix it permanently."));
        }
        mls.add(k.vgap(10));
        DKit.HoverButton loc2 = k.ghostButton("Location settings");
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
        body.add(k.sub("Pin a static Location Service so contacts always find you when you "
                + "change networks. Leave it to the host otherwise."));
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
                "Location service", java.awt.Dialog.ModalityType.MODELESS);

        DKit.HoverButton pin = k.primaryButton("Pin this MLS");
        pin.setAlignmentX(Component.LEFT_ALIGNMENT);
        pin.onClick(() -> {
            String m = f.getText().trim();
            if (!m.startsWith("Mx") || !m.contains("@") || !m.contains(":")) {
                status.setText("Needs the form Mx…@host:port"); return;
            }
            node.setStaticMls(m);
            DesktopEventLog.add("static MLS pinned");
            mSig = ""; refresh(); d.dispose();
        });
        body.add(pin);
        body.add(k.vgap(8));

        if (node.canRegisterPool()) {
            DKit.HoverButton pool = k.primaryButton("Use the eurobuddha pool (register)");
            pool.setAlignmentX(Component.LEFT_ALIGNMENT);
            pool.onClick(() -> {
                pool.setButtonEnabled(false);
                status.setText("Registering with the pool…");
                new Thread(() -> registerPool(status, d), "mls-pool").start();
            });
            body.add(pool);
            body.add(k.vgap(8));
        }

        DKit.HoverButton host = k.ghostButton("Use the host's directory");
        host.setAlignmentX(Component.LEFT_ALIGNMENT);
        host.onClick(() -> {
            node.setStaticMls("");
            DesktopEventLog.add("MLS: using host directory");
            mSig = ""; refresh(); d.dispose();
        });
        body.add(host);
        body.add(k.vgap(6));
        body.add(status);

        d.setContentPane(body);
        d.setSize(430, node.canRegisterPool() ? 330 : 280);
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
                status.setText(ok ? "Registered — permanent MAX# address active"
                        : "Pool registration failed — see the log");
                if (ok) { mSig = ""; refresh(); d.dispose(); }
            });
        } catch (Exception e) {
            DesktopEventLog.add("pool register: " + e);
            javax.swing.SwingUtilities.invokeLater(() ->
                    status.setText("Pool unavailable: " + e.getMessage()));
        }
    }

    private void manualForward() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(16, 16, 16, 16));
        body.add(k.sub("If your router won't open a port automatically, add a rule that forwards "
                + "a TCP port to this computer, then enter your public IP so Parlons can prove "
                + "and advertise it. The port is pinned so the rule survives restarts."));
        body.add(k.vgap(10));
        String lan = DesktopManualForward.localIp();
        int curPort = DesktopManualForward.port();
        body.add(k.copyField("Forward to this computer",
                lan.isEmpty() ? "—" : lan + ":" + curPort, false));
        body.add(k.vgap(10));
        JTextField portF = k.field("Port to forward");
        portF.setText(String.valueOf(curPort));
        portF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        portF.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(portF);
        body.add(k.vgap(8));
        JTextField ipF = k.field("Your public IP (found automatically)");
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

        DKit.HoverButton enable = k.primaryButton("Enable & verify");
        enable.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit.HoverButton off = k.ghostButton("Turn manual off");
        off.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(enable);
        body.add(k.vgap(8));
        body.add(off);
        body.add(k.vgap(6));
        body.add(status);

        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                "Manual port-forward", java.awt.Dialog.ModalityType.MODELESS);
        enable.onClick(() -> {
            int p;
            try { p = Integer.parseInt(portF.getText().trim()); }
            catch (Exception e) { status.setText("Enter a port"); return; }
            if (p <= 0 || p >= 65536) { status.setText("Port must be 1–65535"); return; }
            if (p == DesktopNode.RELAY_PORT) {
                status.setText("Port " + p + " is reserved for relay mode — pick another"); return;
            }
            String ip = ipF.getText().trim();
            if (ip.isEmpty()) { status.setText("Enter your public IP (or wait for auto-detect)"); return; }
            DesktopManualForward.set(true, ip, p);
            node.applyManualForward();
            DesktopEventLog.add("manual port-forward enabled: " + ip + ":" + p);
            status.setText("Enabled — verifying " + ip + ":" + p + " from outside…");
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
        d.setContentPane(body);
        d.setSize(450, 360);
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
        mStatHosts = new JLabel(); mStatContacts = new JLabel();
        mStatMailbox = new JLabel(); mStatOutbox = new JLabel(); mStatServices = new JLabel();
        stats.add(statRow("Hosts connected", "Independent routes to you. More is safer.", mStatHosts));
        stats.add(k.divider());
        stats.add(statRow("Contacts", "People who can reach you and you them", mStatContacts));
        stats.add(k.divider());
        stats.add(statRow("Mailbox held for others", "Encrypted messages you store for offline peers", mStatMailbox));
        stats.add(k.divider());
        stats.add(statRow("Your outbox", "Your messages waiting to be accepted by a host", mStatOutbox));
        stats.add(k.divider());
        stats.add(statRow("Services you answer", "Requests this desktop can serve for others", mStatServices));
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
        mDrDetail = k.sub(node.reachDetail());
        mDrDetail.setAlignmentX(Component.LEFT_ALIGNMENT);
        dr.add(mDrDetail);
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
        // Manual port-forward — for routers with no UPnP (phone parity).
        dr.add(k.vgap(8));
        DKit.HoverButton mfBtn = k.ghostButton(DesktopManualForward.enabled()
                ? "Manual port-forward · on" : "Set up manual port-forward");
        mfBtn.onClick(this::manualForward);
        JPanel mfr = rowX();
        mfr.add(mfBtn);
        mfr.add(Box.createHorizontalGlue());
        dr.add(mfr);
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
        mRelayRoutes = null; mRelayRelayed = null;
        RelayRuntime.Stats rs = node.relayStats();
        if (relayOn && rs != null) {
            relay.add(k.vgap(8));
            mRelayRoutes = new JLabel(String.valueOf(rs.routes));
            mRelayRelayed = new JLabel(String.valueOf(rs.relayed));
            relay.add(kvRow("Clients attached", mRelayRoutes));
            relay.add(kvRow("Messages relayed", mRelayRelayed));
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
            // Typed host:port, a comma list, or the text of a relay's QR (parlons-relay:...).
            java.util.List<String> hs = com.eurobuddha.maxima.core.session.SeedRelays.parse(add.getText());
            if (hs.isEmpty()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "Enter host:port, e.g. 31.125.188.214:9501 - or paste a relay QR text");
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
        DKit.HoverButton reset = k.ghostButton("Reset to defaults");
        reset.onClick(() -> { node.relayStore().reset(); mSig = ""; refresh(); });
        // The compiled-in list is one seed source among several - never the only one.
        JPanel bi = rowX();
        JLabel biLabel = k.sub(node.relayStore().builtInEnabled()
                ? "Built-in relays: on (the list shipped with the app, plus yours)"
                : "Built-in relays: off (only relays you added or this machine remembers)");
        bi.add(biLabel);
        bi.add(Box.createRigidArea(new Dimension(10, 0)));
        bi.add(k.toggle(node.relayStore().builtInEnabled(), on -> {
            if (!node.relayStore().setBuiltInEnabled(on)) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "Add a relay of your own first - this machine needs somewhere to start.");
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
        mBody.add(hostCard);
        mBody.add(k.vgap(14));

        // ---- location service (MLS) — pin a static MLS, register with the
        //      eurobuddha pool, get a permanent MAX# address (full phone parity) ----
        mBody.add(mlsCard());
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

    /** Refresh the per-tick numbers/detail in place — never a rebuild, so the
     *  scroll position is left completely alone. */
    private void updateLive(MaximaNode n, int hosts) {
        if (mStatHosts == null) return;   // not built yet
        mStatHosts.setText(String.valueOf(hosts));
        mStatContacts.setText(String.valueOf(n.contacts().size()));
        mStatMailbox.setText(String.valueOf(n.mailbox().totalItems()));
        mStatOutbox.setText(String.valueOf(n.outbox().size()));
        mStatServices.setText(String.valueOf(n.services().methods().size()));
        if (mDrDetail != null) mDrDetail.setText(node.reachDetail());
        RelayRuntime.Stats rs = node.relayStats();
        if (rs != null && mRelayRoutes != null) mRelayRoutes.setText(String.valueOf(rs.routes));
        if (rs != null && mRelayRelayed != null) mRelayRelayed.setText(String.valueOf(rs.relayed));
    }

    /** A stat row whose value is a caller-owned label, so it can be updated in
     *  place by {@link #updateLive} without rebuilding the card. */
    private JComponent statRow(String title, String hint, JLabel value) {
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
        value.setFont(t.extrabold(18f));
        value.setForeground(t.text);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    /** A label:value line whose value label the caller keeps for in-place updates. */
    private JComponent kvRow(String label, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setFont(t.font(12.5f));
        l.setForeground(t.subtext);
        value.setFont(t.semibold(12.5f));
        value.setForeground(t.text);
        row.add(l, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private void updateHero(int hosts) {
        if (mHeroBig == null) return;
        boolean ok = hosts > 0;
        mHeroBig.setText(mConnecting ? "Connecting…" : (ok ? "Connected" : "Offline"));
        mHeroBig.setForeground(mConnecting ? t.text : (ok ? t.success : t.error));
        // mHeroPill / mHeroSub only exist on the built-in rebuild() path; the
        // classic (jar) rebuildJar() builds only mHeroBig. Guard so a jar-mode
        // heartbeat can't NPE here and leave the whole tab half-built.
        if (mHeroPill != null) {
            mHeroPill.removeAll();
            mHeroPill.add(k.pill(hosts + (hosts == 1 ? " host" : " hosts"), ok ? DKit.OK : DKit.BAD));
            mHeroPill.revalidate();
            mHeroPill.repaint();
        }
        if (mHeroSub != null && !mConnecting) {
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
        javax.swing.JOptionPane.showMessageDialog(this, body, "Share relay " + hostPort,
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
        String kind = DesktopRelayStore.isBuiltIn(hostPort) ? "built-in"
                : node.relayStore().userSeeds().contains(hostPort) ? "yours" : "learned";
        JLabel h = new JLabel(hostPort + "   " + kind);
        h.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        h.setForeground(t.text);
        row.add(h, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        DKit.HoverButton qr = k.ghostButton("QR");
        qr.setFont(t.semibold(11f));
        qr.onClick(() -> shareRelayQr(hostPort));
        actions.add(qr);
        actions.add(Box.createRigidArea(new Dimension(6, 0)));
        if (!connected) {
            DKit.HoverButton c = k.ghostButton("Connect");
            c.setFont(t.semibold(11f));
            c.onClick(() -> connectHost(hostPort));
            actions.add(c);
            actions.add(Box.createRigidArea(new Dimension(6, 0)));
        }
        DKit.HoverButton rm = k.dangerButton("Remove");
        rm.setFont(t.semibold(11f));
        rm.onClick(() -> {
            if (node.relayStore().remove(hostPort)) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "That was your last relay, so the built-in list is back on.");
            }
            mSig = "";
            refresh();
        });
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
