package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.contacts.Contact;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Contacts tab — a faithful recreation of the phone's Contacts page: an
 * identity card ("Your Maxima address" with a QR-share and add-contact action),
 * a search field, and the contact list. Tapping a contact opens the chat;
 * an info action opens the contact card with every host address copyable in full.
 */
public final class ContactsPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final MaximaWindow window;

    private final JPanel mBody = new JPanel();
    private final JPanel mList = new JPanel();
    private final JLabel mHostInfo = new JLabel("…");
    private final JLabel mCount = new JLabel("CONTACTS · 0");
    private JTextField mSearch;
    private String mQuery = "";
    private String mLastSig = "";

    public ContactsPanel(DesktopNode zNode, Theme zTheme, MaximaWindow zWindow) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        window = zWindow;
        setLayout(new BorderLayout());
        setBackground(t.bg);

        mBody.setLayout(new BoxLayout(mBody, BoxLayout.Y_AXIS));
        mBody.setOpaque(false);
        mBody.setBorder(new EmptyBorder(20, 22, 22, 22));
        buildScaffold();

        JScrollPane sp = new JScrollPane(new DKit.ScrollableColumn(k.centered(mBody, 720)),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(t.bg);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        add(sp, BorderLayout.CENTER);
    }

    public String label() { return "Contacts"; }
    public JComponent view() { return this; }

    private void buildScaffold() {
        // Identity card.
        DKit.RoundPanel id = k.card();
        JPanel idrow = new JPanel();
        idrow.setOpaque(false);
        idrow.setLayout(new BoxLayout(idrow, BoxLayout.X_AXIS));
        idrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        QrDisc disc = new QrDisc(t);
        idrow.add(disc);
        idrow.add(Box.createRigidArea(new Dimension(13, 0)));
        JPanel idcol = new JPanel();
        idcol.setOpaque(false);
        idcol.setLayout(new BoxLayout(idcol, BoxLayout.Y_AXIS));
        JLabel idtitle = new JLabel("Your Maxima address");
        idtitle.setFont(t.bold(16f));
        idtitle.setForeground(t.text);
        idtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        mHostInfo.setFont(t.font(12.5f));
        mHostInfo.setForeground(t.subtext);
        mHostInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        idcol.add(idtitle);
        idcol.add(mHostInfo);
        idrow.add(idcol);
        idrow.add(Box.createHorizontalGlue());
        id.add(idrow);
        id.add(k.vgap(14));
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit.HoverButton share = k.primaryButton("Share my address");
        share.onClick(this::showShareDialog);
        DKit.HoverButton add = k.ghostButton("Add contact");
        add.onClick(this::showAddDialog);
        actions.add(share);
        actions.add(Box.createRigidArea(new Dimension(10, 0)));
        actions.add(add);
        actions.add(Box.createHorizontalGlue());
        id.add(actions);
        mBody.add(id);
        mBody.add(k.vgap(14));

        // Search field.
        DKit.RoundPanel searchRow = k.round(t.input, DKit.R_FIELD);
        searchRow.setLayout(new BoxLayout(searchRow, BoxLayout.X_AXIS));
        searchRow.setBorder(new EmptyBorder(2, 12, 2, 12));
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        Icons.Btn si = new Icons.Btn(Icons.SEARCH, t.subtext, null, 24, 16, 1.8f);
        searchRow.add(si);
        searchRow.add(Box.createRigidArea(new Dimension(8, 0)));
        mSearch = new JTextField();
        mSearch.setOpaque(false);
        mSearch.setBorder(new EmptyBorder(9, 0, 9, 0));
        mSearch.setFont(t.font(13.5f));
        mSearch.setForeground(t.text);
        mSearch.setCaretColor(t.text);
        mSearch.getDocument().addDocumentListener(new SimpleDoc(() -> {
            mQuery = mSearch.getText();
            mLastSig = "";
            refresh();
        }));
        searchRow.add(mSearch);
        mBody.add(searchRow);
        mBody.add(k.vgap(14));

        // Count + list.
        mCount.setFont(t.semibold(10.5f));
        mCount.setForeground(t.subtext);
        mCount.setAlignmentX(Component.LEFT_ALIGNMENT);
        mBody.add(mCount);
        mBody.add(k.vgap(8));
        DKit.RoundPanel listCard = k.card();
        listCard.setBorder(new EmptyBorder(4, 8, 4, 8));
        mList.setLayout(new BoxLayout(mList, BoxLayout.Y_AXIS));
        mList.setOpaque(false);
        mList.setAlignmentX(Component.LEFT_ALIGNMENT);
        listCard.add(mList);
        mBody.add(listCard);
        mBody.add(Box.createVerticalGlue());
    }

    public void refresh() {
        List<Contact> all = node.port().contacts();
        StringBuilder sig = new StringBuilder(myAddress()).append('#').append(mQuery);
        for (Contact c : all) {
            sig.append(c.publicKey).append(c.name).append(c.lastSeen).append('|');
        }
        if (sig.toString().equals(mLastSig)) {
            return;
        }
        mLastSig = sig.toString();

        int hosts = 0;
        try { hosts = Math.max(0, node.port().myAddresses().size()); } catch (Exception ignored) { }
        mHostInfo.setText(hosts == 0 ? "waiting for a host…"
                : "Reachable through " + hosts + (hosts == 1 ? " host" : " hosts"));
        mCount.setText("CONTACTS · " + all.size());

        List<Contact> cs = filtered(all);
        mList.removeAll();
        if (cs.isEmpty()) {
            JLabel e = k.sub(all.isEmpty()
                    ? "Nobody yet — share your address or add a friend to get started."
                    : "No contact matches \"" + mQuery.trim() + "\".");
            e.setBorder(new EmptyBorder(8, 8, 8, 8));
            mList.add(e);
        } else {
            for (int i = 0; i < cs.size(); i++) {
                if (i > 0) mList.add(k.divider());
                mList.add(contactRow(cs.get(i)));
            }
        }
        mList.revalidate();
        mList.repaint();
    }

    private List<Contact> filtered(List<Contact> all) {
        String q = mQuery.trim().toLowerCase();
        if (q.isEmpty()) return all;
        List<Contact> out = new ArrayList<>();
        for (Contact c : all) {
            if ((c.name != null && c.name.toLowerCase().contains(q))
                    || (c.publicKey != null && c.publicKey.toLowerCase().contains(q))) {
                out.add(c);
            }
        }
        return out;
    }

    private JComponent contactRow(Contact c) {
        String name = displayName(c);
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(10, 8, 10, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.add(k.avatar(c.publicKey, name, 40), BorderLayout.WEST);
        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel nm = new JLabel(name);
        nm.setFont(t.semibold(13.5f));
        nm.setForeground(t.text);
        nm.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pr = new JLabel(presence(c));
        pr.setFont(t.font(11.5f));
        pr.setForeground(t.subtext);
        JPanel prRow = new JPanel();
        prRow.setOpaque(false);
        prRow.setLayout(new BoxLayout(prRow, BoxLayout.X_AXIS));
        prRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        prRow.add(pr);
        JComponent pill = peerPill(c);
        if (pill != null) {
            prRow.add(Box.createRigidArea(new Dimension(8, 0)));
            prRow.add(pill);
        }
        prRow.add(Box.createHorizontalGlue());
        mid.add(nm);
        mid.add(prRow);
        row.add(mid, BorderLayout.CENTER);
        Icons.Btn info = new Icons.Btn(Icons.CONTACTS, t.subtext, DKit.alpha(t.subtext, 40), 30, 17, 1.6f);
        info.onClick(() -> showContactCard(c));
        row.add(info, BorderLayout.EAST);
        row.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { window.openChat(c.publicKey); }
        });
        return row;
    }

    // ---- dialogs ----

    private void showShareDialog() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(4, 4, 4, 4));
        JLabel qrHolder = new JLabel("Resolving address…", javax.swing.SwingConstants.CENTER);
        qrHolder.setFont(t.font(12f));
        qrHolder.setForeground(t.subtext);
        qrHolder.setAlignmentX(Component.CENTER_ALIGNMENT);
        qrHolder.setPreferredSize(new Dimension(230, 230));
        qrHolder.setMaximumSize(new Dimension(230, 230));
        JPanel qrWrap = new JPanel();
        qrWrap.setOpaque(false);
        qrWrap.setLayout(new BoxLayout(qrWrap, BoxLayout.X_AXIS));
        qrWrap.add(Box.createHorizontalGlue());
        qrWrap.add(qrHolder);
        qrWrap.add(Box.createHorizontalGlue());
        body.add(qrWrap);
        body.add(k.vgap(12));
        JPanel copyHolder = new JPanel(new BorderLayout());
        copyHolder.setOpaque(false);
        body.add(copyHolder);

        JDialog d = dialog("Share my address", body, 320);

        new Thread(() -> {
            final String ip = toIpForm(myAddress());
            String pm = "";
            try { pm = node.permanentAddress(); } catch (Exception ignored) { }
            final String perm = pm == null ? "" : pm;
            javax.swing.SwingUtilities.invokeLater(() -> {
                // Prefer the permanent MAX# when a static MLS is pinned — it keeps
                // resolving even after you move networks. Fall back to the live IP.
                String share = !perm.isEmpty() ? perm : ip;
                if (share == null || share.isEmpty()) {
                    qrHolder.setText("Couldn't resolve a shareable address. Try again when online.");
                    return;
                }
                java.awt.image.BufferedImage qr = DesktopQr.encode(share, 230,
                        0xFF000000 | (t.text.getRGB() & 0xFFFFFF), 0xFFFFFFFF);
                if (qr != null) {
                    qrHolder.setText(null);
                    qrHolder.setIcon(new javax.swing.ImageIcon(qr));
                }
                JPanel fields = new JPanel();
                fields.setOpaque(false);
                fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
                if (!perm.isEmpty()) {
                    fields.add(k.copyField("permanent MAX# address", perm, false));
                    if (ip != null && !ip.isEmpty()) {
                        fields.add(k.vgap(8));
                        fields.add(k.copyField("current address", ip, false));
                    }
                } else {
                    fields.add(k.copyField("maxima address", ip, false));
                }
                copyHolder.add(fields, BorderLayout.CENTER);
                copyHolder.revalidate();
                d.pack();
                d.setLocationRelativeTo(window.frame());
            });
        }, "share-resolve").start();
    }

    private void showAddDialog() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(4, 4, 4, 4));
        body.add(k.sub("Paste a friend's address, then introduce yourself. They appear "
                + "here once they reply."));
        body.add(k.vgap(10));
        JTextField paste = k.field("Mx…@host:port");
        paste.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        paste.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(paste);
        body.add(k.vgap(12));
        DKit.HoverButton intro = k.primaryButton("Introduce myself");
        JPanel r = new JPanel();
        r.setOpaque(false);
        r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.add(intro);
        r.add(Box.createHorizontalGlue());
        body.add(r);
        JDialog d = dialog("Add a contact", body, 380);
        intro.onClick(() -> { if (introduce(paste.getText().trim())) d.dispose(); });
    }

    private void showContactCard(Contact c) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(4, 4, 4, 4));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(k.avatar(c.publicKey, displayName(c), 48));
        head.add(Box.createRigidArea(new Dimension(12, 0)));
        JPanel hc = new JPanel();
        hc.setOpaque(false);
        hc.setLayout(new BoxLayout(hc, BoxLayout.Y_AXIS));
        JLabel nm = new JLabel(displayName(c));
        nm.setFont(t.bold(16f));
        nm.setForeground(t.text);
        nm.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pr = new JLabel(presence(c));
        pr.setFont(t.font(12f));
        pr.setForeground(t.subtext);
        pr.setAlignmentX(Component.LEFT_ALIGNMENT);
        hc.add(nm);
        hc.add(pr);
        head.add(hc);
        head.add(Box.createHorizontalGlue());
        body.add(head);
        body.add(k.vgap(12));

        DKit.HoverButton msg = k.primaryButton("Message");
        JPanel mr = new JPanel();
        mr.setOpaque(false);
        mr.setLayout(new BoxLayout(mr, BoxLayout.X_AXIS));
        mr.setAlignmentX(Component.LEFT_ALIGNMENT);
        mr.add(msg);
        mr.add(Box.createHorizontalGlue());
        body.add(mr);
        body.add(k.vgap(10));

        String addr = c.primaryAddress();
        if (addr != null && !addr.isEmpty()) {
            body.add(k.copyField("maxima address", addr, false));
            int idx = 0;
            for (String a : c.addresses) {
                if (a != null && !a.equals(addr)) {
                    body.add(k.vgap(6));
                    body.add(k.copyField("host " + (++idx), a, false));
                }
            }
        }
        if (c.minimaAddress != null && !c.minimaAddress.isEmpty()) {
            body.add(k.vgap(6));
            body.add(k.copyField("payment address (MINIMA)", c.minimaAddress, false));
        }
        body.add(k.vgap(10));
        DKit.RoundPanel meta = k.card();
        meta.add(k.kvLine("Software", softwareLabel(c)));
        meta.add(k.divider());
        meta.add(k.kvLine("Last seen", c.lastSeen > 0 ? presence(c) : "never"));
        body.add(meta);
        body.add(k.vgap(12));
        DKit.HoverButton remove = k.dangerButton("Remove contact");
        JPanel rr = new JPanel();
        rr.setOpaque(false);
        rr.setLayout(new BoxLayout(rr, BoxLayout.X_AXIS));
        rr.setAlignmentX(Component.LEFT_ALIGNMENT);
        rr.add(remove);
        rr.add(Box.createHorizontalGlue());
        body.add(rr);

        JDialog d = dialog(displayName(c), body, 420);
        msg.onClick(() -> { d.dispose(); window.openChat(c.publicKey); });
        remove.onClick(() -> {
            com.eurobuddha.maxima.core.MaximaNode n = node.node();
            if (n != null) n.removeContact(c.publicKey);
            mLastSig = "";
            refresh();
            d.dispose();
        });
    }

    private boolean introduce(String address) {
        // Accept BOTH forms the phone accepts: a live Mx…@host:port address AND a
        // permanent MAX#… address (which the engine resolves through the MLS).
        boolean ok = !address.isEmpty()
                && ((address.startsWith("Mx") && address.contains("@") && address.contains(":"))
                    || address.startsWith("MAX#"));
        if (!ok) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Paste an Mx…@host:port address or a permanent MAX#… address.");
            return false;
        }
        new Thread(() -> {
            try {
                node.port().introduce(address, true);
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this,
                                "Introduction sent. They'll appear once they reply."));
            } catch (Exception e) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this, "Couldn't introduce: " + e.getMessage()));
            }
        }, "introduce").start();
        return true;
    }

    // ---- helpers ----

    private JDialog dialog(String title, JComponent body, int width) {
        JDialog d = new JDialog(window.frame(), title, false);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(t.card);
        wrap.setBorder(new EmptyBorder(16, 18, 18, 18));
        wrap.add(body, BorderLayout.CENTER);
        d.setContentPane(wrap);
        d.pack();
        d.setSize(Math.max(width, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(window.frame());
        d.setVisible(true);
        return d;
    }

    private String displayName(Contact c) {
        return c.name == null || c.name.isEmpty() || "noname".equals(c.name)
                ? shortKey(c.publicKey) : c.name;
    }

    private String myAddress() {
        try {
            List<String> addrs = node.port().myAddresses();
            for (String a : addrs) {
                int at = a.lastIndexOf('@');
                String host = at < 0 ? "" : a.substring(at + 1);
                int colon = host.lastIndexOf(':');
                String h = colon < 0 ? host : host.substring(0, colon);
                if (h.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || h.contains(":")) {
                    return a;
                }
            }
            return addrs.isEmpty() ? "" : addrs.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    /** Resolve a domain host to an IP; never hand out a domain (returns null on failure). */
    private String toIpForm(String addr) {
        int at = addr == null ? -1 : addr.lastIndexOf('@');
        if (at < 0) return addr;
        String mx = addr.substring(0, at);
        String hp = addr.substring(at + 1);
        if (hp.startsWith("[")) return addr;
        int colon = hp.lastIndexOf(':');
        String host = colon < 0 ? hp : hp.substring(0, colon);
        String port = colon < 0 ? "" : hp.substring(colon);
        if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || host.contains(":")) return addr;
        try {
            return mx + "@" + java.net.InetAddress.getByName(host).getHostAddress() + port;
        } catch (Exception e) {
            return null;
        }
    }

    /** core / classic pill next to a contact's presence; null for a plain peer. */
    private JComponent peerPill(Contact c) {
        String label;
        boolean core;
        if (c.isClassic()) { label = "classic"; core = false; }
        else if ("core".equals(c.kind)) { label = "core"; core = true; }
        else return null;
        DKit.RoundPanel p = k.round(core ? DKit.alpha(t.accent, 45) : t.input, 999);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setBorder(new EmptyBorder(1, 8, 1, 8));
        JLabel l = new JLabel(label);
        l.setFont(t.semibold(9.5f));
        l.setForeground(core ? t.text : t.subtext);
        p.add(l);
        p.setMaximumSize(p.getPreferredSize());
        return p;
    }

    private String softwareLabel(Contact c) {
        if (c.isClassic()) return "Classic Maxima";
        if ("core".equals(c.kind)) return "Minima Core";
        return "Parlons app";
    }

    private String presence(Contact c) {
        long ls = c.lastSeen;
        if (ls <= 0) return "offline";
        long d = System.currentTimeMillis() - ls;
        if (d < 30 * 60 * 1000L) return "online";
        long m = d / 60000;
        String ago = m < 60 ? m + "m" : (m < 1440 ? (m / 60) + "h" : (m / 1440) + "d");
        return "last seen " + ago + " ago";
    }

    private static String shortKey(String key) {
        if (key == null) return "?";
        return key.length() > 12 ? key.substring(0, 10) + "…" : key;
    }

    private static JComponent holder(JComponent c) {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.add(c, BorderLayout.NORTH);
        return h;
    }

    /** The QR-glyph identity disc (dark disc + white QR icon), like the phone's. */
    private static final class QrDisc extends JComponent {
        private final Theme theme;
        QrDisc(Theme t) {
            theme = t;
            setPreferredSize(new Dimension(46, 46));
            setMaximumSize(new Dimension(46, 46));
            setMinimumSize(new Dimension(46, 46));
        }
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int gg = 0x33;
            g2.setColor(new Color(gg, gg + 4, gg + 8));
            g2.fillRoundRect(0, 0, 46, 46, 16, 16);
            // simple QR-ish glyph: three finder squares + a dot cluster
            g2.setColor(Color.WHITE);
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawRect(11, 11, 8, 8);
            g2.drawRect(27, 11, 8, 8);
            g2.drawRect(11, 27, 8, 8);
            g2.fillRect(28, 28, 3, 3);
            g2.fillRect(32, 32, 3, 3);
            g2.dispose();
        }
    }

    /** Minimal document listener that fires one callback on any change. */
    private static final class SimpleDoc implements javax.swing.event.DocumentListener {
        private final Runnable r;
        SimpleDoc(Runnable zr) { r = zr; }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
