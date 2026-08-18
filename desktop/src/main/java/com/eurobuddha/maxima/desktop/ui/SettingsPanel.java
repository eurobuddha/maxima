package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.desktop.DesktopMain;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Settings tab: display name, the maxima publickey (copyable, lowercase),
 * read-receipt and appearance (light/dark) preferences, and an about line. The
 * appearance toggle rebuilds the window under a new {@link Theme}, so the whole
 * app switches light/dark at once — matching the phone's appearance control.
 */
public final class SettingsPanel extends JPanel implements MaximaWindow.Tab {

    private final DesktopNode node;
    private final Theme t;
    private final DKit k;
    private final MaximaWindow window;
    private final JPanel mBody = new JPanel();
    private boolean mBuilt;

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/eurobuddha/maxima/desktop");

    public SettingsPanel(DesktopNode zNode, Theme zTheme, MaximaWindow zWindow) {
        node = zNode;
        t = zTheme;
        k = new DKit(zTheme);
        window = zWindow;
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

    public String label() { return "Settings"; }
    public JComponent view() { return this; }

    public void refresh() {
        if (mBuilt) {
            return;
        }
        mBuilt = true;
        build();
    }

    private void build() {
        mBody.removeAll();
        mBody.add(k.title("Settings"));
        mBody.add(k.vgap(16));

        // Identity card.
        DKit.RoundPanel idCard = k.card();
        idCard.add(k.sectionLabel("Identity"));
        idCard.add(k.vgap(8));
        JPanel nameRow = new JPanel(new BorderLayout(10, 0));
        nameRow.setOpaque(false);
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextField name = k.field("Display name");
        name.setText(currentName());
        nameRow.add(name, BorderLayout.CENTER);
        DKit.HoverButton set = k.primaryButton("Set");
        set.onClick(() -> {
            String nm = name.getText().trim();
            if (!nm.isEmpty()) {
                node.node().setName(nm);
                PREFS.put("name", nm);
                window.frame().setTitle("Maxima — " + nm);
            }
        });
        nameRow.add(set, BorderLayout.EAST);
        idCard.add(nameRow);
        idCard.add(k.vgap(10));
        idCard.add(k.copyField("maxima publickey", node.identity().publicKeyHex(), false));
        mBody.add(idCard);
        mBody.add(k.vgap(14));

        // Privacy card.
        DKit.RoundPanel priv = k.card();
        priv.add(k.sectionLabel("Privacy"));
        priv.add(k.vgap(8));
        boolean rr = PREFS.getBoolean("readReceipts", true);
        node.setReadReceipts(rr);
        priv.add(toggleRow("Read receipts",
                rr ? "Contacts are told when you read their message"
                        : "Contacts see delivery only, never when you read",
                rr, checked -> {
                    PREFS.putBoolean("readReceipts", checked);
                    node.setReadReceipts(checked);
                    rebuild();
                }));
        mBody.add(priv);
        mBody.add(k.vgap(14));

        // Appearance card.
        DKit.RoundPanel appear = k.card();
        appear.add(k.sectionLabel("Appearance"));
        appear.add(k.vgap(8));
        boolean dark = t.mode == Theme.Mode.DARK;
        appear.add(toggleRow("Dark mode",
                dark ? "Greyscale dark palette" : "Greyscale light palette",
                dark, checked -> {
                    PREFS.put("appearance", checked ? "dark" : "light");
                    window.switchTheme(checked ? Theme.Mode.DARK : Theme.Mode.LIGHT);
                }));
        mBody.add(appear);
        mBody.add(k.vgap(14));

        // About.
        DKit.RoundPanel about = k.card();
        about.add(k.sectionLabel("About"));
        about.add(k.vgap(8));
        about.add(k.sub("Maxima desktop · a decentralized, relay-routed messenger."));
        about.add(k.vgap(2));
        about.add(k.sub("Version " + DesktopMain.APP_VERSION));
        mBody.add(about);

        mBody.add(Box.createVerticalGlue());
        mBody.revalidate();
        mBody.repaint();
    }

    private void rebuild() {
        mBuilt = false;
        refresh();
    }

    private String currentName() {
        String p = PREFS.get("name", "");
        return p.isEmpty() ? "Maxima Desktop" : p;
    }

    interface OnToggle { void changed(boolean checked); }

    private JComponent toggleRow(String title, String hint, boolean on, OnToggle cb) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(8, 2, 8, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
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
        Toggle sw = new Toggle(on, t, cb);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(sw, BorderLayout.CENTER);
        row.add(wrap, BorderLayout.EAST);
        return row;
    }

    /** A small drawn iOS-style switch. */
    private static final class Toggle extends JComponent {
        private boolean on;
        private final Theme theme;

        Toggle(boolean zOn, Theme t, OnToggle cb) {
            on = zOn;
            theme = t;
            setPreferredSize(new Dimension(44, 26));
            setMaximumSize(new Dimension(44, 26));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    on = !on;
                    repaint();
                    cb.changed(on);
                }
            });
        }

        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(on ? theme.accent : theme.divider);
            g2.fillRoundRect(0, 2, 44, 22, 22, 22);
            g2.setColor(on ? theme.onAccent : theme.card);
            int x = on ? 22 : 3;
            g2.fillOval(x, 5, 16, 16);
            g2.dispose();
        }
    }
}
