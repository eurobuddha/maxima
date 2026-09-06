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
        return export(zSource, null, zNodeStore, null, zDisplayName, zPassword);
    }

    /** The file names under a data dir that make an account portable (besides the stores). */
    public static final String DEVICES_FILE = "devices.json";
    public static final String SETTINGS_FILE = "cloud-settings.properties";
    public static final String NODE_DIR = "node";
    public static final String CHAT_DIR = "chat";

    /**
     * Build + encrypt the PORTABLE ACCOUNT bundle: the v1 fields every reader knows (phrase,
     * name, MLS, contacts, key uses) PLUS, when {@code zDataDir} is given, the account block -
     * the paired devices, the host settings, every collection and log of the node and chat
     * stores. Both stores must be the LIVE instances (flushed by the caller first). The
     * result restores on any host - cloud or node - with the same identity, so the same
     * MAX#, the same paired devices, the same contacts and the same chat history.
     */
    public static byte[] export(Source zSource, Path zDataDir, FileStore zNodeStore,
                                com.eurobuddha.maxima.core.store.Store zChatStore,
                                String zDisplayName, char[] zPassword) throws Exception {
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
        if (zDataDir != null) {
            b.accountFormat = BackupBundle.ACCOUNT_FORMAT;
            Path devices = zDataDir.resolve(DEVICES_FILE);
            if (Files.isRegularFile(devices)) {
                b.devicesJson = new String(Files.readAllBytes(devices), StandardCharsets.UTF_8);
            }
            Path settings = zDataDir.resolve(SETTINGS_FILE);
            if (Files.isRegularFile(settings)) {
                java.util.Properties p = new java.util.Properties();
                try (java.io.InputStream in = Files.newInputStream(settings)) {
                    p.load(in);
                }
                for (String k : p.stringPropertyNames()) {
                    b.settings.put(k, p.getProperty(k));
                }
            }
            b.stores.put(NODE_DIR, dumpCollections(zDataDir.resolve(NODE_DIR), zNodeStore));
            b.logs.put(NODE_DIR, dumpLogs(zDataDir.resolve(NODE_DIR), zNodeStore));
            if (zChatStore != null) {
                b.stores.put(CHAT_DIR, dumpCollections(zDataDir.resolve(CHAT_DIR), zChatStore));
                b.logs.put(CHAT_DIR, dumpLogs(zDataDir.resolve(CHAT_DIR), zChatStore));
            }
        }
        return BackupCrypto.encrypt(b.toJson().getBytes(StandardCharsets.UTF_8), zPassword);
    }

    /** Every keyed collection of a FileStore directory ({@code <name>.tsv}), read through the
     *  LIVE store so write-behind state is included. */
    private static Map<String, Map<String, String>> dumpCollections(
            Path zDir, com.eurobuddha.maxima.core.store.Store zLive) {
        Map<String, Map<String, String>> out = new java.util.LinkedHashMap<>();
        for (String name : names(zDir, ".tsv")) {
            out.put(name, new java.util.LinkedHashMap<>(zLive.all(name)));
        }
        return out;
    }

    private static Map<String, java.util.List<String>> dumpLogs(
            Path zDir, com.eurobuddha.maxima.core.store.Store zLive) {
        Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        for (String name : names(zDir, ".log")) {
            out.put(name, new java.util.ArrayList<>(zLive.read(name)));
        }
        return out;
    }

    private static java.util.List<String> names(Path zDir, String zSuffix) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] files = zDir.toFile().listFiles();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && n.endsWith(zSuffix) && n.length() > zSuffix.length()) {
                    out.add(n.substring(0, n.length() - zSuffix.length()));
                }
            }
        }
        return out;
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
        applyRestore(zDataDir, zBundle, zUses, "seed.txt");
    }

    /**
     * As {@link #applyRestore(Path, BackupBundle, KeyUsesImporter)} with the host's identity
     * file named: {@code seed.txt} on parlons-cloud, {@code identity.txt} on a Parlons Node
     * (whose WALLET stays on its own vault - only the identity travels). A bundle with the
     * account block restores the paired devices, the host settings, every node and chat
     * collection and the logs too; a plain v1 bundle restores what it always did.
     */
    public static void applyRestore(Path zDataDir, BackupBundle zBundle, KeyUsesImporter zUses,
                                    String zIdentityFile) throws Exception {
        Path seedFile = zDataDir.resolve(zIdentityFile);
        if (Files.exists(seedFile)) {
            throw new IllegalStateException("this data dir already holds an identity ("
                    + seedFile + ") — move it away first; restore never overwrites");
        }
        Files.createDirectories(zDataDir);
        FileStore node = new FileStore(new File(zDataDir.toFile(), NODE_DIR));
        if (zBundle.hasAccount()) {
            Map<String, Map<String, String>> nodeCols = zBundle.stores.get(NODE_DIR);
            if (nodeCols != null) {
                for (Map.Entry<String, Map<String, String>> col : nodeCols.entrySet()) {
                    for (Map.Entry<String, String> e : col.getValue().entrySet()) {
                        if ("settings".equals(col.getKey()) && "staticmls".equals(e.getKey())) {
                            continue;   // the anchor belongs to the OLD host - see below
                        }
                        node.put(col.getKey(), e.getKey(), e.getValue());
                    }
                }
            }
            Map<String, java.util.List<String>> nodeLogs = zBundle.logs.get(NODE_DIR);
            if (nodeLogs != null) {
                for (Map.Entry<String, java.util.List<String>> l : nodeLogs.entrySet()) {
                    node.rewrite(l.getKey(), l.getValue());
                }
            }
            Map<String, Map<String, String>> chatCols = zBundle.stores.get(CHAT_DIR);
            Map<String, java.util.List<String>> chatLogs = zBundle.logs.get(CHAT_DIR);
            if (chatCols != null || chatLogs != null) {
                FileStore chat = new FileStore(new File(zDataDir.toFile(), CHAT_DIR));
                if (chatCols != null) {
                    for (Map.Entry<String, Map<String, String>> col : chatCols.entrySet()) {
                        for (Map.Entry<String, String> e : col.getValue().entrySet()) {
                            chat.put(col.getKey(), e.getKey(), e.getValue());
                        }
                    }
                }
                if (chatLogs != null) {
                    for (Map.Entry<String, java.util.List<String>> l : chatLogs.entrySet()) {
                        chat.rewrite(l.getKey(), l.getValue());
                    }
                }
                chat.flush();
            }
            if (zBundle.devicesJson != null && !zBundle.devicesJson.trim().isEmpty()) {
                writePrivate(zDataDir.resolve(DEVICES_FILE), zBundle.devicesJson);
            }
            if (!zBundle.settings.isEmpty()) {
                java.util.Properties p = new java.util.Properties();
                for (Map.Entry<String, String> e : zBundle.settings.entrySet()) {
                    p.setProperty(e.getKey(), e.getValue());
                }
                try (java.io.OutputStream out = Files.newOutputStream(zDataDir.resolve(SETTINGS_FILE))) {
                    p.store(out, "parlons cloud account settings (restored)");
                }
            }
        }
        // The v1 fields win over the block's copies (they are the same data; a v1-only bundle
        // has nothing else).
        for (Map.Entry<String, String> e : zBundle.contacts.entrySet()) {
            node.put("contacts", e.getKey(), e.getValue());
        }
        if (zBundle.displayName != null && !zBundle.displayName.isEmpty()) {
            node.put("settings", "name", zBundle.displayName);
        }
        // The pinned MLS anchor is NOT restored: it names the old host's relay, which is being
        // retired. The account pins a fresh, reachable anchor on its first boot here; the old
        // MAX# (same key, old anchor) keeps resolving through the fleet's replicated directory.
        node.flush();
        if (zUses != null) {
            zUses.importRaiseOnly(zBundle.keyUses);
        }
        try {
            writePrivate(seedFile, zBundle.phrase.trim());
        } catch (Exception e) {
            try { Files.deleteIfExists(seedFile); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** Create-or-replace a 0600 text file. */
    private static void writePrivate(Path zFile, String zText) throws Exception {
        Files.deleteIfExists(zFile);
        try {
            Files.createFile(zFile, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            // non-POSIX FS — plain create below
        }
        Files.write(zFile, zText.getBytes(StandardCharsets.UTF_8));
    }
}
