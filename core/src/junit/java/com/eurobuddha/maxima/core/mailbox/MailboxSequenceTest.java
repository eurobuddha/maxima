package com.eurobuddha.maxima.core.mailbox;

import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

/** A delayed cumulative ACK must never address a newer incarnation of a mailbox. */
public class MailboxSequenceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void emptiedRecipientsDoNotReuseCursorsInMemoryOrOnDisk() throws Exception {
        for (boolean durable : new boolean[]{false, true}) {
            Mailbox box = new Mailbox();
            if (durable) box.setStore(new FileStore(tmp.newFolder()));
            for (int i = 0; i < 3; i++) {
                assertEquals(Mailbox.Result.STORED, box.store("recipient", new byte[]{1}));
                long old = box.fetch("recipient", 0, 1).get(0).sequence;
                assertEquals(1, box.acknowledge("recipient", old));
                assertEquals(0, box.boxCount());
                assertEquals(Mailbox.Result.STORED, box.store("recipient", new byte[]{2}));
                assertEquals("the duplicate old ACK cannot delete new mail", 0, box.acknowledge("recipient", old));
                assertEquals(1, box.fetch("recipient", old, 1).size());
                assertArrayEquals(new byte[]{2}, box.fetch("recipient", old, 1).get(0).ciphertext());
                box.acknowledge("recipient", box.highestSequence("recipient"));
            }
        }
    }

    @Test public void evictionDoesNotResetARecipientsSequence() {
        Mailbox box = new Mailbox(Mailbox.DEFAULT_TTL_MS, 10, 1000, 1, 1000);
        box.store("first", new byte[]{1}); long old = box.highestSequence("first");
        box.store("other", new byte[]{2}); assertEquals(0, box.count("first"));
        box.store("first", new byte[]{3});
        assertEquals(0, box.acknowledge("first", old));
        assertEquals(1, box.fetch("first", old, 1).size());
        assertEquals(1, box.boxCount());
    }

    @Test public void anEmptyMailboxRemembersIssuedCursorsAcrossRestart() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true);
        Mailbox before = new Mailbox(); before.setStore(store);
        before.store("recipient", new byte[]{1}); long old = before.highestSequence("recipient");
        before.acknowledge("recipient", old);
        // No explicit flush: the sequence reservation must precede successful publication.
        Mailbox after = new Mailbox(); after.setStore(new FileStore(dir.toFile()));
        assertEquals(Mailbox.Result.STORED, after.store("recipient", new byte[]{2}));
        assertEquals(0, after.acknowledge("recipient", old));
        assertEquals(1, after.fetch("recipient", old, 1).size());
    }

    @Test public void startupPruningPersistsOldCursorsBeforeDeletingTheirLastRecord() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        assertTrue(store.putBytes("mailitems", "RECIPIENT|5000|1|old", new byte[]{1}));
        Mailbox firstBoot = new Mailbox(); firstBoot.setStore(store);
        assertEquals(0, firstBoot.count("recipient"));
        assertTrue(store.listBytes("mailitems").isEmpty());
        Mailbox secondBoot = new Mailbox(); secondBoot.setStore(new FileStore(dir.toFile()));
        secondBoot.store("recipient", new byte[]{2});
        assertEquals(0, secondBoot.acknowledge("recipient", 5000));
        assertEquals(1, secondBoot.fetch("recipient", 5000, 1).size());
    }

    @Test public void failedReservationFlushPublishesNothingAndCanBeRetried() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true); Mailbox box = new Mailbox(); box.setStore(store);
        Path blocked = dir.resolve("mailseq.tsv"); Files.createDirectory(blocked);
        Path child = blocked.resolve("blocker"); Files.write(child, new byte[]{1});
        assertEquals(Mailbox.Result.IO_ERROR, box.store("recipient", new byte[]{1}));
        assertEquals(0, box.totalItems()); assertEquals(0, box.totalBytes());
        assertTrue(store.listBytes("mailitems").isEmpty());
        Files.delete(child); Files.delete(blocked);
        assertEquals(Mailbox.Result.STORED, box.store("recipient", new byte[]{1}));
        long seq = box.highestSequence("recipient"); box.acknowledge("recipient", seq);
        Mailbox reopened = new Mailbox(); reopened.setStore(new FileStore(dir.toFile()));
        reopened.store("recipient", new byte[]{2});
        assertEquals(1, reopened.fetch("recipient", seq, 1).size());
    }

    @Test public void invalidReservationsCannotResetTheAllocator() throws Exception {
        for (String invalid : new String[]{"broken", "-1", "9223372036854775808"}) {
            FileStore store = new FileStore(tmp.newFolder()); store.put("mailseq", "reserved", invalid);
            Mailbox box = new Mailbox();
            assertThrows(IllegalStateException.class, () -> box.setStore(store));
            assertEquals(Mailbox.Result.IO_ERROR, box.store("recipient", new byte[]{1}));
            assertEquals(invalid, store.get("mailseq", "reserved"));
            assertTrue(store.listBytes("mailitems").isEmpty());
        }
    }

    @Test public void exhaustedSequencesNeverWrapOrReuseAfterRestart() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        store.put("mailseq", "reserved", Long.toString(Long.MAX_VALUE - 1));
        Mailbox box = new Mailbox(); box.setStore(store);
        assertEquals(Mailbox.Result.STORED, box.store("recipient", new byte[]{1}));
        assertEquals(Long.MAX_VALUE, box.highestSequence("recipient"));
        assertEquals(Mailbox.Result.IO_ERROR, box.store("recipient", new byte[]{2}));
        box.acknowledge("recipient", Long.MAX_VALUE);
        Mailbox reopened = new Mailbox(); reopened.setStore(new FileStore(dir.toFile()));
        assertEquals(Mailbox.Result.IO_ERROR, reopened.store("recipient", new byte[]{3}));
        assertEquals(0, reopened.totalItems());
    }

    @Test public void recipientChurnUsesBoundedMetadataAndAmortizedReservations() throws Exception {
        Path dir = tmp.newFolder().toPath();
        MailboxStoreTest.GatedStore store = new MailboxStoreTest.GatedStore(dir.toFile());
        Mailbox box = new Mailbox(Mailbox.DEFAULT_TTL_MS, 1, 100, 1, 100); box.setStore(store);
        long last = 0;
        for (int i = 0; i < 5000; i++) {
            String key = "recipient-" + i;
            assertEquals(Mailbox.Result.STORED, box.store(key, new byte[]{1}));
            long next = box.highestSequence(key); assertTrue(next > last); last = next;
            assertEquals(1, box.acknowledge(key, next));
        }
        assertEquals(0, box.boxCount()); assertEquals(0, box.totalItems());
        assertEquals("no permanent per-recipient history", 1, store.all("mailseq").size());
        assertTrue("reservations are batched, not fsynced for every item", store.keyedWrites <= 4);
        Mailbox reopened = new Mailbox(); reopened.setStore(new FileStore(dir.toFile()));
        assertEquals(Mailbox.Result.STORED, reopened.store("recipient-4999", new byte[]{2}));
        assertEquals(1, reopened.fetch("recipient-4999", last, 1).size());
    }
}
