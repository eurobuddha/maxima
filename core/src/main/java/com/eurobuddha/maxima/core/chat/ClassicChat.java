package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.util.Json;

import java.util.Map;

/**
 * The CLASSIC chat wire - MaxSolo's format, verbatim.
 *
 * MaxSolo (the reference chat client shipped with every stock Minima node)
 * sends {@code maxima action:send ... application:maxsolo} with a flat JSON
 * payload {@code {username, type, message, filedata}}. Speaking exactly this
 * is what lets a Parlons user chat with any stock-node user with no extra
 * software on their side: the transport interop already existed (our relays
 * and wire are classic-compatible, and {@code ContactCtrl} IS classic's
 * contact handshake) - this codec closes the last gap, the chat payload.
 *
 * Deliberately minimal: text only. Media rides as its caption, payments as a
 * text summary - a classic peer has no way to render more.
 */
public final class ClassicChat {

    /** MaxSolo's application string - the classic chat channel. */
    public static final String APPLICATION = "maxsolo";

    private ClassicChat() {
    }

    /** Build a MaxSolo-format text message. */
    public static String build(String zUsername, String zText) {
        return new Json.Writer()
                .put("username", zUsername == null ? "noname" : zUsername)
                .put("type", "text")
                .put("message", zText == null ? "" : zText)
                .put("filedata", "")
                .done();
    }

    /** Parse a MaxSolo payload into its flat fields. */
    public static Map<String, String> parse(String zJson) {
        return Json.parse(zJson);
    }
}
