package com.eurobuddha.maxima.core.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Writer/reader compatibility and preservation of damaged evidence, using real temporary files. */
public class FileStoreFormatTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void emptyAndEscapedKeysRoundTripThroughReopenAndRemoval() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        String key = "key\t\n\r\\\u00e9\ud83d\ude3a", value = "value\t\n\r\\\u00e9\ud83d\ude3a";
        store.put("records", "", ""); store.put("records", key, value);
        FileStore reopened = new FileStore(dir.toFile());
        assertEquals("empty keys and values are valid writer output", "", reopened.get("records", ""));
        assertEquals(value, reopened.get("records", key));
        assertEquals(2, reopened.all("records").size());
        reopened.remove("records", "");
        assertEquals(Collections.singletonMap(key, value), new FileStore(dir.toFile()).all("records"));
    }

    @Test public void emptyLogEntriesAndUnicodeRoundTripThroughRewriteAndAppend() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        List<String> entries = new ArrayList<>(Arrays.asList("", "\u00e9\ud83d\ude3a\t\\", "", "\r\n"));
        store.rewrite("history", entries);
        assertEquals(entries, new FileStore(dir.toFile()).read("history"));
        store.append("history", ""); entries.add("");
        assertEquals(entries, new FileStore(dir.toFile()).read("history"));
    }

    @Test public void malformedRowsCannotBeSilentlyDroppedOrRewritten() throws Exception {
        for (String bad : Arrays.asList("missing separator\n", "bad\\q\tvalue\n", "key\tbad\\q\n", "key\tunfinished\\\n")) {
            damagedCollection(("saved\tkeep\n" + bad + "other\talso keep\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void invalidUtf8CannotBecomeReplacementCharactersInACollection() throws Exception {
        damagedCollection(new byte[]{'k', '\t', (byte) 0xc3, '(', '\n'});
    }

    private void damagedCollection(byte[] bytes) throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        Path path = dir.resolve("records.tsv"); Files.write(path, bytes);
        assertThrows(UncheckedIOException.class, () -> store.all("records"));
        assertThrows(UncheckedIOException.class, () -> store.put("records", "new", "rejected"));
        assertArrayEquals("do not rewrite damaged evidence", bytes, Files.readAllBytes(path));
        // A failed parse must not cache its valid prefix either. Repair and retry this instance.
        Files.write(path, "saved\trepaired\nother\tkept\n".getBytes(StandardCharsets.UTF_8));
        store.put("records", "new", "accepted");
        assertEquals("repaired", store.get("records", "saved"));
        assertEquals("kept", store.get("records", "other"));
        assertEquals(3, new FileStore(dir.toFile()).all("records").size());
    }

    @Test public void invalidUtf8AndEscapesInLogsAreReportedWithoutReturningAPartialLog() throws Exception {
        for (byte[] bad : Arrays.asList(new byte[]{'k', (byte) 0xc3, '(', '\n'},
                "saved\ninvalid\\q\n".getBytes(StandardCharsets.UTF_8),
                "saved\nunfinished\\\n".getBytes(StandardCharsets.UTF_8))) {
            Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
            Path path = dir.resolve("history.log"); Files.write(path, bad);
            assertThrows(UncheckedIOException.class, () -> store.read("history"));
            assertArrayEquals(bad, Files.readAllBytes(path));
            store.rewrite("history", Arrays.asList("repaired\t\\", ""));
            assertEquals(Arrays.asList("repaired\t\\", ""), store.read("history"));
        }
    }
}
