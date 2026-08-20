package com.eurobuddha.maxima.app.chat;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;

/**
 * The Parlons inbound sound - a short synthesized "pssssst!" (res/raw/pssst).
 *
 * Notifications get it as their channel sound; this helper is the IN-APP path,
 * for a message landing in the conversation you are looking at (where no
 * notification posts). Ringer on vibrate/silent stays silent.
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

    public static void play(Context zCtx) {
        try {
            AudioManager am = (AudioManager) zCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
                return;
            }
            MediaPlayer mp = MediaPlayer.create(zCtx,
                    com.eurobuddha.maxima.app.R.raw.pssst,
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    am.generateAudioSessionId());
            if (mp != null) {
                mp.setOnCompletionListener(MediaPlayer::release);
                mp.start();
            }
        } catch (Exception ignored) {
            // a missed sound is never worth a crash in the inbound path
        }
    }
}
