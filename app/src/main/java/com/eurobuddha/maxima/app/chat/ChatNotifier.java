package com.eurobuddha.maxima.app.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.eurobuddha.maxima.core.chat.ChatEngine;

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

    public static final String CHANNEL_ID = "maxima_chat";

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
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Messages",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Incoming Maxima messages");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /**
     * Show (or update) the notification for a conversation.
     *
     * @param zConversation peer public key, or group id
     * @param zTitle        who it is from, as the user would recognise them
     * @param zText         the newest line
     * @param zCount        unread in this conversation, shown when above one
     */
    public static void show(Context zCtx, String zConversation, String zTitle,
                            String zText, int zCount) {
        NotificationManager nm = zCtx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        createChannel(zCtx);

        Intent open = new Intent(zCtx, ChatActivity.class);
        open.putExtra(ChatActivity.EXTRA_CONVERSATION, zConversation);
        // Without a distinct action the system reuses one PendingIntent across
        // conversations and every notification opens the same thread.
        open.setAction("open:" + zConversation);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(zCtx, idFor(zConversation), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(zCtx, CHANNEL_ID)
                : new Notification.Builder(zCtx);

        b.setContentTitle(zTitle)
                .setContentText(zText)
                .setStyle(new Notification.BigTextStyle().bigText(zText))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (zCount > 1) {
            b.setNumber(zCount);
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
                                 com.eurobuddha.maxima.core.MaximaNode zNode) {
        String conv = zEntry.isGroup() ? zEntry.groupId : zEntry.peer;
        if (zEntry.mine || ChatHub.isForeground(conv)) {
            return;
        }
        String title = Names.of(zNode, zChat, conv);
        String text = zEntry.isGroup()
                ? Names.contact(zNode, zEntry.sender) + ": " + zEntry.body
                : zEntry.body;
        show(zCtx, conv, title, text, zChat.unread(conv));
    }
}
