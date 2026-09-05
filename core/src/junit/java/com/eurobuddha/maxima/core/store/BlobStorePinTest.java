package com.eurobuddha.maxima.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

/** The owner's own published media is pinned: the shelf evicts viewed media around it, never it. */
public class BlobStorePinTest {

    static File tmp() {
        return new File(System.getProperty("java.io.tmpdir"), "maxima-blobs-" + System.nanoTime());
    }

    static byte[] chunk(int zFill, int zLen) {
        byte[] b = new byte[zLen];
        java.util.Arrays.fill(b, (byte) zFill);
        return b;
    }

    @Test
    public void evictionTakesLooseChunksAndNeverPinnedOnes() throws Exception {
        BlobStore s = new BlobStore(tmp(), 3000);   // room for three 1000-byte chunks
        String mine = s.put(chunk(1, 1000), true);
        String viewedA = s.put(chunk(2, 1000));
        Thread.sleep(20);
        String viewedB = s.put(chunk(3, 1000));
        assertEquals(3000, s.bytes());
        String viewedC = s.put(chunk(4, 1000));   // full: the least recently fetched loose one goes
        assertNotNull(s.get(mine));
        assertNull("oldest viewed chunk evicted", s.get(viewedA));
        assertNotNull(s.get(viewedB));
        assertNotNull(s.get(viewedC));
        assertTrue(s.isPinned(mine));
        assertFalse(s.isPinned(viewedB));
        assertEquals(1000, s.pinnedBytes());
    }

    @Test
    public void pinnedChunksSurviveAReopenAndCountTowardTheCap() throws Exception {
        File dir = tmp();
        BlobStore s = new BlobStore(dir, 3000);
        String mine = s.put(chunk(1, 1000), true);
        s.put(chunk(2, 1000));
        BlobStore again = new BlobStore(dir, 3000);
        assertEquals(2000, again.bytes());
        assertEquals(1000, again.pinnedBytes());
        assertTrue(again.isPinned(mine));
        assertNotNull(again.get(mine));
        assertEquals(2, again.count());
    }

    @Test
    public void aShelfFullOfOwnMediaRefusesLoudlyInsteadOfDroppingIt() throws Exception {
        BlobStore s = new BlobStore(tmp(), 2000);
        s.put(chunk(1, 1000), true);
        s.put(chunk(2, 1000));           // a viewed chunk
        s.put(chunk(3, 1000), true);     // pin: evicts the viewed chunk to make room
        assertEquals(2000, s.pinnedBytes());
        try {
            s.put(chunk(4, 1000));
            fail("nothing left to evict: must refuse, not drop a pinned chunk");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("full"));
        }
        assertEquals(2, s.count());
    }

    @Test
    public void rePuttingALooseChunkAsPinnedMovesItUnderThePin() throws Exception {
        BlobStore s = new BlobStore(tmp(), 5000);
        String id = s.put(chunk(9, 500));
        assertFalse(s.isPinned(id));
        assertEquals(id, s.put(chunk(9, 500), true));
        assertTrue(s.isPinned(id));
        assertEquals(500, s.pinnedBytes());
        assertEquals(500, s.bytes());
        assertNotNull(s.get(id));
    }
}
