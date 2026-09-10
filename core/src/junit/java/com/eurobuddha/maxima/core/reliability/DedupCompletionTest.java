package com.eurobuddha.maxima.core.reliability;

import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class DedupCompletionTest {
    @Test public void outcomesShareTheDedupBudgetAndForgetAllowsRetry() {
        DedupCache cache = new DedupCache(60_000, 2); long now = System.currentTimeMillis();
        CompletableFuture<Void> failed = new CompletableFuture<>(); failed.completeExceptionally(new IllegalStateException("uncertain RPC"));
        assertEquals(DedupCache.Verdict.ACCEPT, cache.check("one", now)); cache.trackCompletion("one", failed);
        assertEquals(DedupCache.Verdict.DUPLICATE, cache.check("one", now)); assertSame(failed, cache.completion("one"));
        cache.check("two", now); cache.check("three", now); assertNull(cache.completion("one")); assertEquals(2, cache.size());
        cache.trackCompletion("three", failed); cache.forget("three"); assertNull(cache.completion("three"));
        assertEquals(DedupCache.Verdict.ACCEPT, cache.check("three", now)); cache.trackCompletion("three", failed);
        cache.clear(); assertNull(cache.completion("three")); assertEquals(0, cache.size());
    }
}
