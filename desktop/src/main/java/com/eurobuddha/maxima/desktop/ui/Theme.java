package com.eurobuddha.maxima.desktop.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

/**
 * The desktop's greyscale design system — the exact ux_* token palette the phone
 * uses (values/colors.xml + values-night), plus the Manrope family bundled as a
 * resource. One instance is created per launch for the chosen light/dark mode and
 * threaded through every panel, so the whole window shares one palette and one set
 * of fonts, matching the Android look and feel.
 */
public final class Theme {

    public enum Mode { LIGHT, DARK }

    // ---- palette (ARGB hex, from the phone's ux_* tokens) ----
    public final Color bg;
    public final Color card;
    public final Color input;
    public final Color divider;
    public final Color press;
    public final Color header;
    public final Color onHeader;
    public final Color text;
    public final Color subtext;
    public final Color accent;
    public final Color onAccent;
    public final Color selected;
    public final Color success;
    public final Color error;
    public final Color pending;
    public final Color chatBg;
    public final Color bubbleIn;
    public final Color bubbleInText;
    public final Color bubbleOut;
    public final Color bubbleOutText;

    public final Mode mode;

    // ---- fonts ----
    private final Font regular;
    private final Font medium;
    private final Font semibold;
    private final Font bold;
    private final Font extrabold;

    private static Font sRegular;
    private static Font sMedium;
    private static Font sSemibold;
    private static Font sBold;
    private static Font sExtrabold;

    private int textPercent;
    static final int MIN_TEXT_PERCENT = 80, MAX_TEXT_PERCENT = 150;

    public Theme(Mode zMode) {
        this(zMode, java.util.prefs.Preferences.userRoot()
                .node(DesktopNode.PREFS).getInt("textPercent", 100));
    }

    Theme(Mode zMode, int percent) {
        textPercent = clampTextPercent(percent);
        mode = zMode;
        boolean d = zMode == Mode.DARK;
        bg            = c(d ? 0x16181B : 0xF5F4F1);
        chatBg        = c(d ? 0x16181B : 0xF5F4F1);
        card          = c(d ? 0x1E2126 : 0xFFFFFF);
        input         = c(d ? 0x23272C : 0xEFEEEB);
        divider       = c(d ? 0x2A2E34 : 0xEBE9E6);
        press         = d ? new Color(255, 255, 255, 0x1F) : new Color(0, 0, 0, 0x14);
        header        = c(d ? 0x22262B : 0x2A2E33);
        onHeader      = c(d ? 0xF1F2F3 : 0xFFFFFF);
        text          = c(d ? 0xE6E8EA : 0x23262B);
        subtext       = c(d ? 0x9A9EA6 : 0x676C73);
        accent        = c(d ? 0xE9E9EB : 0x2A2E33);
        onAccent      = c(d ? 0x23262B : 0xFFFFFF);
        selected      = d ? new Color(255, 255, 255, 0x14) : new Color(0, 0, 0, 0x11);
        success       = c(d ? 0x5AAE80 : 0x4F9B72);
        error         = c(d ? 0xFF6B5C : 0xC0503C);
        pending       = c(d ? 0xE6A23C : 0xB98A3C);
        bubbleIn      = c(d ? 0x23272C : 0xFFFFFF);
        bubbleInText  = c(d ? 0xE6E8EA : 0x23262B);
        bubbleOut     = c(d ? 0x333840 : 0x31353C);
        bubbleOutText = c(d ? 0xF1F2F3 : 0xFFFFFF);

        loadFonts();
        regular   = sRegular;
        medium    = sMedium;
        semibold  = sSemibold;
        bold      = sBold;
        extrabold = sExtrabold;
    }

    /** Pick light/dark from the OS where we can tell, else default to light. */
    public static Mode detectMode() {
        // macOS: AppleInterfaceStyle == "Dark" when in dark mode.
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                        .redirectErrorStream(true).start();
                p.waitFor();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                r.close();
                if (line != null && line.toLowerCase().contains("dark")) {
                    return Mode.DARK;
                }
            }
        } catch (Exception ignored) {
        }
        return Mode.LIGHT;
    }

    // ---- fonts ----

    public Font font(float size)          { return regular.deriveFont(size * textPercent / 100f); }
    public Font medium(float size)        { return medium.deriveFont(size * textPercent / 100f); }
    public Font semibold(float size)      { return semibold.deriveFont(size * textPercent / 100f); }
    public Font bold(float size)          { return bold.deriveFont(size * textPercent / 100f); }
    public Font extrabold(float size)     { return extrabold.deriveFont(size * textPercent / 100f); }

    int textPercent() { return textPercent; }

    static int clampTextPercent(int percent) {
        return Math.max(MIN_TEXT_PERCENT, Math.min(MAX_TEXT_PERCENT, percent));
    }

    /** Resize existing widgets in place: drafts, selection and wallet ownership survive. */
    void resizeText(java.awt.Component root, int percent) {
        int next = clampTextPercent(percent);
        if (next == textPercent) return;
        float ratio = (float) next / textPercent;
        textPercent = next;
        resizeFonts(root, ratio);
        root.revalidate();
        root.repaint();
    }

    static void resizeFonts(java.awt.Component c, float ratio) {
        java.awt.Dimension previousPreferred = c.getPreferredSize();
        java.awt.Dimension previousMax = c.isMaximumSizeSet() ? c.getMaximumSize() : null;
        if (c.isFontSet() && c.getFont() != null) {
            c.setFont(c.getFont().deriveFont(c.getFont().getSize2D() * ratio));
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                resizeFonts(child, ratio);
            }
        }
        // Text rows often freeze their maximum height at construction time.
        // Follow preferred sizes for shrink-wrapped labels/pills; keep layout caps.
        if (c.isMaximumSizeSet() && !(c instanceof javax.swing.JScrollPane)) {
            java.awt.Dimension preferred = c.getPreferredSize();
            int width = previousMax.width == previousPreferred.width
                    ? preferred.width : previousMax.width;
            int height = previousMax.height == previousPreferred.height
                    ? preferred.height : Math.max(previousMax.height, preferred.height);
            c.setMaximumSize(new java.awt.Dimension(width, height));
        }
    }

    private static synchronized void loadFonts() {
        if (sRegular != null) {
            return;
        }
        sRegular   = load("/fonts/manrope_regular.ttf", Font.PLAIN);
        sMedium    = load("/fonts/manrope_medium.ttf", Font.PLAIN);
        sSemibold  = load("/fonts/manrope_semibold.ttf", Font.PLAIN);
        sBold      = load("/fonts/manrope_bold.ttf", Font.BOLD);
        sExtrabold = load("/fonts/manrope_extrabold.ttf", Font.BOLD);
    }

    private static Font load(String zPath, int zFallbackStyle) {
        try (InputStream in = Theme.class.getResourceAsStream(zPath)) {
            if (in != null) {
                Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
                return f.deriveFont(13f);
            }
        } catch (Exception ignored) {
        }
        // Never crash for a missing font — fall back to the platform sans.
        return new Font("SansSerif", zFallbackStyle, 13);
    }

    private static Color c(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
