package com.eurobuddha.maxima.core.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The receipt state machine — never regress except to FAILED, and the tick
 *  predicates. Pins the delivery-status logic the whole two-tick UI rests on. */
public class ReceiptTest {

    @Test
    public void advancesUpTheLadder() {
        assertEquals(Receipt.DELIVERED, Receipt.merge(Receipt.SENT, Receipt.DELIVERED));
        assertEquals(Receipt.READ, Receipt.merge(Receipt.DELIVERED, Receipt.READ));
        assertEquals(Receipt.SENT, Receipt.merge(Receipt.QUEUED, Receipt.SENT));
    }

    @Test
    public void neverRegressesOnOutOfOrderReceipts() {
        // A SENT arriving after DELIVERED (unordered network) must not un-deliver.
        assertEquals(Receipt.DELIVERED, Receipt.merge(Receipt.DELIVERED, Receipt.SENT));
        assertEquals(Receipt.READ, Receipt.merge(Receipt.READ, Receipt.DELIVERED));
        assertEquals(Receipt.READ, Receipt.merge(Receipt.READ, Receipt.SENT));
    }

    @Test
    public void failedRetryThenSupersede() {
        // A later success supersedes an earlier failure...
        assertEquals(Receipt.DELIVERED, Receipt.merge(Receipt.FAILED, Receipt.DELIVERED));
        // ...but a bare SENT does not clear a failure on its own.
        assertEquals(Receipt.FAILED, Receipt.merge(Receipt.FAILED, Receipt.SENT));
    }

    @Test
    public void unknownIncomingIsNoOp() {
        assertEquals(Receipt.DELIVERED, Receipt.merge(Receipt.DELIVERED, null));
        assertEquals(Receipt.DELIVERED, Receipt.merge(Receipt.DELIVERED, "garbage"));
    }

    @Test
    public void tickPredicates() {
        assertFalse(Receipt.isSent(Receipt.QUEUED));
        assertTrue(Receipt.isSent(Receipt.SENT));
        assertTrue(Receipt.isSent(Receipt.DELIVERED));
        assertFalse(Receipt.isDelivered(Receipt.SENT));
        assertTrue(Receipt.isDelivered(Receipt.DELIVERED));
        assertTrue(Receipt.isDelivered(Receipt.READ));
        // FAILED is never a "sent" or "delivered" tick, whatever its rank.
        assertFalse(Receipt.isSent(Receipt.FAILED));
        assertFalse(Receipt.isDelivered(Receipt.FAILED));
    }
}
