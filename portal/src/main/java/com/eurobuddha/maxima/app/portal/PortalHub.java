package com.eurobuddha.maxima.app.portal;

import org.minima.utils.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The portal's event bus — the device-side end of the cloud push channel. The cloud node dials
 * this device with events (new message, delivery tick, call signal); {@link CloudSession} feeds
 * them here, and screens subscribe. Mirrors the app's ChatHub role, but the source is the
 * ACCOUNT's node, not a local engine.
 */
public final class PortalHub {

    public interface Listener {
        void onEvent(JSONObject zEvent);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** Which conversation is on screen right now ("" = none) — notifications skip it. */
    private static volatile String sForeground = "";

    private PortalHub() {
    }

    public static void add(Listener l) {
        if (!LISTENERS.contains(l)) {
            LISTENERS.add(l);
        }
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    public static void setForeground(String zPeerOrEmpty) {
        sForeground = zPeerOrEmpty == null ? "" : zPeerOrEmpty;
    }

    public static boolean isForeground(String zPeer) {
        return zPeer != null && zPeer.equalsIgnoreCase(sForeground);
    }

    public static void dispatch(JSONObject zEvent) {
        for (Listener l : LISTENERS) {
            try {
                l.onEvent(zEvent);
            } catch (Exception ignored) {
            }
        }
    }
}
