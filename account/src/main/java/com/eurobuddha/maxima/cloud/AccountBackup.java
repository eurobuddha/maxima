package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * The account's portable encrypted backup — byte-identical .pbk format to the phone app
 * (PARLONSBK | scrypt | AES-GCM), same bundle fields (phrase, displayName, mls, contacts,
 * keyUses), so an account moves phone↔cloud↔node with no translation.
 *
 * Where the seed and the Winternitz counters live differs per host (a seed.txt + file counters
 * on the cloud; the node's own vault + key DB on a Parlons Node), so this class takes them
 * through {@link Source} and hands the counters back through {@link KeyUsesImporter}.
 */
public final class AccountBackup {

    private AccountBackup() {
    }

    /** Where the phrase and the key-use counters come from on this host. */
    public interface Source {
        /** The 24-word phrase. Never logged by callers. */
        String phrase() throws Exception;
        /** Winternitz uses per key modifier — empty where the node owns its own counters. */
        Map<String, Integer> keyUses();
    }

    /** Applies restored counters RAISE-ONLY. No-op where the node owns its own counters. */
    public interface KeyUsesImporter {
        void importRaiseOnly(Map<String, Integer> zUses) throws Exception;
    }

    /** Build + encrypt the account bundle. {@code zNodeStore} must be the LIVE node's own
     *  FileStore instance (a second instance over a running store risks stale reads). */
    public static byte[] export(Source zSource, FileStore zNodeStore, String zDisplayName,
                                char[] zPassword) throws Exception {
        BackupBundle b = new BackupBundle();
        b.phrase = zSource.phrase();
        b.displayName = zDisplayName == null ? "" : zDisplayName;
        String mls = zNodeStore.get("settings", "staticmls");
        b.mls = mls == null ? "" : mls;
        b.contacts.putAll(zNodeStore.all("contacts"));
        Map<String, Integer> uses = zSource.keyUses();
        if (uses != null) {
            b.keyUses.putAll(uses);
        }
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
     * OFFLINE restore into a data dir (the process must NOT be running — operator CLI only).
     * Refuses to overwrite an existing identity: move the old data dir away first. Writes
     * contacts + name + mls into the node store, the key-use counters RAISE-ONLY via
     * {@code zUses}, and {@code seed.txt} LAST, atomically 0600 — its absence means an
     * incomplete restore is safe to retry.
     */
    public static void applyRestore(Path zDataDir, BackupBundle zBundle, KeyUsesImporter zUses)
            throws Exception {
        Path seedFile = zDataDir.resolve("seed.txt");
        if (Files.exists(seedFile)) {
            throw new IllegalStateException("this data dir already holds an identity ("
                    + seedFile + ") — move it away first; restore never overwrites");
        }
        Files.createDirectories(zDataDir);
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
        if (zUses != null) {
            zUses.importRaiseOnly(zBundle.keyUses);
        }
        try {
            try {
                Files.createFile(seedFile, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException nonPosix) {
                // non-POSIX FS — plain create below
            }
            Files.write(seedFile, zBundle.phrase.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { Files.deleteIfExists(seedFile); } catch (Exception ignored) { }
            throw e;
        }
    }
}
