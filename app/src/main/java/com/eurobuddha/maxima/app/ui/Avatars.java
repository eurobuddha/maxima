package com.eurobuddha.maxima.app.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.TextView;

/**
 * Identity avatars: a person's initial on a hue-hashed disc, ringed with a faint
 * accent halo - FreezePeach's avatar look, so the same contact wears the same
 * colour everywhere (list, chat header, group roster) and is recognisable at a
 * glance. No profile photos: nobody has to host your face. The colour is derived
 * deterministically from the identity so it is stable across sessions and devices.
 */
public final class Avatars {

    private Avatars() {
    }

    /** FreezePeach's curated 10-hue palette - legible with white text on any
     *  ground, and deliberately free of muddy green/yellow (which would clash
     *  with the green send bubble). */
    private static final int[] TONES = {
            0xFF4C82FF, 0xFF6C6CF0, 0xFF9B6CFF, 0xFFC964D8, 0xFFE8618F,
            0xFFFF6B6B, 0xFFF2954B, 0xFF2BAEB0, 0xFF3EB6E8, 0xFF7E8AA0
    };

    /** The faint ring around every avatar: Bitcoin-orange accent at ~70% alpha. */
    private static final int RING = 0xB3F7931A;

    /** Stable colour for an identity key - mirrors FreezePeach's picker: for a
     *  hex pubkey take chars 2..6, else hash the string. */
    public static int colour(String zKey) {
        int v;
        if (zKey != null && zKey.length() >= 6) {
            try {
                v = Integer.parseInt(zKey.substring(2, 6), 16);
            } catch (Exception e) {
                v = zKey.hashCode();
            }
        } else {
            v = zKey == null ? 0 : zKey.hashCode();
        }
        return TONES[Math.abs(v) % TONES.length];
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
        zAvatar.setBackground(ringed(colour(zKey)));
        zAvatar.setTextColor(0xFFFFFFFF);
    }

    /** An oval hue disc set inside a hairline accent ring, with a small gap. */
    private static Drawable ringed(int zDisc) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(1.5f), RING);

        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(zDisc);

        LayerDrawable ld = new LayerDrawable(new Drawable[]{ring, disc});
        int gap = dp(3);
        ld.setLayerInset(1, gap, gap, gap, gap);
        return ld;
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
