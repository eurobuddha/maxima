package com.eurobuddha.maxima.desktop.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * The desktop chat window — a left navigation rail (Chats, Contacts, Wallet,
 * Network, Settings) and a card-switched content area, painted from the shared
 * {@link Theme} to match the phone's five tabs and greyscale look. A 2-second
 * heartbeat refreshes the visible tab, exactly like the Android {@code MainActivity}.
 */
public final class MaximaWindow {

    /** Every tab is a panel that can repaint itself from current node state. */
    public interface Tab {
        String label();
        JComponent view();
        /** Rebuild from current state (called on show + on the 2s heartbeat). */
        void refresh();
    }

    private final JFrame mFrame;
    private final Theme t;
    private final DKit k;
    private final DesktopNode mNode;

    private final CardLayout mCards = new CardLayout();
    private final JPanel mContent = new JPanel(mCards);
    private final List<Tab> mTabs = new ArrayList<>();
    private final List<NavItem> mNav = new ArrayList<>();
    private int mSelected = 0;

    public MaximaWindow(DesktopNode zNode, Theme zTheme) {
        mNode = zNode;
        t = zTheme;
        k = new DKit(zTheme);

        mFrame = new JFrame("Maxima");
        mFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mFrame.setMinimumSize(new Dimension(960, 640));
        mFrame.setSize(1100, 720);
        mFrame.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(t.bg);

        // Build the five tabs.
        mTabs.add(new ChatsPanel(mNode, t));
        mTabs.add(new ContactsPanel(mNode, t));
        mTabs.add(new WalletPanel(mNode, t));
        mTabs.add(new NetworkPanel(mNode, t));
        mTabs.add(new SettingsPanel(mNode, t, this));

        for (int i = 0; i < mTabs.size(); i++) {
            mContent.add(mTabs.get(i).view(), String.valueOf(i));
        }
        mContent.setBackground(t.bg);

        root.add(buildRail(), BorderLayout.WEST);
        root.add(mContent, BorderLayout.CENTER);
        mFrame.setContentPane(root);

        select(0);

        // Heartbeat: refresh the visible tab every 2s (mirrors the phone).
        Timer beat = new Timer(2000, e -> {
            Tab vis = mTabs.get(mSelected);
            try { vis.refresh(); } catch (Exception ignored) { }
            for (NavItem n : mNav) { n.repaint(); }
        });
        beat.start();

        // Node change events also nudge the visible tab immediately.
        mNode.addChangeListener(() -> SwingUtilities.invokeLater(() -> {
            try { mTabs.get(mSelected).refresh(); } catch (Exception ignored) { }
            for (NavItem n : mNav) { n.repaint(); }
        }));
    }

    public void show() {
        mFrame.setVisible(true);
    }

    public JFrame frame() {
        return mFrame;
    }

    public DesktopNode node() {
        return mNode;
    }

    /** Rebuild the whole window under a new light/dark palette, preserving the
     *  frame geometry. The node is reused, so no reconnect. */
    public void switchTheme(Theme.Mode m) {
        MaximaWindow w = new MaximaWindow(mNode, new Theme(m));
        w.frame().setBounds(mFrame.getBounds());
        w.frame().setExtendedState(mFrame.getExtendedState());
        w.show();
        mFrame.dispose();
    }

    // ---- rail ----

    private JComponent buildRail() {
        JPanel rail = new JPanel();
        rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
        rail.setBackground(t.header);
        rail.setBorder(new EmptyBorder(18, 12, 18, 12));
        rail.setPreferredSize(new Dimension(196, 10));

        JLabel brand = new JLabel("maxima");
        brand.setFont(t.extrabold(18f));
        brand.setForeground(t.onHeader);
        brand.setBorder(new EmptyBorder(2, 8, 18, 8));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        rail.add(brand);

        String[] labels = {"Chats", "Contacts", "Wallet", "Network", "Settings"};
        for (int i = 0; i < labels.length; i++) {
            NavItem n = new NavItem(i, labels[i]);
            mNav.add(n);
            rail.add(n);
            rail.add(k.vgap(4));
        }
        return rail;
    }

    void select(int i) {
        mSelected = i;
        mCards.show(mContent, String.valueOf(i));
        try { mTabs.get(i).refresh(); } catch (Exception ignored) { }
        for (NavItem n : mNav) { n.repaint(); }
    }

    /** A single rail entry: drawn icon + label, selected state highlighted. Shows
     *  an unread badge on the Chats item. */
    private final class NavItem extends JPanel {
        private final int index;
        private final String text;
        private boolean hover;

        NavItem(int i, String label) {
            index = i;
            text = label;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setPreferredSize(new Dimension(170, 44));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) { select(index); }
            });
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean sel = index == mSelected;
            if (sel || hover) {
                g2.setColor(sel ? DKit.alpha(t.onHeader, sel ? 38 : 20)
                        : DKit.alpha(t.onHeader, 16));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            int cx = 22;
            int cy = getHeight() / 2;
            drawIcon(g2, index, cx, cy, sel);

            g2.setFont(sel ? t.semibold(13.5f) : t.medium(13.5f));
            g2.setColor(sel ? t.onHeader : DKit.alpha(t.onHeader, 200));
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 44, cy + fm.getAscent() / 2 - 2);

            if (index == 0) {
                int unread = safeUnread();
                if (unread > 0) {
                    String s = unread > 99 ? "99+" : String.valueOf(unread);
                    g2.setFont(t.bold(10.5f));
                    java.awt.FontMetrics bm = g2.getFontMetrics();
                    int bw = bm.stringWidth(s) + 12;
                    int bx = getWidth() - bw - 10;
                    g2.setColor(t.error);
                    g2.fillRoundRect(bx, cy - 9, bw, 18, 18, 18);
                    g2.setColor(Color.WHITE);
                    g2.drawString(s, bx + 6, cy + bm.getAscent() / 2 - 1);
                }
            }
            g2.dispose();
        }

        private int safeUnread() {
            try {
                return mNode.chat().totalUnread();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /** Minimal, hand-drawn line icons — no stock glyph set, matching the phone's
     *  custom icon ethos. */
    private void drawIcon(Graphics2D g2, int i, int cx, int cy, boolean sel) {
        Color c = sel ? t.onHeader : DKit.alpha(t.onHeader, 200);
        g2.setColor(c);
        g2.setStroke(new java.awt.BasicStroke(1.7f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        int s = 8;
        switch (i) {
            case 0: // Chats — speech bubble
                g2.drawRoundRect(cx - s, cy - s + 1, s * 2, (int) (s * 1.5), 6, 6);
                g2.drawLine(cx - 3, cy + s - 1, cx - 6, cy + s + 3);
                break;
            case 1: // Contacts — person
                g2.drawOval(cx - 4, cy - s, 8, 8);
                g2.drawArc(cx - s, cy + 1, s * 2, s * 2, 0, 180);
                break;
            case 2: // Wallet — card
                g2.drawRoundRect(cx - s, cy - 6, s * 2, 12, 4, 4);
                g2.drawLine(cx - s, cy - 1, cx + s, cy - 1);
                break;
            case 3: // Network — nodes
                g2.drawOval(cx - s, cy - s, 5, 5);
                g2.drawOval(cx + 3, cy - 2, 5, 5);
                g2.drawOval(cx - 3, cy + 3, 5, 5);
                g2.drawLine(cx - s + 3, cy - s + 3, cx + 5, cy);
                g2.drawLine(cx - 1, cy + 5, cx + 5, cy);
                break;
            default: // Settings — gear-ish
                g2.drawOval(cx - 6, cy - 6, 12, 12);
                g2.drawOval(cx - 2, cy - 2, 4, 4);
        }
    }

    // silence unused
    static final int UNUSED = SwingConstants.CENTER;
}
