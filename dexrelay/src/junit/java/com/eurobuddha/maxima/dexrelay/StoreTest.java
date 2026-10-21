package com.eurobuddha.maxima.dexrelay;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Highest-seq-wins, cursor semantics, expiry sweep, long-poll wake. Uses pre-validated envelopes built via a test key. */
public class StoreTest {

    static final long NOW = 1800000000L - 3600;
    final TestSigner signer = new TestSigner();

    @Test public void highestSeqWinsLowerOrEqualDropped() {
        Store s = new Store();
        Envelope e1 = signer.order("0x0102030405060708", 1, "100", NOW);
        Envelope e2 = signer.order("0x0102030405060708", 2, "90", NOW);
        assertTrue(s.publish(e1));
        assertFalse(s.publish(e1));                 // equal seq
        assertTrue(s.publish(e2));
        assertFalse(s.publish(e1));                 // lower seq after a higher one
        assertEquals(1, s.snapshot("MINIMA/MxUSD").size());
        assertEquals("90", s.snapshot("MINIMA/MxUSD").get(0).amount);
    }

    @Test public void cancelRemovesFromSnapshotButIsEmittedAsAChange() {
        Store s = new Store();
        s.publish(signer.order("0x0102030405060708", 1, "100", NOW));
        long c = s.cursor();
        s.publish(signer.order("0x0102030405060708", 2, "0", NOW));
        assertEquals(0, s.snapshot("MINIMA/MxUSD").size());
        Store.Changes ch = s.changesSince("MINIMA/MxUSD", c);
        assertEquals(1, ch.orders.size());
        assertTrue(ch.orders.get(0).isCancel());
        assertFalse(ch.reset);
    }

    @Test public void cursorTooOldForcesASnapshotReset() {
        Store s = new Store(4 /* tiny change log */);
        for (int i = 1; i <= 10; i++) s.publish(signer.order("0x01020304050607" + String.format("%02x", i), 1, "1", NOW));
        Store.Changes ch = s.changesSince("MINIMA/MxUSD", 1);
        assertTrue(ch.reset);
        assertEquals(10, ch.orders.size());
        Store.Changes recent = s.changesSince("MINIMA/MxUSD", s.cursor() - 2);
        assertFalse(recent.reset);
        assertEquals(2, recent.orders.size());
    }

    @Test public void expirySweepDropsOrdersAndEmitsNothingNew() {
        Store s = new Store();
        s.publish(signer.order("0x0102030405060708", 1, "100", NOW));
        s.sweep(NOW + 1);                              // still live (expiry is NOW+3600)
        assertEquals(1, s.snapshot("MINIMA/MxUSD").size());
        s.sweep(NOW + 3601);
        assertEquals(0, s.snapshot("MINIMA/MxUSD").size());
    }

    @Test public void awaitWakesWithinMillisOfAPublish() throws Exception {
        Store s = new Store();
        long c = s.cursor();
        AtomicLong woke = new AtomicLong(-1);
        Thread t = new Thread(() -> { long t0 = System.nanoTime(); s.await(c, 5000); woke.set((System.nanoTime() - t0) / 1_000_000); });
        t.start();
        Thread.sleep(150);
        s.publish(signer.order("0x0102030405060708", 1, "100", NOW));
        t.join(2000);
        assertTrue("woke at " + woke.get() + "ms", woke.get() >= 100 && woke.get() < 1000);
    }

    @Test public void awaitTimesOutWithoutAPublish() {
        Store s = new Store();
        long t0 = System.nanoTime();
        s.await(s.cursor(), 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("timed out after " + ms, ms >= 180 && ms < 1500);
    }

    @Test public void inboxIsPerRecipientWithOwnCursor() {
        Store s = new Store();
        String to = "0x" + "22".repeat(64);
        long c = s.inboxCursor(to);
        assertTrue(s.deliver(signer.msg(to, "aabb", NOW)));
        assertTrue(s.deliver(signer.msg(to, "ccdd", NOW)));
        assertTrue(s.deliver(signer.msg("0x" + "33".repeat(64), "eeff", NOW)));
        List<Envelope> got = s.inboxSince(to, c);
        assertEquals(2, got.size());
        assertEquals("aabb", got.get(0).blob);
        assertEquals(0, s.inboxSince(to, s.inboxCursor(to)).size());
        s.sweep(NOW + 601);                            // msg TTL is 10 min
        assertEquals(0, s.inboxSince(to, 0).size());
    }
}
