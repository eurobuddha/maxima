package com.eurobuddha.maxima.desktop.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

import javax.swing.JComponent;

/**
 * WhatsApp-style voice-note waveform, byte-compatible with the phone's
 * WaveformView: 32 bars, each a 1..15 level packed one hex nibble per bar in the
 * media caption ("M:SS|<32 hex>"). encode/decode/summarise are ported verbatim
 * so a bar drawn here looks identical to the phone and vice versa.
 */
final class Waveform {

    static final int BAR_COUNT = 32;

    private Waveform() { }

    /** Recorded amplitudes -> BAR_COUNT levels (1..15), max-pooled. */
    static int[] summarise(List<Integer> samples) {
        int[] out = new int[BAR_COUNT];
        if (samples == null || samples.isEmpty()) {
            java.util.Arrays.fill(out, 4);
            return out;
        }
        int n = samples.size();
        int peak = 1;
        for (int v : samples) peak = Math.max(peak, v);
        for (int i = 0; i < BAR_COUNT; i++) {
            int a = i * n / BAR_COUNT, b = Math.max(a + 1, (i + 1) * n / BAR_COUNT);
            int m = 0;
            for (int j = a; j < b && j < n; j++) m = Math.max(m, samples.get(j));
            out[i] = 1 + Math.min(14, (int) (Math.sqrt(m / (double) peak) * 14));
        }
        return out;
    }

    /** Levels -> compact hex (one nibble per bar). */
    static String encode(int[] levels) {
        StringBuilder sb = new StringBuilder(levels.length);
        for (int v : levels) sb.append(Character.forDigit(Math.max(0, Math.min(15, v)), 16));
        return sb.toString();
    }

    /** Hex -> levels; null if not a waveform. */
    static int[] decode(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() > 64) return null;
        int[] out = new int[hex.length()];
        for (int i = 0; i < hex.length(); i++) {
            int v = Character.digit(hex.charAt(i), 16);
            if (v < 0) return null;
            out[i] = v;
        }
        return out;
    }

    /** Split a voice-note caption "M:SS|hex" into {duration, hex}. */
    static String[] splitCaption(String caption) {
        if (caption == null) return new String[]{"", ""};
        int bar = caption.indexOf('|');
        if (bar < 0) return new String[]{caption, ""};
        return new String[]{caption.substring(0, bar), caption.substring(bar + 1)};
    }

    /** The bar strip, with a played fraction (0..1) that dims the not-yet-played tail. */
    static final class Bars extends JComponent {
        private int[] levels;
        private Color ink;
        private double progress;

        Bars(int[] levels, Color ink) {
            this.levels = levels == null ? new int[BAR_COUNT] : levels;
            this.ink = ink;
            setOpaque(false);
        }

        void setProgress(double p) { progress = Math.max(0, Math.min(1, p)); repaint(); }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), n = levels.length;
            double slot = (double) w / n;
            int bw = Math.max(2, (int) (slot * 0.55));
            int playedUpTo = (int) (progress * n);
            for (int i = 0; i < n; i++) {
                float frac = Math.max(1, levels[i]) / 15f;
                int bh = Math.max(bw, (int) (h * frac));
                int x = (int) (i * slot + (slot - bw) / 2);
                int y = (h - bh) / 2;
                int alpha = i < playedUpTo ? 255 : 92;
                g2.setColor(DKit.alpha(ink, alpha));
                g2.fillRoundRect(x, y, bw, bh, bw, bw);
            }
            g2.dispose();
        }
    }
}
