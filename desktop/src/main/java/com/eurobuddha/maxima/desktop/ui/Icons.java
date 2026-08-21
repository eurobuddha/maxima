package com.eurobuddha.maxima.desktop.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;

import javax.swing.JComponent;

/**
 * Hand-drawn vector icons — the desktop counterpart of the phone's custom icon
 * set (no stock glyph font, so nothing renders as a tofu box). Each icon is a
 * pure Graphics2D path scaled to the component, tinted a given color. Also a
 * small clickable {@link Btn} for icon buttons.
 */
public final class Icons {

    private Icons() { }

    // Icon names.
    public static final String PLUS = "plus";
    public static final String SEND = "send";
    public static final String SMILE = "smile";
    public static final String CAMERA = "camera";
    public static final String SUN = "sun";
    public static final String MOON = "moon";
    public static final String CHEVRON_LEFT = "chevron_left";
    public static final String COPY = "copy";
    public static final String CHATS = "chats";
    public static final String CONTACTS = "contacts";
    public static final String WALLET = "wallet";
    public static final String NETWORK = "network";
    public static final String SETTINGS = "settings";
    public static final String SEARCH = "search";
    public static final String CHECK = "check";
    public static final String MORE = "more";
    public static final String THEME = "theme";
    public static final String CALL = "call";
    public static final String VIDEO = "video";

    /** Paint icon {@code name} centred in a {@code size}×{@code size} box at (x,y). */
    public static void paint(Graphics2D g, String name, int x, int y, int size, Color color, float stroke) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double s = size;
        switch (name) {
            case PLUS: {
                g2.draw(new java.awt.geom.Line2D.Double(s * 0.5, s * 0.16, s * 0.5, s * 0.84));
                g2.draw(new java.awt.geom.Line2D.Double(s * 0.16, s * 0.5, s * 0.84, s * 0.5));
                break;
            }
            case SEND: {
                // paper plane
                GeneralPath p = new GeneralPath();
                p.moveTo(s * 0.14, s * 0.5);
                p.lineTo(s * 0.86, s * 0.16);
                p.lineTo(s * 0.56, s * 0.86);
                p.lineTo(s * 0.46, s * 0.56);
                p.closePath();
                g2.fill(p);
                break;
            }
            case SMILE: {
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * 0.12, s * 0.12, s * 0.76, s * 0.76));
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.34, s * 0.36, s * 0.08, s * 0.08));
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.58, s * 0.36, s * 0.08, s * 0.08));
                g2.draw(new java.awt.geom.Arc2D.Double(s * 0.30, s * 0.44, s * 0.40, s * 0.34, 200, 140, java.awt.geom.Arc2D.OPEN));
                break;
            }
            case CAMERA: {
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * 0.12, s * 0.28, s * 0.76, s * 0.5, 6, 6));
                g2.draw(new java.awt.geom.Line2D.Double(s * 0.36, s * 0.28, s * 0.44, s * 0.18));
                g2.draw(new java.awt.geom.Line2D.Double(s * 0.44, s * 0.18, s * 0.56, s * 0.18));
                g2.draw(new java.awt.geom.Line2D.Double(s * 0.56, s * 0.18, s * 0.64, s * 0.28));
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * 0.38, s * 0.42, s * 0.24, s * 0.24));
                break;
            }
            case CALL: {
                GeneralPath p = new GeneralPath();
                p.moveTo(s * 0.30, s * 0.24);
                p.lineTo(s * 0.42, s * 0.24);
                p.lineTo(s * 0.48, s * 0.40);
                p.lineTo(s * 0.40, s * 0.48);
                p.curveTo(s * 0.46, s * 0.60, s * 0.54, s * 0.66, s * 0.62, s * 0.68);
                p.lineTo(s * 0.70, s * 0.58);
                p.lineTo(s * 0.84, s * 0.66);
                p.lineTo(s * 0.84, s * 0.78);
                p.curveTo(s * 0.84, s * 0.82, s * 0.80, s * 0.84, s * 0.72, s * 0.84);
                p.curveTo(s * 0.44, s * 0.84, s * 0.20, s * 0.58, s * 0.20, s * 0.34);
                p.curveTo(s * 0.20, s * 0.28, s * 0.24, s * 0.24, s * 0.30, s * 0.24);
                p.closePath();
                g2.fill(p);
                break;
            }
            case VIDEO: {
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * 0.16, s * 0.34, s * 0.44, s * 0.32, 6, 6));
                GeneralPath tri = new GeneralPath();
                tri.moveTo(s * 0.66, s * 0.42);
                tri.lineTo(s * 0.84, s * 0.34);
                tri.lineTo(s * 0.84, s * 0.66);
                tri.lineTo(s * 0.66, s * 0.58);
                tri.closePath();
                g2.draw(tri);
                break;
            }
            case SUN: {
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.34, s * 0.34, s * 0.32, s * 0.32));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    double cx = s * 0.5, cy = s * 0.5;
                    g2.draw(new java.awt.geom.Line2D.Double(
                            cx + Math.cos(a) * s * 0.4, cy + Math.sin(a) * s * 0.4,
                            cx + Math.cos(a) * s * 0.48, cy + Math.sin(a) * s * 0.48));
                }
                break;
            }
            case MOON: {
                java.awt.geom.Area moon = new java.awt.geom.Area(
                        new java.awt.geom.Ellipse2D.Double(s * 0.2, s * 0.16, s * 0.6, s * 0.6));
                moon.subtract(new java.awt.geom.Area(
                        new java.awt.geom.Ellipse2D.Double(s * 0.36, s * 0.1, s * 0.6, s * 0.6)));
                g2.fill(moon);
                break;
            }
            case CHEVRON_LEFT: {
                GeneralPath p = new GeneralPath();
                p.moveTo(s * 0.62, s * 0.2);
                p.lineTo(s * 0.34, s * 0.5);
                p.lineTo(s * 0.62, s * 0.8);
                g2.draw(p);
                break;
            }
            case COPY: {
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * 0.30, s * 0.30, s * 0.5, s * 0.5, 4, 4));
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * 0.18, s * 0.18, s * 0.5, s * 0.5, 4, 4));
                break;
            }
            case CHATS: {
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * 0.14, s * 0.18, s * 0.72, s * 0.5, 10, 10));
                GeneralPath tail = new GeneralPath();
                tail.moveTo(s * 0.32, s * 0.68);
                tail.lineTo(s * 0.30, s * 0.84);
                tail.lineTo(s * 0.46, s * 0.68);
                g2.draw(tail);
                break;
            }
            case CONTACTS: {
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * 0.34, s * 0.16, s * 0.32, s * 0.32));
                g2.draw(new java.awt.geom.Arc2D.Double(s * 0.2, s * 0.52, s * 0.6, s * 0.6, 0, 180, java.awt.geom.Arc2D.OPEN));
                break;
            }
            case WALLET: {
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * 0.14, s * 0.26, s * 0.72, s * 0.48, 8, 8));
                g2.draw(new java.awt.geom.Line2D.Double(s * 0.14, s * 0.44, s * 0.86, s * 0.44));
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.66, s * 0.5, s * 0.08, s * 0.08));
                break;
            }
            case NETWORK: {
                double[][] pts = {{0.2, 0.28}, {0.78, 0.34}, {0.4, 0.76}};
                for (double[] a : pts)
                    for (double[] b : pts)
                        if (a != b)
                            g2.draw(new java.awt.geom.Line2D.Double(s * a[0] + s * 0.05, s * a[1] + s * 0.05,
                                    s * b[0] + s * 0.05, s * b[1] + s * 0.05));
                for (double[] p : pts) g2.fill(new java.awt.geom.Ellipse2D.Double(s * p[0], s * p[1], s * 0.13, s * 0.13));
                break;
            }
            case SETTINGS: {
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * 0.2, s * 0.2, s * 0.6, s * 0.6));
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * 0.4, s * 0.4, s * 0.2, s * 0.2));
                break;
            }
            case SEARCH: {
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * 0.22, s * 0.22, s * 0.4, s * 0.4));
                g2.draw(new java.awt.geom.Line2D.Double(s * 0.58, s * 0.58, s * 0.82, s * 0.82));
                break;
            }
            case CHECK: {
                GeneralPath p = new GeneralPath(Path2D.WIND_NON_ZERO);
                p.moveTo(s * 0.22, s * 0.52);
                p.lineTo(s * 0.42, s * 0.72);
                p.lineTo(s * 0.8, s * 0.28);
                g2.draw(p);
                break;
            }
            case MORE: {
                // three vertical dots (Android overflow)
                double d = s * 0.12;
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.5 - d / 2, s * 0.18, d, d));
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.5 - d / 2, s * 0.44, d, d));
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * 0.5 - d / 2, s * 0.70, d, d));
                break;
            }
            case THEME: {
                // half-filled contrast circle (light/dark)
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * 0.18, s * 0.18, s * 0.64, s * 0.64));
                java.awt.geom.Arc2D half = new java.awt.geom.Arc2D.Double(
                        s * 0.18, s * 0.18, s * 0.64, s * 0.64, 90, 180, java.awt.geom.Arc2D.PIE);
                g2.fill(half);
                break;
            }
            default:
                break;
        }
        g2.dispose();
    }

    /** A clickable icon button: a hand-drawn icon over an optional hover disc. */
    public static final class Btn extends JComponent {
        private final String icon;
        private final Color color;
        private final Color hoverDisc;
        private final int size;
        private final float stroke;
        private boolean hover;
        private Runnable action;

        public Btn(String zIcon, Color zColor, Color zHoverDisc, int box, int iconSize, float zStroke) {
            icon = zIcon;
            color = zColor;
            hoverDisc = zHoverDisc;
            size = iconSize;
            stroke = zStroke;
            setPreferredSize(new Dimension(box, box));
            setMaximumSize(new Dimension(box, box));
            setMinimumSize(new Dimension(box, box));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) { if (action != null) action.run(); }
            });
        }

        public Btn onClick(Runnable r) { action = r; return this; }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (hover && hoverDisc != null) {
                g2.setColor(hoverDisc);
                g2.fillOval(2, 2, getWidth() - 4, getHeight() - 4);
            }
            int off = (getWidth() - size) / 2;
            Icons.paint(g2, icon, off, off, size, color, stroke);
            g2.dispose();
        }
    }
}
