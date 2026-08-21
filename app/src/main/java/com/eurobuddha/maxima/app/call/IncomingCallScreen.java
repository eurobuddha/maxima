package com.eurobuddha.maxima.app.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.chat.Names;

/**
 * Surfaces an incoming call: a full-screen-intent CALL notification (rings
 * through the lock screen) opening {@link CallActivity}. The ringtone loops
 * from CallManager itself - the channel stays soundless so ringing stops the
 * instant the call is answered or ends, not when Android feels like it.
 */
public final class IncomingCallScreen {

    public static final String CHANNEL_ID = "maxima_calls";
    public static final int NOTIF_ID = 0x4D43;   // "MC"

    private IncomingCallScreen() {
    }

    public static void show(Context zCtx, String zPeerKey) {
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Calls",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Incoming Parlons calls");
            ch.setSound(null, null);   // CallManager loops the ringtone itself
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        String who = Names.contact(MaximaService.port(), zPeerKey);
        Intent open = new Intent(zCtx, CallActivity.class);
        open.putExtra(CallActivity.EXTRA_PEER, zPeerKey);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(zCtx, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(zCtx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_maxima)
                .setContentTitle(who)
                .setContentText("Incoming Parlons call")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build();
        nm.notify(NOTIF_ID, n);
        try {
            zCtx.startActivity(open);
        } catch (Exception ignored) {
            // background-start restrictions: the full-screen intent covers it
        }
    }

    public static void dismiss(Context zCtx) {
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIF_ID);
        }
    }
}
