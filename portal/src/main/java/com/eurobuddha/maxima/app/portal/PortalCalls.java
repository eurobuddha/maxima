package com.eurobuddha.maxima.app.portal;

import android.content.Context;

import org.minima.utils.json.JSONObject;

/**
 * Entry point for pushed CALL signals. Routes offer/answer/ice/bye/busy/taken events into the
 * portal's call manager (WebRTC terminates on THIS device; the cloud relays signaling under the
 * account identity).
 */
public final class PortalCalls {

    private PortalCalls() {
    }

    public static void onPushedSignal(Context app, JSONObject ev) {
        PortalCallManager.get(app).onSignal(ev);
    }
}
