package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.MaximaNode;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Network tab: a plain connection status hero and the live transport figures
 * (hosts, contacts, mailbox held for others, outbox, services) plus the list of
 * hosts this desktop is attached to — the desktop counterpart of the phone's
 * Network tab.
 */
public final class NetworkPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final JPanel mBody = new JPanel();
    private String mLastSig = "";

    public NetworkPanel(DesktopNode zNode, Theme zTheme) {
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

    public String label() { return "Network"; }
    public JComponent view() { return this; }

    public void refresh() {
        MaximaNode n = node.node();
        int hosts = n.pool().activeCount();
        String sig = hosts + "|" + n.contacts().size() + "|" + n.mailbox().totalItems()
                + "|" + n.outbox().size() + "|" + n.services().methods().size()
                + "|" + n.pool().activeHosts();
        if (sig.equals(mLastSig)) {
            return;
        }
        mLastSig = sig;
        rebuild(n, hosts);
    }

    private void rebuild(MaximaNode n, int hosts) {
        mBody.removeAll();
        mBody.add(k.title("Network"));
        mBody.add(k.vgap(16));

        // Connection hero.
        DKit.RoundPanel hero = k.card();
        JPanel line = new JPanel();
        line.setOpaque(false);
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel big = new JLabel(hosts > 0 ? "Connected" : "Offline");
        big.setFont(t.extrabold(22f));
        big.setForeground(hosts > 0 ? t.success : t.error);
        line.add(big);
        line.add(Box.createHorizontalGlue());
        line.add(k.pill(hosts + (hosts == 1 ? " host" : " hosts"),
                hosts > 0 ? DKit.OK : DKit.BAD));
        hero.add(line);
        hero.add(k.vgap(6));
        hero.add(k.sub(hosts > 0
                ? "Reachable through " + hosts + (hosts == 1 ? " independent route." : " independent routes.")
                : "Reconnecting to the relay fleet…"));
        mBody.add(hero);
        mBody.add(k.vgap(14));

        // Transport figures.
        mBody.add(k.sectionLabel("Transport"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel stats = k.card();
        stats.add(stat("Hosts connected", "Independent routes to you. More is safer.",
                String.valueOf(hosts)));
        stats.add(k.divider());
        stats.add(stat("Contacts", "People who can reach you and you them",
                String.valueOf(n.contacts().size())));
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

        // Host list.
        mBody.add(k.sectionLabel("Hosts"));
        mBody.add(k.vgap(8));
        DKit.RoundPanel hostCard = k.card();
        List<String> active = n.pool().activeHosts();
        if (active.isEmpty()) {
            hostCard.add(k.sub("No hosts connected yet — waiting on the fleet."));
        } else {
            for (int i = 0; i < active.size(); i++) {
                if (i > 0) {
                    hostCard.add(k.divider());
                }
                hostCard.add(hostLine(active.get(i)));
            }
        }
        mBody.add(hostCard);

        mBody.add(Box.createVerticalGlue());
        mBody.revalidate();
        mBody.repaint();
    }

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
        JLabel hl = new JLabel(hint);
        hl.setFont(t.font(11.5f));
        hl.setForeground(t.subtext);
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

    private JComponent hostLine(String hostPort) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(9, 2, 9, 2));
        JLabel dot = new JLabel("●");
        dot.setForeground(t.success);
        dot.setFont(t.font(11f));
        row.add(dot, BorderLayout.WEST);
        JLabel h = new JLabel(hostPort);
        h.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        h.setForeground(t.text);
        row.add(h, BorderLayout.CENTER);
        return row;
    }
}
