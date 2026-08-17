package com.eurobuddha.maxima.app.ui;

import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

/**
 * Identity avatars: a person's initial on a circle whose colour is derived
 * deterministically from their identity, so the same contact wears the same
 * colour everywhere (list, chat header, group roster) and is recognisable at a
 * glance - the Telegram/Google-Contacts approach, and the "rich colour-per-
 * identity initials" the messenger uses instead of profile photos (nobody has
 * to host your face).
 *
 * Scope note: the app CHROME still uses the family palette in colors.xml. These
 * tones are used ONLY for the avatar circle - a curated, dark-theme-harmonised
 * set, not new UI colours - so the family look is untouched.
 */
public final class Avatars {

    private Avatars() {
    }

    /** A curated palette that reads well on the #0A0A0F ground. Avatar-only. */
    private static final int[] TONES = {
            0xFFE17076, 0xFFEDA86C, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD,
            0xFFEE7AAE, 0xFFD4A055, 0xFF7E85D8, 0xFF5EC199, 0xFFC86F9E, 0xFF66B37A
    };

    /** Stable colour for an identity key. */
    public static int colour(String zKey) {
        if (zKey == null || zKey.isEmpty()) {
            return TONES[0];
        }
        int h = 0;
        for (int i = 0; i < zKey.length(); i++) {
            h = h * 31 + zKey.charAt(i);
        }
        return TONES[Math.floorMod(h, TONES.length)];
    }

    /**
     * Paint an avatar TextView: the identity's initial (white) on its
     * identity-coloured circle. {@code zKey} drives the colour (the stable
     * identity pubkey / group id); {@code zName} drives the letter.
     */
    public static void apply(TextView zAvatar, String zKey, String zName) {
        zAvatar.setText(Ui.initial(zName));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(colour(zKey));
        zAvatar.setBackground(bg);
        zAvatar.setTextColor(0xFFFFFFFF);
    }
}
