package com.eurobuddha.maxima.app.portal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Avatars;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;

import org.minima.utils.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Message notifications for the portal — fed by cloud PUSH events, not a local engine.
 *
 * Ported to the phone app's WhatsApp-style MessagingStyle stack: each conversation is one
 * notification that a new line lands under the right sender (with that sender's colour-per-identity
 * avatar). Android keeps no history for us and the portal has no ChatEngine to rebuild from, so we
 * keep a small in-memory stack per conversation and append pushed lines to it (cleared when the
 * thread is opened). Same channel discipline as ChatNotifier: a high-importance channel that chirps
 * the Parlons "pssst", plus a silent sibling the sound toggle posts on (a channel's sound is
 * immutable once created).
 */
public final class PortalNotifier {

    public static final String CHANNEL_ID = "parlons_cloud_chat_v2";
    /** Sound-off sibling (channel sound is immutable, so the toggle switches channels). */
    public static final String CHANNEL_ID_MUTED = "parlons_cloud_chat_v2_muted";

    private static final String PREFS = "parlons_cloud_notify";
    private static final String K_SOUND = "message_sound";
    private static final int STACK_CAP = 8;

    /** A pushed line we still need to render in the stack. */
    private static final class Line {
        final String senderKey;
        final String senderName;
        final String text;
        final long time;
        Line(String k, String n, String t, long ms) { senderKey = k; senderName = n; text = t; time = ms; }
    }

    /** Per-conversation recent-line stacks (Android keeps none; the portal has no engine).
     *  LRU-bounded so a device notified by many distinct peers doesn't retain a deque forever. */
    private static final int MAX_CONVERSATIONS = 50;
    private static final Map<String, Deque<Line>> sStacks =
            new java.util.LinkedHashMap<String, Deque<Line>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<Line>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            };

    private PortalNotifier() {
    }

    public static boolean messageSound(Context c) {
        return prefs(c).getBoolean(K_SOUND, true);
    }

    public static void setMessageSound(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_SOUND, on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || (nm.getNotificationChannel(CHANNEL_ID) != null
                && nm.getNotificationChannel(CHANNEL_ID_MUTED) != null)) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Messages",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Messages arriving on your cloud account");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        // Name-based URI (raw/pssst): a numeric resource id is only stable within one build and the
        // channel keeps the URI forever.
        ch.setSound(Uri.parse("android.resource://" + c.getPackageName() + "/raw/pssst"),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        nm.createNotificationChannel(ch);
        NotificationChannel muted = new NotificationChannel(CHANNEL_ID_MUTED, "Messages (muted)",
                NotificationManager.IMPORTANCE_HIGH);
        muted.setDescription("Messages arriving on your cloud account, sound off");
        muted.enableVibration(true);
        muted.setShowBadge(true);
        muted.setSound(null, null);
        nm.createNotificationChannel(muted);
        try {
            nm.deleteNotificationChannel("parlons_cloud_chat");   // superseded, silent
        } catch (Exception ignored) {
        }
    }

    /** Post (or update) the notification for one conversation from a pushed message event. */
    public static void onPushedMessage(Context c, JSONObject ev) {
        String peer = str(ev, "peer");
        if (peer.isEmpty() || PortalHub.isForeground(peer)) {
            return;         // that thread is on screen — no notification
        }
        ensureChannels(c);

        boolean group = Boolean.TRUE.equals(ev.get("group"));
        String convName = str(ev, "name");
        if (convName.isEmpty()) {
            convName = peer;
        }
        // Sender identity: in a group the pushed event carries the author (sender key + sname);
        // in a 1:1 the sender IS the peer.
        String senderKey = group ? firstNonEmpty(str(ev, "sender"), peer) : peer;
        String senderName = group ? firstNonEmpty(str(ev, "sname"), senderKey) : convName;
        String body = str(ev, "body");
        String line = ChatPay.isPayment(body) ? ChatPay.preview(body)
                : ChatMedia.isMedia(body) ? ChatMedia.preview(body) : body;
        long when = lng(ev, "time");
        if (when <= 0) {
            when = System.currentTimeMillis();
        }

        // Append to this conversation's in-memory stack (capped).
        Deque<Line> stack;
        synchronized (sStacks) {
            stack = sStacks.get(peer);
            if (stack == null) {
                stack = new ArrayDeque<>();
                sStacks.put(peer, stack);
            }
            stack.addLast(new Line(senderKey, senderName, line, when));
            while (stack.size() > STACK_CAP) {
                stack.removeFirst();
            }
        }

        Person me = new Person.Builder().setName("You").build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me);
        if (group) {
            style.setConversationTitle(convName);
            style.setGroupConversation(true);
        }
        Map<String, Person> people = new HashMap<>();
        synchronized (sStacks) {
            for (Line ln : stack) {
                Person p = people.get(ln.senderKey);
                if (p == null) {
                    p = new Person.Builder()
                            .setName(ln.senderName)
                            .setKey(ln.senderKey)
                            .setIcon(IconCompat.createWithBitmap(
                                    Avatars.bitmap(ln.senderKey, ln.senderName, 128)))
                            .build();
                    people.put(ln.senderKey, p);
                }
                style.addMessage(ln.text, ln.time, p);
            }
        }

        Intent open = new Intent(c, CloudChatActivity.class);
        open.putExtra(CloudChatActivity.EXTRA_PEER, peer);
        open.putExtra(CloudChatActivity.EXTRA_NAME, convName);
        open.putExtra(CloudChatActivity.EXTRA_GROUP, group);
        open.setAction("open:" + peer);   // distinct action per conversation, else intents collapse
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, idFor(peer), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean sound = messageSound(c);
        if (sound) {
            ensureAudible(c);
        }
        String channel = sound ? CHANNEL_ID : CHANNEL_ID_MUTED;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_send)
                .setStyle(style)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setWhen(when)
                .setContentIntent(pi);
        if (!group) {
            b.setLargeIcon(Avatars.bitmap(peer, convName, 128));
        }
        try {
            c.getSystemService(NotificationManager.class).notify(idFor(peer), b.build());
        } catch (Exception ignored) {
            // POST_NOTIFICATIONS can be denied; a missing notification must not crash the push pump.
        }
    }

    /** Wallet send outcome (async on the node) — always worth a notification. */
    public static void onWalletEvent(Context c, JSONObject ev) {
        ensureChannels(c);
        boolean okEv = "walletsent".equals(String.valueOf(ev.get("type")));
        String to = str(ev, "to");
        String title = okEv ? "Sent " + str(ev, "amount") + " MINIMA" : "Wallet send failed";
        String line = okEv
                ? "To " + to + "  ·  txid " + str(ev, "txid")     // full values, never truncated
                : str(ev, "error");
        Notification n = new NotificationCompat.Builder(c, CHANNEL_ID_MUTED)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(title)
                .setContentText(line)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(line))
                .setAutoCancel(true)
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(
                    0x574C0000 | (int) (System.currentTimeMillis() & 0xFFF), n);
        } catch (Exception ignored) {
            // POST_NOTIFICATIONS can be denied — must not abort the push fan-out (ledger/dispatch).
        }
    }

    public static void clear(Context c, String zPeer) {
        if (zPeer == null) {
            return;
        }
        synchronized (sStacks) {
            sStacks.remove(zPeer);
        }
        c.getSystemService(NotificationManager.class).cancel(idFor(zPeer));
    }

    /**
     * Message sound ON means the user expects to HEAR it — but the notification stream is its own
     * volume slider (separate from ringtone/media) and ships muted on some phones. Raising it is
     * permitted; lowering never happens here (per the sounds-as-auditioned rule: linear gain only).
     */
    private static void ensureAudible(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
            int cur = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            if (cur < max / 2) {
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, (max * 2) / 3, 0);
            }
        } catch (Exception ignored) {
            // some DND / policy states refuse volume changes — never crash
        }
    }

    private static int idFor(String zPeer) {
        return 0x504E0000 | (zPeer.hashCode() & 0xFFFF);
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }
}
