package com.eurobuddha.maxima.app.portal.wallet;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Deterministic token identicon — a native port of the utxoWallet dapp's buildIdenticonSVG: a 5×5 grid
 * mirrored on the vertical axis, cells + hue derived from the tokenid's hex. Same tokenid → same picture,
 * computed locally (leaks nothing). Plus the built-in Minima glyph for the native token.
 */
public final class Identicon {

    private Identicon() {}

    public static Bitmap forToken(String tokenid, int px) {
        String hex = (tokenid == null ? "" : tokenid).replaceFirst("(?i)^0x", "").toLowerCase();
        StringBuilder sb = new StringBuilder(hex);
        while (sb.length() < 32) sb.append('0');
        hex = sb.toString();

        int hue   = Integer.parseInt(hex.substring(0, 4), 16) % 360;
        int sat   = 55 + (Integer.parseInt(hex.substring(4, 6), 16) % 25);
        int light = 48 + (Integer.parseInt(hex.substring(6, 8), 16) % 10);
        int fg = hsl(hue, sat, light);
        int bg = hsl(hue, Math.max(20, sat - 30), 12);

        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(bg);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(fg);

        float scale = px / 48f;      // dapp viewBox is 48, cells 8px, outer pad 4px
        float cell = 8 * scale, pad = 4 * scale;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 3; c++) {
                int idx = (r * 3 + c + 8) % 32;
                if ((Character.digit(hex.charAt(idx), 16) & 1) == 0) continue;
                int mirror = (c == 0) ? 4 : (c == 1 ? 3 : 2);
                drawCell(cv, p, c, r, cell, pad);
                if (mirror != c) drawCell(cv, p, mirror, r, cell, pad);
            }
        }
        return bmp;
    }

    private static void drawCell(Canvas cv, Paint p, int col, int row, float cell, float pad) {
        float x = col * cell + pad, y = row * cell + pad;
        cv.drawRect(x, y, x + cell, y + cell, p);
    }

    /** The built-in Minima glyph (circle + M), stroked in the given colour. */
    public static Bitmap minima(int px, int color) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float s = px / 48f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setColor(color);
        p.setStrokeWidth(2.2f * s);
        cv.drawCircle(24 * s, 24 * s, 20 * s, p);
        p.setStrokeWidth(2.6f * s); p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        path.moveTo(14 * s, 32 * s); path.lineTo(14 * s, 18 * s); path.lineTo(24 * s, 26 * s);
        path.lineTo(34 * s, 18 * s); path.lineTo(34 * s, 32 * s);
        cv.drawPath(path, p);
        return bmp;
    }

    /** The web-validation checkmark badge: white ring, blue disc, white tick. */
    public static Bitmap checkBadge(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = px / 2f;
        p.setColor(0xFFFFFFFF); c.drawCircle(r, r, r, p);                             // white outline ring
        p.setColor(0xFF3A3F47); c.drawCircle(r, r, r - Math.max(1, px * 0.09f), p);   // blue disc
        p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px * 0.13f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        path.moveTo(px * 0.28f, px * 0.52f);
        path.lineTo(px * 0.43f, px * 0.67f);
        path.lineTo(px * 0.73f, px * 0.35f);
        c.drawPath(path, p);
        return bmp;
    }

    /** The dapp's brutalist square checkbox: 1px border, accent fill + black tick when checked. */
    public static Bitmap squareCheck(int px, boolean checked, int boxBg, int border, int accent) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(checked ? accent : boxBg);
        c.drawRect(0, 0, px, px, p);
        p.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1f, px * 0.08f); p.setStrokeWidth(sw); p.setColor(border);
        c.drawRect(sw / 2, sw / 2, px - sw / 2, px - sw / 2, p);
        if (checked) {
            p.setColor(0xFF000000);   // dapp: black tick on the orange fill (both themes)
            p.setStrokeWidth(px * 0.13f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            path.moveTo(px * 0.26f, px * 0.52f); path.lineTo(px * 0.44f, px * 0.70f); path.lineTo(px * 0.76f, px * 0.30f);
            c.drawPath(path, p);
        }
        return b;
    }

    /** HSL (h 0-360, s/l 0-100) → ARGB int. */
    private static int hsl(int h, int s, int l) {
        float ss = s / 100f, ll = l / 100f;
        float c = (1 - Math.abs(2 * ll - 1)) * ss;
        float hp = h / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        float m = ll - c / 2;
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }
}
