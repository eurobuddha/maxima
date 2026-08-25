package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.net.BindException;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    public static final String VERSION = "0.4.32";

    private static final int DEFAULT_PORT = 9001;
    private static final String DEFAULT_PROTOCOL = "1.0.48";
    private static final int DEFAULT_RATE = 600;
    /** Default media shelf: 4GB, matching the MaxLite relay it replaces. */
    private static final long DEFAULT_BLOB_BYTES = 4L * 1024 * 1024 * 1024;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String data = System.getProperty("user.home") + "/.maxima";
        String host = "";
        int rate = DEFAULT_RATE;
        String protocol = DEFAULT_PROTOCOL;
        boolean selftest = false;
        long blobBytes = DEFAULT_BLOB_BYTES;
        java.util.List<String> peers = new java.util.ArrayList<>();

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
                case "--blobstore":
                    // Media shelf size in MB (0 = off). A relay/desktop hosts a
                    // lot; a Pi may want it small or disabled.
                    blobBytes = (long) intArg(args, ++i, "--blobstore") * 1024L * 1024L;
                    if (blobBytes < 0) {
                        fail("--blobstore must be >= 0 (MB)");
                    }
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
                case "--peers":
                    // Comma-separated fleet host:ports the Phase-B mesh forwards resolve
                    // misses to (bootstrap set; trust is the publisher's signed proof).
                    for (String p : strArg(args, ++i, "--peers").split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) {
                            peers.add(t);
                        }
                    }
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

        // Env fallback so the systemd unit can carry the fleet list without editing argv.
        if (peers.isEmpty()) {
            String env = System.getenv("MAXIMA_PEERS");
            if (env != null && !env.trim().isEmpty()) {
                for (String p : env.split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) {
                        peers.add(t);
                    }
                }
            }
        }

        try {
            run(port, data, rate, protocol, host, blobBytes, peers);
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

    private static void run(int port, String data, int rate, String protocol, String host, long blobBytes,
                            java.util.List<String> peers)
            throws Exception {
        Path dir = Paths.get(data);

        // Seed resolution (0600 atomic) lives in RelayRuntime; how a NEW seed is
        // shown to the operator is a CLI policy and stays here.
        RelayRuntime.Seed seed = RelayRuntime.loadOrCreateSeed(dir);
        Path seedFile = seed.file;
        if (!seed.created) {
            System.out.println("Loaded identity from " + seedFile);
        } else {
            System.out.println("Generated a NEW identity at " + seedFile);
            System.out.println();
            System.out.println("  !! This phrase is also a Minima WALLET seed. Back it up like money.");
            if (System.console() != null) {
                // Interactive: the operator is looking at a terminal, so show it.
                System.out.println("  " + seed.phrase);
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

        MaximaIdentity id = MaximaIdentity.fromPhrase(seed.phrase);

        System.out.println("Maxima relay " + VERSION + " starting");
        System.out.println("  identity : " + id.mxIdentity().substring(0, 44) + "...");
        System.out.println("  port     : " + port);
        System.out.println("  data     : " + dir);
        System.out.println("  rate cap : " + rate + " msg/min per destination");
        System.out.println("  media    : " + (blobBytes > 0
                ? (blobBytes / (1024 * 1024)) + " MB blob shelf" : "off"));

        RelayRuntime runtime = new RelayRuntime(id, port, protocol, rate, host, dir);
        runtime.setBlobBytes(blobBytes);
        runtime.setPeers(peers);
        if (!peers.isEmpty()) {
            System.out.println("  mesh     : forwarding resolve misses to " + peers.size()
                    + " bootstrap peer(s)");
        }
        runtime.setTickListener(s -> System.out.printf(
                "[relay] conns=%d routes=%d relayed=%d stored=%d dropped=%d mail=%d dir=%d%n",
                s.connections, s.routes, s.relayed, s.stored, s.dropped, s.mail, s.directory));
        runtime.start();

        // STUN on the same port number, UDP side: phones discover their public
        // address for calls from OUR fleet instead of a third party. Disable
        // with MAXIMA_STUN=false.
        if (!"false".equalsIgnoreCase(System.getenv("MAXIMA_STUN"))) {
            Thread stun = new Thread(new MiniStun(port), "ministun");
            stun.setDaemon(true);
            stun.start();
        }

        System.out.println("  listening on 0.0.0.0:" + port);
        System.out.println();
        System.out.println("  Clients reach you at <their-Mx-key>@<your-public-ip>:" + port);
        System.out.println("  This port must be open to the internet or the relay cannot relay.");
        System.out.println();

        // Flush write-behind mail on shutdown so a clean stop loses nothing.
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::stop));

        // Block forever; the maintain loop runs on RelayRuntime's own thread.
        Thread.currentThread().join();
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
        out.println("  --blobstore <MB> media shelf size in MB, 0=off (default 4096)");
        out.println("  --protocol <s>   greeting version string       (default " + DEFAULT_PROTOCOL + ")");
        out.println("  --peers <list>   comma-separated fleet host:ports to forward resolve");
        out.println("                   misses to (Phase-B MLS mesh; or env MAXIMA_PEERS)");
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
