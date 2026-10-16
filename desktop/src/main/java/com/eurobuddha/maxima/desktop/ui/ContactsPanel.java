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
public final class ContactsPanel extends JPanel implements MaximaWindow.Tab, MaximaWindow.Responsive {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final MaximaWindow window;

    private final JPanel mBody = new JPanel();
    private final JPanel mList = new JPanel();
    private final JLabel mHostInfo = new JLabel("…");
    private final JLabel mCount = new JLabel();
    private JTextField mSearch;
    private JPanel mActions;
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

    public String label() { return Language.text("nav.contacts"); }
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
        JLabel idtitle = Language.bind(new JLabel(), Language.msg("desktopChat.yourAddress"));
        Language.font(idtitle, t.bold(16f));
        idtitle.setForeground(t.text);
        idtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        Language.font(mHostInfo, t.font(12.5f));
        mHostInfo.setForeground(t.subtext);
        mHostInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        idcol.add(idtitle);
        idcol.add(mHostInfo);
        idrow.add(idcol);
        idrow.add(Box.createHorizontalGlue());
        id.add(idrow);
        id.add(k.vgap(14));
        JPanel actions = new JPanel();
        mActions = actions;
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        DKit.HoverButton share = k.primaryButton(Language.msg("nativeContacts.shareMyAddress"));
        share.onClick(this::showShareDialog);
        DKit.HoverButton add = k.ghostButton(Language.msg("contacts.add"));
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
        Language.font(mSearch, t.font(13.5f));
        mSearch.setForeground(t.text);
        mSearch.setCaretColor(t.text);
        Language.tooltip(mSearch, Language.msg("common.search"));
        mSearch.getDocument().addDocumentListener(new SimpleDoc(() -> {
            mQuery = mSearch.getText();
            mLastSig = "";
            refresh();
        }));
        searchRow.add(mSearch);
        mBody.add(searchRow);
        mBody.add(k.vgap(14));

        // Count + list.
        Language.font(mCount, t.semibold(10.5f));
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
        Language.bind(mHostInfo, hosts == 0 ? Language.msg("desktopChat.waitingHost")
                : Language.msg("nativeContacts.hosts", "count", hosts));
        Language.bind(mCount, Language.msg("nativeContacts.listCount", "count", all.size()), value -> value.toUpperCase(Language.locale()));

        List<Contact> cs = filtered(all);
        mList.removeAll();
        if (cs.isEmpty()) {
            JLabel e = k.sub(all.isEmpty()
                    ? Language.msg("desktopChat.emptyContacts")
                    : Language.msg("nativeContacts.noMatches", "query", mQuery.trim()));
            e.setBorder(new EmptyBorder(8, 8, 8, 8));
            mList.add(e);
        } else {
            for (int i = 0; i < cs.size(); i++) {
                if (i > 0) mList.add(k.divider());
                mList.add(contactRow(cs.get(i)));
            }
        }
        if (getWidth() > 0) onWidth(getWidth());
        mList.revalidate();
        mList.repaint();
    }

    /** Pick another contact and send them zWho as a contact card (ChatContact body kind). */
    private void showSendContactPicker(final Contact zWho, final String zShare) {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        final JDialog[] holder = new JDialog[1];
        int n = 0;
        for (final Contact t : node.port().contacts()) {
            if (t.publicKey == null || t.publicKey.equalsIgnoreCase(zWho.publicKey)) continue;
            n++;
            DKit.HoverButton row = k.ghostButton(displayName(t));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.onClick(() -> {
                if (holder[0] != null) holder[0].dispose();
                final String card = com.eurobuddha.maxima.core.chat.ChatContact.wrap(zWho.publicKey, zWho.name, zShare);
                new Thread(() -> {
                    try { node.chat().send(t, card); } catch (Exception ignored) { }
                }, "send-contact-card").start();
            });
            body.add(row);
            body.add(k.vgap(4));
        }
        if (n == 0) body.add(k.sub(Language.msg("desktopChat.noRecipients")));
        holder[0] = dialog(Language.msg("nativeContacts.sendNameTo", "name", displayName(zWho)), body, 360);
    }

    private List<Contact> filtered(List<Contact> all) {
        String q = mQuery.trim();
        if (q.isEmpty()) return all;
        List<Contact> out = new ArrayList<>();
        for (Contact c : all) {
            if ((c.name != null && com.eurobuddha.maxima.app.i18n.SearchText.indexOf(c.name, q) >= 0)
                    || (c.publicKey != null && com.eurobuddha.maxima.app.i18n.SearchText.indexOf(c.publicKey, q) >= 0)) {
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
        Language.font(nm, t.semibold(13.5f), name);
        nm.setForeground(t.text);
        nm.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pr = Language.render(new JLabel(), () -> presence(c));
        Language.font(pr, t.font(11.5f));
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
        Language.tooltip(info, Language.msg("chat.details"));
        info.onClick(() -> showContactCard(c));
        row.add(info, BorderLayout.EAST);
        row.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { window.openChat(c.publicKey); }
        });
        return row;
    }

    @Override public void onWidth(int width) {
        DKit.layoutActions(mActions, Math.max(1, Math.min(720, width - 44) - 32));
    }

    // ---- dialogs ----

    private void showShareDialog() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(4, 4, 4, 4));
        JLabel qrHolder = Language.bind(new JLabel("", javax.swing.SwingConstants.CENTER), Language.msg("desktopChat.resolving"));
        Language.font(qrHolder, t.font(12f));
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

        JDialog d = dialog(Language.msg("nativeContacts.shareMyAddress"), body, 320);

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
                    Language.bind(qrHolder, Language.msg("desktopChat.resolveFailed"));
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
                    fields.add(k.copyField(Language.msg("desktopChat.permanent"), perm, false));
                    if (ip != null && !ip.isEmpty()) {
                        fields.add(k.vgap(8));
                        fields.add(k.copyField(Language.msg("desktopChat.currentAddress"), ip, false));
                    }
                } else {
                    fields.add(k.copyField(Language.msg("desktopChat.maximaAddress"), ip, false));
                }
                // KeetChat identity (keets pubkey via the linked node) - what a
                // KeetChat user adds as a contact. Shown in FULL, copyable.
                String kpk = node.chat().keetsPubkey();
                if (kpk != null) {
                    fields.add(k.vgap(8));
                    fields.add(k.copyField(Language.msg("desktopChat.keetsAddress"), kpk, false));
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
        body.add(k.sub(Language.msg("desktopChat.introduceHelp")));
        body.add(k.vgap(10));
        JTextField paste = k.field("Mx…@host:port");
        paste.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        paste.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(paste);
        body.add(k.vgap(12));
        DKit.HoverButton intro = k.primaryButton(Language.msg("nativeContacts.introduce"));
        JPanel r = new JPanel();
        r.setOpaque(false);
        r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.add(intro);
        r.add(Box.createHorizontalGlue());
        body.add(r);
        JDialog d = dialog(Language.msg("contacts.addHeading"), body, 380);
        intro.onClick(() -> { if (introduce(paste.getText().trim())) d.dispose(); });
    }

    private void showContactCard(Contact c) {
        List<JPanel> actionRows = new ArrayList<>();
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
        Language.font(nm, t.bold(16f), displayName(c));
        nm.setForeground(t.text);
        nm.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pr = Language.render(new JLabel(), () -> presence(c));
        Language.font(pr, t.font(12f));
        pr.setForeground(t.subtext);
        pr.setAlignmentX(Component.LEFT_ALIGNMENT);
        hc.add(nm);
        hc.add(pr);
        head.add(hc);
        head.add(Box.createHorizontalGlue());
        body.add(head);
        body.add(k.vgap(12));

        DKit.HoverButton msg = k.primaryButton(Language.msg("chat.message"));
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
            body.add(k.copyField(Language.msg("desktopChat.maximaAddress"), addr, false));
            int idx = 0;
            for (String a : c.addresses) {
                if (a != null && !a.equals(addr)) {
                    body.add(k.vgap(6));
                    body.add(k.copyField(Language.msg("nativeContacts.hostNumber", "number", ++idx), a, false));
                }
            }
        }
        // Share this contact: the permanent address when we know their directory (it survives
        // their relay changes), else the current one; and send it to another contact as a card.
        final String share = c.shareAddress();
        final DKit.HoverButton sendTo = k.ghostButton(Language.msg("contacts.sendTo"));
        if (!share.isEmpty()) {
            if (!share.equals(addr)) {
                body.add(k.vgap(6));
                body.add(k.copyField(Language.msg("nativeContacts.permanentShare"), share, false));
            }
            body.add(k.vgap(6));
            JPanel sr = new JPanel();
            sr.setOpaque(false);
            sr.setLayout(new BoxLayout(sr, BoxLayout.X_AXIS));
            sr.setAlignmentX(Component.LEFT_ALIGNMENT);
            DKit.HoverButton copyShare = k.ghostButton(Language.msg("desktopChat.copyShare"));
            copyShare.onClick(() -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(share), null));
            sr.add(copyShare);
            sr.add(k.hgap(8));
            sr.add(sendTo);
            sr.add(Box.createHorizontalGlue());
            actionRows.add(sr);
            body.add(sr);
        }
        if (c.minimaAddress != null && !c.minimaAddress.isEmpty()) {
            body.add(k.vgap(6));
            body.add(k.copyField(Language.msg("nativeContacts.paymentAddress"), c.minimaAddress, false));
        }
        body.add(k.vgap(10));
        DKit.RoundPanel meta = k.card();
        meta.add(k.kvLine(Language.msg("nativeContacts.software"), softwareLabel(c)));
        meta.add(k.divider());
        meta.add(k.kvLine(Language.msg("nativeContacts.lastSeen"), () -> c.lastSeen > 0 ? presence(c) : Language.text("nativeContacts.never")));
        body.add(meta);
        body.add(k.vgap(12));
        DKit.HoverButton remove = k.dangerButton(Language.msg("contacts.remove"));
        JPanel rr = new JPanel();
        rr.setOpaque(false);
        rr.setLayout(new BoxLayout(rr, BoxLayout.X_AXIS));
        rr.setAlignmentX(Component.LEFT_ALIGNMENT);
        rr.add(remove);
        rr.add(Box.createHorizontalGlue());
        body.add(rr);

        JDialog d = dialog(displayName(c), body, 420, actionRows.toArray(new JPanel[0]));
        msg.onClick(() -> { d.dispose(); window.openChat(c.publicKey); });
        sendTo.onClick(() -> { d.dispose(); showSendContactPicker(c, share); });
        remove.onClick(() -> {
            com.eurobuddha.maxima.core.MaximaNode n = node.node();
            if (n != null) n.removeContact(c.publicKey);
            mLastSig = "";
            refresh();
            d.dispose();
        });
    }

    private boolean introduce(String address) {
        // A bare keets pubkey (0x + 64 hex - the KeetChat QR / identity dialog):
        // a keets-only contact, no Maxima handshake. Needs the linked
        // minimaCore Desktop node (Settings -> Node link).
        if (address.matches("(?i)0x[0-9a-f]{64}")) {
            if (!node.chat().hasKeets()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        Language.text("desktopChat.keetsRequired"));
                return false;
            }
            com.eurobuddha.maxima.core.contacts.Contact kc = node.port().storeKeetsContact(address, "");
            if (kc == null) {
                javax.swing.JOptionPane.showMessageDialog(this, Language.text("nativeContacts.keetsUnsupported"));
                return false;
            }
            refresh();
            javax.swing.JOptionPane.showMessageDialog(this, Language.text("nativeContacts.keetsAdded"));
            return true;
        }
        // Accept BOTH forms the phone accepts: a live Mx…@host:port address AND a
        // permanent MAX#… address (which the engine resolves through the MLS).
        boolean ok = !address.isEmpty()
                && ((address.startsWith("Mx") && address.contains("@") && address.contains(":"))
                    || address.startsWith("MAX#"));
        if (!ok) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    Language.text("desktopChat.invalidAddress"));
            return false;
        }
        new Thread(() -> {
            try {
                node.port().introduce(address, true);
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this,
                                Language.text("desktopChat.introSent")));
            } catch (Exception e) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        javax.swing.JOptionPane.showMessageDialog(this, Language.text("desktopChat.introError", "error", Language.system(e.getMessage()))));
            }
        }, "introduce").start();
        return true;
    }

    // ---- helpers ----

    private JDialog dialog(CharSequence title, JComponent body, int width, JPanel... actionRows) {
        JDialog d = new JDialog(window.frame(), title.toString(), false);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        Language.title(d, title);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(t.card);
        wrap.setBorder(new EmptyBorder(16, 18, 18, 18));
        JScrollPane scroll = new JScrollPane(new DKit.ScrollableColumn(body),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(t.card);
        wrap.add(scroll, BorderLayout.CENTER);
        d.setContentPane(wrap);
        Runnable reflow = () -> {
            java.awt.Insets padding = body.getInsets();
            int available = Math.max(1, scroll.getViewport().getExtentSize().width - padding.left - padding.right);
            for (JPanel row : actionRows) DKit.layoutActions(row, available);
        };
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { reflow.run(); }
        });
        Runnable languageChanged = () -> {
            Language.refresh(d);
            reflow.run();
        };
        Language.addListener(languageChanged);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { Language.removeListener(languageChanged); }
        });
        Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        d.setSize(Math.min(screen.width - 80, Math.max(width, 490)), 500);
        d.validate();
        reflow.run();
        d.validate();
        d.setSize(d.getWidth(), Math.min(screen.height - 80,
                Math.max(300 * t.textPercent() / 100, body.getPreferredSize().height + 70)));
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
        CharSequence label;
        boolean core;
        if (c.isKeets()) { label = "keets"; core = true; }
        else if (c.isClassic()) { label = Language.msg("desktopChat.classic"); core = false; }
        else if ("core".equals(c.kind)) { label = Language.msg("desktopChat.core"); core = true; }
        else return null;
        DKit.RoundPanel p = k.round(core ? DKit.alpha(t.accent, 45) : t.input, 999);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setBorder(new EmptyBorder(1, 8, 1, 8));
        JLabel l = Language.bind(new JLabel(), label);
        Language.font(l, t.semibold(9.5f));
        l.setForeground(core ? t.text : t.subtext);
        p.add(l);
        p.setMaximumSize(p.getPreferredSize());
        return p;
    }

    private CharSequence softwareLabel(Contact c) {
        if (c.isKeets()) return "KeetChat (keets)";
        if (c.isClassic()) return "Classic Maxima";
        if ("core".equals(c.kind)) return "Minima Core";
        return Language.msg("desktopChat.app");
    }

    private String presence(Contact c) {
        if (c.isKeets()) return "KeetChat";
        long ls = c.lastSeen;
        if (ls <= 0) return Language.text("nativeContacts.offline");
        long d = System.currentTimeMillis() - ls;
        if (d < 30 * 60 * 1000L) return Language.text("presence.online");
        return Language.text("presence.lastSeen", "time", Language.relative(d));
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
