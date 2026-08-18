package com.eurobuddha.maxima.desktop.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * The desktop's shared widget vocabulary — the Swing counterpart of the phone's
 * {@code Kit}. Rounded cards, status pills, primary/ghost buttons, fields and
 * copy-fields, all painted from a {@link Theme} so the window matches the Android
 * greyscale look. Pure AWT/Swing; no third-party UI library.
 */
public final class DKit {

    public final Theme t;

    public DKit(Theme zTheme) {
        t = zTheme;
    }

    // ---- pill kinds ----
    public static final int OK = 0;
    public static final int WARN = 1;
    public static final int BAD = 2;
    public static final int NEUTRAL = 3;

    public Color pillColor(int kind) {
        switch (kind) {
            case OK: return t.success;
            case WARN: return t.pending;
            case BAD: return t.error;
            default: return t.subtext;
        }
    }

    // ---- rounded panel ----

    /** A rounded, filled panel (a "card"), background = card token, with padding. */
    public RoundPanel card() {
        RoundPanel p = new RoundPanel(t.card, 16);
        p.setBorder(new EmptyBorder(14, 16, 14, 16));
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    public RoundPanel round(Color bg, int radius) {
        return new RoundPanel(bg, radius);
    }

    // ---- labels ----

    public JLabel label(String s, Font f, Color c) {
        JLabel l = new JLabel(s);
        l.setFont(f);
        l.setForeground(c);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    public JLabel title(String s) {
        return label(s, t.extrabold(20f), t.text);
    }

    public JLabel sectionLabel(String s) {
        JLabel l = label(s.toUpperCase(), t.semibold(10.5f), t.subtext);
        return l;
    }

    public JLabel sub(String s) {
        JLabel l = label(s, t.font(12.5f), t.subtext);
        return l;
    }

    public JComponent divider() {
        JPanel d = new JPanel();
        d.setBackground(t.divider);
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        d.setPreferredSize(new Dimension(10, 1));
        d.setAlignmentX(Component.LEFT_ALIGNMENT);
        return d;
    }

    public Component vgap(int px) {
        return Box.createRigidArea(new Dimension(0, px));
    }

    public Component hgap(int px) {
        return Box.createRigidArea(new Dimension(px, 0));
    }

    // ---- status pill ----

    public JComponent pill(String textStr, int kind) {
        Color c = pillColor(kind);
        RoundPanel p = new RoundPanel(alpha(c, 34), 999);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setBorder(new EmptyBorder(4, 10, 4, 10));
        JLabel l = label(textStr, t.semibold(11f), c);
        p.add(l);
        p.setMaximumSize(p.getPreferredSize());
        return p;
    }

    // ---- buttons ----

    public HoverButton primaryButton(String s) {
        HoverButton b = new HoverButton(s, t.accent, t.onAccent, t);
        return b;
    }

    public HoverButton ghostButton(String s) {
        HoverButton b = new HoverButton(s, t.input, t.text, t);
        return b;
    }

    public HoverButton dangerButton(String s) {
        HoverButton b = new HoverButton(s, t.input, t.error, t);
        return b;
    }

    // ---- fields ----

    public JTextField field(String placeholder) {
        JTextField f = new JTextField();
        f.setFont(t.font(13f));
        f.setForeground(t.text);
        f.setCaretColor(t.text);
        f.setBackground(t.input);
        f.setBorder(new EmptyBorder(10, 12, 10, 12));
        f.putClientProperty("placeholder", placeholder);
        f.setOpaque(true);
        return f;
    }

    // ---- copy field (label + monospace value + click to copy full value) ----

    /** A copyable value field. RULE 1: the FULL value is copied, never truncated —
     *  {@code upperLabel=false} keeps a term-of-art label verbatim. */
    public JComponent copyField(String labelText, String value, boolean upperLabel) {
        RoundPanel box = new RoundPanel(t.input, 12);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(new EmptyBorder(10, 14, 10, 14));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel l = label(upperLabel ? labelText.toUpperCase() : labelText,
                t.semibold(10f), t.subtext);
        JLabel v = new JLabel("<html><div style='width:520px'>" + esc(value) + "</div></html>");
        v.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        v.setForeground(t.text);
        v.setAlignmentX(Component.LEFT_ALIGNMENT);
        v.setBorder(new EmptyBorder(6, 0, 0, 0));

        box.add(l);
        box.add(v);
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        box.setToolTipText("Click to copy");
        box.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                copy(value);
                l.setText((upperLabel ? "COPIED" : "copied"));
                javax.swing.Timer tm = new javax.swing.Timer(1200, ev ->
                        l.setText(upperLabel ? labelText.toUpperCase() : labelText));
                tm.setRepeats(false);
                tm.start();
            }
        });
        return box;
    }

    // ---- avatar (deterministic greyscale identicon disc + initial) ----

    public JComponent avatar(String key, String initial, int size) {
        return new Avatar(key, initial, size, t);
    }

    // ---- helpers ----

    public static void copy(String s) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(s), null);
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ============================================================
    // Widgets
    // ============================================================

    /** A panel that paints a rounded, anti-aliased filled background. */
    public static final class RoundPanel extends JPanel {
        private final Color fill;
        private final int radius;

        public RoundPanel(Color zFill, int zRadius) {
            fill = zFill;
            radius = zRadius;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            int r = Math.min(radius, Math.min(getWidth(), getHeight()));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A flat, rounded, hover-lightening button (no native L&F chrome). */
    public static final class HoverButton extends JLabel {
        private final Color base;
        private boolean hover;
        private boolean enabledFlag = true;
        private Runnable action;
        private final Theme theme;

        HoverButton(String s, Color bg, Color fg, Theme t) {
            super(s, SwingConstants.CENTER);
            base = bg;
            theme = t;
            setForeground(fg);
            setFont(t.semibold(13f));
            setOpaque(false);
            setBorder(new EmptyBorder(10, 18, 10, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) {
                    if (enabledFlag && action != null) {
                        action.run();
                    }
                }
            });
        }

        public HoverButton onClick(Runnable r) {
            action = r;
            return this;
        }

        public void setButtonEnabled(boolean b) {
            enabledFlag = b;
            setCursor(Cursor.getPredefinedCursor(b ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = base;
            if (!enabledFlag) {
                c = alpha(base, 90);
            } else if (hover) {
                c = mix(base, theme.mode == Theme.Mode.DARK ? Color.WHITE : Color.BLACK, 0.08f);
            }
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 999, 999);
            g2.dispose();
            super.paintComponent(g);
        }

        private static Color mix(Color a, Color b, float f) {
            return new Color(
                    (int) (a.getRed() * (1 - f) + b.getRed() * f),
                    (int) (a.getGreen() * (1 - f) + b.getGreen() * f),
                    (int) (a.getBlue() * (1 - f) + b.getBlue() * f));
        }
    }

    /** Deterministic greyscale disc + centred initial (matches the phone's identicon). */
    public static final class Avatar extends JComponent {
        private final Color disc;
        private final String initial;
        private final int size;
        private final Theme theme;

        Avatar(String key, String initialStr, int zSize, Theme t) {
            size = zSize;
            theme = t;
            initial = initialStr == null || initialStr.isEmpty()
                    ? "?" : initialStr.substring(0, 1).toUpperCase();
            int h = key == null ? 0 : key.hashCode();
            // Greyscale disc: vary lightness only, staying in the neutral band.
            int base = t.mode == Theme.Mode.DARK ? 70 : 190;
            int spread = (Math.abs(h) % 40) - 20;
            int g = clamp(base + spread);
            disc = new Color(g, g, g);
            setPreferredSize(new Dimension(zSize, zSize));
            setMaximumSize(new Dimension(zSize, zSize));
            setMinimumSize(new Dimension(zSize, zSize));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(disc);
            g2.fillOval(0, 0, size, size);
            g2.setColor(theme.mode == Theme.Mode.DARK ? Color.WHITE : Color.WHITE);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.92f));
            g2.setFont(theme.bold(size * 0.42f));
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(initial);
            int th = fm.getAscent();
            g2.drawString(initial, (size - tw) / 2, (size + th) / 2 - 2);
            g2.dispose();
        }

        private static int clamp(int v) {
            return Math.max(30, Math.min(220, v));
        }
    }

    // silence unused import warning path
    static final BasicStroke HAIRLINE = new BasicStroke(1f);
}
