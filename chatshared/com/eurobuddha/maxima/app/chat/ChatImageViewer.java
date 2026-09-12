package com.eurobuddha.maxima.app.chat;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.LruCache;
import com.eurobuddha.maxima.core.chat.ChatImages;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Parlons' zoom/save/share viewer with Salon's bounded carousel navigation. Shared by both APKs. */
public final class ChatImageViewer extends Dialog {
    public interface Loader { Bitmap load(ChatImages.Photo photo) throws Exception; }
    private final Activity activity;
    private ChatImages photos;
    public interface History { java.util.List<ChatImages.Photo> before(long time) throws Exception; }
    private History history;
    private long oldest;
    private boolean historyBusy;
    public void setHistory(History history, long oldest) { this.history = history; this.oldest = oldest; }
    private final Loader loader;
    private final LruCache<String, Bitmap> cache;
    private final ThreadPoolExecutor worker = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    private final ZoomImageView image;
    private final TextView counter, status, previous, next, save, share;
    private final LinearLayout bar, navigation;
    private Future<?> pending;
    private int generation;
    private Bitmap displayed;

    public ChatImageViewer(Activity activity, ChatImages photos, LruCache<String, Bitmap> cache,
            Loader loader, Consumer<Bitmap> savePhoto, Consumer<Bitmap> sharePhoto) {
        super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.activity = activity; this.photos = photos; this.cache = cache; this.loader = loader;
        FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xFF000000);
        // Android 15 edge-to-edge: keep controls above the system navigation bar/cutout.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        image = new ZoomImageView(activity);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        status = text(""); status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        status.setOnClickListener(v -> bind());

        bar = new LinearLayout(activity); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(10), dp(6), dp(8)); bar.setBackgroundColor(0xB3000000);
        TextView close = text("×"); close.setTextSize(28); close.setContentDescription("Close photo viewer");
        close.setOnClickListener(v -> dismiss()); bar.addView(close);
        counter = text(""); counter.setTextSize(13);
        counter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        save = text("Save"); share = text("Share");
        save.setOnClickListener(v -> { if (displayed != null) savePhoto.accept(displayed); });
        share.setOnClickListener(v -> { if (displayed != null) sharePhoto.accept(displayed); });
        bar.addView(save); bar.addView(share);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        navigation = new LinearLayout(activity); navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(0, 0, 0, dp(16));
        previous = text("‹"); previous.setTextSize(32); previous.setContentDescription("Previous photo");
        next = text("›"); next.setTextSize(32); next.setContentDescription("Next photo");
        previous.setOnClickListener(v -> page(-1)); next.setOnClickListener(v -> page(1));
        navigation.addView(previous); navigation.addView(next);
        root.addView(navigation, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        image.setOnPage(this::page);
        image.setOnSingleTap(() -> {
            int visibility = bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
            bar.setVisibility(visibility); navigation.setVisibility(visibility);
        });
        setContentView(root);
    }

    @Override public void show() {
        if (photos.current() == null || activity.isFinishing() || activity.isDestroyed()) return;
        super.show(); bind();
    }
    @Override public void dismiss() {
        generation++;
        if (pending != null) pending.cancel(true);
        worker.shutdownNow();
        displayed = null;
        image.setImageBitmap(null);
        super.dismiss();
    }
    private void page(int delta) {
        if (historyBusy) return;
        if (photos.move(delta)) bind();
        else if (delta < 0 && photos.index() == 0 && history != null) older();
    }
    private void older() {
        historyBusy = true;
        int ticket = ++generation;
        if (pending != null) pending.cancel(true);
        worker.purge();
        status.setText("Loading earlier photos…"); status.setEnabled(false); status.setVisibility(View.VISIBLE);
        previous.setEnabled(false); next.setEnabled(false);
        final ChatImages initial = photos;
        final History source = history;
        final long before = oldest;
        pending = worker.submit(() -> {
            ChatImages updated = initial;
            long cursor = before;
            boolean done = false, failed = false;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.List<ChatImages.Photo> page = source.before(cursor);
                    long earliest = cursor;
                    for (ChatImages.Photo p : page) earliest = Math.min(earliest, p.time);
                    if (page.isEmpty() || earliest >= cursor || earliest <= 0) { done = true; break; }
                    cursor = earliest;
                    updated = updated.withOlder(page);
                    if (updated.index() > initial.index()) break;
                }
            } catch (Exception e) { failed = true; }
            final ChatImages result = updated;
            final long nextCursor = cursor;
            final boolean end = done, error = failed;
            activity.runOnUiThread(() -> {
                if (ticket != generation || !isShowing() || activity.isDestroyed()) return;
                historyBusy = false; previous.setEnabled(true); next.setEnabled(true);
                photos = result; oldest = nextCursor;
                if (end) history = null;
                if (!error) photos.move(-1);
                bind();
                if (error) android.widget.Toast.makeText(activity,
                        "Couldn't load earlier photos. Swipe right to retry.", android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }
    private void bind() {
        if (!isShowing()) return;
        int ticket = ++generation;
        if (pending != null) pending.cancel(true);
        worker.purge(); // Rapid swipes cannot accumulate a queue of obsolete downloads.
        ChatImages.Photo photo = photos.current();
        counter.setText((photos.index() + 1) + " / " + photos.size());
        previous.setVisibility(photos.index() > 0 || history != null ? View.VISIBLE : View.INVISIBLE);
        next.setVisibility(photos.index() + 1 < photos.size() ? View.VISIBLE : View.INVISIBLE);
        showBitmap(cache.get(photo.id));
        if (displayed != null) return;
        status.setText("Loading photo…"); status.setEnabled(false);
        pending = worker.submit(() -> {
            Bitmap result = null;
            try { result = loader.load(photo); } catch (Exception ignored) { }
            final Bitmap bitmap = result;
            if (bitmap != null) cache.put(photo.id, bitmap);
            activity.runOnUiThread(() -> {
                if (ticket != generation || !isShowing() || activity.isDestroyed()) return;
                showBitmap(bitmap);
                if (bitmap == null) {
                    status.setText("Photo unavailable · tap to retry"); status.setEnabled(true);
                }
            });
        });
    }
    private void showBitmap(Bitmap bitmap) {
        displayed = bitmap; image.setImageBitmap(bitmap);
        status.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
        save.setEnabled(bitmap != null); share.setEnabled(bitmap != null);
        save.setAlpha(bitmap == null ? .4f : 1f); share.setAlpha(bitmap == null ? .4f : 1f);
    }
    private TextView text(String label) {
        TextView v = new TextView(activity); v.setText(label); v.setTextColor(0xFFFFFFFF);
        v.setTextSize(15); v.setGravity(Gravity.CENTER); v.setMinWidth(dp(48)); v.setMinHeight(dp(48));
        v.setPadding(dp(10), dp(4), dp(10), dp(4)); return v;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
