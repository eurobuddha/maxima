package com.eurobuddha.maxima.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;

/**
 * The Maxima mark, drawn for the menu-bar / system tray.
 *
 * The same "M as three connected mesh nodes" used for the Android status-bar
 * icon (ic_stat_maxima) — a comms mark, deliberately not a data-transfer arrow.
 * Drawn in code so the module carries no image asset, and tinted by state so a
 * glance at the tray says whether the relay is publicly reachable.
 */
final class TrayIcons {

    private TrayIcons() {
    }

    /** Reachable/advertised — the mark in full strength. */
    static BufferedImage advertised(int size) {
        return mark(size, new Color(0x2E, 0xCC, 0x71), 1.0f);   // green
    }

    /** Running but not yet publicly reachable — dimmed. */
    static BufferedImage working(int size) {
        return mark(size, new Color(0xF3, 0x9C, 0x12), 1.0f);   // amber
    }

    /** Stopped / error. */
    static BufferedImage stopped(int size) {
        return mark(size, new Color(0x9A, 0x9A, 0xA8), 1.0f);   // grey
    }

    /**
     * Draw the mark at {@code size} px in {@code colour}.
     *
     * Geometry matches ic_stat_maxima on a 24-unit grid: two legs, a centre
     * vertex, and three nodes (top-left, top-right, centre-low).
     */
    private static BufferedImage mark(int size, Color colour, float alpha) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
                Math.round(255 * alpha)));

        double u = size / 24.0;   // one grid unit in px
        // M skeleton: up the left leg, in to the centre node, out to the right leg.
        GeneralPath p = new GeneralPath();
        p.moveTo(6.5 * u, 18 * u);
        p.lineTo(6.5 * u, 9 * u);
        p.lineTo(12 * u, 13.5 * u);
        p.lineTo(17.5 * u, 9 * u);
        p.lineTo(17.5 * u, 18 * u);
        g.setStroke(new BasicStroke((float) (2.2 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);

        // three nodes
        double r = 1.9 * u;
        dot(g, 6.5 * u, 7.2 * u, r);
        dot(g, 17.5 * u, 7.2 * u, r);
        dot(g, 12 * u, 15.6 * u, r);

        g.dispose();
        return img;
    }

    private static void dot(Graphics2D g, double cx, double cy, double r) {
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }
}
