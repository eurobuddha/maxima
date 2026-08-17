package com.eurobuddha.maxima.app.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

/**
 * Identity avatars: a person's initial on a hue-hashed disc, so the same contact
 * wears the same colour everywhere (list, chat header, group roster) and is
 * recognisable at a glance. No profile photos: nobody has to host your face. The
 * colour is derived deterministically from the identity so it is stable across
 * sessions and devices.
 *
 * The palette is a set of MUTED, desaturated tones that sit inside the greyscale
 * design language (no bright/garish hues, no coloured ring) while still giving
 * each identity a distinct, recognisable colour.
 */
public final class Avatars {

    private Avatars() {
    }

    /** A pool of 30 muted, evenly-spaced hues. Generated (not hand-picked) so
     *  contacts spread across the wheel and each gets a distinct, stable colour;
     *  saturation/lightness are kept low so the discs stay calm inside the
     *  all-greyscale design and read white text cleanly. */
    private static final int POOL = 30;

    /** A hairline inset edge for a touch of depth — faint translucent black,
     *  not the old accent ring. */
    private static final int EDGE = 0x1A000000;

    /** Stable colour for an identity key. Minima public keys are DER-encoded and
     *  share a constant prefix (30819F30…), so an early slice collides for every
     *  contact — hash the WHOLE key instead so each identity gets a distinct tone.
     *  String.hashCode is deterministic, so the colour is stable across devices. */
    public static int colour(String zKey) {
        int v = (zKey == null || zKey.isEmpty()) ? 0 : zKey.hashCode();
        int idx = Math.floorMod(v, POOL);
        // Spread hue across the wheel; nudge saturation/lightness slightly per
        // index so neighbours differ a touch more, staying muted throughout.
        float hue = (idx * 360f) / POOL;
        float sat = 0.32f + (idx % 3) * 0.03f;      // 0.32–0.38
        float light = 0.42f + (idx % 2) * 0.03f;    // 0.42–0.45
        return hsl(hue, sat, light);
    }

    /** HSL (h 0-360, s/l 0-1) → opaque ARGB int. */
    private static int hsl(float h, float s, float l) {
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
        return Color.rgb(Math.round((r + m) * 255),
                Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    private static int dp(float zDp) {
        return Math.round(zDp * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
    }

    /**
     * Paint an avatar TextView: the identity's initial (white) on its
     * identity-coloured disc, inside a faint accent ring. {@code zKey} drives
     * the colour (the stable identity pubkey / group id); {@code zName} drives
     * the letter.
     */
    public static void apply(TextView zAvatar, String zKey, String zName) {
        zAvatar.setText(Ui.initial(zName));
        zAvatar.setBackground(disc(colour(zKey)));
        zAvatar.setTextColor(0xFFFFFFFF);
    }

    /** A plain muted hue disc with a hairline inset edge — no accent ring. */
    private static Drawable disc(int zDisc) {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(zDisc);
        disc.setStroke(dp(1f), EDGE);
        return disc;
    }

    /**
     * The same avatar as a round bitmap, for surfaces that take an image rather
     * than a view - notification {@code Person} icons, most of all. Disc only:
     * the system masks large icons to a circle, which would clip a ring.
     * {@code zPx} is the pixel diameter.
     */
    public static Bitmap bitmap(String zKey, String zName, int zPx) {
        Bitmap bmp = Bitmap.createBitmap(zPx, zPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(colour(zKey));
        float r = zPx / 2f;
        c.drawCircle(r, r, r, disc);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(zPx * 0.42f);
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = r - (fm.ascent + fm.descent) / 2f;
        c.drawText(Ui.initial(zName), r, baseline, text);
        return bmp;
    }
}
