package com.eurobuddha.maxima.app.i18n;

import android.content.Context;
import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.core.chat.ChatContact;
import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;

/** Localizes only type labels from parsed messages; user text, names and filenames stay literal. */
public final class ChatPreview {
    private ChatPreview() {}
    public static String text(Context context, String body) {
        CharSequence value = message(body);
        return value instanceof Language.Message ? ((Language.Message)value).resolve(context) : value.toString();
    }
    /** New servers send bounded display metadata; older/cached summaries stay literal. */
    public static String summary(Context context, String legacy, String kind, String value) {
        CharSequence display = typed(kind, value);
        if (display == null) return text(context, legacy);
        return display instanceof Language.Message ? ((Language.Message) display).resolve(context) : display.toString();
    }
    private static CharSequence typed(String kind, String value) {
        if (kind == null || value == null) return null;
        if ("payment".equals(kind)) return value;
        if ("file".equals(kind)) return "📎 " + value;
        if ("contact".equals(kind)) return value.isEmpty() ? Language.msg(R.string.l10n_media_unnamed_contact)
                : Language.msg(R.string.l10n_media_contact, value);
        int resource, captionResource;
        switch (kind) {
            case "photo": resource = R.string.l10n_media_photo; captionResource = R.string.l10n_media_photo_caption; break;
            case "video": resource = R.string.l10n_media_video; captionResource = R.string.l10n_media_video_caption; break;
            case "voice": resource = R.string.l10n_media_voice; captionResource = R.string.l10n_media_voice_caption; break;
            default: return null;
        }
        return value.isEmpty() ? Language.msg(resource) : Language.msg(captionResource, value);
    }
    /** Retains only display text for notification stacks, never media manifests or contact addresses. */
    public static CharSequence message(String body) {
        if (ChatPay.isPayment(body)) return ChatPay.preview(body);
        String[] contact = ChatContact.parse(body);
        if (contact != null) return typed("contact", contact[1]);
        String[] media = ChatMedia.parse(body);
        if (media == null) return body == null ? "" : body;
        if (media[1].startsWith(ChatFile.PREFIX)) return typed("file", media[2]);
        String kind = media[0].startsWith("video") ? "video" : media[0].startsWith("audio") ? "voice" : "photo";
        String caption = media[2];
        if ("voice".equals(kind)) {
            int bar = caption.indexOf('|');
            if (bar >= 0) caption = caption.substring(0, bar);
        }
        return typed(kind, caption);
    }
}
