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

    /** The ONE custom token Parlons can send. Two currencies only - Minima and MxUSD. */
    public static final String TOKENID_MXUSD =
            "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    /** The native Minima token id. */
    public static final String TOKENID_MINIMA = "0x00";

    /** Family rule: this token is ALWAYS called MxUSD - never USDT, never mxUSDT. */
    public static final String NAME_MXUSD = "MxUSD";

    /** Native Minima, labelled explicitly in every bubble and preview. */
    public static final String NAME_MINIMA = "MINIMA";

    private ChatPay() {
    }

    /**
     * Which token a payment body is about. The body carries the token NAME, and with exactly two
     * currencies the name IS the discriminator - so the body format is untouched, every payment
     * already stored on disk keeps working, and an old peer (which renders amount + name and
     * nothing else) is unaffected.
     *
     * ponytail: name -> id lookup, not a registry. A THIRD currency means carrying the id
     * explicitly - a 5th SEP field BEFORE the memo, with parse() tolerating 4-field rows.
     */
    public static String tokenId(String zBody) {
        return NAME_MXUSD.equalsIgnoreCase(tokenName(zBody)) ? TOKENID_MXUSD : TOKENID_MINIMA;
    }

    /** The label for a token id - the inverse of {@link #tokenId(String)}. */
    public static String nameFor(String zTokenId) {
        return TOKENID_MXUSD.equalsIgnoreCase(zTokenId) ? NAME_MXUSD : NAME_MINIMA;
    }

    /** True for the two token ids Parlons will send. Anything else is refused, never defaulted. */
    public static boolean isSendable(String zTokenId) {
        return TOKENID_MINIMA.equals(zTokenId) || TOKENID_MXUSD.equalsIgnoreCase(zTokenId);
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
