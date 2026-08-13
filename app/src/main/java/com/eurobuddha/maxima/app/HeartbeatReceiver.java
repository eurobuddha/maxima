package com.eurobuddha.maxima.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * The only wake mechanism that reliably fires under Doze.
 *
 * A foreground service keeps the process resident but does NOT exempt
 * Handler.postDelayed from Doze - overnight, a postDelayed loop collapses to
 * the maintenance windows and the connection dies. An exact allow-while-idle
 * alarm is the fix, and it must RE-SCHEDULE ITSELF FIRST so a failure below
 * still leaves the chain intact.
 */
public final class HeartbeatReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.eurobuddha.maxima.app.HEARTBEAT";
    public static final long INTERVAL_MS = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        // Re-arm before doing anything that can throw.
        schedule(context);
        try {
            MaximaService.start(context);
        } catch (Exception e) {
            Log.w(MaximaService.TAG, "heartbeat could not start service: " + e);
        }
    }

    public static void schedule(Context zContext) {
        AlarmManager am = zContext.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        Intent i = new Intent(zContext, HeartbeatReceiver.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(zContext, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = System.currentTimeMillis() + INTERVAL_MS;
        try {
            boolean canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || am.canScheduleExactAlarms();
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                // Still allow-while-idle, just not exact.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }
}
