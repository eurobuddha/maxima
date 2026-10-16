package com.eurobuddha.maxima.cloud;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class AdminPairingPasswordTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void appSetupAndChangeRequireMatchingConfirmationAndCurrentPassword() throws Exception {
        Path dir = temp.getRoot().toPath();
        AdminPairingPassword guard = new AdminPairingPassword(dir);
        assertFalse(guard.configured());
        assertThrows(IllegalArgumentException.class, () -> guard.change(new char[0], "short".toCharArray(), "short".toCharArray()));
        assertFalse(guard.configured());
        char[] first = "first synthetic password".toCharArray(), next = "next synthetic password".toCharArray();
        guard.change(new char[0], first, first);
        assertTrue(guard.configured());
        byte[] original = Files.readAllBytes(dir.resolve(AdminPairingPassword.FILE));
        assertThrows(IllegalArgumentException.class, () -> guard.change(first, next, first));
        assertThrows(SecurityException.class, () -> guard.change(new char[0], next, next));
        assertArrayEquals(original, Files.readAllBytes(dir.resolve(AdminPairingPassword.FILE)));
        guard.change(first, next, next);
        assertTrue(new AdminPairingPassword(dir).verify(next));
        assertFalse(new AdminPairingPassword(dir).verify(first));
    }

    @Test public void symlinkCannotBeTreatedAsUnconfiguredDuringAppSetup() throws Exception {
        Path dir = temp.getRoot().toPath(), target = dir.resolve("not-created");
        Files.createSymbolicLink(dir.resolve(AdminPairingPassword.FILE), target);
        AdminPairingPassword guard = new AdminPairingPassword(dir);
        assertTrue(guard.configured());
        assertThrows(IllegalStateException.class, () -> guard.change("current password".toCharArray(),
                "new synthetic password".toCharArray(), "new synthetic password".toCharArray()));
        assertFalse(Files.exists(target));
    }
    @Test public void passwordUsesAuthenticatedContainerAndIsNeverStoredAsText() throws Exception {
        Path dir = temp.getRoot().toPath();
        AdminPairingPassword password = new AdminPairingPassword(dir);
        password.set("a synthetic admin password".toCharArray());
        byte[] stored = Files.readAllBytes(dir.resolve(AdminPairingPassword.FILE));
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains("a synthetic admin password"));
        assertTrue(password.verify("a synthetic admin password".toCharArray()));
        assertFalse(new AdminPairingPassword(dir).verify("wrong password".toCharArray()));
        assertThrows(IllegalStateException.class, () -> password.verify("a synthetic admin password".toCharArray()));
    }
    @Test public void missingEmptyAndInvalidConfigurationNeverAuthenticate() throws Exception {
        Path dir = temp.getRoot().toPath(); AdminPairingPassword guard = new AdminPairingPassword(dir);
        assertFalse(guard.verify(new char[0]));
        assertThrows(IllegalStateException.class, () -> guard.verify("password".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> guard.set("short".toCharArray()));
        Path elsewhere = temp.newFile("outside").toPath();
        Files.createSymbolicLink(dir.resolve(AdminPairingPassword.FILE), elsewhere);
        assertThrows(IllegalStateException.class, () -> guard.verify("password".toCharArray()));
    }
}
