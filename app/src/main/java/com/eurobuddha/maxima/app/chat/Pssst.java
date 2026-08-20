package com.eurobuddha.maxima.app.chat;

import android.content.Context;
import android.media.AudioManager;

/**
 * The Parlons inbound sound (res/raw/pssst) rides the notification channel
 * and chirps ONLY for notifications - a message landing in the conversation
 * you are looking at stays silent. This helper's job is making sure the
 * notification stream can actually be heard.
 */
public final class Pssst {

    private Pssst() {
    }

    /**
     * Message sound ON means the user expects to HEAR it - but the
     * notification stream is its own volume slider (separate from ringtone
     * and media) and ships muted on some phones. Raising it is permitted to
     * apps; lowering never happens here.
     */
    public static void ensureAudible(Context zCtx) {
        try {
            if (!com.eurobuddha.maxima.app.ChatPrefs.messageSound(zCtx)) {
                return;
            }
            AudioManager am = (AudioManager) zCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
            int cur = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            if (cur < max / 2) {
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, (max * 2) / 3, 0);
            }
        } catch (Exception ignored) {
            // some DND / policy states refuse volume changes - never crash
        }
    }

}
