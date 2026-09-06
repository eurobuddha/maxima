package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/** Stage-3 item 4: the portable account bundle moves an account whole, to any host. */
public class AccountBackupTest {

    static Path tmp(String zTag) throws Exception {
        return Files.createTempDirectory("parlons-acct-" + zTag);
    }

    static final String PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    static AccountBackup.Source source() {
        return new AccountBackup.Source() {
            public String phrase() { return PHRASE; }
            public Map<String, Integer> keyUses() { return Collections.singletonMap("0x1000", 7); }
        };
    }

    /** A data dir shaped like a live account: node + chat stores, devices.json, settings. */
    static Path liveAccount() throws Exception {
        Path dir = tmp("live");
        FileStore node = new FileStore(new File(dir.toFile(), AccountBackup.NODE_DIR));
        node.put("contacts", "0xAAA", "Alice|MAX#0xAAA#Mx…@1.2.3.4:9501");
        node.put("contacts", "0xBBB", "Bob|MAX#0xBBB#Mx…@5.6.7.8:9501");
        node.put("settings", "name", "eurobuddhaCloud");
        node.put("settings", "staticmls", "Mx…@95.179.179.181:9501");
        node.put("peers", "45.77.246.226:9501", "1757100000000");
        node.append("addrhistory", "1757100000000 Mx…@95.179.179.181:9501");
        node.append("addrhistory", "1757100060000 Mx…@65.109.31.226:9501");
        node.flush();
        FileStore chat = new FileStore(new File(dir.toFile(), AccountBackup.CHAT_DIR));
        chat.put("chat_messages", "m1", "{\"from\":\"0xAAA\",\"text\":\"hello\"}");
        chat.put("chat_messages", "m2", "{\"from\":\"me\",\"text\":\"hi\"}");
        chat.put("chat_read", "0xAAA", "m2");
        chat.put("chat_groups", "g1", "{\"name\":\"fleet\"}");
        chat.flush();
        Files.write(dir.resolve(AccountBackup.DEVICES_FILE),
                "{\"authorized\":[{\"key\":\"0xD1\",\"label\":\"S23\",\"pairedAt\":1757000000000}],\"pending\":[]}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(AccountBackup.SETTINGS_FILE),
                "extrarelays=1.2.3.4:9501\nbuiltinrelays=false\n".getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void theWholeAccountRoundTripsToAFreshHostWithTheSameIdentity() throws Exception {
        Path live = liveAccount();
        FileStore node = new FileStore(new File(live.toFile(), AccountBackup.NODE_DIR));
        FileStore chat = new FileStore(new File(live.toFile(), AccountBackup.CHAT_DIR));
        char[] pw = "correct horse".toCharArray();
        byte[] blob = AccountBackup.export(source(), live, node, chat, "eurobuddhaCloud", pw);

        BackupBundle b = AccountBackup.read(blob, "correct horse".toCharArray());
        assertTrue(b.hasAccount());
        assertEquals(1, b.version);   // still the shape every older reader accepts
        assertEquals(PHRASE, b.phrase);
        assertEquals(2, b.contacts.size());
        assertEquals("false", b.settings.get("builtinrelays"));
        assertTrue(b.devicesJson.contains("\"0xD1\""));
        assertEquals(3, b.stores.get(AccountBackup.CHAT_DIR).size());
        assertEquals(2, b.logs.get(AccountBackup.NODE_DIR).get("addrhistory").size());

        // A NODE host: identity lands in identity.txt, everything else where the account reads it.
        Path fresh = tmp("node");
        AccountBackup.applyRestore(fresh, b, null, "identity.txt");
        assertEquals(PHRASE, new String(Files.readAllBytes(fresh.resolve("identity.txt")), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fresh.resolve("seed.txt")));
        FileStore node2 = new FileStore(new File(fresh.toFile(), AccountBackup.NODE_DIR));
        assertEquals(node.all("contacts"), node2.all("contacts"));
        assertEquals("eurobuddhaCloud", node2.get("settings", "name"));
        assertEquals("1757100000000", node2.get("peers", "45.77.246.226:9501"));
        assertEquals(node.read("addrhistory"), node2.read("addrhistory"));
        FileStore chat2 = new FileStore(new File(fresh.toFile(), AccountBackup.CHAT_DIR));
        assertEquals(chat.all("chat_messages"), chat2.all("chat_messages"));
        assertEquals(chat.all("chat_read"), chat2.all("chat_read"));
        assertEquals(chat.all("chat_groups"), chat2.all("chat_groups"));
        assertEquals(new String(Files.readAllBytes(live.resolve(AccountBackup.DEVICES_FILE)), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(fresh.resolve(AccountBackup.DEVICES_FILE)), StandardCharsets.UTF_8));
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = Files.newInputStream(fresh.resolve(AccountBackup.SETTINGS_FILE))) {
            p.load(in);
        }
        assertEquals("1.2.3.4:9501", p.getProperty("extrarelays"));
        assertEquals("false", p.getProperty("builtinrelays"));
        try {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(fresh.resolve("identity.txt"))));
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(fresh.resolve(AccountBackup.DEVICES_FILE))));
        } catch (UnsupportedOperationException nonPosix) {
            // Windows: no POSIX bits to check
        }

        // A CLOUD host: the same bundle, seed.txt instead.
        Path cloud = tmp("cloud");
        Map<String, Integer> imported = new LinkedHashMap<>();
        AccountBackup.applyRestore(cloud, b, imported::putAll);
        assertEquals(PHRASE, new String(Files.readAllBytes(cloud.resolve("seed.txt")), StandardCharsets.UTF_8));
        assertEquals(Integer.valueOf(7), imported.get("0x1000"));
    }

    @Test
    public void restoreNeverOverwritesAnExistingIdentityAndWritesItLast() throws Exception {
        Path live = liveAccount();
        FileStore node = new FileStore(new File(live.toFile(), AccountBackup.NODE_DIR));
        FileStore chat = new FileStore(new File(live.toFile(), AccountBackup.CHAT_DIR));
        byte[] blob = AccountBackup.export(source(), live, node, chat, "x", "pw123456".toCharArray());
        BackupBundle b = AccountBackup.read(blob, "pw123456".toCharArray());
        Path taken = tmp("taken");
        Files.write(taken.resolve("identity.txt"), "other words".getBytes(StandardCharsets.UTF_8));
        try {
            AccountBackup.applyRestore(taken, b, null, "identity.txt");
            fail("must refuse");
        } catch (IllegalStateException expected) {
            // the existing identity is untouched
            assertEquals("other words", new String(Files.readAllBytes(taken.resolve("identity.txt")), StandardCharsets.UTF_8));
            assertFalse("nothing else was written either", Files.exists(taken.resolve(AccountBackup.DEVICES_FILE)));
        }
    }

    @Test
    public void anOlderBundleWithoutTheAccountBlockStillRestores() throws Exception {
        BackupBundle old = new BackupBundle();
        old.phrase = PHRASE;
        old.displayName = "old";
        old.contacts.put("0xAAA", "Alice|MAX#…");
        BackupBundle back = BackupBundle.fromJson(old.toJson());
        assertFalse(back.hasAccount());
        Path fresh = tmp("old");
        AccountBackup.applyRestore(fresh, back, null, "identity.txt");
        FileStore node = new FileStore(new File(fresh.toFile(), AccountBackup.NODE_DIR));
        assertEquals("Alice|MAX#…", node.get("contacts", "0xAAA"));
        assertEquals("old", node.get("settings", "name"));
        assertFalse(Files.exists(fresh.resolve(AccountBackup.DEVICES_FILE)));
    }

    @Test
    public void theWrongPassphraseIsRefused() throws Exception {
        Path live = liveAccount();
        FileStore node = new FileStore(new File(live.toFile(), AccountBackup.NODE_DIR));
        byte[] blob = AccountBackup.export(source(), live, node, null, "x", "pw123456".toCharArray());
        try {
            AccountBackup.read(blob, "wrong pass".toCharArray());
            fail("must refuse");
        } catch (Exception expected) {
            assertTrue(expected instanceof javax.crypto.AEADBadTagException
                    || expected.getCause() instanceof javax.crypto.AEADBadTagException
                    || expected.getMessage() != null);
        }
    }
}
