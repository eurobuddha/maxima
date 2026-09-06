package com.eurobuddha.maxima.core.mailbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.util.List;

import org.junit.Test;

/**
 * Held mail is one file per item and NOT in the heap: the relay's memory must stop scaling
 * with what it holds. These pin the storage shape, the reload, the migration from the old
 * hex-in-a-TSV records, and the new global item cap.
 */
public class MailboxStoreTest {

    static File tmp(String zTag) {
        return new File(System.getProperty("java.io.tmpdir"), "maxima-mail-" + zTag + "-" + System.nanoTime());
    }

    static int files(File dir) {
        File[] f = new File(dir, "mailitems.d").listFiles((d, n) -> !n.endsWith(".tmp"));
        return f == null ? 0 : f.length;
    }

    static final String KEY = "0xABCDEF0123456789";

    @Test
    public void itemsAreOneFileEachAndReadFromDiskOnDemand() throws Exception {
        File dir = tmp("files");
        Mailbox m = new Mailbox();
        m.setStore(new FileStore(dir));
        byte[] a = "first".getBytes();
        byte[] b = "second".getBytes();
        assertEquals(Mailbox.Result.STORED, m.store(KEY, a));
        assertEquals(Mailbox.Result.STORED, m.store(KEY, b));
        assertEquals(2, files(dir));
        assertFalse("no keyed TSV for mail any more", new File(dir, "mailbox.tsv").exists());
        List<Mailbox.Item> held = m.fetch(KEY, 0, 10);
        assertEquals(2, held.size());
        assertEquals(a.length, held.get(0).size);
        assertArrayEquals(a, held.get(0).ciphertext());
        assertArrayEquals(b, held.get(1).ciphertext());
        assertEquals(2, m.totalItems());
        assertEquals(a.length + b.length, m.totalBytes());
    }

    @Test
    public void heldMailSurvivesRestartAndAcknowledgeDeletesTheFile() throws Exception {
        File dir = tmp("reload");
        Mailbox m1 = new Mailbox();
        m1.setStore(new FileStore(dir));
        m1.store(KEY, "held".getBytes());
        m1.store(KEY, "more".getBytes());
        long seq = m1.highestSequence(KEY);

        Mailbox m2 = new Mailbox();
        m2.setStore(new FileStore(dir));
        List<Mailbox.Item> after = m2.fetch(KEY, 0, 10);
        assertEquals(2, after.size());
        assertEquals("held", new String(after.get(0).ciphertext()));
        assertEquals("more", new String(after.get(1).ciphertext()));
        assertEquals(Mailbox.Result.STORED, m2.store(KEY, "third".getBytes()));
        assertEquals("sequence continues after a reload", seq + 1, m2.highestSequence(KEY));

        m2.acknowledge(KEY, seq);
        assertEquals(1, files(dir));
        Mailbox m3 = new Mailbox();
        m3.setStore(new FileStore(dir));
        assertEquals(1, m3.count(KEY));
        assertEquals("third", new String(m3.fetch(KEY, 0, 10).get(0).ciphertext()));
    }

    @Test
    public void legacyHexRecordsAreMigratedOnce() throws Exception {
        File dir = tmp("legacy");
        FileStore store = new FileStore(dir);
        byte[] ct = "old style".getBytes();
        long storedAt = System.currentTimeMillis() - 1000;
        store.put("mailbox", KEY + "|7", storedAt + "|" + new MiniData(ct).to0xString());

        Mailbox m = new Mailbox();
        m.setStore(store);
        assertTrue("old collection emptied", store.all("mailbox").isEmpty());
        assertEquals(1, files(dir));
        List<Mailbox.Item> held = m.fetch(KEY, 0, 10);
        assertEquals(1, held.size());
        assertEquals(7, held.get(0).sequence);
        assertEquals(storedAt, held.get(0).storedAt);
        assertArrayEquals(ct, held.get(0).ciphertext());
        assertEquals(Mailbox.Result.STORED, m.store(KEY, "new".getBytes()));
        assertEquals(8, m.highestSequence(KEY));
    }

    @Test
    public void expiredItemsAreDroppedOnReloadWithoutReadingThem() throws Exception {
        File dir = tmp("expire");
        Mailbox m1 = new Mailbox(60_000, 200, 1 << 20);
        m1.setStore(new FileStore(dir));
        m1.store(KEY, "fresh".getBytes());
        assertEquals(1, files(dir));
        Mailbox m2 = new Mailbox(-1, 200, 1 << 20);   // everything is already too old
        m2.setStore(new FileStore(dir));
        assertEquals(0, m2.count(KEY));
        assertEquals(0, files(dir));
    }

    @Test
    public void theGlobalItemCapEvictsWholeBoxesLeastRecentlyActiveFirst() {
        Mailbox m = new Mailbox(Mailbox.DEFAULT_TTL_MS, 200, 1 << 20, 100, 1 << 30, 4);
        assertEquals(Mailbox.Result.STORED, m.store("0xA", "1".getBytes()));
        assertEquals(Mailbox.Result.STORED, m.store("0xA", "2".getBytes()));
        assertEquals(Mailbox.Result.STORED, m.store("0xB", "3".getBytes()));
        assertEquals(Mailbox.Result.STORED, m.store("0xB", "4".getBytes()));
        assertEquals(4, m.totalItems());
        // a fifth item for a NEW key: box A (least recently active) is evicted to make room
        assertEquals(Mailbox.Result.STORED, m.store("0xC", "5".getBytes()));
        assertEquals(0, m.count("0xA"));
        assertEquals(2, m.count("0xB"));
        assertEquals(3, m.totalItems());
    }

    @Test
    public void memoryOnlyStoreKeepsBytesOnTheItem() {
        Mailbox m = new Mailbox();
        m.store(KEY, "in memory".getBytes());
        assertEquals("in memory", new String(m.fetch(KEY, 0, 1).get(0).ciphertext()));
        assertEquals(1, m.acknowledge(KEY, 1));
        assertTrue(m.fetch(KEY, 0, 1).isEmpty());
    }

    /** A Store whose durable write can be held open or made to fail, wrapped around a real one. */
    static final class GatedStore implements com.eurobuddha.maxima.core.store.Store {
        final FileStore inner;
        volatile java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        volatile java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(0);
        volatile boolean fail;

        GatedStore(File zDir) {
            inner = new FileStore(zDir);
        }

        @Override public void put(String c, String k, String v) { inner.put(c, k, v); }
        @Override public String get(String c, String k) { return inner.get(c, k); }
        @Override public void remove(String c, String k) { inner.remove(c, k); }
        @Override public java.util.Map<String, String> all(String c) { return inner.all(c); }
        @Override public void append(String l, String line) { inner.append(l, line); }
        @Override public List<String> read(String l) { return inner.read(l); }
        @Override public void rewrite(String l, List<String> lines) { inner.rewrite(l, lines); }
        @Override public void flush() { inner.flush(); }
        @Override public byte[] getBytes(String c, String k) { return inner.getBytes(c, k); }
        @Override public void removeBytes(String c, String k) { inner.removeBytes(c, k); }
        @Override public java.util.Map<String, Integer> listBytes(String c) { return inner.listBytes(c); }

        @Override
        public boolean putBytes(String c, String k, byte[] v) {
            entered.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return !fail && inner.putBytes(c, k, v);
        }
    }

    @Test
    public void theDurableWriteHappensOutsideTheMailboxMonitor() throws Exception {
        File dir = tmp("outside");
        GatedStore gated = new GatedStore(dir);
        Mailbox m = new Mailbox();
        m.setStore(gated);
        gated.entered = new java.util.concurrent.CountDownLatch(1);
        gated.release = new java.util.concurrent.CountDownLatch(1);
        Mailbox.Result[] out = new Mailbox.Result[1];
        Thread writer = new Thread(() -> out[0] = m.store(KEY, "held".getBytes()));
        writer.start();
        assertTrue("the write is in progress", gated.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // While that fsync is "running", every other mailbox operation must still get the monitor.
        int[] seen = new int[1];
        Thread reader = new Thread(() -> {
            seen[0] = m.count("0xOTHER") + m.fetch(KEY, 0, 10).size() + m.boxCount();
            m.store("0xOTHER", "someone else's mail".getBytes());   // reserves, then blocks in the store too
        });
        reader.start();
        long t0 = System.currentTimeMillis();
        while (m.totalItems() < 2 && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(10);
        }
        assertEquals("a second store reserved its slot while the first write was still on disk", 2, m.totalItems());
        assertEquals("the pending item is not visible to fetch yet", 0, m.fetch(KEY, 0, 10).size());

        gated.release.countDown();
        writer.join(5000);
        reader.join(5000);
        assertEquals(Mailbox.Result.STORED, out[0]);
        assertEquals(2, files(dir));
        List<Mailbox.Item> held = m.fetch(KEY, 0, 10);
        assertEquals(1, held.size());
        assertArrayEquals("held".getBytes(), held.get(0).ciphertext());
        assertEquals(1, m.highestSequence(KEY));
    }

    @Test
    public void aFailedDurableWriteIsReportedAndHoldsNothing() throws Exception {
        File dir = tmp("ioerror");
        GatedStore gated = new GatedStore(dir);
        gated.fail = true;
        Mailbox m = new Mailbox();
        m.setStore(gated);
        assertEquals(Mailbox.Result.IO_ERROR, m.store(KEY, "lost".getBytes()));
        assertEquals("the reservation was undone", 0, m.totalItems());
        assertEquals(0, m.totalBytes());
        assertEquals(0, files(dir));
        gated.fail = false;
        assertEquals("the disk came back: the retry stores", Mailbox.Result.STORED, m.store(KEY, "lost".getBytes()));
        assertEquals(1, m.totalItems());
        assertEquals(1, files(dir));
    }

    @Test
    public void aBoxEvictedDuringItsWriteLeavesNoOrphanFile() throws Exception {
        File dir = tmp("evictmid");
        GatedStore gated = new GatedStore(dir);
        // Two boxes max: the third key evicts the least recently active box.
        Mailbox m = new Mailbox(Mailbox.DEFAULT_TTL_MS, 10, 1 << 20, 2, 1 << 20);
        m.setStore(gated);
        gated.release = new java.util.concurrent.CountDownLatch(0);
        assertEquals(Mailbox.Result.STORED, m.store("0xB", "b".getBytes()));
        gated.entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch relA = new java.util.concurrent.CountDownLatch(1);
        gated.release = relA;   // A's write will hold on this latch
        Mailbox.Result[] out = new Mailbox.Result[1];
        Thread writer = new Thread(() -> out[0] = m.store("0xA", "a-in-flight".getBytes()));
        writer.start();
        assertTrue(gated.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // Touch B (so A is the least recently active), then add C: with the cap at 2, A is
        // evicted while its write is still in flight. Later writes must not block.
        Thread.sleep(5);
        gated.release = new java.util.concurrent.CountDownLatch(0);
        assertEquals(Mailbox.Result.STORED, m.store("0xB", "b2".getBytes()));
        assertEquals(Mailbox.Result.STORED, m.store("0xC", "c".getBytes()));
        relA.countDown();
        writer.join(5000);
        assertEquals("the evicted box's in-flight item is refused", Mailbox.Result.QUOTA_COUNT, out[0]);
        assertEquals("b, b2, c - and no orphan for A", 3, files(dir));
        assertEquals(3, m.totalItems());
        assertEquals(0, m.count("0xA"));
    }
}
