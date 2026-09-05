package com.eurobuddha.maxima.app.portal.wallet;

import com.eurobuddha.maxima.app.R;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * One place that builds this app's notifications, so the Activity and {@link MintService} post
 * identical alerts. Follows the family's Notifier convention (apks/limit).
 */
public final class Notifier {

    /** Ongoing foreground-service notification — low importance, it must not buzz every block. */
    public static final String CH_FG = "nftwallet_fg";
    /** Mint finished / mint needs you — worth a heads-up. */
    public static final String CH_ALERT = "nftwallet_alert";
    private static int sAlertId = 3100;

    private Notifier() {}

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CH_FG, "Minting in the background",
                    NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Mint updates",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    public static void alert(Context c, String title, String body) {
        ensureChannels(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(sAlertId++, new NotificationCompat.Builder(c, CH_ALERT)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .build());
    }

    /** Tapping any of our notifications opens the wallet. */
    public static android.app.PendingIntent openApp(Context c) {
        android.content.Intent i = new android.content.Intent(c, WalletActivity.class);
        i.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return android.app.PendingIntent.getActivity(c, 0, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }
}
