package com.eurobuddha.maxima.app.i18n;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import com.eurobuddha.maxima.app.R;

/** Updates only names/descriptions on existing channels; user sound, vibration and importance stay intact. */
public final class NotificationLabels {
    private NotificationLabels() {}
    public static void update(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        update(context, manager, "maxima_chat_v3", R.string.l10n_notification_messages, R.string.l10n_notification_incoming_messages);
        update(context, manager, "maxima_chat_v3_muted", R.string.l10n_notification_messages_muted, R.string.l10n_notification_incoming_muted);
        update(context, manager, "parlons_cloud_chat_v2", R.string.l10n_notification_messages, R.string.l10n_notification_cloud_messages);
        update(context, manager, "parlons_cloud_chat_v2_muted", R.string.l10n_notification_messages_muted, R.string.l10n_notification_cloud_muted);
        update(context, manager, "maxima_calls_v2", R.string.l10n_notification_calls, R.string.l10n_notification_incoming_calls);
        update(context, manager, "parlons_cloud_calls_v2", R.string.l10n_notification_calls, R.string.l10n_notification_incoming_calls);
        update(context, manager, "nftwallet_fg", R.string.l10n_notification_minting, 0);
        update(context, manager, "nftwallet_alert", R.string.l10n_notification_mint_updates, 0);
        update(context, manager, "parlons_cloud_svc", R.string.l10n_notification_cloud_connection, R.string.l10n_notification_cloud_connection_help);
    }
    private static void update(Context context, NotificationManager manager, String id, int name, int description) {
        NotificationChannel channel = manager.getNotificationChannel(id);
        if (channel == null) return;
        String title = Language.text(context,name), detail = description == 0 ? channel.getDescription() : Language.text(context,description);
        if (title.contentEquals(channel.getName() == null ? "" : channel.getName()) && java.util.Objects.equals(detail,channel.getDescription())) return;
        channel.setName(title);channel.setDescription(detail);manager.createNotificationChannel(channel);
    }
}
