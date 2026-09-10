package com.eurobuddha.maxima.core.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Validate binary metadata using real files, including sparse lengths without large allocations. */
public class FileStoreBinaryReadTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path record(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(dir.resolve("items.d"))) {
            return files.findFirst().get();
        }
    }

    @Test public void aCallerBudgetBoundsRepresentableFilesAndAllowsExactFits() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        assertTrue(store.putBytes("items", "key", new byte[]{1, 2}));
        assertArrayEquals(new byte[]{1, 2}, store.getBytes("items", "key", 2));
        assertNull(store.getBytes("items", "key", 1));
        assertNull(store.getBytes("items", "missing", 2));
        assertTrue(store.putBytes("items", "empty", new byte[0]));
        assertArrayEquals(new byte[0], store.getBytes("items", "empty", 0));
        // Leave one record so the existing file locator remains unambiguous.
        store.removeBytes("items", "empty");
        Path path = record(dir);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(4L + 3 + (16 << 20));
        }
        assertNull(store.getBytes("items", "key", 2));
        assertEquals(4L + 3 + (16 << 20), Files.size(path));
        assertTrue(store.putBytes("items", "key", new byte[]{3}));
        assertArrayEquals(new byte[]{3}, store.getBytes("items", "key", 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeReadBudgetsAreRejected() throws Exception {
        new FileStore(tmp.newFolder()).getBytes("items", "key", -1);
    }

    @Test public void anotherKeysRecordCannotBeReadOrListedUnderTheWrongFilename() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        assertTrue(store.putBytes("items", "key", new byte[]{1})); Path path = record(dir);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(4); file.write("bad".getBytes(StandardCharsets.UTF_8));
        }
        byte[] damaged = Files.readAllBytes(path);
        assertNull(store.getBytes("items", "key"));
        assertTrue(store.listBytes("items").isEmpty());
        assertArrayEquals("reading never rewrites damaged evidence", damaged, Files.readAllBytes(path));
        assertTrue(store.putBytes("items", "key", new byte[]{2}));
        assertArrayEquals(new byte[]{2}, store.getBytes("items", "key"));
        assertEquals(Collections.singletonMap("key", 1), store.listBytes("items"));
    }

    @Test public void oversizedValuesAreRejectedBeforeNarrowingTheirLength() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        assertTrue(store.putBytes("items", "key", new byte[]{1})); Path path = record(dir);
        long length = 4L + 3 + Integer.MAX_VALUE + 1;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength(length); }
        assertNull("do not cast an unrepresentable size into an array length", store.getBytes("items", "key"));
        assertTrue("a listing cannot report a negative value length", store.listBytes("items").isEmpty());
        assertEquals(length, Files.size(path));
    }

    @Test public void anInflatedKeyHeaderCannotControlTheReadAllocation() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        assertTrue(store.putBytes("items", "key", new byte[]{1})); Path path = record(dir);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.writeInt(1 << 20); file.setLength(4L + (1 << 20) + 1);
        }
        assertNull("stored key length must match the requested key", store.getBytes("items", "key"));
    }

    @Test public void emptyKeysEmptyValuesAndUnicodeSurviveListingAndReopen() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        String key = "key\t\n\u00e9\ud83d\ude3a";
        assertTrue(store.putBytes("items", "", new byte[0]));
        assertTrue(store.putBytes("items", key, new byte[]{0, -1}));
        FileStore reopened = new FileStore(dir.toFile());
        Map<String, Integer> expected = new HashMap<>(); expected.put("", 0); expected.put(key, 2);
        assertEquals(expected, reopened.listBytes("items"));
        assertArrayEquals(new byte[0], reopened.getBytes("items", ""));
        assertArrayEquals(new byte[]{0, -1}, reopened.getBytes("items", key));
    }

    @Test public void invalidUtf8CannotInventAReplacementKeyDuringListing() throws Exception {
        Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
        String key = "\ufffd(";
        assertTrue(store.putBytes("items", key, new byte[]{1})); Path path = record(dir);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(2); out.write(new byte[]{(byte) 0xc3, '('}); out.writeByte(1);
        }
        assertTrue(store.listBytes("items").isEmpty());
        assertNull(store.getBytes("items", key));
    }

    @Test public void truncatedHeadersAndKeysNeverProduceRecords() throws Exception {
        for (byte[] bytes : Arrays.asList(new byte[]{0, 0, 0}, new byte[]{0, 0, 0, 3, 'k'})) {
            Path dir = tmp.newFolder().toPath(); FileStore store = new FileStore(dir.toFile());
            store.putBytes("items", "key", new byte[]{1}); Files.write(record(dir), bytes);
            assertNull(store.getBytes("items", "key")); assertTrue(store.listBytes("items").isEmpty());
        }
    }
}
