package com.eurobuddha.maxima.cloud;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class AdminPairingPasswordTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
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
