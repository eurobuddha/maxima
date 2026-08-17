package com.eurobuddha.maxima.core.chat;

/**
 * A payment shown IN the conversation, carried in the message BODY - so it
 * rides the existing message path unchanged (same threading, persistence and
 * summaries), exactly like {@link ChatMedia}. Mirrors FreezePeach's in-chat
 * payment bubble.
 *
 * A payment message's body is:
 *   {MARK}{amount}{SEP}{tokenName}{SEP}{txid}{SEP}{memo}
 *
 * MARK and SEP are control characters (SOH) that never appear in a typed
 * message, so plain text is never mistaken for a payment. The memo is last so
 * it may contain anything. The transaction itself is real and already on the
 * chain by the time this rides the wire; this body is only how the two ends
 * SHOW it to each other.
 */
public final class ChatPay {

    private static final String MARK = "p";
    private static final char SEP = '\u0001';

    private ChatPay() {
    }

    public static String wrap(String zAmount, String zTokenName, String zTxid, String zMemo) {
        return MARK + safe(zAmount) + SEP + safe(zTokenName) + SEP + safe(zTxid)
                + SEP + (zMemo == null ? "" : zMemo);
    }

    public static boolean isPayment(String zBody) {
        return zBody != null && zBody.startsWith(MARK);
    }

    /** {amount, tokenName, txid, memo} or null if the body is not a payment. */
    public static String[] parse(String zBody) {
        if (!isPayment(zBody)) {
            return null;
        }
        String rest = zBody.substring(MARK.length());
        int a = rest.indexOf(SEP);
        if (a < 0) {
            return null;
        }
        int b = rest.indexOf(SEP, a + 1);
        if (b < 0) {
            return null;
        }
        int c = rest.indexOf(SEP, b + 1);
        if (c < 0) {
            return null;
        }
        return new String[]{
                rest.substring(0, a),
                rest.substring(a + 1, b),
                rest.substring(b + 1, c),
                rest.substring(c + 1)
        };
    }

    public static String amount(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[0];
    }

    public static String tokenName(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[1];
    }

    public static String txid(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[2];
    }

    public static String memo(String zBody) {
        String[] p = parse(zBody);
        return p == null ? "" : p[3];
    }

    /** A short preview line for conversation summaries / notifications. */
    public static String preview(String zBody) {
        String[] p = parse(zBody);
        if (p == null) {
            return zBody;
        }
        String head = "💸 " + p[0] + " " + p[1];   // 💸 amount token
        return p[3].isEmpty() ? head : head + "  " + p[3];
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace(SEP, ' ');
    }
}
