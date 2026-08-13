package com.eurobuddha.maxima.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restart after a reboot AND after an app update. Both matter: a transport that
 * silently stops existing until the user next opens the app is not a transport.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SeedStore.hasIdentity(context)) {
            return;
        }
        HeartbeatReceiver.schedule(context);
        MaximaWorker.enqueue(context);
        try {
            MaximaService.start(context);
        } catch (Exception ignored) {
            // WorkManager will pick it up.
        }
    }
}
