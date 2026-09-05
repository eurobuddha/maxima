package com.eurobuddha.maxima.app.portal.wallet;

import android.content.Context;
import android.graphics.Typeface;

import com.eurobuddha.maxima.app.R;

/**
 * Central design-token object of the wallet — ONE design: Parlons Cloud's greyscale palette
 * (the {@code ux_*} colours, light or dark following the system), Manrope, soft 12dp corners.
 * Every wallet view reads colours/metrics from here instead of hard-coded resources, so the
 * ported NFTwallet screens render as native Parlons Cloud screens. {@link #load} must run
 * before any view is built (it resolves the day/night palette for the current configuration).
 */
public final class Design {

    /** Kept for the ported code's switch statements; there is exactly one mode. */
    public enum Mode { PARLONS }

    private static boolean sDark;
    private static Typeface sFace = Typeface.DEFAULT;
    private static Typeface sFaceBold = Typeface.DEFAULT_BOLD;

    private static int bg, surface, surface2, border, accent, accent2, accentSoft, text, dim, dim2,
            heading, red, redSoft, amber, amberSoft, onAccent, success;

    private Design() {}

    public static void load(Context c) {
        bg         = c.getColor(R.color.ux_bg);
        surface    = c.getColor(R.color.ux_card);
        surface2   = c.getColor(R.color.ux_card2);
        border     = c.getColor(R.color.ux_divider);
        accent     = c.getColor(R.color.ux_accent);
        accent2    = c.getColor(R.color.ux_accent_press);
        accentSoft = c.getColor(R.color.ux_selected);
        text       = c.getColor(R.color.ux_text);
        dim        = c.getColor(R.color.ux_subtext);
        dim2       = (dim & 0x00FFFFFF) | 0x99000000;
        heading    = text;
        red        = c.getColor(R.color.ux_error);
        redSoft    = (red & 0x00FFFFFF) | 0x26000000;
        amber      = c.getColor(R.color.ux_pending);
        amberSoft  = (amber & 0x00FFFFFF) | 0x26000000;
        onAccent   = c.getColor(R.color.ux_on_accent);
        success    = c.getColor(R.color.ux_success);
        // dark when the ground is dark (the palette flips with the system night mode)
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        sDark = (r * 299 + g * 587 + b * 114) / 1000 < 128;
        try {
            Typeface t = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.manrope);
            if (t != null) {
                sFace = t;
                sFaceBold = Typeface.create(t, Typeface.BOLD);
            }
        } catch (Exception ignored) {
        }
    }

    public static void set(Context c, Mode m) { /* one design */ }
    public static Mode mode() { return Mode.PARLONS; }
    public static boolean isOriginal() { return false; }
    public static boolean isDark() { return sDark; }
    public static Mode next() { return Mode.PARLONS; }
    public static String label() { return sDark ? "Parlons · Dark" : "Parlons · Light"; }

    // ---- semantic colors (ARGB ints) ----
    public static int bg()         { return bg; }
    public static int surface()    { return surface; }
    public static int surface2()   { return surface2; }
    public static int border()     { return border; }
    public static int border2()    { return border; }
    public static int accent()     { return accent; }
    public static int accent2()    { return accent2; }
    public static int accentSoft() { return accentSoft; }
    public static int text()       { return text; }
    public static int dim()        { return dim; }
    public static int dim2()       { return dim2; }
    public static int heading()    { return heading; }
    public static int red()        { return red; }
    public static int redSoft()    { return redSoft; }
    public static int amber()      { return amber; }
    public static int amberSoft()  { return amberSoft; }
    /** Links / the validated badge: greyscale design, so the accent carries it. */
    public static int blue()       { return accent; }
    public static int blueSoft()   { return accentSoft; }
    /** Text drawn on top of the accent fill (buttons). */
    public static int onAccent()   { return onAccent; }
    public static int success()    { return success; }

    // ---- metrics / type ----
    public static Typeface typeface()    { return sFace; }
    public static Typeface typefaceBold(){ return sFaceBold; }
    public static float radiusDp()       { return 12f; }
    public static boolean upperLabels()  { return false; }
    public static float labelTracking()  { return 0.0f; }
}
