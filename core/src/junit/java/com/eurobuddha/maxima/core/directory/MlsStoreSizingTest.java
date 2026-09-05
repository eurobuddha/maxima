package com.eurobuddha.maxima.core.directory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** The directory's memory must be bounded by the heap it runs in, not by a flat constant. */
public class MlsStoreSizingTest {

    @Test
    public void theCapFollowsTheHeap() {
        assertEquals(6144, MlsStore.sizedForHeap(96L << 20));                       // 96 MB relay: 24 MB of proofs
        assertEquals(MlsStore.MIN_ENTRIES, MlsStore.sizedForHeap(16L << 20));        // tiny heap: the floor
        assertTrue(MlsStore.sizedForHeap(512L << 20) > MlsStore.MIN_ENTRIES);
        assertTrue(MlsStore.sizedForHeap(512L << 20) < MlsStore.DEFAULT_MAX_ENTRIES);
        assertEquals(MlsStore.DEFAULT_MAX_ENTRIES, MlsStore.sizedForHeap(64L << 30));  // 64 GB box
    }

    @Test
    public void theCapEvictsLeastRecentlyUsed() {
        MlsStore s = new MlsStore();
        s.setMaxEntries(2);
        s.setOpenResolve(true);
        s.put("0xA", Collections.singletonList("a@h:1"), Collections.emptyList());
        s.put("0xB", Collections.singletonList("b@h:1"), Collections.emptyList());
        assertNotNull(s.get("0xA", "0xANYONE"));   // A is now the most recently used
        s.put("0xC", Collections.singletonList("c@h:1"), Collections.emptyList());
        assertEquals(2, s.size());
        assertNull("B was least recently used", s.get("0xB", "0xANYONE"));
        assertNotNull(s.get("0xA", "0xANYONE"));
        assertNotNull(s.get("0xC", "0xANYONE"));
    }

    @Test
    public void openResolveDoesNotRetainReaderLists() {
        MlsStore open = new MlsStore();
        open.setOpenResolve(true);
        open.put("0xA", Collections.singletonList("a@h:1"), Arrays.asList("0xR1", "0xR2"));
        assertEquals(0, open.peek("0xA").allowedReaders.size());
        assertNotNull("still resolves for anyone", open.get("0xA", "0xSTRANGER"));

        MlsStore closed = new MlsStore();
        closed.put("0xA", Collections.singletonList("a@h:1"), Arrays.asList("0xR1", "0xR2"));
        assertEquals(2, closed.peek("0xA").allowedReaders.size());
        assertNotNull(closed.get("0xA", "0xR1"));
        assertNull(closed.get("0xA", "0xSTRANGER"));
    }
}
