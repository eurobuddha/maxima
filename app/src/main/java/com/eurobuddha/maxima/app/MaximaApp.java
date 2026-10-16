package com.eurobuddha.maxima.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Applies the saved appearance (System / Light / Dark) as early as possible -
 * before any activity inflates - so the first frame is already the right theme
 * and there is no light-to-dark flash on launch. "System" (the default) simply
 * follows the phone's light/dark setting.
 */
public final class MaximaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        com.eurobuddha.maxima.app.i18n.Language.initialize(this);
        AppCompatDelegate.setDefaultNightMode(ChatPrefs.nightMode(this));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        com.eurobuddha.maxima.app.i18n.Language.configurationChanged(this);
    }

}
