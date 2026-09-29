package com.eurobuddha.maxima.node;

import com.eurobuddha.maxima.cloud.*;
import com.eurobuddha.maxima.core.codec.*;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.minima.utils.json.JSONObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic identities only. Exercises the real stage, owner gate and offline rename recovery. */
public class NodeIdentityImportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private final byte[] owner = {1,2,3}, other = {4,5,6}, sourcePhone = {7,8,9};
    private Path root;
    private DevicePairing pairing;
    private NodeIdentityImport importer;
    private String originalKey;

    @Before public void setup() throws Exception {
        root = temp.newFolder().toPath();
        originalKey = MaximaIdentity.fromSeed(new MiniData(new byte[32])).publicKeyHex();
        byte[] seed = new byte[32]; seed[0] = 9;
        String directory = MaximaIdentity.fromSeed(new MiniData(seed)).mxIdentity() + "@127.0.0.1:9501";
        pairing = new DevicePairing(root);
        pair(pairing, owner); pair(pairing, other);
        Files.writeString(root.resolve("identity.txt"), "original identity");
        Files.createDirectory(root.resolve("vault")); Files.writeString(root.resolve("vault/wallet"), "untouched wallet");
        Files.writeString(root.resolve("cloud-settings.properties"), "target connectivity");
        Files.createDirectory(root.resolve("private-files")); Files.writeString(root.resolve("private-files/old-secret"), "old file");
        Files.createDirectory(root.resolve("media")); Files.writeString(root.resolve("media/old-media"), "old photo");
        importer = new NodeIdentityImport(root, originalKey, pairing, () -> Collections.singletonList(directory), () -> {});
    }
    @After public void stop() { if (importer != null) importer.close(); }
    private static void pair(DevicePairing p, byte[] key) throws Exception {
        assertEquals(DevicePairing.Result.AUTHORIZED, p.requestPair(key, "synthetic", p.newBootstrapCode()));
    }
    private JSONObject req(String action, Object... fields) {
        JSONObject o = new JSONObject(); o.put("id", ID); o.put("action", action);
        for (int i=0; i<fields.length; i+=2) o.put((String) fields[i], fields[i+1]); return o;
    }
    private JSONObject call(String action, Object... fields) throws Exception { return importer.handle(owner, req(action, fields)); }
    private JSONObject ready(String phrase, boolean custom) throws Exception {
        call("begin", "total", 0); call("prepare", "phrase", phrase, "anyPhrase", custom); return await();
    }
    private JSONObject await() throws Exception {
        long until = System.currentTimeMillis()+15000;
        JSONObject r;
        do { r=call("status"); if (!"preparing".equals(r.get("state"))) return r; Thread.sleep(10); }
        while (System.currentTimeMillis()<until);
        fail("Preparation did not finish"); return null;
    }
    private void commit() throws Exception { call("commit", "confirm", true, "oldHostStopped", true); }
    private interface Checked { void run() throws Exception; }
    private static void denied(Checked c) throws Exception {
        try { c.run(); fail("must reject"); } catch (IllegalArgumentException | IllegalStateException expected) { }
    }

    @Test public void phraseStagesWithoutChangingLiveAccountThenSwapsOfflineAndKeepsWallet() throws Exception {
        JSONObject result=ready(PHRASE, false);
        assertEquals("ready", result.get("state"));
        assertTrue(((String) result.get("address")).startsWith("MAX#"+MaximaIdentity.fromPhrase(PHRASE).publicKeyHex()+"#"));
        assertEquals("original identity", Files.readString(root.resolve("identity.txt")));
        denied(() -> call("commit", "confirm", true));
        commit(); commit(); // lost reply is safe to retry
        assertEquals("original identity", Files.readString(root.resolve("identity.txt")));
        importer.close(); NodeIdentityImport.applyPending(root); NodeIdentityImport.applyPending(root);
        assertEquals(com.eurobuddha.maxima.core.identity.Bip39.cleanSeedPhrase(PHRASE), Files.readString(root.resolve("identity.txt")));
        assertEquals("original identity", Files.readString(root.resolve("account-history/"+ID+"/previous/identity.txt")));
        assertEquals("untouched wallet", Files.readString(root.resolve("vault/wallet")));
        assertEquals("target connectivity", Files.readString(root.resolve("cloud-settings.properties")));
        DevicePairing newPairing = new DevicePairing(root);
        assertTrue(newPairing.isAuthorized(owner)); assertFalse(newPairing.isAuthorized(other));
        assertFalse(Files.exists(root.resolve("pair-code.txt")));
        assertFalse(Files.exists(root.resolve("private-files"))); assertFalse(Files.exists(root.resolve("media")));
        assertEquals("old file", Files.readString(root.resolve("account-history/"+ID+"/previous/private-files/old-secret")));
    }
    @Test public void customPhraseUsesExactApkUtf8AndSurvivesRestart() throws Exception {
        String raw = "  Café\nMixed CASE!  ";
        assertEquals("ready", ready(raw,true).get("state"));
        commit(); importer.close(); NodeIdentityImport.applyPending(root);
        String seed = Files.readString(root.resolve("identity.txt"));
        assertEquals(MaximaIdentity.fromSeed(new MiniData(Hashes.sha3(new MiniString(raw).getData()))).publicKeyHex(),
                MaximaIdentity.fromNodeSecret(seed).publicKeyHex());
        assertEquals(raw, Files.readString(root.resolve("identity-anyphrase.txt")));
    }
    @Test public void ownerIsolationRevocationAndCancellation() throws Exception {
        denied(() -> importer.handle(sourcePhone, req("begin","total",0)));
        call("begin", "total", 0);
        denied(() -> importer.handle(other, req("status")));
        denied(() -> importer.handle(other, req("cancel")));
        assertTrue(pairing.revoke(other, new MiniData(owner).to0xString()));
        denied(() -> call("prepare","phrase",PHRASE));
        assertEquals("original identity",Files.readString(root.resolve("identity.txt")));
    }
    @Test public void cancelledStageCannotChangeAccountOnRestart() throws Exception {
        ready(PHRASE,false); call("cancel"); call("cancel");
        NodeIdentityImport.applyPending(root);
        assertEquals("original identity",Files.readString(root.resolve("identity.txt")));
        assertFalse(Files.exists(root.resolve(".identity-import")));
    }
    @Test public void uploadBoundsIncompleteFilesAndExactReplay() throws Exception {
        denied(() -> call("begin", "total", NodeIdentityImport.MAX_BYTES+1)); call("cancel");
        call("begin", "total", 5);
        denied(() -> call("prepare","password","test"));
        String chunk = Base64.getEncoder().encodeToString(new byte[]{1,2,3});
        assertEquals(3,call("upload","offset",0,"chunk",chunk).get("received"));
        assertEquals(3,call("upload","offset",0,"chunk",chunk).get("received"));
        denied(() -> call("upload","offset",0,"chunk",Base64.getEncoder().encodeToString(new byte[]{4})));
        denied(() -> call("upload","offset",3,"chunk",chunk));
    }
    @Test public void encryptedBackupRestoresSourceDataAndPairings() throws Exception {
        Path source = temp.newFolder().toPath(); DevicePairing src = new DevicePairing(source); pair(src,sourcePhone);
        BackupBundle b = new BackupBundle(); b.phrase=PHRASE; b.displayName="Imported synthetic"; b.accountFormat=1;
        b.devicesJson=Files.readString(source.resolve("devices.json")); b.contacts.put("0x1234","Alice");
        b.stores.put("chat",Collections.singletonMap("messages",Collections.singletonMap("m1","hello")));
        byte[] bytes=BackupCrypto.encrypt(b.toJson().getBytes(StandardCharsets.UTF_8),"password".toCharArray());
        upload(bytes); call("prepare","password","wrong"); assertEquals("failed",await().get("state"));
        assertEquals("original identity",Files.readString(root.resolve("identity.txt"))); call("cancel");
        upload(bytes); call("prepare","password","password"); assertEquals("ready",await().get("state"));
        commit(); importer.close(); NodeIdentityImport.applyPending(root);
        assertTrue(new DevicePairing(root).isAuthorized(sourcePhone)); assertTrue(new DevicePairing(root).isAuthorized(owner));
        assertEquals("Alice",new FileStore(root.resolve("node").toFile()).get("contacts","0x1234"));
        assertEquals("hello",new FileStore(root.resolve("chat").toFile()).get("messages","m1"));
    }
    private void upload(byte[] bytes) throws Exception {
        call("begin","total",bytes.length);
        for (int off=0; off<bytes.length; off+=60000) call("upload","offset",off,"chunk",
                Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,off,Math.min(off+60000,bytes.length))));
    }
    @Test public void startupResumesAfterSomeOldFilesMoved() throws Exception {
        ready(PHRASE,false); commit(); importer.close();
        Path previous = root.resolve(".identity-import/previous"); Files.createDirectory(previous);
        Files.move(root.resolve("devices.json"),previous.resolve("devices.json"));
        NodeIdentityImport.applyPending(root);
        assertEquals(com.eurobuddha.maxima.core.identity.Bip39.cleanSeedPhrase(PHRASE),Files.readString(root.resolve("identity.txt")));
        assertTrue(Files.exists(previous.getParent().getParent().resolve("account-history/"+ID+"/previous/devices.json")));
    }
    @Test public void startupResumesAfterSomeNewFilesInstalled() throws Exception {
        ready(PHRASE,false); commit(); importer.close();
        Path pending=root.resolve(".identity-import"), prev=pending.resolve("previous"); Files.createDirectory(prev);
        for (String name : NodeIdentityImport.ACCOUNT_FILES) if (Files.exists(root.resolve(name))) Files.move(root.resolve(name),prev.resolve(name));
        Files.writeString(pending.resolve("old-moved"),ID);
        Files.move(pending.resolve("next/identity.txt"),root.resolve("identity.txt"));
        NodeIdentityImport.applyPending(root);
        assertTrue(new DevicePairing(root).isAuthorized(owner)); assertEquals(com.eurobuddha.maxima.core.identity.Bip39.cleanSeedPhrase(PHRASE),Files.readString(root.resolve("identity.txt")));
    }
    @Test public void missingStagedSeedStopsBeforeMovingLiveFiles() throws Exception {
        ready(PHRASE,false); commit(); importer.close();
        Files.delete(root.resolve(".identity-import/next/identity.txt"));
        denied(() -> NodeIdentityImport.applyPending(root));
        assertEquals("original identity",Files.readString(root.resolve("identity.txt")));
        assertTrue(new DevicePairing(root).isAuthorized(other));
    }
    @Test public void standardModeRejectsNonWordPhrase() throws Exception {
        assertEquals("failed",ready("this is a custom phrase",false).get("state"));
        assertEquals("original identity",Files.readString(root.resolve("identity.txt")));
    }
}
