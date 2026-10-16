package com.eurobuddha.maxima.app.i18n;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.core.chat.ChatContact;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ChatPreviewTest {
    private final Context context = mock(Context.class);
    private final Resources resources = mock(Resources.class);
    public ChatPreviewTest() {
        when(context.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(new Configuration());
        when(context.createConfigurationContext(any(Configuration.class))).thenReturn(context);
        when(resources.getString(R.string.l10n_media_photo)).thenReturn("📷 Фото");
        when(resources.getString(R.string.l10n_media_voice)).thenReturn("🎤 Голосовое сообщение");
        when(resources.getString(eq(R.string.l10n_media_photo_caption), any())).thenAnswer(c -> "📷 Фото  " + c.getArgument(1));
        when(resources.getString(eq(R.string.l10n_media_voice_caption), any())).thenAnswer(c -> "🎤 Голосовое сообщение  " + c.getArgument(1));
        when(resources.getString(eq(R.string.l10n_media_contact), any())).thenAnswer(c -> "👤 Контакт: " + c.getArgument(1));
    }
    @Test public void userTextNeverGetsTranslatedByItsContent() {
        for (String text : new String[]{"Photo", "Chats", "📷 Photo", "You: Привет 中文", "\u0001m\u0001broken"})
            assertEquals(text, ChatPreview.text(context, text));
    }
    @Test public void parsedLabelsTranslateButNamesCaptionsAndPaymentDataStayLiteral() {
        assertEquals("📷 Фото  中文 Photo", ChatPreview.text(context, ChatMedia.wrap("image/jpeg", "mx1_ref", "中文 Photo")));
        assertEquals("🎤 Голосовое сообщение  0:42", ChatPreview.text(context, ChatMedia.wrap("audio/webm", "mx1_ref", "0:42|abcf")));
        assertEquals("👤 Контакт: Photo", ChatPreview.text(context, ChatContact.wrap("key", "Photo", "MAX#address")));
        String payment = ChatPay.wrap("1.25", "TOKEN", "0x123", "Оплата 中文");
        assertEquals(ChatPay.preview(payment), ChatPreview.text(context, payment));
    }
    @Test public void cloudMetadataTranslatesLabelsAndRetainsLegacyFallback() {
        assertEquals("📷 Фото  中文 Photo", ChatPreview.summary(context, "📷 Photo  中文 Photo", "photo", "中文 Photo"));
        assertEquals("🎤 Голосовое сообщение  0:42", ChatPreview.summary(context, "🎤 Voice note  0:42", "voice", "0:42"));
        assertEquals("👤 Контакт: Photo", ChatPreview.summary(context, "👤 Contact: Photo", "contact", "Photo"));
        assertEquals("📎 中文.zip", ChatPreview.summary(context, "old", "file", "中文.zip"));
        assertEquals("💸 1.23456789 TOKEN", ChatPreview.summary(context, "old", "payment", "💸 1.23456789 TOKEN"));
        for (String kind : new String[]{null, "future"})
            assertEquals("📷 Photo", ChatPreview.summary(context, "📷 Photo", kind, "typed"));
        assertEquals("📷 Photo", ChatPreview.summary(context, "📷 Photo", "photo", null));
    }

}
