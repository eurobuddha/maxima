package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The CLOUD host's backup plumbing: where its seed (seed.txt) and its file-backed Winternitz
 * counters ({@link CloudKeyUses}) live. The .pbk format itself — byte-identical to the phone
 * app's — is {@link AccountBackup} in the shared account layer; this class only supplies the
 * host-specific halves.
 */
public final class CloudBackupManager {

    private CloudBackupManager() {
    }

    /** Build + encrypt the account bundle. {@code zNodeStore} must be the LIVE node's own
     *  FileStore instance (a second instance over a running store risks stale reads). */
    public static byte[] export(Path zDataDir, FileStore zNodeStore, String zDisplayName,
                                char[] zPassword) throws Exception {
        AccountBackup.Source src = new AccountBackup.Source() {
            public String phrase() throws Exception { return readPhrase(zDataDir); }
            public java.util.Map<String, Integer> keyUses() {
                return CloudKeyUses.exportAll(new File(zDataDir.toFile(), "wallet"));
            }
        };
        return AccountBackup.export(src, zNodeStore, zDisplayName, zPassword);
    }

    /** Decrypt + parse + sanity-guard a .pbk blob. */
    public static BackupBundle read(byte[] zBlob, char[] zPassword) throws Exception {
        return AccountBackup.read(zBlob, zPassword);
    }

    /** OFFLINE restore into a data dir (the node must NOT be running — operator CLI only).
     *  Counters are applied RAISE-ONLY; seed.txt is written last. */
    public static void applyRestore(Path zDataDir, BackupBundle zBundle) throws Exception {
        AccountBackup.applyRestore(zDataDir, zBundle,
                uses -> CloudKeyUses.importRaiseOnly(new File(zDataDir.toFile(), "wallet"), uses));
    }

    static String readPhrase(Path zDataDir) throws Exception {
        return new String(Files.readAllBytes(zDataDir.resolve("seed.txt")),
                StandardCharsets.UTF_8).trim();
    }
}
