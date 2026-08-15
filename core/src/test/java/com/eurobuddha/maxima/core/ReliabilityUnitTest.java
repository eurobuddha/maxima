package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.reliability.DedupCache;
import com.eurobuddha.maxima.core.reliability.Outbox;

import java.util.Collections;
import java.util.List;

/**
 * The reliability layer classic Maxima entirely lacks: dedup, a freshness
 * window, and a retrying outbox. Each rule is a promise to the user - "you will
 * not see a message twice", "a replay from last week is refused", "a send that
 * failed will be retried" - so each is tested as a rule.
 */
public class ReliabilityUnitTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== RELIABILITY ===\n");

        // ---- DedupCache ----
        DedupCache d = new DedupCache();
        long now = System.currentTimeMillis();
        if (d.check("0xA", now) == DedupCache.Verdict.ACCEPT) {
            ok("a first-seen msgid is ACCEPTED");
        } else {
            bad("first msgid not accepted");
        }
        if (d.check("0xA", now) == DedupCache.Verdict.DUPLICATE) {
            ok("the same msgid again is a DUPLICATE");
        } else {
            bad("duplicate not detected");
        }
        if (d.check("0xB", now - (6L * 60 * 60 * 1000 + 1000)) == DedupCache.Verdict.STALE) {
            ok("a timestamp older than the 6h window is STALE");
        } else {
            bad("stale not detected");
        }
        if (d.check("0xC", now + (6L * 60 * 60 * 1000 + 1000)) == DedupCache.Verdict.STALE) {
            ok("a timestamp too far in the FUTURE is also STALE (clock-skew abuse)");
        } else {
            bad("future stale not detected");
        }
        // seenBefore bypasses freshness (the mailbox-replay path)
        DedupCache d2 = new DedupCache();
        boolean first = d2.seenBefore("0xZ");
        boolean second = d2.seenBefore("0xZ");
        if (!first && second) {
            ok("seenBefore dedups without a freshness check (for fetched mail)");
        } else {
            bad("seenBefore: first=" + first + " second=" + second);
        }
        // eviction bound
        DedupCache d3 = new DedupCache(60_000, 100);
        for (int i = 0; i < 500; i++) {
            d3.check("0xK" + i, now);
        }
        if (d3.size() <= 100) {
            ok("the dedup cache is bounded (" + d3.size() + " <= 100), not a memory leak");
        } else {
            bad("dedup cache unbounded: " + d3.size());
        }

        // ---- Outbox ----
        Outbox ob = new Outbox();
        Outbox.Item it = ob.add("0xMID1", "0xPEER",
                Collections.singletonList("Mx...@1.2.3.4:9501"), "app", new byte[]{1});
        if (ob.size() == 1 && ob.contains("0xMID1")) {
            ok("a queued message is in the outbox");
        } else {
            bad("outbox add");
        }
        // due immediately on first add
        List<Outbox.Item> due = ob.due();
        if (due.size() == 1) {
            ok("a fresh item is due immediately");
        } else {
            bad("due: " + due.size());
        }
        // after a failure it backs off - not due again right away
        ob.failed(it, "connection refused");
        if (ob.due().isEmpty()) {
            ok("after a failure the item backs off (not immediately due again)");
        } else {
            bad("backoff not applied");
        }
        // acknowledge removes it
        if (ob.acknowledge("0xMID1") && ob.size() == 0 && !ob.contains("0xMID1")) {
            ok("acknowledge removes the item from the outbox");
        } else {
            bad("acknowledge did not remove");
        }
        // capacity + max attempts
        Outbox cap = new Outbox(3, 2);
        for (int i = 0; i < 10; i++) {
            cap.add("0xM" + i, "0xP", Collections.singletonList("Mx@1.1.1.1:1"), "a", new byte[0]);
        }
        if (cap.size() <= 3) {
            ok("the outbox is capacity-bounded (" + cap.size() + " <= 3)");
        } else {
            bad("outbox unbounded: " + cap.size());
        }
        // a message that fails past max attempts is dropped (failed returns whether to keep)
        Outbox mx = new Outbox(10, 2);
        Outbox.Item f = mx.add("0xF", "0xP", Collections.singletonList("Mx@1.1.1.1:1"), "a", new byte[0]);
        mx.failed(f, "e1");
        boolean keptAfter2 = mx.failed(f, "e2");
        if (!keptAfter2 && !mx.contains("0xF")) {
            ok("a message that exhausts its retry attempts is given up on and removed");
        } else {
            bad("max-attempts not enforced: kept=" + keptAfter2 + " contains=" + mx.contains("0xF"));
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Reliability holds.");
    }
}
