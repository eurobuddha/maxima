package com.eurobuddha.maxima.app.chat;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;
import android.widget.Toast;
import com.eurobuddha.maxima.core.chat.ChatLinks;

/** Shared by the original and Cloud Android chat renderers. */
public final class ChatLinkText {
    private ChatLinkText() {}
    public static void reset(TextView view) {
        view.setOnTouchListener(null);
        view.setOnLongClickListener(null);
        view.setMovementMethod(null);
        view.setLinksClickable(false);
    }
    public static void bind(TextView view, String text, Runnable messageMenu) {
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
        int ink = view.getCurrentTextColor();
        view.setLinkTextColor(Color.red(ink) + Color.green(ink) + Color.blue(ink) > 450
                ? Color.rgb(135, 206, 255) : Color.rgb(0, 88, 166));
        view.setOnLongClickListener(v -> { messageMenu.run(); return true; });
        if (!links.isEmpty()) {
            view.setLinksClickable(true);
            view.setMovementMethod(LinkMovementMethod.getInstance());
            final CharSequence boundText = view.getText();
            // Own only gestures that begin on a link. Text selection and scrolling elsewhere
            // retain their native handling; a long press must not also open the browser.
            view.setOnTouchListener(new View.OnTouchListener() {
                URLSpan pressed;
                float downX, downY;
                boolean longPress, cancelled;
                final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                final Runnable hold = () -> {
                    if (pressed != null && !cancelled && view.isAttachedToWindow()
                            && view.getText() == boundText) {
                        longPress = true;
                        showLinkMenu(view, pressed.getURL());
                    }
                };
                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        pressed = linkAt(view, event);
                        if (pressed == null) return false;
                        downX = event.getX(); downY = event.getY();
                        longPress = false; cancelled = false;
                        view.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                        return true;
                    }
                    if (pressed == null) return false;
                    if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                            && (Math.abs(event.getX()-downX) > slop || Math.abs(event.getY()-downY) > slop)) {
                        cancelled = true; view.removeCallbacks(hold);
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        view.removeCallbacks(hold);
                        URLSpan link = pressed; pressed = null;
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && !longPress && !cancelled)
                            link.onClick(view);
                    }
                    return true;
                }
            });
        }
    }

    private static URLSpan linkAt(TextView view, MotionEvent event) {
        Layout layout = view.getLayout();
        if (layout == null || !(view.getText() instanceof Spanned)) return null;
        float x = event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
        int y = (int) event.getY() - view.getTotalPaddingTop() + view.getScrollY();
        if (y < 0 || y >= layout.getHeight()) return null;
        int line = layout.getLineForVertical(y);
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
        int offset = layout.getOffsetForHorizontal(line, x);
        Spanned text = (Spanned) view.getText();
        for (URLSpan span : text.getSpans(offset, offset, URLSpan.class))
            if (offset >= text.getSpanStart(span) && offset < text.getSpanEnd(span)) return span;
        return null;
    }

    private static void showLinkMenu(TextView view, String url) {
        new android.app.AlertDialog.Builder(view.getContext()).setTitle(url)
                .setItems(new String[]{"Open link", "Copy link", "Share link"}, (dialog, which) -> {
                    try {
                        if (which == 0) new URLSpan(url).onClick(view);
                        else if (which == 1) {
                            ClipboardManager clipboard = view.getContext().getSystemService(ClipboardManager.class);
                            clipboard.setPrimaryClip(ClipData.newPlainText("link", url));
                            Toast.makeText(view.getContext(), "Link copied", Toast.LENGTH_SHORT).show();
                        } else {
                            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, url);
                            view.getContext().startActivity(Intent.createChooser(share, "Share link"));
                        }
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(view.getContext(), "No app available for this action", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }
}
