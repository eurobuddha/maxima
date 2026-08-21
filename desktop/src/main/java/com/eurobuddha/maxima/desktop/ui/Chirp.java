package com.eurobuddha.maxima.desktop.ui;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.prefs.Preferences;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/**
 * The inbound "pssssst!" chirp — the desktop half of the phone's notification
 * sound. Plays the SAME asset (res/raw/pssst.wav, 16-bit PCM, directly playable
 * by Java Sound), only for a message you are not already looking at, and only
 * when the "Message sound" preference is on (mirrors ChatPrefs.messageSound).
 */
public final class Chirp {

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/eurobuddha/maxima/desktop");
    private static final String KEY = "messageSound";

    private static Clip sClip;      // reused; rewound each play
    private static boolean sTried;  // load once, fail silent

    private Chirp() { }

    public static boolean enabled() {
        return PREFS.getBoolean(KEY, true);   // default on, like the phone
    }

    public static void setEnabled(boolean on) {
        PREFS.putBoolean(KEY, on);
    }

    /** Play the chirp if sound is enabled. Never throws — audio is a nicety. */
    public static synchronized void play() {
        if (!enabled()) {
            return;
        }
        try {
            Clip clip = clip();
            if (clip == null) {
                return;
            }
            clip.stop();
            clip.setFramePosition(0);
            clip.start();
        } catch (Exception ignored) {
            // A machine with no audio device must not break messaging.
        }
    }

    private static Clip clip() {
        if (sClip != null || sTried) {
            return sClip;
        }
        sTried = true;
        try (InputStream raw = Chirp.class.getResourceAsStream("/sounds/pssst.wav")) {
            if (raw == null) {
                return null;
            }
            AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
            Clip c = AudioSystem.getClip();
            c.open(in);
            sClip = c;
        } catch (Exception ignored) {
            sClip = null;
        }
        return sClip;
    }
}
