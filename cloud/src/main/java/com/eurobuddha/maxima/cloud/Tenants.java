package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The OPTIONAL multi-account host: every {@code <dir>/<name>/} is one account's data dir - the
 * same layout parlons-cloud uses for one account, the same layout a portable bundle restores
 * into - run in one process, sharing one pool relay. Nothing about an account changes when it
 * is hosted this way: its own identity, its own paired devices, its own contacts and chat,
 * its own backup; it leaves at any time as a bundle and comes back on any node or server with
 * the same MAX#.
 *
 * What this is NOT: a trusted middle. A host operator can read a tenant's keys while the
 * process runs (an always-on account must hold its key to act for you), exactly as the
 * operator of any single-account server could. Seeds can be kept ENCRYPTED AT REST
 * ({@code seed.enc}: scrypt + AES-GCM under an unlock passphrase asked at boot) so a disk
 * image or a backup of the host is not a key dump - and a user who wants no operator at all
 * runs their own node: the host is one option among several, replaceable by design.
 *
 * Limits: every tenant attaches to ~3 fleet relays from this host's one IP; relays cap
 * unregistered connections per source IP (32 by default, {@code -Dmaxima.relay.maxpersource}),
 * so plan on about ten tenants per host before raising it on the relays you run.
 */
public final class Tenants {

    static final String SEED_PLAIN = "seed.txt";
    static final String SEED_ENC = "seed.enc";

    private Tenants() {
    }

    /** Tenant data dirs: every readable subdirectory, sorted by name. */
    public static List<Path> list(Path zTenantsDir) throws Exception {
        List<Path> out = new ArrayList<>();
        File[] dirs = zTenantsDir.toFile().listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.getName().startsWith(".")) {
                    out.add(d.toPath());
                }
            }
        }
        return out;
    }

    /** The unlock passphrase from the console or PARLONS_UNLOCK; null when none was asked for. */
    public static char[] unlockPassphrase(String zHow, boolean zRequired) {
        if (zHow == null) {
            if (zRequired) {
                throw new IllegalArgumentException("--encrypt-seeds needs --unlock prompt|env");
            }
            return null;
        }
        if ("env".equalsIgnoreCase(zHow)) {
            String v = System.getenv("PARLONS_UNLOCK");
            if (v == null || v.isEmpty()) {
                throw new IllegalArgumentException("--unlock env: PARLONS_UNLOCK is not set");
            }
            return v.toCharArray();
        }
        java.io.Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException("--unlock prompt needs an interactive console "
                    + "(use --unlock env under systemd)");
        }
        char[] pw = console.readPassword("Unlock passphrase for tenant seeds: ");
        if (pw == null || pw.length < 6) {
            throw new IllegalArgumentException("use an unlock passphrase of at least 6 characters");
        }
        return pw;
    }

    /** Encrypt a phrase for at-rest storage (the same PARLONSBK container the backups use). */
    public static byte[] encryptSeed(String zPhrase, char[] zUnlock) throws Exception {
        return BackupCrypto.encrypt(zPhrase.trim().getBytes(StandardCharsets.UTF_8), zUnlock);
    }

    public static String decryptSeed(byte[] zBlob, char[] zUnlock) throws Exception {
        return new String(BackupCrypto.decrypt(zBlob, zUnlock), StandardCharsets.UTF_8).trim();
    }

    /**
     * The tenant's phrase: {@code seed.enc} (needs the unlock passphrase) or {@code seed.txt};
     * a dir with neither gets a NEW identity - encrypted at rest when an unlock passphrase is
     * in force, plain (0600) otherwise.
     */
    public static String phraseFor(Path zDir, char[] zUnlock) throws Exception {
        Path enc = zDir.resolve(SEED_ENC);
        Path plain = zDir.resolve(SEED_PLAIN);
        if (Files.isRegularFile(enc)) {
            if (zUnlock == null) {
                throw new IllegalStateException(zDir.getFileName() + ": seed.enc needs --unlock");
            }
            return decryptSeed(Files.readAllBytes(enc), zUnlock);
        }
        if (Files.isRegularFile(plain)) {
            return new String(Files.readAllBytes(plain), StandardCharsets.UTF_8).trim();
        }
        Files.createDirectories(zDir);
        String phrase = String.join(" ", Bip39.generate(24));
        if (zUnlock != null) {
            writePrivate(enc, encryptSeed(phrase, zUnlock));
        } else {
            writePrivate(plain, phrase.getBytes(StandardCharsets.UTF_8));
        }
        return phrase;
    }

    /** seed.txt -> seed.enc for every tenant (verify, then delete the plaintext). */
    public static void encryptSeeds(Path zTenantsDir, char[] zUnlock) throws Exception {
        int n = 0;
        for (Path dir : list(zTenantsDir)) {
            Path plain = dir.resolve(SEED_PLAIN);
            if (!Files.isRegularFile(plain)) {
                continue;
            }
            String phrase = new String(Files.readAllBytes(plain), StandardCharsets.UTF_8).trim();
            byte[] blob = encryptSeed(phrase, zUnlock);
            if (!phrase.equals(decryptSeed(blob, zUnlock))) {
                throw new IllegalStateException(dir.getFileName() + ": verify failed, plaintext kept");
            }
            writePrivate(dir.resolve(SEED_ENC), blob);
            Files.delete(plain);
            System.out.println("  " + dir.getFileName() + ": seed encrypted at rest (seed.enc)");
            n++;
        }
        System.out.println(n + " tenant seed(s) encrypted. Start with --tenants " + zTenantsDir
                + " --unlock prompt|env from now on.");
    }

    /** Run every tenant in this process; blocks forever. */
    public static void run(Path zTenantsDir, ParlonsCore.Config zBase, char[] zUnlock) throws Exception {
        List<Path> dirs = list(zTenantsDir);
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("no tenant directories under " + zTenantsDir
                    + " (make one per account: <dir>/<name>/, or --restore a bundle into it)");
        }
        System.out.println("Parlons Cloud " + Main.VERSION + " multi-account host: " + dirs.size()
                + " tenant(s) under " + zTenantsDir);
        List<ParlonsCore> cores = new ArrayList<>();
        RelayRuntime shared = null;
        for (Path dir : dirs) {
            final String phrase = phraseFor(dir, zUnlock);
            MaximaIdentity id = MaximaIdentity.fromPhrase(phrase);
            ParlonsCore.Config cfg = copy(zBase);
            cfg.logTag = "parlons-cloud/" + dir.getFileName();
            cfg.displayName = null;   // each account keeps its own stored name
            boolean first = cores.isEmpty();
            if (!first) {
                cfg.relayPort = 0;    // one relay per host, shared below
                cfg.directPort = 0;   // one Tier-2 listener per host (the first tenant's)
            }
            AccountWallet wallet = new CloudAccountWallet(id, dir);
            AccountBackup.Source backup = new AccountBackup.Source() {
                public String phrase() { return phrase; }
                public java.util.Map<String, Integer> keyUses() {
                    return CloudKeyUses.exportAll(new File(dir.toFile(), "wallet"));
                }
            };
            ParlonsCore core = new ParlonsCore(id, dir, cfg, wallet, backup);
            if (!first && shared != null) {
                core.useExternalRelay(shared);
            }
            cores.add(core);
            System.out.println("  tenant " + dir.getFileName() + ": " + id.mxIdentity()
                    + (Files.isRegularFile(dir.resolve(SEED_ENC)) ? " (seed encrypted at rest)" : ""));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (ParlonsCore c : cores) {
                try { c.shutdown(); } catch (Exception ignored) { }
            }
        }, "parlons-tenants-shutdown"));
        for (int i = 0; i < cores.size(); i++) {
            ParlonsCore core = cores.get(i);
            core.start();
            if (i == 0) {
                shared = core.relayRuntime();
                for (int j = 1; j < cores.size(); j++) {
                    if (shared != null) {
                        cores.get(j).useExternalRelay(shared);
                    }
                }
            }
        }
        System.out.println();
        System.out.println("  Running " + cores.size() + " account(s). Pair a device to each with its own"
                + " pair-code.txt (cat <tenants>/<name>/pair-code.txt).");
        Object lock = new Object();
        synchronized (lock) {
            while (true) {
                lock.wait();
            }
        }
    }

    private static ParlonsCore.Config copy(ParlonsCore.Config b) {
        ParlonsCore.Config c = new ParlonsCore.Config();
        c.displayName = b.displayName;
        c.version = b.version;
        c.relayPort = b.relayPort;
        c.directPort = b.directPort;
        c.ownRelay = b.ownRelay;
        c.publicHost = b.publicHost;
        c.extraRelays = new java.util.concurrent.CopyOnWriteArrayList<>(b.extraRelays);
        c.builtInRelays = b.builtInRelays;
        c.meshPeers = new ArrayList<>(b.meshPeers);
        c.relayBlobMb = b.relayBlobMb;
        c.logTag = b.logTag;
        return c;
    }

    private static void writePrivate(Path zFile, byte[] zBytes) throws Exception {
        Files.deleteIfExists(zFile);
        try {
            Files.createFile(zFile, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            // plain create below
        }
        Files.write(zFile, zBytes);
    }
}
