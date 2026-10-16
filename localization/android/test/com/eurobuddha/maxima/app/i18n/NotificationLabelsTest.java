package com.eurobuddha.maxima.app.i18n;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import com.eurobuddha.maxima.app.R;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class NotificationLabelsTest {
    @Test public void renamesExistingChannelsWithoutChangingUserNotificationSettings() {
        Context context = mock(Context.class);
        Resources resources = mock(Resources.class);
        NotificationManager manager = mock(NotificationManager.class);
        NotificationChannel channel = mock(NotificationChannel.class);
        when(context.getSystemService(NotificationManager.class)).thenReturn(manager);
        when(context.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(new Configuration());
        when(context.createConfigurationContext(any(Configuration.class))).thenReturn(context);
        when(resources.getString(R.string.l10n_notification_messages)).thenReturn("Сообщения");
        when(resources.getString(R.string.l10n_notification_incoming_messages)).thenReturn("Входящие сообщения Parlons");
        when(manager.getNotificationChannel("maxima_chat_v3")).thenReturn(channel);
        when(channel.getName()).thenReturn("Messages");
        NotificationLabels.update(context);
        verify(channel).setName("Сообщения");
        verify(channel).setDescription("Входящие сообщения Parlons");
        verify(manager).createNotificationChannel(channel);
        verify(channel,never()).setImportance(anyInt());
        verify(channel,never()).setSound(any(),any());
        verify(channel,never()).enableVibration(anyBoolean());
        verify(channel,never()).setVibrationPattern(any());
        verify(channel,never()).setShowBadge(anyBoolean());
        verify(manager,never()).deleteNotificationChannel(anyString());
    }
}
