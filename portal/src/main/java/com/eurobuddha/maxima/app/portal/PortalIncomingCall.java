package com.eurobuddha.maxima.app.portal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.eurobuddha.maxima.app.R;

/** Incoming calls use Android's ringtone player and prominent call notification. */
public final class PortalIncomingCall {
    // Channel sound is immutable. Migrate the previously code-silent channel once.
    private static final String LEGACY_CHANNEL_ID = "parlons_cloud_calls";
    public static final String CHANNEL_ID = "parlons_cloud_calls_v2";
    public static final int NOTIF_ID = 0x5043;
    private static final String EXTRA_DECLINE = "decline_call_id";

    private PortalIncomingCall() { }

    public static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel old = nm.getNotificationChannel(LEGACY_CHANNEL_ID);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Incoming calls",
                old == null ? NotificationManager.IMPORTANCE_HIGH : old.getImportance());
        ch.setDescription("Incoming Parlons calls");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        // Preserve an explicitly chosen sound (including user-selected silence).
        if (old != null && (old.getSound() != null
                || (Build.VERSION.SDK_INT >= 30 && old.hasUserSetSound()))) {
            sound = old.getSound();
        }
        ch.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        if (old != null) {
            if (old.getVibrationPattern() != null) ch.setVibrationPattern(old.getVibrationPattern());
            ch.setLockscreenVisibility(old.getLockscreenVisibility());
        }
        ch.enableVibration(old == null || old.shouldVibrate());
        nm.createNotificationChannel(ch);
    }

    public static boolean canShowFullScreen(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return Build.VERSION.SDK_INT < 34 || (nm != null && nm.canUseFullScreenIntent());
    }

    public static void openCallSettings(Context ctx) {
        ensureChannel(ctx);
        Intent intent = Build.VERSION.SDK_INT >= 34 && !canShowFullScreen(ctx)
                ? new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:" + ctx.getPackageName()))
                : new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
        ctx.startActivity(intent);
    }

    public static void show(Context zCtx, String zPeerKey, String zName, boolean zVideo) {
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(zCtx);
        String who = zName;
        if (who == null || who.isEmpty()) who = "Contact";
        boolean video = zVideo;
        String callId = PortalCallManager.get(zCtx).callId();
        Intent open = new Intent(zCtx, PortalCallActivity.class)
                .setData(new Uri.Builder().scheme("parlons-call").authority("incoming")
                        .appendPath(callId).build());
        open.putExtra(PortalCallActivity.EXTRA_PEER, zPeerKey);
        open.putExtra(PortalCallActivity.EXTRA_VIDEO, video);
        open.putExtra(PortalCallActivity.EXTRA_CALL_ID, callId);
        open.putExtra(PortalCallActivity.EXTRA_NAME, who);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent view = PendingIntent.getActivity(zCtx, NOTIF_ID, open, flags);
        Intent answerIntent = new Intent(open).setAction("parlons.call.ANSWER")
                .putExtra(PortalCallActivity.EXTRA_ANSWER_CALL_ID, callId);
        PendingIntent answer = PendingIntent.getActivity(zCtx, NOTIF_ID, answerIntent, flags);
        Intent declineIntent = new Intent(zCtx, DeclineReceiver.class)
                .setData(open.getData()).putExtra(EXTRA_DECLINE, callId);
        PendingIntent decline = PendingIntent.getBroadcast(zCtx, NOTIF_ID, declineIntent, flags);
        Notification.Builder builder = new Notification.Builder(zCtx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(who)
                .setContentText(video ? "Incoming Parlons video call" : "Incoming Parlons call")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(view)
                .setFullScreenIntent(view, true);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(Notification.CallStyle.forIncomingCall(
                    new Person.Builder().setName(who).setImportant(true).build(), decline, answer)
                    .setIsVideo(video));
        } else {
            builder.setStyle(new Notification.BigTextStyle().bigText(
                    video ? "Incoming Parlons video call" : "Incoming Parlons call"))
                    .addAction(new Notification.Action.Builder(null, "Decline", decline).build())
                    .addAction(new Notification.Action.Builder(null, "Answer", answer).build());
        }
        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_INSISTENT;
        nm.notify(NOTIF_ID, notification);
        try {
            zCtx.startActivity(open);
        } catch (Exception ignored) {
            // Android's full-screen intent / heads-up call notification covers background starts.
        }
    }

    /** Private receiver reached only through our immutable notification PendingIntent. */
    public static final class DeclineReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            PortalCallManager.get(ctx).decline(intent.getStringExtra(EXTRA_DECLINE));
        }
    }

    public static void dismiss(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);
    }
}
