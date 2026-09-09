package com.eurobuddha.wallet;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** The same suite runs against the source compiled by BOTH Cloud and Desktop. No signing. */
public class FileKeyUsesTest {
    @Test public void malformedOrEmptyExistingCountersNeverBecomeZero() throws Exception {
        for (String bad : new String[]{"", "uses_v3_1000=broken\n", "uses_v3_1000=-1\n", "uses_v3_1000=2147483648\n"}) {
            Path dir = Files.createTempDirectory("keyuses-corrupt-test");
            Files.writeString(dir.resolve("keyuses_a.properties"), bad);
            Files.writeString(dir.resolve("keyuses_b.properties"), "uses_v3_1000=37\n");
            FileKeyUses k = new FileKeyUses(dir.toFile(), "v3");
            assertThrows(IllegalStateException.class, () -> k.currentUses(1000));
            assertThrows(IllegalStateException.class, () -> k.reserveNextUse(1000));
            assertThrows(IllegalStateException.class, k::snapshotAllUses);
            assertThrows(IllegalStateException.class, () -> FileKeyUses.exportAll(dir.toFile()));
            assertThrows(IllegalStateException.class, () -> FileKeyUses.importRaiseOnly(dir.toFile(), Collections.singletonMap("uses_v3_1000", 40)));
            assertEquals("no attempted operation rewrites damaged evidence", bad, Files.readString(dir.resolve("keyuses_a.properties")));
        }
    }

    @Test public void unreadableMirrorRefusesEvenWhenTheOtherIsValid() throws Exception {
        Path dir = Files.createTempDirectory("keyuses-unreadable-test");
        Files.createDirectory(dir.resolve("keyuses_a.properties"));
        Files.writeString(dir.resolve("keyuses_b.properties"), "uses_v3_1000=37\n");
        FileKeyUses k = new FileKeyUses(dir.toFile(), "v3");
        assertThrows(IllegalStateException.class, () -> k.currentUses(1000));
        assertThrows(IllegalStateException.class, () -> k.reserveNextUse(1000));
    }

    @Test public void mirrorsNamespacesAndRaiseOnlyRestoresKeepTheirExistingFormat() throws Exception {
        Path dir = Files.createTempDirectory("keyuses-merge-test");
        Files.writeString(dir.resolve("keyuses_a.properties"), "uses_v3_1000=37\nuses_0=5\n");
        Files.writeString(dir.resolve("keyuses_b.properties"), "uses_v3_1000=40\nuses_0=9\n");
        FileKeyUses k = new FileKeyUses(dir.toFile(), "v3");
        assertEquals(40, k.reserveNextUse(1000));
        k.recordExternalUses(1000, 2);
        FileKeyUses.importRaiseOnly(dir.toFile(), Collections.singletonMap("uses_v3_1000", 10));
        assertEquals(41, new FileKeyUses(dir.toFile(), "v3").currentUses(1000));
        assertEquals(9, new FileKeyUses(dir.toFile(), "").currentUses(0));
        assertEquals(Integer.valueOf(41), FileKeyUses.exportAll(dir.toFile()).get("uses_v3_1000"));
        assertEquals(Collections.singletonMap(1000, 41), k.snapshotAllUses());
    }

    @Test public void aFailedSecondMirrorWriteCannotReturnOrReuseTheReservedLeaf() throws Exception {
        Path dir = Files.createTempDirectory("keyuses-write-failure-test");
        FileKeyUses k = new FileKeyUses(dir.toFile(), "v3");
        k.recordExternalUses(1000, 12);
        Path blocked = dir.resolve("keyuses_b.properties.tmp");
        Files.createDirectory(blocked);
        Files.write(blocked.resolve("blocker"), new byte[]{1});
        assertThrows(IllegalStateException.class, () -> k.reserveNextUse(1000));
        assertEquals(13, new FileKeyUses(dir.toFile(), "v3").currentUses(1000));
        Files.delete(blocked.resolve("blocker"));
        Files.delete(blocked);
        assertEquals("failed operation wastes its leaf", 13, k.reserveNextUse(1000));
    }

    @Test public void independentInstancesNeverReserveTheSameLeaf() throws Exception {
        File dir = Files.createTempDirectory("keyuses-concurrency-test").toFile();
        Set<Integer> reserved = ConcurrentHashMap.newKeySet();
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Callable<Void>> work = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) work.add(() -> {
                FileKeyUses k = new FileKeyUses(dir, "v3");
                for (int j = 0; j < 10; j++) assertTrue(reserved.add(k.reserveNextUse(1000)));
                return null;
            });
            for (Future<Void> f : exec.invokeAll(work)) f.get(10, TimeUnit.SECONDS);
            assertEquals(40, reserved.size());
            assertEquals(40, new FileKeyUses(dir, "v3").currentUses(1000));
        } finally { exec.shutdownNow(); }
    }

    @Test public void negativeImportsAndIntegerOverflowCannotWrapCounters() throws Exception {
        File dir = Files.createTempDirectory("keyuses-overflow-test").toFile();
        FileKeyUses k = new FileKeyUses(dir, "v3");
        assertThrows(IllegalArgumentException.class, () -> k.recordExternalUses(1000, -1));
        assertThrows(IllegalArgumentException.class, () -> FileKeyUses.importRaiseOnly(dir, Collections.singletonMap("uses_v3_1000", -1)));
        k.recordExternalUses(1000, Integer.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> k.reserveNextUse(1000));
        assertEquals(Integer.MAX_VALUE, k.currentUses(1000));
    }
}
