package com.eurobuddha.maxima.app.backup;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackupBundleTest {
    @Test public void encryptedBackupPreservesCustomModeAndExactPhrase() throws Exception {
        BackupBundle original = new BackupBundle();
        original.phrase = "  abandon  ability\tCafé!\n";
        original.anyPhrase = true;
        original.contacts.put("contact", "saved contact");
        original.keyUses.put("counter", 4567);
        char[] password = "synthetic-test-password".toCharArray();
        byte[] encrypted = BackupCrypto.encrypt(original.toJson().getBytes(StandardCharsets.UTF_8), password);
        BackupBundle restored = BackupManager.read(encrypted, password);
        assertEquals(2, restored.version); // Old apps must refuse this format, not reinterpret its phrase.
        assertTrue(restored.anyPhrase);
        assertEquals(original.phrase, restored.phrase);
        assertEquals(original.contacts, restored.contacts);
        assertEquals(original.keyUses, restored.keyUses);
    }

    @Test public void oldBackupRetainsStandardDerivation() {
        BackupBundle old = BackupBundle.fromJson("{\"version\":1,\"phrase\":\"ABANDON ABILITY\"}");
        assertFalse(old.anyPhrase);
        assertEquals("ABANDON ABILITY", old.phrase);
    }
}
