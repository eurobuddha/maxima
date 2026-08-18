package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.contacts.Contact;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Contacts tab: your address (copyable in full — RULE 1), an add-contact box
 * that introduces you to a pasted {@code Mx…@host:port} address, and the list of
 * people who can reach you.
 */
public final class ContactsPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final JPanel mBody = new JPanel();
    private String mLastSig = "";

    public ContactsPanel(DesktopNode zNode, Theme zTheme) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        setLayout(new BorderLayout());
        setBackground(t.bg);
        mBody.setLayout(new BoxLayout(mBody, BoxLayout.Y_AXIS));
        mBody.setBackground(t.bg);
        mBody.setBorder(new EmptyBorder(22, 26, 22, 26));
        JScrollPane sp = new JScrollPane(mBody,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(t.bg);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        add(sp, BorderLayout.CENTER);
    }

    public String label() { return "Contacts"; }
    public JComponent view() { return this; }

    public void refresh() {
        List<Contact> cs = node.node().contacts();
        StringBuilder sig = new StringBuilder(myAddress());
        for (Contact c : cs) {
            sig.append(c.publicKey).append(c.name).append(c.lastSeen).append('|');
        }
        if (sig.toString().equals(mLastSig)) {
            return;
        }
        mLastSig = sig.toString();
        rebuild(cs);
    }

    private void rebuild(List<Contact> cs) {
        mBody.removeAll();
        mBody.add(k.title("Contacts"));
        mBody.add(k.vgap(16));

        // My address card.
        DKit.RoundPanel mine = k.card();
        mine.add(k.sectionLabel("Your address"));
        mine.add(k.vgap(8));
        String addr = myAddress();
        if (addr.isEmpty()) {
            mine.add(k.sub("No address yet — waiting for a host."));
        } else {
            mine.add(k.copyField("maxima address", addr, false));
            mine.add(k.vgap(4));
            mine.add(k.sub("Share this so others can reach you."));
        }
        mBody.add(mine);
        mBody.add(k.vgap(14));

        // Add contact card.
        DKit.RoundPanel add = k.card();
        add.add(k.sectionLabel("Add someone"));
        add.add(k.vgap(8));
        JTextField paste = k.field("Mx…@host:port");
        paste.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        paste.setAlignmentX(Component.LEFT_ALIGNMENT);
        add.add(paste);
        add.add(k.vgap(10));
        DKit.HoverButton intro = k.primaryButton("Introduce myself");
        intro.onClick(() -> introduce(paste.getText().trim()));
        JPanel introRow = leftRow(intro);
        add.add(introRow);
        add.add(k.vgap(4));
        add.add(k.sub("They appear here once they reply to the introduction."));
        mBody.add(add);
        mBody.add(k.vgap(14));

        // Contact list.
        mBody.add(k.sectionLabel(cs.size() + (cs.size() == 1 ? " contact" : " contacts")));
        mBody.add(k.vgap(8));
        if (cs.isEmpty()) {
            DKit.RoundPanel none = k.card();
            none.add(k.sub("No contacts yet. Add someone above."));
            mBody.add(none);
        } else {
            DKit.RoundPanel listCard = k.card();
            listCard.setBorder(new EmptyBorder(4, 6, 4, 6));
            for (int i = 0; i < cs.size(); i++) {
                if (i > 0) {
                    listCard.add(k.divider());
                }
                listCard.add(contactRow(cs.get(i)));
            }
            mBody.add(listCard);
        }
        mBody.add(Box.createVerticalGlue());
        mBody.revalidate();
        mBody.repaint();
    }

    private JComponent contactRow(Contact c) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(10, 10, 10, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String name = c.name == null || c.name.isEmpty() || "noname".equals(c.name)
                ? shortKey(c.publicKey) : c.name;
        row.add(k.avatar(c.publicKey, name, 38), BorderLayout.WEST);
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
        pr.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(nm);
        mid.add(pr);
        row.add(mid, BorderLayout.CENTER);
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { detail(c); }
        });
        return row;
    }

    private void detail(Contact c) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(t.card);
        body.setBorder(new EmptyBorder(6, 6, 6, 6));
        String addr = c.primaryAddress();
        if (addr != null && !addr.isEmpty()) {
            body.add(k.copyField("maxima address", addr, false));
            // RULE 1: every host copyable in full.
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
        JOptionPane.showMessageDialog(this, body,
                (c.name == null ? "Contact" : c.name), JOptionPane.PLAIN_MESSAGE);
    }

    private void introduce(String address) {
        if (address.isEmpty() || !address.contains("@")) {
            JOptionPane.showMessageDialog(this, "Needs the form Mx…@host:port");
            return;
        }
        new Thread(() -> {
            try {
                node.node().introduce(address, true);
                javax.swing.SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                                "Introduction sent. They'll appear once they reply."));
            } catch (Exception e) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "Couldn't introduce: " + e.getMessage()));
            }
        }, "introduce").start();
    }

    // ---- helpers ----

    /** Our shareable address, preferring a host that is already an IP. */
    private String myAddress() {
        try {
            List<String> addrs = node.node().myAddresses();
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

    private String presence(Contact c) {
        long ls = c.lastSeen;
        if (ls <= 0) {
            return "offline";
        }
        long d = System.currentTimeMillis() - ls;
        if (d < 30 * 60 * 1000L) {
            return "online";
        }
        long m = d / 60000;
        String ago = m < 60 ? m + "m" : (m < 1440 ? (m / 60) + "h" : (m / 1440) + "d");
        return "last seen " + ago + " ago";
    }

    private JPanel leftRow(Component c) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(c);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private static String shortKey(String key) {
        if (key == null) return "?";
        return key.length() > 12 ? key.substring(0, 10) + "…" : key;
    }
}
