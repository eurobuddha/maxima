package com.eurobuddha.maxima.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.eurobuddha.maxima.app.portal.CloudSession;

/**
 * Portal process init. Installs SHA3-256 (Minima's seed/identity hashing needs it and Android's
 * providers lack it) BEFORE any activity or {@link CloudSession} touches the crypto — the session
 * can derive the device key off the UI thread the moment the app starts, so this must run first.
 * Also restores the saved day/night choice.
 */
public final class PortalApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Sha3Provider.install();
        try {
            int night = CloudSession.prefs(this)
                    .getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            AppCompatDelegate.setDefaultNightMode(night);
        } catch (Exception ignored) {
        }
    }
}
