package com.eurobuddha.maxima.app.portal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.eurobuddha.maxima.app.R;

/**
 * Surfaces an incoming call on the portal: a full-screen-intent CALL notification (rings through
 * the lock screen) opening {@link PortalCallActivity}. The ringtone loops from
 * {@link PortalCallManager} itself — the channel stays soundless so ringing stops the instant
 * the call is answered or ends.
 */
public final class PortalIncomingCall {

    public static final String CHANNEL_ID = "parlons_cloud_calls";
    public static final int NOTIF_ID = 0x5043;   // "PC"

    private PortalIncomingCall() {
    }

    public static void show(Context zCtx, String zPeerKey, String zName, boolean zVideo) {
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Calls",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Incoming Parlons calls on your cloud account");
            ch.setSound(null, null);   // the manager loops the ringtone itself
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        String who = zName == null || zName.isEmpty() ? "contact" : zName;
        Intent open = new Intent(zCtx, PortalCallActivity.class);
        open.putExtra(PortalCallActivity.EXTRA_PEER, zPeerKey);
        open.putExtra(PortalCallActivity.EXTRA_NAME, who);
        open.putExtra(PortalCallActivity.EXTRA_VIDEO, zVideo);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(zCtx, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(zCtx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(who)
                .setContentText(zVideo ? "Incoming Parlons video call" : "Incoming Parlons call")
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
