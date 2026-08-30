package com.eurobuddha.maxima.app.portal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.core.chat.ChatMedia;

import org.minima.utils.json.JSONObject;

/**
 * Message notifications for the portal — fed by cloud PUSH events, not a local engine. Same
 * channel discipline as the app's ChatNotifier (high-importance channel, per-conversation
 * notification id + open-intent action so each notification opens ITS thread), but the content
 * is the pushed event: sender name, body (media bodies render as their 📷/🎤 preview).
 */
public final class PortalNotifier {

    private static final String CHANNEL = "parlons_cloud_chat";

    private PortalNotifier() {
    }

    private static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "Messages", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Messages arriving on your cloud account");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    /** Post (or update) the notification for one conversation from a pushed message event. */
    public static void onPushedMessage(Context c, JSONObject ev) {
        String peer = str(ev, "peer");
        if (peer.isEmpty() || PortalHub.isForeground(peer)) {
            return;         // that thread is on screen — no notification
        }
        ensureChannel(c);
        String name = str(ev, "name");
        if (name.isEmpty()) {
            name = peer;
        }
        String body = str(ev, "body");
        String line = ChatMedia.isMedia(body) ? ChatMedia.preview(body) : body;

        Intent open = new Intent(c, CloudChatActivity.class);
        open.putExtra(CloudChatActivity.EXTRA_PEER, peer);
        open.putExtra(CloudChatActivity.EXTRA_NAME, name);
        open.setAction("open:" + peer);   // distinct action per conversation, else intents collapse
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(name)
                .setContentText(line)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(line))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        c.getSystemService(NotificationManager.class).notify(idFor(peer), n);
    }

    public static void clear(Context c, String zPeer) {
        c.getSystemService(NotificationManager.class).cancel(idFor(zPeer));
    }

    private static int idFor(String zPeer) {
        return 0x504E0000 | (zPeer.hashCode() & 0xFFFF);
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
