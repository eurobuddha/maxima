package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Headless Maxima relay.
 *
 * CLI rules, learned from getting them wrong in 0.1.0:
 *   - informational flags NEVER touch disk. --help used to fall through into
 *     the start path and generate a seed phrase, which is wallet-grade
 *     material created by asking for help.
 *   - unknown flags are an ERROR. Silently ignoring a typo meant --pot 9501
 *     quietly listened on the default port instead.
 *   - a port already in use gets an explanation, not a stack trace.
 */
public final class Main {

    /** Build version. Keep in step with dist/ and the app's versionName. */
    public static final String VERSION = "0.1.9";

    private static final int DEFAULT_PORT = 9001;
    private static final String DEFAULT_PROTOCOL = "1.0.48";
    private static final int DEFAULT_RATE = 600;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String data = System.getProperty("user.home") + "/.maxima";
        String host = "";
        int rate = DEFAULT_RATE;
        String protocol = DEFAULT_PROTOCOL;
        boolean selftest = false;

        // --- parse first, do nothing else, so informational flags are pure ---
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    usage(System.out);
                    return;
                case "-v":
                case "--version":
                    System.out.println("maxima-server " + VERSION);
                    return;
                case "--port":
                    port = intArg(args, ++i, "--port");
                    if (port < 1 || port > 65535) {
                        fail("--port must be 1-65535, got " + port);
                    }
                    break;
                case "--host":
                    host = strArg(args, ++i, "--host");
                    break;
                case "--data":
                    data = strArg(args, ++i, "--data");
                    break;
                case "--rate":
                    rate = intArg(args, ++i, "--rate");
                    if (rate < 1 || rate > 1_000_000) {
                        // A negative/zero rate self-DoSes (every message fails);
                        // a huge one disables the flood defence. Bound both.
                        fail("--rate must be 1-1000000, got " + rate);
                    }
                    break;
                case "--selftest":
                    selftest = true;
                    break;
                case "--protocol":
                    protocol = strArg(args, ++i, "--protocol");
                    break;
                default:
                    // Silently ignoring this is how you end up on the wrong port.
                    System.err.println("Unknown option: " + a);
                    System.err.println();
                    usage(System.err);
                    System.exit(2);
            }
        }

        if (selftest) {
            System.exit(SelfTest.run(port, protocol));
        }

        try {
            run(port, data, rate, protocol, host);
        } catch (BindException be) {
            System.err.println();
            System.err.println("ERROR: port " + port + " is already in use.");
            System.err.println();
            System.err.println("  Something else is bound to it - very likely a Minima node,");
            System.err.println("  which uses 9001 by default. Pick a free port:");
            System.err.println();
            System.err.println("      java -jar maxima-server-" + VERSION + ".jar --port 9501");
            System.err.println();
            System.err.println("  Check what holds it with:  lsof -i :" + port);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("ERROR: " + e);
            System.exit(1);
        }
    }

    private static void run(int port, String data, int rate, String protocol, String host)
            throws Exception {
        Path dir = Paths.get(data);
        Files.createDirectories(dir);
        Path seedFile = dir.resolve("seed.txt");

        String phrase;
        if (Files.exists(seedFile)) {
            phrase = new String(Files.readAllBytes(seedFile), StandardCharsets.UTF_8).trim();
            System.out.println("Loaded identity from " + seedFile);
        } else {
            List<String> words = Bip39.generate(24);
            phrase = String.join(" ", words);
            // Create the seed file 0600 ATOMICALLY (perms baked into creation),
            // then write. The old order - write, THEN chmod - left the spendable
            // wallet seed world/group-readable for a window, and silently kept it
            // that way if the chmod failed. A seed we can't protect is not a seed
            // we keep: fail loudly instead.
            try {
                Files.createFile(seedFile, java.nio.file.attribute.PosixFilePermissions
                        .asFileAttribute(java.nio.file.attribute.PosixFilePermissions
                                .fromString("rw-------")));
                Files.write(seedFile, phrase.getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.WRITE);
            } catch (UnsupportedOperationException nonPosix) {
                // Non-POSIX filesystem (unusual for a relay host). Best effort.
                Files.write(seedFile, phrase.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(seedFile);
                } catch (Exception ignored) {
                }
                throw new IllegalStateException(
                        "refusing to store the seed without owner-only permissions", e);
            }
            System.out.println("Generated a NEW identity at " + seedFile);
            System.out.println();
            System.out.println("  !! This phrase is also a Minima WALLET seed. Back it up like money.");
            if (System.console() != null) {
                // Interactive: the operator is looking at a terminal, so show it.
                System.out.println("  " + phrase);
            } else {
                // Under systemd, stdout is a LOG FILE. Printing a wallet seed
                // into a file that gets rotated, backed up and shipped to a log
                // collector is how a seed leaks - and the operator has no way to
                // un-print it. The seed file itself is 0600; point at that.
                System.out.println("  Not printed here: stdout is not a terminal and this");
                System.out.println("  would write a spendable seed into a log file.");
                System.out.println("  Read it once, over ssh:   cat " + seedFile);
            }
            System.out.println();
        }

        MaximaIdentity id = MaximaIdentity.fromPhrase(phrase);

        System.out.println("Maxima relay " + VERSION + " starting");
        System.out.println("  identity : " + id.mxIdentity().substring(0, 44) + "...");
        System.out.println("  port     : " + port);
        System.out.println("  data     : " + dir);
        System.out.println("  rate cap : " + rate + " msg/min per destination");

        RelayServer relay = new RelayServer(id, port, protocol);
        relay.setRateLimit(rate);
        relay.setPublicHost(host);
        // Durable mailbox under <data>/mailbox, so a restart does not lose the
        // ciphertext we are holding for offline peers.
        relay.setStore(new com.eurobuddha.maxima.core.store.FileStore(
                dir.resolve("relaystore").toFile()));
        relay.start();

        System.out.println("  listening on 0.0.0.0:" + port);
        System.out.println();
        System.out.println("  Clients reach you at <their-Mx-key>@<your-public-ip>:" + port);
        System.out.println("  This port must be open to the internet or the relay cannot relay.");
        System.out.println();

        // Flush write-behind mail on shutdown so a clean stop loses nothing.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            relay.flush();
            relay.stop();
        }));

        while (true) {
            Thread.sleep(30_000);
            // Maintenance: expire directory entries, sweep the rate maps, and
            // flush write-behind mail to disk (one rewrite per dirty collection,
            // not per stored item).
            relay.maintain();
            relay.flush();
            System.out.printf("[relay] conns=%d routes=%d relayed=%d stored=%d dropped=%d mail=%d dir=%d%n",
                    relay.connectionCount(), relay.routeCount(),
                    relay.relayedCount(), relay.storedCount(), relay.droppedCount(),
                    relay.mailbox().totalItems(), relay.directory().size());
        }
    }

    // ---------------------------------------------------------------

    private static void usage(java.io.PrintStream out) {
        out.println("maxima-server " + VERSION + " - Maxima relay, directory and mailbox");
        out.println();
        out.println("USAGE");
        out.println("  java -jar maxima-server-" + VERSION + ".jar [options]");
        out.println();
        out.println("OPTIONS");
        out.println("  --port <n>       TCP port to listen on         (default " + DEFAULT_PORT + ")");
        out.println("  --data <dir>     data directory                (default ~/.maxima)");
        out.println("  --host <ip>      public address to advertise   (default: say nothing,");
        out.println("                   which is right unless clients reach you at a DIFFERENT");
        out.println("                   address from the one they should keep using)");
        out.println("  --rate <n>       max messages/min per peer     (default " + DEFAULT_RATE + ")");
        out.println("  --protocol <s>   greeting version string       (default " + DEFAULT_PROTOCOL + ")");
        out.println("  --selftest       run an on-box test and exit (no firewall involved)");
        out.println("  -v, --version    print the version and exit");
        out.println("  -h, --help       print this and exit");
        out.println();
        out.println("EXAMPLES");
        out.println("  # 9001 is the Minima default - use another port if a node has it");
        out.println("  java -jar maxima-server-" + VERSION + ".jar --port 9501");
        out.println();
        out.println("  java -jar maxima-server-" + VERSION + ".jar --port 9501 --data /var/lib/maxima");
        out.println();
        out.println("NOTES");
        out.println("  On first run a seed phrase is generated and written to <data>/seed.txt");
        out.println("  (mode 600). That phrase is ALSO a spendable Minima wallet seed - back it");
        out.println("  up like money. Reusing the same phrase reproduces the same identity.");
        out.println();
        out.println("  The listening port must be reachable from the public internet, otherwise");
        out.println("  no client can attach and the relay has nothing to relay.");
    }

    private static String strArg(String[] args, int i, String name) {
        if (i >= args.length) {
            fail(name + " needs a value");
        }
        return args[i];
    }

    private static int intArg(String[] args, int i, String name) {
        String s = strArg(args, i, name);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            fail(name + " needs a number, got '" + s + "'");
            return -1;
        }
    }

    private static void fail(String msg) {
        System.err.println("ERROR: " + msg);
        System.err.println();
        usage(System.err);
        System.exit(2);
    }
}
