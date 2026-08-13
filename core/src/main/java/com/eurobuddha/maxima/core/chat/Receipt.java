package com.eurobuddha.maxima.core.chat;

/**
 * The two ticks.
 *
 * Classic Maxima cannot express this at all: its `delivered` flag means "a
 * relay queued bytes into a socket buffer", and on a relayed path the recipient
 * may never have seen the message. Distinguishing the two states is only
 * possible because a reply can be a fresh outbound message rather than a
 * socket-level ack.
 *
 * States, in order:
 *
 *   QUEUED     in our outbox, not yet on a wire
 *   SENT       a relay accepted it (MAXIMA_OK). ONE TICK.
 *              This is as far as classic can ever see.
 *   DELIVERED  the RECIPIENT decrypted it and sent a receipt back. TWO TICKS.
 *   READ       the recipient opened the conversation. Optional, and off by
 *              default - read receipts are a privacy choice, not a given.
 *   FAILED     no address worked and the attempts ran out.
 *
 * For a GROUP, delivery is per member: two ticks means every current member
 * sent a receipt. Showing two ticks when only some members received it would
 * be a lie, and the whole point of the second tick is that it is not one.
 */
public final class Receipt {

    public static final String QUEUED = "queued";
    public static final String SENT = "sent";
    public static final String DELIVERED = "delivered";
    public static final String READ = "read";
    public static final String FAILED = "failed";

    private Receipt() {
    }

    /** Rank, so a late or out-of-order receipt can never move a message backwards. */
    public static int rank(String zState) {
        if (zState == null) {
            return 0;
        }
        switch (zState) {
            case QUEUED: return 1;
            case SENT: return 2;
            case DELIVERED: return 3;
            case READ: return 4;
            case FAILED: return 5;
            default: return 0;
        }
    }

    /**
     * Merge a new state into the current one.
     *
     * Receipts arrive over an unordered network, so a SENT can land after a
     * DELIVERED. Never regress - except to FAILED, which is terminal and always
     * worth showing.
     */
    public static String merge(String zCurrent, String zIncoming) {
        if (FAILED.equals(zIncoming)) {
            return FAILED;
        }
        if (FAILED.equals(zCurrent)) {
            // A later success supersedes an earlier failure - a retry got through.
            return rank(zIncoming) >= rank(DELIVERED) ? zIncoming : zCurrent;
        }
        return rank(zIncoming) > rank(zCurrent) ? zIncoming : zCurrent;
    }

    /** One tick. */
    public static boolean isSent(String zState) {
        return rank(zState) >= rank(SENT) && !FAILED.equals(zState);
    }

    /** Two ticks. */
    public static boolean isDelivered(String zState) {
        return rank(zState) >= rank(DELIVERED) && !FAILED.equals(zState);
    }
}
