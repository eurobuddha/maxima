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

    public static final String ACCOUNT_FILE = AccountFiles.ACCOUNT_FILE;   // the tenant's permanent MAX# address
    public static final String INVITE_FILE = AccountFiles.INVITE_FILE;     // MAX#…?code=… - one QR pairs a phone
    public static final String STOP_MARKER = ".stop";          // touch it to stop that tenant only
    static final long POLL_MS = 5_000;
    static final long RETRY_FAILED_MS = 60_000;

    /** The invite a phone scans once: {@code MAX#…#Mx…@host:port?code=XXXX-XXXX-XXXX}. Null until
     *  both halves exist. A bare MAX# stays valid everywhere; the code half is one-time. */
    public static String invite(String zPermanent, String zCode) {
        return AccountFiles.invite(zPermanent, zCode);
    }

    /** Sub-folders that are not running yet (hot-add), sorted by name. */
    public static List<Path> newTenants(Path zTenantsDir, java.util.Collection<Path> zRunning) throws Exception {
        List<Path> out = new ArrayList<>();
        for (Path p : list(zTenantsDir)) {
            if (!zRunning.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    public static boolean stopRequested(Path zDir) {
        return Files.exists(zDir.resolve(STOP_MARKER));
    }

    /** Keep {@code account.txt} and {@code invite.txt} current for one tenant (see {@link AccountFiles}). */
    public static boolean refreshFiles(Path zDir, String zPermanent) throws Exception {
        return AccountFiles.refresh(zDir, zPermanent);
    }

    /**
     * The operator's side of hot-add: make {@code <dir>/<name>/} and wait for the RUNNING host to
     * bring it up and write its invite. Prints the address, the pair code and the invite in full.
     * @return the invite, or null when the host did not answer within the wait
     */
    public static String newTenant(Path zTenantsDir, String zName, long zWaitMs) throws Exception {
        if (zName == null || !zName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("tenant name: letters, digits, . _ - (1-64 chars)");
        }
        Path dir = zTenantsDir.resolve(zName);
        boolean existed = Files.isDirectory(dir);
        Files.createDirectories(dir);
        System.out.println((existed ? "tenant exists: " : "tenant folder made: ") + dir);
        System.out.println("waiting for the host to start it" + (existed ? " (or for its invite)" : "") + "…");
        long deadline = System.currentTimeMillis() + zWaitMs;
        Path invite = dir.resolve(INVITE_FILE);
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(invite)) {
                String inv = new String(Files.readAllBytes(invite), StandardCharsets.UTF_8).trim();
                if (inv.startsWith("MAX#") && inv.contains("?code=")) {
                    String code = inv.substring(inv.indexOf("?code=") + 6);
                    String address = inv.substring(0, inv.indexOf("?code="));
                    System.out.println();
                    System.out.println("tenant " + zName + " is up.");
                    System.out.println("  address:   " + address);
                    System.out.println("  pair code: " + code + "   (one-time)");
                    System.out.println("  invite:    " + inv);
                    System.out.println("  Hand the invite to the user as a QR; the iPhone app scans it and pairs.");
                    return inv;
                }
            }
            Thread.sleep(500);
        }
        System.err.println("no invite after " + (zWaitMs / 1000) + " s - is the host running?"
                + "  (systemctl status parlons-tenants; it polls " + zTenantsDir + " every 5 s)");
        return null;
    }

    /** One running multi-account host: starts every folder, then keeps polling for more. */
    static final class Host {
        final Path dir;
        final ParlonsCore.Config base;
        final char[] unlock;
        final java.util.Map<Path, ParlonsCore> cores = new java.util.LinkedHashMap<>();
        final java.util.Map<Path, Long> failedAt = new java.util.HashMap<>();
        RelayRuntime shared;

        Host(Path zDir, ParlonsCore.Config zBase, char[] zUnlock) {
            dir = zDir; base = zBase; unlock = zUnlock;
        }

        void start(Path zTenant) throws Exception {
            final String phrase = phraseFor(zTenant, unlock);
            MaximaIdentity id = MaximaIdentity.fromPhrase(phrase);
            ParlonsCore.Config cfg = copy(base);
            cfg.logTag = "parlons-cloud/" + zTenant.getFileName();
            cfg.displayName = null;   // each account keeps its own stored name
            boolean first = cores.isEmpty();
            if (!first) {
                cfg.relayPort = 0;    // one relay per host, shared below
                cfg.directPort = 0;   // one Tier-2 listener per host (the first tenant's)
            }
            AccountWallet wallet = new CloudAccountWallet(id, zTenant);
            AccountBackup.Source backup = new AccountBackup.Source() {
                public String phrase() { return phrase; }
                public java.util.Map<String, Integer> keyUses() {
                    return CloudKeyUses.exportAll(new File(zTenant.toFile(), "wallet"));
                }
            };
            ParlonsCore core = new ParlonsCore(id, zTenant, cfg, wallet, backup);
            if (!first && shared != null) {
                core.useExternalRelay(shared);
            }
            core.start();
            if (first) {
                shared = core.relayRuntime();
            }
            cores.put(zTenant, core);
            System.out.println("  tenant " + zTenant.getFileName() + ": " + id.mxIdentity()
                    + (Files.isRegularFile(zTenant.resolve(SEED_ENC)) ? " (seed encrypted at rest)" : ""));
        }

        /** One pass: stop marked tenants, start new folders, refresh account/invite files. */
        void poll() {
            long now = System.currentTimeMillis();
            for (java.util.Iterator<java.util.Map.Entry<Path, ParlonsCore>> it = cores.entrySet().iterator(); it.hasNext();) {
                java.util.Map.Entry<Path, ParlonsCore> e = it.next();
                if (stopRequested(e.getKey())) {
                    System.out.println("  tenant " + e.getKey().getFileName() + ": stopped (" + STOP_MARKER + ")");
                    try { e.getValue().shutdown(); } catch (Exception ignored) { }
                    it.remove();
                    continue;
                }
                try {
                    refreshFiles(e.getKey(), e.getValue().node().permanentAddress());
                } catch (Exception ex) {
                    System.out.println("  tenant " + e.getKey().getFileName() + ": files: " + ex);
                }
            }
            try {
                for (Path p : newTenants(dir, cores.keySet())) {
                    if (stopRequested(p)) {
                        continue;   // parked: remove .stop to start it
                    }
                    Long f = failedAt.get(p);
                    if (f != null && now - f < RETRY_FAILED_MS) {
                        continue;
                    }
                    try {
                        System.out.println("hot-add: " + p.getFileName());
                        start(p);
                        failedAt.remove(p);
                    } catch (Exception ex) {
                        failedAt.put(p, now);
                        System.out.println("  tenant " + p.getFileName() + ": failed to start (" + ex + "), retry in 60 s");
                    }
                }
            } catch (Exception ex) {
                System.out.println("tenant scan: " + ex);
            }
        }

        void shutdownAll() {
            for (ParlonsCore c : cores.values()) {
                try { c.shutdown(); } catch (Exception ignored) { }
            }
        }
    }

    /** Run every tenant in this process, keep watching the folder for more; blocks forever. */
    public static void run(Path zTenantsDir, ParlonsCore.Config zBase, char[] zUnlock) throws Exception {
        Files.createDirectories(zTenantsDir);
        List<Path> dirs = list(zTenantsDir);
        System.out.println("Parlons Cloud " + Main.VERSION + " multi-account host: " + dirs.size()
                + " tenant(s) under " + zTenantsDir + (dirs.isEmpty()
                ? " - none yet; make <dir>/<name>/ (or `--tenant-new`) and it starts within 5 s" : ""));
        Host host = new Host(zTenantsDir, zBase, zUnlock);
        Runtime.getRuntime().addShutdownHook(new Thread(host::shutdownAll, "parlons-tenants-shutdown"));
        for (Path dir : dirs) {
            if (stopRequested(dir)) {
                System.out.println("  tenant " + dir.getFileName() + ": parked (" + STOP_MARKER + " present)");
                continue;
            }
            host.start(dir);
        }
        System.out.println();
        System.out.println("  Running " + host.cores.size() + " account(s). Each has its own pair-code.txt,"
                + " account.txt and invite.txt (cat <tenants>/<name>/invite.txt). New folders start"
                + " within 5 s; touch <name>/" + STOP_MARKER + " to stop one.");
        while (true) {
            Thread.sleep(POLL_MS);
            host.poll();
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
