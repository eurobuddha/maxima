package com.eurobuddha.maxima.desktop.ui;

import java.awt.AlphaComposite;
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

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * The desktop's shared widget vocabulary — the Swing counterpart of the phone's
 * {@code Kit}, matching the exact greyscale shapes measured from the Android
 * drawables: cards 14px, buttons 10px rounded rectangles (NOT pills), fields 12px,
 * segmented 12/9px, and status chips fully rounded (999). Everything paints from a
 * {@link Theme}. Pure AWT/Swing; no third-party UI library.
 */
public final class DKit {

    // Corner radii, mirrored 1:1 from app/src/main/res/drawable/*.xml.
    public static final int R_CARD = 14;
    public static final int R_BUTTON = 10;
    public static final int R_FIELD = 12;
    public static final int R_SEG_TRACK = 12;
    public static final int R_SEG_THUMB = 9;

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

    // ---- rounded panel / card ----

    /** A rounded, filled card (background = card token, 14px, padded). */
    public RoundPanel card() {
        RoundPanel p = new RoundPanel(t.card, R_CARD);
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

    /** Uppercase section eyebrow, matching the phone's sectionLabel. */
    public JLabel sectionLabel(String s) {
        JLabel l = label(s.toUpperCase(), t.semibold(10.5f), t.subtext);
        return l;
    }

    public JLabel sub(String s) {
        // Wrap long sub text so nothing overflows horizontally.
        JLabel l = new JLabel("<html>" + esc(s) + "</html>");
        l.setFont(t.font(12.5f));
        l.setForeground(t.subtext);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** A label-left / value-right line (like the phone's kv row). */
    public JComponent kvLine(String label, String value) {
        JPanel row = new JPanel(new java.awt.BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setFont(t.font(12.5f));
        l.setForeground(t.subtext);
        JLabel v = new JLabel(value);
        v.setFont(t.semibold(12.5f));
        v.setForeground(t.text);
        row.add(l, java.awt.BorderLayout.WEST);
        row.add(v, java.awt.BorderLayout.EAST);
        return row;
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

    // ---- status pill (the ONLY stadium/999 shape) ----

    public JComponent pill(String textStr, int kind) {
        Color c = pillColor(kind);
        RoundPanel p = new RoundPanel(alpha(c, 34), 999);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setBorder(new EmptyBorder(4, 10, 4, 10));
        p.add(label(textStr, t.semibold(11f), c));
        p.setMaximumSize(p.getPreferredSize());
        return p;
    }

    // ---- buttons (10px rounded rectangles) ----

    public HoverButton primaryButton(String s) {
        return new HoverButton(s, t.accent, t.onAccent, t);
    }

    public HoverButton ghostButton(String s) {
        return new HoverButton(s, t.input, t.text, t);
    }

    public HoverButton dangerButton(String s) {
        return new HoverButton(s, t.input, t.error, t);
    }

    // ---- fields ----

    public JTextField field(String placeholder) {
        PlaceholderField f = new PlaceholderField(placeholder, t);
        return f;
    }

    // ---- copy field (label + wrapping monospace value + click to copy full value) ----

    /** A copyable value field. RULE 1: the FULL value is copied, never truncated,
     *  and the value WRAPS (no horizontal overflow). {@code upperLabel=false} keeps a
     *  term-of-art label verbatim. */
    public JComponent copyField(String labelText, String value, boolean upperLabel) {
        RoundPanel box = new RoundPanel(t.input, R_FIELD);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(new EmptyBorder(10, 14, 10, 14));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel l = label(upperLabel ? labelText.toUpperCase() : labelText,
                t.semibold(10f), t.subtext);

        WrapText v = new WrapText(value == null ? "" : value);
        v.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        v.setForeground(t.text);
        v.setBorder(new EmptyBorder(6, 0, 0, 0));

        box.add(l);
        box.add(v);
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        box.setToolTipText("Click to copy");
        MouseAdapter copyOnClick = new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                copy(value);
                l.setText(upperLabel ? "COPIED" : "copied");
                javax.swing.Timer tm = new javax.swing.Timer(1200, ev ->
                        l.setText(upperLabel ? labelText.toUpperCase() : labelText));
                tm.setRepeats(false);
                tm.start();
            }
        };
        box.addMouseListener(copyOnClick);
        v.addMouseListener(copyOnClick);
        return box;
    }

    // ---- segmented control (System/Light/Dark, Balance/History …) ----

    public Segmented segmented(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        return new Segmented(labels, selected, t, onPick);
    }

    // ---- switch (phone SwitchCompat look) ----

    public Toggle toggle(boolean on, java.util.function.Consumer<Boolean> cb) {
        return new Toggle(on, t, cb);
    }

    // ---- avatar (deterministic greyscale identicon disc + initial) ----

    public JComponent avatar(String key, String initial, int size) {
        return new Avatar(key, initial, size, t);
    }

    // ---- centered, width-capped column (fluid-fill single-column pages) ----

    /** Center a vertical body, capped at {@code maxWidth}; it fills when the window
     *  is narrower. This is how the non-chat pages stay phone-like on a wide desktop
     *  window instead of stretching a single column edge to edge. */
    public JComponent centered(JComponent body, int maxWidth) {
        body.setMaximumSize(new Dimension(maxWidth, Integer.MAX_VALUE));
        JPanel rowp = new JPanel();
        rowp.setOpaque(false);
        rowp.setLayout(new BoxLayout(rowp, BoxLayout.X_AXIS));
        rowp.add(Box.createHorizontalGlue());
        rowp.add(body);
        rowp.add(Box.createHorizontalGlue());
        return rowp;
    }

    // ---- helpers ----

    public static void copy(String s) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(s == null ? "" : s), null);
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    static Color mix(Color a, Color b, float f) {
        return new Color(
                (int) (a.getRed() * (1 - f) + b.getRed() * f),
                (int) (a.getGreen() * (1 - f) + b.getGreen() * f),
                (int) (a.getBlue() * (1 - f) + b.getBlue() * f));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ============================================================
    // Widgets
    // ============================================================

    /**
     * A read-only, transparent, always-wrapping text block. The key to reliable
     * wrapping inside a BoxLayout: report a maximum size with UNBOUNDED width and a
     * height derived from the CURRENT width, so the layout stretches it full-width
     * and line-wrap then reflows. Fixes long addresses/keys/logs overflowing.
     */
    public static final class WrapText extends JTextArea {
        public WrapText(String s) {
            super(s);
            setOpaque(false);
            setEditable(false);
            setFocusable(false);
            setLineWrap(true);
            setWrapStyleWord(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        public Dimension getPreferredSize() {
            // Height for the current width (0 width falls back to the default).
            int w = getWidth();
            if (w > 0) {
                setSize(w, Short.MAX_VALUE);
            }
            return super.getPreferredSize();
        }
    }

    /** A panel that paints a rounded, anti-aliased filled background. */
    public static final class RoundPanel extends JPanel {
        private Color fill;
        private final int radius;

        public RoundPanel(Color zFill, int zRadius) {
            fill = zFill;
            radius = zRadius;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        public void setFill(Color c) { fill = c; repaint(); }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            int r = Math.min(radius * 2, Math.min(getWidth(), getHeight()));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A flat button: 10px rounded RECTANGLE (matches btn_primary/btn_ghost), hover-lightening. */
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
            setBorder(new EmptyBorder(11, 18, 11, 18));
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

        public HoverButton onClick(Runnable r) { action = r; return this; }

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
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), R_BUTTON * 2, R_BUTTON * 2);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Placeholder-aware text field with the phone's field_pill look (12px). */
    public static final class PlaceholderField extends JTextField {
        private final String placeholder;
        private final Theme theme;

        PlaceholderField(String zPlaceholder, Theme t) {
            placeholder = zPlaceholder == null ? "" : zPlaceholder;
            theme = t;
            setFont(t.font(13f));
            setForeground(t.text);
            setCaretColor(t.text);
            setOpaque(false);
            setBorder(new EmptyBorder(11, 14, 11, 14));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(theme.input);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), R_FIELD * 2, R_FIELD * 2);
            g2.dispose();
            super.paintComponent(g);
            if (getText().isEmpty() && !placeholder.isEmpty()) {
                Graphics2D g3 = (Graphics2D) g.create();
                g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g3.setColor(theme.subtext);
                g3.setFont(getFont());
                java.awt.Insets in = getInsets();
                java.awt.FontMetrics fm = g3.getFontMetrics();
                g3.drawString(placeholder, in.left, in.top + fm.getAscent());
                g3.dispose();
            }
        }
    }

    /** A greyscale segmented control (seg_track 12px + seg_thumb 9px), like the phone. */
    public static final class Segmented extends JComponent {
        private final String[] labels;
        private int selected;
        private final Theme theme;
        private final java.util.function.IntConsumer onPick;

        Segmented(String[] zLabels, int zSelected, Theme t, java.util.function.IntConsumer zOnPick) {
            labels = zLabels;
            selected = zSelected;
            theme = t;
            onPick = zOnPick;
            setPreferredSize(new Dimension(200, 40));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    int seg = labels.length == 0 ? 0 : e.getX() * labels.length / Math.max(1, getWidth());
                    seg = Math.max(0, Math.min(labels.length - 1, seg));
                    if (seg != selected) {
                        selected = seg;
                        repaint();
                        if (onPick != null) onPick.accept(seg);
                    }
                }
            });
        }

        public void setSelected(int i) { selected = i; repaint(); }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(theme.input);
            g2.fillRoundRect(0, 0, w, h, R_SEG_TRACK * 2, R_SEG_TRACK * 2);
            int n = labels.length;
            int pad = 4;
            int segW = (w - pad * 2) / Math.max(1, n);
            // thumb
            g2.setColor(theme.card);
            int tx = pad + selected * segW;
            g2.fillRoundRect(tx, pad, segW, h - pad * 2, R_SEG_THUMB * 2, R_SEG_THUMB * 2);
            // labels
            for (int i = 0; i < n; i++) {
                g2.setFont(i == selected ? theme.semibold(12.5f) : theme.medium(12.5f));
                g2.setColor(i == selected ? theme.text : theme.subtext);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int lx = pad + i * segW + (segW - fm.stringWidth(labels[i])) / 2;
                int ly = (h + fm.getAscent()) / 2 - 2;
                g2.drawString(labels[i], lx, ly);
            }
            g2.dispose();
        }
    }

    /** A drawn iOS/Material-style switch matching the phone's SwitchCompat colors. */
    public static final class Toggle extends JComponent {
        private boolean on;
        private final Theme theme;

        Toggle(boolean zOn, Theme t, java.util.function.Consumer<Boolean> cb) {
            on = zOn;
            theme = t;
            setPreferredSize(new Dimension(46, 28));
            setMaximumSize(new Dimension(46, 28));
            setMinimumSize(new Dimension(46, 28));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    on = !on;
                    repaint();
                    if (cb != null) cb.accept(on);
                }
            });
        }

        public void setOn(boolean b) { on = b; repaint(); }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(on ? theme.accent : theme.divider);
            g2.fillRoundRect(0, 3, 46, 22, 22, 22);
            g2.setColor(on ? theme.onAccent : theme.card);
            int x = on ? 24 : 4;
            g2.fillOval(x, 5, 18, 18);
            g2.dispose();
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
            disc = identityColour(key);
            setPreferredSize(new Dimension(zSize, zSize));
            setMaximumSize(new Dimension(zSize, zSize));
            setMinimumSize(new Dimension(zSize, zSize));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(disc);
            g2.fillOval(0, 0, size, size);
            g2.setColor(Color.WHITE);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.92f));
            g2.setFont(theme.bold(size * 0.42f));
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(initial);
            int th = fm.getAscent();
            g2.drawString(initial, (size - tw) / 2, (size + th) / 2 - 2);
            g2.dispose();
        }

        /** The phone's exact hue-hashed muted disc colour (Avatars.colour): hash the
         *  WHOLE key mod 30 → spread hue, low saturation/lightness so white reads. */
        static Color identityColour(String key) {
            int v = (key == null || key.isEmpty()) ? 0 : key.hashCode();
            int idx = Math.floorMod(v, 30);
            float hue = (idx * 360f) / 30f;
            float sat = 0.32f + (idx % 3) * 0.03f;
            float light = 0.42f + (idx % 2) * 0.03f;
            return hsl(hue, sat, light);
        }

        private static Color hsl(float h, float s, float l) {
            float c = (1 - Math.abs(2 * l - 1)) * s;
            float hp = h / 60f;
            float x = c * (1 - Math.abs(hp % 2 - 1));
            float r = 0, g = 0, b = 0;
            if (hp < 1) { r = c; g = x; }
            else if (hp < 2) { r = x; g = c; }
            else if (hp < 3) { g = c; b = x; }
            else if (hp < 4) { g = x; b = c; }
            else if (hp < 5) { r = x; b = c; }
            else { r = c; b = x; }
            float m = l - c / 2;
            return new Color(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
        }
    }
}
