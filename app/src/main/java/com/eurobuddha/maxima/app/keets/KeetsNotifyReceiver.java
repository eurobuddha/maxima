package com.eurobuddha.maxima.app.keets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import com.eurobuddha.maxima.app.MaximaService;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.minimaapi.MinimaAPI;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Inbound keets chat, pushed by the minimaCore node's drainer as a KEETS NOTIFY
 * broadcast (the node owns the inbox and has already stored + acked; the event
 * carries the FULL base64 wire payload, so nothing is polled here). Routed into
 * the chat engine's keets inbound path, which handles text/photos/payments and
 * the read/payaddr controls alike.
 */
public final class KeetsNotifyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context zContext, Intent zIntent) {
        if (!MinimaAPI.checkMinimaID(zContext, zIntent)) {
            return;
        }
        String raw = zIntent.getStringExtra("com.eurobuddha.minimacore.NOTIFY_DATA");
        if (raw == null) {
            return;
        }
        final String from;
        final String payload;
        final String id;
        try {
            JSONObject ev = new JSONObject(raw);
            if (!"KEETS".equals(ev.optString("event"))) {
                return;
            }
            JSONObject data = ev.getJSONObject("data");
            from = data.getString("from");
            id = data.optString("id", "");
            payload = new String(Base64.decode(data.getString("payload"), Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        ChatEngine ce = MaximaService.chat();
        if (ce == null) {
            return;   // service not up yet - the message is safe in the node's KeetChat
        }
        // onKeetsInbound blocks on the history load, may send a receipt, and an
        // image ingest holds an IPC fetch for up to 90s - never on the receiver's
        // main thread, and never under goAsync(): holding the PendingResult that
        // long ANRs the app (proven on-device 2026-10-14). MaximaService is
        // running (ce != null), so the process outlives the receiver anyway.
        new Thread(() -> {
            try {
                ce.onKeetsInbound(from, payload, id);
            } catch (Exception ignored) {
            }
        }, "keets-notify").start();
    }
}
