package com.eurobuddha.maxima.app.chat;

import android.content.ActivityNotFoundException;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.eurobuddha.maxima.core.chat.ChatLinks;

/** Shared by the original and Cloud Android chat renderers. */
public final class ChatLinkText {
    private ChatLinkText() {}
    public static void reset(TextView view) {
        view.setMovementMethod(null);
        view.setLinksClickable(false);
    }
    public static void bind(TextView view, String text) {
        SpannableString value = new SpannableString(text);
        java.util.List<ChatLinks.Link> links = ChatLinks.find(text);
        for (ChatLinks.Link link : links) value.setSpan(new URLSpan(link.url) {
            @Override public void onClick(View widget) {
                try { super.onClick(widget); }
                catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(widget.getContext(), "No browser available to open this link", Toast.LENGTH_SHORT).show();
                }
            }
        }, link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(value);
        view.setLinkTextColor(view.getCurrentTextColor());
        if (!links.isEmpty()) {
            view.setLinksClickable(true);
            view.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }
}
