package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * The cloud account's portable encrypted backup — byte-identical .pbk format to the phone app
 * (PARLONSBK | scrypt | AES-GCM), same bundle fields (phrase, displayName, mls, contacts,
 * keyUses), so an account moves phone↔cloud with no translation. The keyUses map is the
 * fund-critical piece: it carries the Winternitz counter so a restored wallet can never re-sign
 * a used leaf — which is exactly why the encrypted backup, not bare words, is the migration path.
 */
public final class CloudBackupManager {

    private CloudBackupManager() {
    }

    /** Build + encrypt the account bundle. {@code zNodeStore} must be the LIVE node's own
     *  FileStore instance (a second instance over a running store risks stale reads). */
    public static byte[] export(Path zDataDir, FileStore zNodeStore, String zDisplayName,
                                char[] zPassword) throws Exception {
        BackupBundle b = new BackupBundle();
        b.phrase = readPhrase(zDataDir);
        b.displayName = zDisplayName == null ? "" : zDisplayName;
        String mls = zNodeStore.get("settings", "staticmls");
        b.mls = mls == null ? "" : mls;
        b.contacts.putAll(zNodeStore.all("contacts"));
        b.keyUses.putAll(CloudKeyUses.exportAll(new File(zDataDir.toFile(), "wallet")));
        return BackupCrypto.encrypt(b.toJson().getBytes(StandardCharsets.UTF_8), zPassword);
    }

    /** Decrypt + parse + sanity-guard a .pbk blob. */
    public static BackupBundle read(byte[] zBlob, char[] zPassword) throws Exception {
        byte[] plain = BackupCrypto.decrypt(zBlob, zPassword);
        BackupBundle b = BackupBundle.fromJson(new String(plain, StandardCharsets.UTF_8));
        if (b.version < 1 || b.version > BackupBundle.CURRENT_VERSION) {
            throw new IllegalArgumentException("Backup from an unsupported version");
        }
        if (b.phrase == null || b.phrase.trim().isEmpty()) {
            throw new IllegalArgumentException("Backup holds no seed phrase");
        }
        return b;
    }

    /**
     * OFFLINE restore into a data dir (the node must NOT be running — operator CLI only).
     * Refuses to overwrite an existing identity: move the old data dir away first. Writes
     * seed.txt atomically 0600, contacts + name + mls into the node store, and the key-use
     * counters RAISE-ONLY.
     */
    public static void applyRestore(Path zDataDir, BackupBundle zBundle) throws Exception {
        Path seedFile = zDataDir.resolve("seed.txt");
        if (Files.exists(seedFile)) {
            throw new IllegalStateException("this data dir already holds an identity ("
                    + seedFile + ") — move it away first; restore never overwrites");
        }
        Files.createDirectories(zDataDir);
        // Atomic 0600 create — same discipline as RelayRuntime.loadOrCreateSeed.
        try {
            Files.createFile(seedFile, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            // non-POSIX FS — plain create below
        }
        try {
            Files.write(seedFile, zBundle.phrase.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { Files.deleteIfExists(seedFile); } catch (Exception ignored) { }
            throw e;
        }

        FileStore node = new FileStore(new File(zDataDir.toFile(), "node"));
        for (Map.Entry<String, String> e : zBundle.contacts.entrySet()) {
            node.put("contacts", e.getKey(), e.getValue());
        }
        if (zBundle.displayName != null && !zBundle.displayName.isEmpty()) {
            node.put("settings", "name", zBundle.displayName);
        }
        if (zBundle.mls != null && !zBundle.mls.isEmpty()) {
            node.put("settings", "staticmls", zBundle.mls);
        }
        node.flush();

        CloudKeyUses.importRaiseOnly(new File(zDataDir.toFile(), "wallet"), zBundle.keyUses);
    }

    static String readPhrase(Path zDataDir) throws Exception {
        return new String(Files.readAllBytes(zDataDir.resolve("seed.txt")),
                StandardCharsets.UTF_8).trim();
    }
}
