package com.eurobuddha.maxima.app.chat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.ui.Avatars;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbound chat, surfaced to the user.
 *
 * Deliberately separate from the transport's own ongoing notification: that one
 * is IMPORTANCE_LOW and must never buzz, this one is IMPORTANCE_HIGH and must.
 * Putting them on one channel would force a choice between a permanently
 * buzzing status icon and silent messages.
 *
 * One notification per conversation, keyed by the conversation id, so a busy
 * group is one line that updates rather than fifty.
 */
public final class ChatNotifier {

    public static final String CHANNEL_ID = "maxima_chat_v3";

    /**
     * A channel's sound is immutable, so the Settings toggle cannot mute
     * CHANNEL_ID - it posts on this silent sibling instead: same importance,
     * still vibrates, no sound.
     */
    public static final String CHANNEL_ID_MUTED = "maxima_chat_v3_muted";

    /**
     * Notification ids must be ints, conversation keys are hex strings. The
     * hash can in principle collide, which would merge two conversations into
     * one notification - annoying, never wrong, and far better than keeping a
     * persistent id table for something this disposable.
     */
    private static int idFor(String zConversation) {
        return 0x4D00_0000 | (zConversation.hashCode() & 0x00FF_FFFF);
    }

    private ChatNotifier() {
    }

    public static void createChannel(Context zCtx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm == null || (nm.getNotificationChannel(CHANNEL_ID) != null
                && nm.getNotificationChannel(CHANNEL_ID_MUTED) != null)) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Messages",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Incoming Parlons messages");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        // The Parlons "pssssst!" - channel sound settings are immutable after
        // creation, hence the versioned channel id; superseded channels are
        // removed so Settings shows one Messages entry, not several. The URI
        // is NAME-based (raw/pssst): a numeric resource id is only stable
        // within one build, and the channel keeps the URI forever.
        ch.setSound(android.net.Uri.parse("android.resource://"
                        + zCtx.getPackageName() + "/raw/pssst"),
                new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(
                                android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        nm.createNotificationChannel(ch);
        NotificationChannel muted = new NotificationChannel(CHANNEL_ID_MUTED,
                "Messages (muted)", NotificationManager.IMPORTANCE_HIGH);
        muted.setDescription("Incoming Parlons messages, sound off");
        muted.enableVibration(true);
        muted.setShowBadge(true);
        muted.setSound(null, null);
        nm.createNotificationChannel(muted);
        try {
            nm.deleteNotificationChannel("maxima_chat");
            nm.deleteNotificationChannel("maxima_chat_v2");
        } catch (Exception ignored) {
        }
    }

    /**
     * Post (or update) a conversation's notification as a WhatsApp-style
     * threaded message stack: each new line under the right sender, with that
     * sender's colour-per-identity avatar, all under the one conversation.
     *
     * Android keeps no history for us, so we rebuild the stack each time from
     * the engine: the inbound messages that arrived since the thread was last
     * read (the honest "new" set), oldest first, capped so a flood stays sane.
     */
    private static void postConversation(Context zCtx, ChatEngine zChat,
                                         com.eurobuddha.maxima.core.ChatPort zNode,
                                         String zConversation) {
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        createChannel(zCtx);

        boolean group = zChat.group(zConversation) != null;
        long since = zChat.lastRead(zConversation);
        List<ChatEngine.Entry> conv = zChat.conversation(zConversation);
        Collections.sort(conv, (a, b) -> Long.compare(a.time, b.time));
        List<ChatEngine.Entry> fresh = new ArrayList<>();
        for (ChatEngine.Entry e : conv) {
            if (!e.mine && e.time > since) {
                fresh.add(e);
            }
        }
        if (fresh.isEmpty()) {
            return;   // caught up - nothing to shout about
        }
        if (fresh.size() > 8) {
            fresh = fresh.subList(fresh.size() - 8, fresh.size());
        }

        Person me = new Person.Builder().setName("You").build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me);
        if (group) {
            style.setConversationTitle(Names.of(zNode, zChat, zConversation));
            style.setGroupConversation(true);
        }
        Map<String, Person> people = new HashMap<>();
        for (ChatEngine.Entry e : fresh) {
            Person p = people.get(e.sender);
            if (p == null) {
                String who = Names.contact(zNode, e.sender);
                p = new Person.Builder()
                        .setName(who)
                        .setKey(e.sender)
                        .setIcon(IconCompat.createWithBitmap(
                                Avatars.bitmap(e.sender, who, 128)))
                        .build();
                people.put(e.sender, p);
            }
            String line = ChatPay.isPayment(e.body)
                    ? ChatPay.preview(e.body) : ChatMedia.preview(e.body);
            style.addMessage(line, e.time, p);
        }

        Intent open = new Intent(zCtx, ChatActivity.class);
        open.putExtra(ChatActivity.EXTRA_CONVERSATION, zConversation);
        // Without a distinct action the system reuses one PendingIntent across
        // conversations and every notification opens the same thread.
        open.setAction("open:" + zConversation);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(zCtx, idFor(zConversation), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean sound = com.eurobuddha.maxima.app.ChatPrefs.messageSound(zCtx);
        if (sound) {
            Pssst.ensureAudible(zCtx);
        }
        String channel = sound ? CHANNEL_ID : CHANNEL_ID_MUTED;
        NotificationCompat.Builder b = new NotificationCompat.Builder(zCtx, channel)
                .setSmallIcon(R.drawable.ic_stat_maxima)
                .setStyle(style)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setWhen(fresh.get(fresh.size() - 1).time)
                .setContentIntent(pi);
        if (!group) {
            b.setLargeIcon(Avatars.bitmap(zConversation,
                    Names.of(zNode, zChat, zConversation), 128));
        }
        try {
            nm.notify(idFor(zConversation), b.build());
        } catch (Exception ignored) {
            // POST_NOTIFICATIONS can be denied; a missing notification must not
            // take down the transport thread that produced the message.
        }
    }

    /** Called when the user opens a thread - the badge has served its purpose. */
    public static void clear(Context zCtx, String zConversation) {
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(idFor(zConversation));
        }
    }

    /**
     * Decide and post for one inbound entry.
     *
     * A group notification is titled with the GROUP and prefixed with the
     * author: titling it with the author instead would make one group look like
     * several separate conversations, and give no way to tell which group a
     * line came from.
     */
    public static void onInbound(Context zCtx, ChatEngine zChat, ChatEngine.Entry zEntry,
                                 com.eurobuddha.maxima.core.ChatPort zNode) {
        String conv = zEntry.isGroup() ? zEntry.groupId : zEntry.peer;
        if (zEntry.mine) {
            return;
        }
        if (ChatHub.isForeground(conv)) {
            // No notification while you are looking at the conversation -
            // just the soft in-app pssst (if the toggle allows it).
            if (com.eurobuddha.maxima.app.ChatPrefs.messageSound(zCtx)) {
                Pssst.ensureAudible(zCtx);
                Pssst.play(zCtx);
            }
            return;
        }
        postConversation(zCtx, zChat, zNode, conv);
    }
}
