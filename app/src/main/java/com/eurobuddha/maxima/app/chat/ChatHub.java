package com.eurobuddha.maxima.app.chat;

import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one place UI screens hear about chat, and the one place that knows what
 * is currently on screen.
 *
 * {@link ChatEngine} allows a SINGLE listener, and the service claims it -
 * inbound messages must reach the notifier whether or not an activity exists.
 * The activities are therefore observers of the service's listener rather than
 * competing for the slot, which also means an activity being destroyed can
 * never silently unhook notifications.
 *
 * Every callback is re-posted to the main thread, because they originate on the
 * transport pump thread and an activity touching a View off it will crash.
 */
public final class ChatHub {

    private static final List<ChatEngine.Listener> sObservers = new CopyOnWriteArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /**
     * The conversation currently on screen, or "" for none.
     *
     * A notification for the thread you are already reading is noise, and worse,
     * it teaches you to swipe notifications away without looking.
     */
    private static volatile String sForeground = "";

    private ChatHub() {
    }

    public static void register(ChatEngine.Listener zObserver) {
        sObservers.add(zObserver);
    }

    public static void unregister(ChatEngine.Listener zObserver) {
        sObservers.remove(zObserver);
    }

    public static void setForeground(String zConversation) {
        sForeground = zConversation == null ? "" : zConversation;
    }

    public static String foreground() {
        return sForeground;
    }

    public static boolean isForeground(String zConversation) {
        return !sForeground.isEmpty() && sForeground.equals(zConversation);
    }

    // ---- dispatch, always on the main thread ----

    public static void dispatchMessage(ChatEngine.Entry zEntry) {
        sMain.post(() -> {
            for (ChatEngine.Listener l : sObservers) {
                try {
                    l.onMessage(zEntry);
                } catch (Exception ignored) {
                }
            }
        });
    }

    public static void dispatchState(ChatEngine.Entry zEntry) {
        sMain.post(() -> {
            for (ChatEngine.Listener l : sObservers) {
                try {
                    l.onStateChanged(zEntry);
                } catch (Exception ignored) {
                }
            }
        });
    }

    public static void dispatchGroup(Group zGroup) {
        sMain.post(() -> {
            for (ChatEngine.Listener l : sObservers) {
                try {
                    l.onGroupChanged(zGroup);
                } catch (Exception ignored) {
                }
            }
        });
    }
}
