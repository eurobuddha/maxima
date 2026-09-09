package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.codec.MiniData;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.Assert.*;

public class DevicePairingPersistenceTest {
    private static final byte[] OWNER = {1, 2, 3}, DEVICE = {4, 5, 6};
    private static final String KEY = new MiniData(DEVICE).to0xString();

    @Test public void failedChangesRestoreAuthorizationPendingAndWakeState() throws Exception {
        Path dir = Files.createTempDirectory("pairing-persistence-test");
        DevicePairing p = new DevicePairing(dir);
        p.authorizeLocal(OWNER, "owner", true);
        p.requestPair(DEVICE, "phone", "");
        Path file = dir.resolve("devices.json"), saved = dir.resolve("saved.json");
        Files.move(file, saved);
        Files.createDirectory(file);
        Files.write(file.resolve("blocker"), new byte[]{1});
        try {
            assertThrows(IllegalStateException.class, () -> p.approve(OWNER, KEY));
            assertFalse(p.isAuthorized(DEVICE));
            assertTrue(p.pendingKeys().contains(KEY));
        } finally { unblock(file, saved); }
        assertTrue(p.approve(OWNER, KEY));
        assertTrue(p.setApns(KEY, "saved-token", "prod", "off"));
        Files.move(file, saved);
        Files.createDirectory(file);
        Files.write(file.resolve("blocker"), new byte[]{1});
        try {
            assertThrows(IllegalStateException.class, () -> p.setApns(KEY, "changed-token", "sandbox", "off"));
            assertEquals("saved-token", p.device(KEY).apnsToken);
            assertThrows(IllegalStateException.class, () -> p.revoke(OWNER, KEY));
            assertTrue("failed revocation is reported and agrees with disk", p.isAuthorized(DEVICE));
        } finally { unblock(file, saved); }
        assertTrue(new DevicePairing(dir).isAuthorized(DEVICE));
        assertTrue(p.revoke(OWNER, KEY));
        assertFalse(new DevicePairing(dir).isAuthorized(DEVICE));
    }

    @Test public void failedBootstrapPersistenceDoesNotAuthorizeOrLeaveAReusableCode() throws Exception {
        Path dir = Files.createTempDirectory("pairing-bootstrap-test");
        DevicePairing p = new DevicePairing(dir);
        String code = p.newBootstrapCode();
        Files.createDirectory(dir.resolve("devices.json"));
        Files.write(dir.resolve("devices.json/blocker"), new byte[]{1});
        assertThrows(IllegalStateException.class, () -> p.requestPair(DEVICE, "phone", code));
        assertFalse(p.isAuthorized(DEVICE));
        assertFalse(p.hasBootstrapCode());
    }

    @Test public void codeWriteAndCorruptAuthorizationFilesFailExplicitly() throws Exception {
        Path dir = Files.createTempDirectory("pairing-errors-test");
        DevicePairing p = new DevicePairing(dir);
        Files.createDirectory(dir.resolve("pair-code.txt"));
        Files.write(dir.resolve("pair-code.txt/blocker"), new byte[]{1});
        assertThrows(IllegalStateException.class, p::newBootstrapCode);
        Files.write(dir.resolve("devices.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> new DevicePairing(dir));
    }

    @Test public void pendingRequestsAreBoundedButOwnerCodeStillWorks() throws Exception {
        Path dir = Files.createTempDirectory("pairing-cap-test");
        DevicePairing p = new DevicePairing(dir);
        for (int i = 0; i < DevicePairing.MAX_PENDING; i++)
            assertEquals(DevicePairing.Result.PENDING, p.requestPair(new byte[]{99, (byte)i}, "phone", ""));
        assertEquals(DevicePairing.MAX_PENDING, new DevicePairing(dir).pendingKeys().size());
        assertThrows(IllegalStateException.class, () -> p.requestPair(DEVICE, "phone", ""));
        assertThrows(IllegalArgumentException.class, () -> p.requestPair(DEVICE, "x".repeat(DevicePairing.MAX_LABEL_CHARS + 1), ""));
        String code = p.newBootstrapCode();
        assertEquals(DevicePairing.Result.AUTHORIZED, p.requestPair(DEVICE, "phone", code));
        assertTrue(new DevicePairing(dir).isAuthorized(DEVICE));
    }

    @Test public void failedPrivateReplacementLeavesPreviousFileIntact() throws Exception {
        Path file = Files.createTempDirectory("private-replacement-test").resolve("private.txt");
        AccountFiles.writePrivate(file, "previous".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> AccountFiles.writePrivate(file, null));
        assertEquals("previous", Files.readString(file));
        AccountFiles.writePrivate(file, "replacement".getBytes(StandardCharsets.UTF_8));
        assertEquals("replacement", Files.readString(file));
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        try (java.util.stream.Stream<Path> files = Files.list(file.getParent())) {
            assertEquals("no temporary secrets remain", 1, files.count());
        }
    }

    private static void unblock(Path file, Path saved) throws Exception {
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        Files.move(saved, file);
    }
}
