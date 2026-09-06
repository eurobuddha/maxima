package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.net.BindException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Parlons Cloud — headless entry point.
 *
 * Runs a full Parlons node (identity + chat) plus an in-process pool relay on a VPS.
 * Phase 1: it holds the identity, attaches to the fleet as the sole holder of its routing
 * key, drains its mailbox, sends/receives, and serves the fleet as a pool relay. Remote
 * control (device pairing / owner channel) and the watch-only wallet come in later phases.
 *
 * The 24-word seed is wallet-grade + the comms identity — same handling rules as the relay:
 * never printed into a non-terminal (a systemd log), stored 0600, read once over ssh.
 */
public final class Main {

    /** Build version. Independent of the relay's server VERSION. */
    public static final String VERSION = "0.11.38";

    private static final int DEFAULT_RELAY_PORT = 9501;
    private static final int DEFAULT_DIRECT_PORT = 9536;
    private static final int DEFAULT_BLOB_MB = 1024;

    public static void main(String[] args) {
        String data = System.getProperty("user.home") + "/.parlons";
        ParlonsCore.Config cfg = new ParlonsCore.Config();
        cfg.version = VERSION;
        cfg.relayPort = DEFAULT_RELAY_PORT;
        cfg.directPort = DEFAULT_DIRECT_PORT;
        cfg.relayBlobMb = DEFAULT_BLOB_MB;

        String importSeedArg = null;
        String restoreArg = null;
        String tenantsArg = null;
        String unlockArg = null;
        boolean encryptSeeds = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    usage(System.out);
                    return;
                case "--import-seed":
                    importSeedArg = strArg(args, ++i, "--import-seed");
                    break;
                case "--restore":
                    restoreArg = strArg(args, ++i, "--restore");
                    break;
                case "--tenants":
                    tenantsArg = strArg(args, ++i, "--tenants");
                    break;
                case "--unlock":
                    unlockArg = strArg(args, ++i, "--unlock");
                    break;
                case "--encrypt-seeds":
                    encryptSeeds = true;
                    break;
                case "-v":
                case "--version":
                    System.out.println("parlons-cloud " + VERSION);
                    return;
                case "--data":
                    data = strArg(args, ++i, "--data");
                    break;
                case "--name":
                    cfg.displayName = strArg(args, ++i, "--name");
                    break;
                case "--relay-port":
                    cfg.relayPort = intArg(args, ++i, "--relay-port");
                    break;
                case "--direct-port":
                    cfg.directPort = intArg(args, ++i, "--direct-port");
                    break;
                case "--host":
                    cfg.publicHost = strArg(args, ++i, "--host");
                    break;
                case "--relays":
                    // Keep the copy-on-write list type: runtime host add/detach mutates this from
                    // the node pump while configuredHosts()/persist iterate it.
                    cfg.extraRelays = new java.util.concurrent.CopyOnWriteArrayList<>(
                            csv(strArg(args, ++i, "--relays")));
                    break;
                case "--peers":
                    cfg.meshPeers = new java.util.concurrent.CopyOnWriteArrayList<>(
                            csv(strArg(args, ++i, "--peers")));
                    break;
                case "--no-builtin-relays":
                    cfg.builtInRelays = false;   // seeds are --relays (and remembered relays) only
                    break;
                case "--blobstore":
                    cfg.relayBlobMb = intArg(args, ++i, "--blobstore");
                    break;
                case "--no-relay":
                    cfg.relayPort = 0;
                    break;
                case "--no-direct":
                    cfg.directPort = 0;
                    break;
                default:
                    System.err.println("Unknown option: " + a);
                    System.err.println();
                    usage(System.err);
                    System.exit(2);
            }
        }

        // Env fallback for the mesh peer list, so a systemd unit can carry it without argv.
        if (cfg.meshPeers.isEmpty()) {
            String env = System.getenv("MAXIMA_PEERS");
            if (env != null && !env.trim().isEmpty()) {
                cfg.meshPeers = csv(env);
            }
        }

        try {
            if (importSeedArg != null) {
                importSeed(Paths.get(data), importSeedArg);
                return;
            }
            if (restoreArg != null) {
                restoreBackup(Paths.get(data), restoreArg);
                return;
            }
            if (tenantsArg != null) {
                char[] unlock = Tenants.unlockPassphrase(unlockArg, encryptSeeds);
                if (encryptSeeds) {
                    Tenants.encryptSeeds(Paths.get(tenantsArg), unlock);
                    return;
                }
                Tenants.run(Paths.get(tenantsArg), cfg, unlock);
                return;
            }
            run(Paths.get(data), cfg);
        } catch (BindException be) {
            System.err.println();
            System.err.println("ERROR: relay port " + cfg.relayPort + " is already in use.");
            System.err.println("  Pick a free one with --relay-port, or --no-relay to run the");
            System.err.println("  account without contributing a pool relay.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("ERROR: " + e);
            System.exit(1);
        }
    }

    /**
     * Import an EXISTING 24-word phrase as this node's identity+wallet seed. Operator-only
     * (console); refuses to overwrite. Value is a file path holding the phrase, or "prompt"
     * to type it interactively (console echo — use a private terminal).
     */
    private static void importSeed(Path dir, String zSource) throws Exception {
        Path seedFile = dir.resolve("seed.txt");
        if (java.nio.file.Files.exists(seedFile)) {
            System.err.println("ERROR: " + seedFile + " already exists — move the old data dir "
                    + "away first; import never overwrites an identity.");
            System.exit(1);
        }
        String phrase;
        if ("prompt".equalsIgnoreCase(zSource)) {
            java.io.Console console = System.console();
            if (console == null) {
                System.err.println("ERROR: no console — pass a file path instead.");
                System.exit(1);
                return;
            }
            phrase = new String(console.readPassword("Paste the 24-word phrase: "));
        } else {
            phrase = new String(java.nio.file.Files.readAllBytes(Paths.get(zSource)),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        phrase = com.eurobuddha.maxima.core.identity.Bip39.cleanSeedPhrase(phrase);
        if (!com.eurobuddha.maxima.core.identity.Bip39.checksumValid(
                java.util.Arrays.asList(phrase.split(" ")))) {
            System.out.println("note: no valid BIP39 checksum — normal for a Minima-node phrase.");
        }
        java.nio.file.Files.createDirectories(dir);
        try {
            java.nio.file.Files.createFile(seedFile,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
        }
        java.nio.file.Files.write(seedFile,
                phrase.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("Identity imported to " + seedFile);
        System.out.println();
        System.out.println("  !! SINGLE-HOLDER RULE: this phrase must now live ONLY here. If a");
        System.out.println("     phone was using it, resync that phone's wallet to a NEW seed");
        System.out.println("     first — two key-use counters over one seed can leak a key.");
    }

    /** Restore a phone/cloud .pbk encrypted backup into a FRESH data dir. Operator-only. */
    private static void restoreBackup(Path dir, String zPbkPath) throws Exception {
        java.io.Console console = System.console();
        if (console == null) {
            System.err.println("ERROR: --restore needs an interactive console (passphrase).");
            System.exit(1);
            return;
        }
        byte[] blob = java.nio.file.Files.readAllBytes(Paths.get(zPbkPath));
        char[] pw = console.readPassword("Backup passphrase: ");
        BackupBundle b;
        try {
            b = CloudBackupManager.read(blob, pw);
        } catch (javax.crypto.AEADBadTagException bad) {
            System.err.println("ERROR: wrong passphrase (or a damaged backup file).");
            System.exit(1);
            return;
        } finally {
            java.util.Arrays.fill(pw, '\0');
        }
        CloudBackupManager.applyRestore(dir, b);
        int chatRows = 0;
        java.util.Map<String, java.util.Map<String, String>> chat = b.stores.get(AccountBackup.CHAT_DIR);
        if (chat != null) {
            for (java.util.Map<String, String> col : chat.values()) {
                chatRows += col.size();
            }
        }
        int devices = 0;
        try {
            org.json.JSONArray auth = new org.json.JSONObject(b.devicesJson).optJSONArray("authorized");
            devices = auth == null ? 0 : auth.length();
        } catch (Exception ignored) {
            // no or unreadable devices block: reported as 0
        }
        System.out.println("Restored: " + (b.displayName.isEmpty() ? "(unnamed)" : b.displayName)
                + " · " + b.contacts.size() + " contact(s) · " + b.keyUses.size()
                + " key-use counter(s) (raise-only)"
                + (b.hasAccount() ? " · " + devices + " paired device(s), settings and " + chatRows
                        + " chat record(s) (portable account bundle)"
                        : " · (an older backup: contacts and identity only)"));
        System.out.println();
        System.out.println("  !! The node this backup came from must be STOPPED for good — two");
        System.out.println("     holders of one seed will eventually reuse a one-time signature");
        System.out.println("     key.");
        System.out.println();
        System.out.println("  Start the node normally to bring the account online here. Paired");
        System.out.println("  devices reconnect on their own: the identity - and so the MAX# - is");
        System.out.println("  the same, and the fleet's replicated directory resolves it here.");
    }

    private static void run(Path dir, ParlonsCore.Config cfg) throws Exception {
        // Seed resolution (0600, atomic) is shared with the relay.
        RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(dir);
        if (!seed.created) {
            System.out.println("Loaded identity from " + seed.file);
        } else {
            System.out.println("Generated a NEW identity at " + seed.file);
            System.out.println();
            System.out.println("  !! This phrase is your Parlons identity AND a spendable Minima WALLET");
            System.out.println("     seed. Back it up like money. Whoever holds it IS this account.");
            if (System.console() != null) {
                System.out.println("  " + seed.phrase);
            } else {
                // Under systemd, stdout is a log file — never print a spendable seed there.
                System.out.println("  Not printed here: stdout is not a terminal and this would");
                System.out.println("  write a spendable seed into a log file.");
                System.out.println("  Read it once, over ssh:   cat " + seed.file);
            }
            System.out.println();
        }

        MaximaIdentity id = MaximaIdentity.fromPhrase(seed.phrase);

        System.out.println("Parlons Cloud " + VERSION + " starting");
        System.out.println("  data     : " + dir);
        System.out.println("  name     : " + (cfg.displayName == null
                ? "(kept from the account's stored name)" : cfg.displayName));
        System.out.println("  relay    : " + (cfg.relayPort > 0
                ? ("pool host on port " + cfg.relayPort) : "off (--no-relay)"));
        System.out.println("  direct   : " + (cfg.directPort > 0
                ? ("Tier-2 reachability on port " + cfg.directPort) : "off"));

        // The cloud wallet: key-#1000 signer over the seed + the remote MegaMMR gateway; the
        // .pbk backup reads the phrase from seed.txt and the file-backed Winternitz counters.
        AccountWallet wallet = new CloudAccountWallet(id, dir);
        AccountBackup.Source backup = new AccountBackup.Source() {
            public String phrase() throws Exception { return CloudBackupManager.readPhrase(dir); }
            public java.util.Map<String, Integer> keyUses() {
                return CloudKeyUses.exportAll(new java.io.File(dir.toFile(), "wallet"));
            }
        };
        ParlonsCore core = new ParlonsCore(id, dir, cfg, wallet, backup);

        Runtime.getRuntime().addShutdownHook(new Thread(core::shutdown, "parlons-cloud-shutdown"));
        core.start();

        System.out.println();
        System.out.println("  Running. This node holds your identity and stays online for you.");
        System.out.println("  Pair a device to drive it: see the pairing note above (cat pair-code.txt).");
        System.out.println();

        // Block forever; all work runs on the maintenance/reader threads.
        Object lock = new Object();
        synchronized (lock) {
            while (true) {
                lock.wait();
            }
        }
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String p : s.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String strArg(String[] args, int i, String flag) {
        if (i >= args.length) {
            fail(flag + " needs a value");
        }
        return args[i];
    }

    private static int intArg(String[] args, int i, String flag) {
        try {
            return Integer.parseInt(strArg(args, i, flag));
        } catch (NumberFormatException e) {
            fail(flag + " needs a number");
            return -1;
        }
    }

    private static void fail(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(2);
    }

    private static void usage(java.io.PrintStream out) {
        out.println("Parlons Cloud " + VERSION + " — your always-on Parlons account + a network relay");
        out.println();
        out.println("Usage: java -jar parlons-cloud-" + VERSION + ".jar [options]");
        out.println();
        out.println("  --data <dir>        data directory                 (default ~/.parlons)");
        out.println("  --name <name>       display name for your account  (default: keep the stored name)");
        out.println("  --relay-port <n>    pool-relay port                (default " + DEFAULT_RELAY_PORT + ")");
        out.println("  --no-relay          run the account WITHOUT a pool relay");
        out.println("  --direct-port <n>   Tier-2 direct reachability port (default " + DEFAULT_DIRECT_PORT + ")");
        out.println("  --no-direct         disable direct reachability");
        out.println("  --host <ip>         public address to advertise    (default: say nothing)");
        out.println("  --relays <list>     extra fleet relays to attach to (comma-separated)");
        out.println("  --no-builtin-relays do NOT use the compiled-in relay list as a seed source:");
        out.println("                      only --relays / relays added from the panel (needs at least one)");
        out.println("  --peers <list>      mesh peers for the pool relay   (comma-separated; or MAXIMA_PEERS)");
        out.println("  --blobstore <MB>    relay media shelf size in MB    (default " + DEFAULT_BLOB_MB + ")");
        out.println("  --import-seed <f|prompt>  adopt an EXISTING 24-word phrase (fresh dir only)");
        out.println("  --restore <file.pbk>      restore an encrypted Parlons backup (fresh dir only):");
        out.println("                            the PORTABLE ACCOUNT bundle - identity, paired devices,");
        out.println("                            settings, contacts, chat - so the account comes back");
        out.println("                            here with the same MAX#");
        out.println();
        out.println("  Multi-account host (optional, self-hostable; every tenant can leave with its bundle):");
        out.println("  --tenants <dir>     run EVERY <dir>/<name>/ account in this one process, sharing");
        out.println("                      one pool relay (the first tenant's --relay-port); a tenant dir");
        out.println("                      holds seed.txt (or seed.enc), or is filled by --restore first");
        out.println("  --unlock <prompt|env>  the passphrase that opens seed.enc files: asked on the");
        out.println("                      console, or read from PARLONS_UNLOCK (systemd). Needed when");
        out.println("                      any tenant seed is encrypted at rest");
        out.println("  --encrypt-seeds     with --tenants and --unlock: turn every tenant's seed.txt into");
        out.println("                      seed.enc (scrypt + AES-GCM under the unlock passphrase), verify,");
        out.println("                      delete the plaintext, and exit. At-rest protection for the disk");
        out.println("                      and its backups; the running process still holds the phrases.");
        out.println("  -v, --version       print version and exit");
        out.println("  -h, --help          this help");
        out.println();
        out.println("  The 24-word seed at <data>/seed.txt is your identity AND wallet. Back it up.");
    }
}
