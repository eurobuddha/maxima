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
    public void inviteIsTheAddressPlusTheOneTimeCode() {
        String max = "MAX#0xABCD#MxABC@1.2.3.4:9501";
        assertEquals(max + "?code=AB12-CD34-EF56", Tenants.invite(max, " AB12-CD34-EF56\n"));
        assertEquals(null, Tenants.invite(max, ""));
        assertEquals(null, Tenants.invite("MxABC@1.2.3.4:9501", "AB12"));   // a bare Mx is not permanent
        assertEquals(null, Tenants.invite(null, "AB12"));
    }

    @Test
    public void hotAddSeesNewFoldersAndStopMarkers() throws Exception {
        Path tenants = Files.createTempDirectory("parlons-tenants3");
        Files.createDirectories(tenants.resolve("alice"));
        Files.createDirectories(tenants.resolve(".hidden"));
        java.util.List<Path> running = new java.util.ArrayList<>();
        assertEquals(java.util.Arrays.asList(tenants.resolve("alice")), Tenants.newTenants(tenants, running));
        running.add(tenants.resolve("alice"));
        assertTrue(Tenants.newTenants(tenants, running).isEmpty());
        Files.createDirectories(tenants.resolve("bob"));
        assertEquals(java.util.Arrays.asList(tenants.resolve("bob")), Tenants.newTenants(tenants, running));
        assertFalse(Tenants.stopRequested(tenants.resolve("bob")));
        Files.write(tenants.resolve("bob").resolve(Tenants.STOP_MARKER), new byte[0]);
        assertTrue(Tenants.stopRequested(tenants.resolve("bob")));
    }

    @Test
    public void accountAndInviteFilesFollowTheAddressAndTheLatestPairCode() throws Exception {
        Path t = Files.createTempDirectory("parlons-tenants4").resolve("carol");
        Files.createDirectories(t);
        assertFalse("no address yet: nothing written", Tenants.refreshFiles(t, null));
        assertFalse(Tenants.refreshFiles(t, "(rotating — no static MLS pinned yet)"));
        String max = "MAX#0xABCD#MxABC@1.2.3.4:9501";
        assertTrue(Tenants.refreshFiles(t, max));
        assertEquals(max, new String(Files.readAllBytes(t.resolve(Tenants.ACCOUNT_FILE)), StandardCharsets.UTF_8).trim());
        assertFalse("no pair code: no invite", Files.exists(t.resolve(Tenants.INVITE_FILE)));
        Files.write(t.resolve("pair-code.txt"), "AB12-CD34-EF56\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(Tenants.refreshFiles(t, max));
        assertEquals(max + "?code=AB12-CD34-EF56",
                new String(Files.readAllBytes(t.resolve(Tenants.INVITE_FILE)), StandardCharsets.UTF_8).trim());
        assertFalse("unchanged: nothing rewritten", Tenants.refreshFiles(t, max));
        // a fresh code (pair.newcode) replaces the invite
        Thread.sleep(20);
        Files.write(t.resolve("pair-code.txt"), "ZZ99-YY88-XX77\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(t.resolve("pair-code.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertTrue(Tenants.refreshFiles(t, max));
        assertTrue(new String(Files.readAllBytes(t.resolve(Tenants.INVITE_FILE)), StandardCharsets.UTF_8).contains("ZZ99-YY88-XX77"));
    }

    @Test
    public void newTenantWaitsForTheHostAndPrintsTheInvite() throws Exception {
        Path tenants = Files.createTempDirectory("parlons-tenants5");
        // no host running: the folder is made, the wait ends without an invite
        assertEquals(null, Tenants.newTenant(tenants, "dave", 600));
        assertTrue(Files.isDirectory(tenants.resolve("dave")));
        // a "host" that answers while we wait
        Thread host = new Thread(() -> {
            try {
                Thread.sleep(300);
                Files.write(tenants.resolve("dave").resolve(Tenants.INVITE_FILE),
                        "MAX#0xAB#MxA@h:1?code=AA11-BB22-CC33\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        });
        host.start();
        assertEquals("MAX#0xAB#MxA@h:1?code=AA11-BB22-CC33", Tenants.newTenant(tenants, "dave", 5_000));
        try { Tenants.newTenant(tenants, "../evil", 10); fail(); } catch (IllegalArgumentException expected) { }
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
