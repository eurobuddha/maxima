package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** The multi-account host's at-rest seed handling. */
public class TenantsTest {

    @Test
    public void seedsEncryptAtRestVerifyAndOpenOnlyWithTheUnlockPassphrase() throws Exception {
        Path tenants = Files.createTempDirectory("parlons-tenants");
        Path a = tenants.resolve("alice");
        Path b = tenants.resolve("bob");
        Files.createDirectories(a);
        Files.createDirectories(b);
        String pa = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu";
        Files.write(a.resolve("seed.txt"), pa.getBytes(StandardCharsets.UTF_8));
        // bob has no seed yet: he gets a fresh, encrypted one when an unlock is in force
        char[] unlock = "host unlock phrase".toCharArray();

        Tenants.encryptSeeds(tenants, unlock);
        assertFalse("plaintext gone", Files.exists(a.resolve("seed.txt")));
        assertTrue(Files.exists(a.resolve("seed.enc")));
        assertEquals(pa, Tenants.phraseFor(a, unlock));
        try {
            Tenants.phraseFor(a, "wrong".toCharArray());
            fail("wrong unlock must not open the seed");
        } catch (Exception expected) {
            // AEAD tag failure
        }
        try {
            Tenants.phraseFor(a, null);
            fail("an encrypted seed needs the unlock");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("--unlock"));
        }

        String pb = Tenants.phraseFor(b, unlock);
        assertEquals(24, pb.split(" ").length);
        assertTrue("a new tenant under an unlock is born encrypted", Files.exists(b.resolve("seed.enc")));
        assertFalse(Files.exists(b.resolve("seed.txt")));
        assertEquals("the same phrase on the next boot", pb, Tenants.phraseFor(b, unlock));

        assertEquals(2, Tenants.list(tenants).size());
        assertEquals("alice", Tenants.list(tenants).get(0).getFileName().toString());
    }

    @Test
    public void withoutAnUnlockANewTenantGetsAPlainOwnerOnlySeed() throws Exception {
        Path tenants = Files.createTempDirectory("parlons-tenants2");
        Path c = tenants.resolve("carol");
        String p = Tenants.phraseFor(c, null);
        assertEquals(24, p.split(" ").length);
        assertTrue(Files.exists(c.resolve("seed.txt")));
        try {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(c.resolve("seed.txt"))));
        } catch (UnsupportedOperationException nonPosix) {
            // no POSIX bits here
        }
    }
}
