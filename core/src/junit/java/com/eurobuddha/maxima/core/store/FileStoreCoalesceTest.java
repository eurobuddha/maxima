package com.eurobuddha.maxima.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

/** A coalescing store writes a burst once, on its timer or on demand - never per record. */
public class FileStoreCoalesceTest {

    static File tmp() {
        return new File(System.getProperty("java.io.tmpdir"), "maxima-store-" + System.nanoTime());
    }

    @Test
    public void aBurstLandsOnceAfterTheDelay() throws Exception {
        File dir = tmp();
        FileStore s = FileStore.coalescing(dir, 300);
        for (int i = 0; i < 200; i++) {
            s.put("messages", "m" + i, "body " + i);
        }
        File f = new File(dir, "messages.tsv");
        assertFalse("nothing written yet: the burst is being coalesced", f.exists());
        assertEquals("reads see the writes at once", "body 7", s.get("messages", "m7"));
        long until = System.currentTimeMillis() + 3000;
        while (!f.exists() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertTrue("written after the delay", f.exists());
        // a fresh store sees every record of the burst
        FileStore again = new FileStore(dir);
        assertEquals(200, again.all("messages").size());
        assertEquals("body 199", again.get("messages", "m199"));
    }

    @Test
    public void flushForcesTheBurstOutImmediately() throws Exception {
        File dir = tmp();
        FileStore s = FileStore.coalescing(dir, 60_000);   // a timer that would not fire in this test
        s.put("messages", "a", "1");
        s.put("messages", "b", "2");
        File f = new File(dir, "messages.tsv");
        assertFalse(f.exists());
        s.flush();   // what the engine does before it acknowledges held mail
        assertTrue(f.exists());
        assertEquals(2, new FileStore(dir).all("messages").size());
    }
}
