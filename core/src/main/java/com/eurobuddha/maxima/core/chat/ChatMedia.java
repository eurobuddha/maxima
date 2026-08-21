package com.eurobuddha.maxima.core.chat;

/**
 * Media in chat, carried in the message BODY — so it rides the existing text
 * path unchanged: same send, same receipts, same threading, same persistence.
 *
 * A media message's body is:
 *   {MARK}{mime}{SEP}{mx1 manifest ref}{SEP}{caption}
 *
 * where MARK and SEP are control characters (SOH) that never appear in a mime
 * type or a base64url manifest ref; the caption is last so it may contain
 * anything. The manifest (with its AES key) sits inside the message, which the
 * transport seals end-to-end — the key reaches exactly the recipients the
 * message is sealed to, no one else. Plain text has no marker and is untouched.
 */
public final class ChatMedia {

    /** SOH-fenced marker, chosen so no human-typed message can begin with it. */
    private static final String MARK = "m";
    private static final char SEP = '';

    private ChatMedia() {
    }

    public static String wrap(String zMime, String zManifestRef, String zCaption) {
        return MARK + safe(zMime) + SEP + zManifestRef + SEP
                + (zCaption == null ? "" : zCaption);
    }

    public static boolean isMedia(String zBody) {
        return zBody != null && zBody.startsWith(MARK);
    }

    /** {mime, ref, caption} or null if the body is not media. */
    public static String[] parse(String zBody) {
        if (!isMedia(zBody)) {
            return null;
        }
        String rest = zBody.substring(MARK.length());   // mime SEP ref SEP caption(rest)
        int a = rest.indexOf(SEP);
        if (a < 0) {
            return null;
        }
        int b = rest.indexOf(SEP, a + 1);
        if (b < 0) {
            return null;
        }
        return new String[]{
                rest.substring(0, a),
                rest.substring(a + 1, b),
                rest.substring(b + 1)
        };
    }

    public static String mime(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[0];
    }

    public static String ref(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[1];
    }

    public static String caption(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[2];
    }

    /** A short preview line for conversation summaries / notifications. */
    public static String preview(String zBody) {
        String[] p = parse(zBody);
        if (p == null) {
            return zBody;
        }
        String kind = p[0].startsWith("video") ? "🎥 Video"
                : p[0].startsWith("audio") ? "🎤 Voice note"
                : "📷 Photo";
        String cap = p[2];
        // Voice notes carry "duration|waveformhex" in the caption slot - only
        // the duration is human-facing.
        if (p[0].startsWith("audio")) {
            int bar = cap.indexOf('|');
            if (bar >= 0) {
                cap = cap.substring(0, bar);
            }
        }
        return cap.isEmpty() ? kind : kind + "  " + cap;
    }

    private static String safe(String s) {
        return s == null ? "application/octet-stream" : s.replace(SEP, ' ');
    }
}
