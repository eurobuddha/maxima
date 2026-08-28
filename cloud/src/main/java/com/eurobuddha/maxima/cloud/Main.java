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
    public static final String VERSION = "0.4.0";

    private static final int DEFAULT_RELAY_PORT = 9501;
    private static final int DEFAULT_DIRECT_PORT = 9536;
    private static final int DEFAULT_BLOB_MB = 1024;

    public static void main(String[] args) {
        String data = System.getProperty("user.home") + "/.parlons";
        ParlonsCore.Config cfg = new ParlonsCore.Config();
        cfg.relayPort = DEFAULT_RELAY_PORT;
        cfg.directPort = DEFAULT_DIRECT_PORT;
        cfg.relayBlobMb = DEFAULT_BLOB_MB;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    usage(System.out);
                    return;
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
                    cfg.extraRelays = csv(strArg(args, ++i, "--relays"));
                    break;
                case "--peers":
                    cfg.meshPeers = csv(strArg(args, ++i, "--peers"));
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
        System.out.println("  name     : " + cfg.displayName);
        System.out.println("  relay    : " + (cfg.relayPort > 0
                ? ("pool host on port " + cfg.relayPort) : "off (--no-relay)"));
        System.out.println("  direct   : " + (cfg.directPort > 0
                ? ("Tier-2 reachability on port " + cfg.directPort) : "off"));

        ParlonsCore core = new ParlonsCore(id, dir, cfg);

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
        out.println("  --name <name>       display name for your account  (default 'Parlons Cloud')");
        out.println("  --relay-port <n>    pool-relay port                (default " + DEFAULT_RELAY_PORT + ")");
        out.println("  --no-relay          run the account WITHOUT a pool relay");
        out.println("  --direct-port <n>   Tier-2 direct reachability port (default " + DEFAULT_DIRECT_PORT + ")");
        out.println("  --no-direct         disable direct reachability");
        out.println("  --host <ip>         public address to advertise    (default: say nothing)");
        out.println("  --relays <list>     extra fleet relays to attach to (comma-separated)");
        out.println("  --peers <list>      mesh peers for the pool relay   (comma-separated; or MAXIMA_PEERS)");
        out.println("  --blobstore <MB>    relay media shelf size in MB    (default " + DEFAULT_BLOB_MB + ")");
        out.println("  -v, --version       print version and exit");
        out.println("  -h, --help          this help");
        out.println();
        out.println("  The 24-word seed at <data>/seed.txt is your identity AND wallet. Back it up.");
    }
}
