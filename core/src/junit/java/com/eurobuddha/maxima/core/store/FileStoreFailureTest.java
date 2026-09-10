package com.eurobuddha.maxima.core.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Real filesystem failures, then retry and reopen; no live account state. */
public class FileStoreFailureTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void failedFlushReportsFailureAndRetainsEveryUnwrittenCollection() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true);
        store.put("first", "key", "one");
        store.put("second", "key", "two");
        Path blocked = dir.resolve("first.tsv");
        Files.createDirectory(blocked);
        Files.write(blocked.resolve("blocker"), new byte[]{1});
        assertThrows(UncheckedIOException.class, store::flush);
        Files.delete(blocked.resolve("blocker"));
        Files.delete(blocked);
        store.flush(); // no new put should be necessary to recover
        FileStore reopened = new FileStore(dir.toFile());
        assertEquals("one", reopened.get("first", "key"));
        assertEquals("two", reopened.get("second", "key"));
        try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
            assertEquals("only the completed snapshots remain", 2, paths.count());
        }
    }

    @Test public void failedImmediatePutCanBeRecoveredByFlush() throws Exception {
        retryImmediateMutation(false);
    }

    @Test public void failedImmediateRemovalCanBeRecoveredByFlush() throws Exception {
        retryImmediateMutation(true);
    }

    private void retryImmediateMutation(boolean remove) throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        Path saved = dir.resolveSibling("saved");
        FileStore store = new FileStore(dir.toFile());
        store.put("messages", "key", "old");
        Files.move(dir, saved);
        Files.write(dir, new byte[]{1}); // same portable obstruction as pairing persistence tests
        try {
            assertThrows(UncheckedIOException.class, () -> {
                if (remove) store.remove("messages", "key");
                else store.put("messages", "key", "new");
            });
            assertEquals("previous snapshot survives", "old",
                    new FileStore(saved.toFile()).get("messages", "key"));
        } finally {
            Files.delete(dir);
            Files.move(saved, dir);
        }
        store.flush();
        assertEquals(remove ? null : "new", new FileStore(dir.toFile()).get("messages", "key"));
    }

    @Test public void failedReplacementNeverDeletesAnExistingDirectory() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true);
        store.put("messages", "key", "new");
        Path target = Files.createDirectory(dir.resolve("messages.tsv"));
        assertThrows(UncheckedIOException.class, store::flush);
        assertTrue("do not unlink an unexpected target to force replacement", Files.isDirectory(target));
        Files.delete(target);
        store.flush();
        assertEquals("new", new FileStore(dir.toFile()).get("messages", "key"));
    }

    @Test public void rewrittenLogsReportFailureAndStillRoundTripEscaping() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        Path target = Files.createDirectory(dir.resolve("history.log"));
        Files.write(target.resolve("blocker"), new byte[]{1});
        assertThrows(UncheckedIOException.class, () -> store.rewrite("history", Arrays.asList("line")));
        Files.delete(target.resolve("blocker"));
        Files.delete(target);
        java.util.List<String> lines = Arrays.asList("one\ntwo", "tab\tand\\slash\r");
        store.rewrite("history", lines);
        assertEquals(lines, new FileStore(dir.toFile()).read("history"));
    }

    @Test public void replacementKeepsFormatAndCreatesPrivateFiles() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        store.put("messages", "key\t\\", "old");
        store.put("messages", "key\t\\", "new\n\r\t\\");
        assertEquals("new\n\r\t\\", new FileStore(dir.toFile()).get("messages", "key\t\\"));
        Path file = dir.resolve("messages.tsv");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }

    @Test public void binaryReplacementNeverDeletesAnExistingDirectory() throws Exception {
        Path dir = tmp.newFolder("binary").toPath();
        FileStore store = new FileStore(dir.toFile());
        assertTrue(store.putBytes("items", "key", new byte[]{1}));
        Path target;
        try (java.util.stream.Stream<Path> paths = Files.list(dir.resolve("items.d"))) {
            target = paths.findFirst().get();
        }
        Files.delete(target);
        Files.createDirectory(target);
        assertFalse(store.putBytes("items", "key", new byte[]{2}));
        assertTrue("do not unlink an unexpected target", Files.isDirectory(target));
        try (java.util.stream.Stream<Path> paths = Files.list(target.getParent())) {
            assertEquals("failed staging is cleaned up", 1, paths.count());
        }
        Files.delete(target);
        assertTrue(store.putBytes("items", "key", new byte[]{2}));
        assertArrayEquals(new byte[]{2}, new FileStore(dir.toFile()).getBytes("items", "key"));
    }

    @Test public void binaryReplacementDoesNotTouchAnExistingStagingFile() throws Exception {
        Path dir = tmp.newFolder("binary").toPath();
        FileStore store = new FileStore(dir.toFile());
        String key = "key\twith\nUnicode-\u00e9";
        assertTrue(store.putBytes("items", key, new byte[]{1}));
        Path target;
        try (java.util.stream.Stream<Path> paths = Files.list(dir.resolve("items.d"))) {
            target = paths.findFirst().get();
        }
        Path staging = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(staging, new byte[]{9});
        byte[] value = new byte[]{0, 1, -1, 42};
        assertTrue(store.putBytes("items", key, value));
        assertArrayEquals("another writer's staging file survives", new byte[]{9}, Files.readAllBytes(staging));
        FileStore reopened = new FileStore(dir.toFile());
        assertArrayEquals(value, reopened.getBytes("items", key));
        assertEquals(java.util.Collections.singletonMap(key, value.length), reopened.listBytes("items"));
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
        }
        try (java.util.stream.Stream<Path> paths = Files.list(target.getParent())) {
            assertEquals("only completed record and untouched staging remain", 2, paths.count());
        }
    }
}
