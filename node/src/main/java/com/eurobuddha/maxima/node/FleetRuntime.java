package com.eurobuddha.maxima.node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.minima.system.params.GeneralParams;

/**
 * Fleet mode ({@code -Dparlons.fleet=true}): this one jar makes a fresh public Linux box a full
 * fleet endpoint for every service. On top of what the node already is (full Minima node, Maxima
 * cape, wallet gateway, DEX cape, KeetsChat bridge) it turns on the Minima server role and the
 * keets transport, and runs the keets sidecar in <b>serve</b> mode — ONE JavaScript process that
 * is the box's keets transport, its public HyperDHT bootstrap node (UDP 49737) and its keets
 * mailbox — using the Node.js runtime and sidecar carried INSIDE the fleet jar
 * ({@code parlons-node-fleet-<ver>.jar}, Gradle task {@code fleetJar}).
 *
 * <p>Resources: {@code fleet/VERSIONS} (node + sidecar versions), {@code fleet/node-linux-<arch>.gz}
 * (the bare {@code node} binary, gzip), {@code fleet/keets-sidecar.zip} (the sidecar with its
 * Linux prebuilds). They are extracted once into {@code <data>/runtime/<versions>/} (atomic rename,
 * inside the unit's ReadWritePaths) and reused on every later start.
 *
 * <p>Knobs: {@code -Dparlons.public.host=<ip>} (the address the DHT and the cape name themselves by;
 * the installer detects it), {@code -Dparlons.keets.dhtport=49737}, {@code -Dparlons.dex.public=<https url>}
 * (turns the DEX cape on behind that TLS front). Minima flags the operator set in
 * {@code -Dparlons.node.args} always win over what fleet mode would set.
 */
final class FleetRuntime {

    static final int DEFAULT_DHT_PORT = 49737;

    private final File runtimeDir;
    final String nodeVersion;
    final String sidecarVersion;
    final File nodeBinary;
    final File sidecarIndex;

    private FleetRuntime(File runtimeDir, String nodeVersion, String sidecarVersion) {
        this.runtimeDir = runtimeDir;
        this.nodeVersion = nodeVersion;
        this.sidecarVersion = sidecarVersion;
        this.nodeBinary = new File(runtimeDir, "node/bin/node");
        this.sidecarIndex = new File(runtimeDir, "keets-sidecar/index.js");
    }

    static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("parlons.fleet", "false").trim());
    }

    /**
     * Property defaults a fleet box wants, applied before anything reads them and only when the
     * operator left them unset: admin RPC on (loopback), the DEX cape on its loopback port, the
     * cape named by the public host.
     */
    static void applyPropertyDefaults() {
        if (!enabled()) return;
        setIfAbsent("parlons.node.rpc", "true");
        // The DEX cape announces its URL in the relay mesh; without a TLS front (-Dparlons.dex.public)
        // that would be a plain-http URL phones refuse, so it stays off until one is given.
        if (!System.getProperty("parlons.dex.public", "").trim().isEmpty()) setIfAbsent("parlons.dex.port", "9602");
        String host = publicHost();
        if (!host.isEmpty()) setIfAbsent("parlons.relay.host", host);
    }

    private static void setIfAbsent(String zKey, String zValue) {
        String cur = System.getProperty(zKey);
        if (cur == null || cur.trim().isEmpty()) System.setProperty(zKey, zValue);
    }

    static String publicHost() {
        return System.getProperty("parlons.public.host", "").trim();
    }

    /**
     * Extract the embedded runtime (first run) and point the embedded node's keets settings at it.
     * Exits 2 with a plain message when this jar/platform cannot do fleet duty.
     */
    static FleetRuntime apply(File zDataFolder) {
        FleetRuntime rt;
        try {
            rt = ensure(zDataFolder);
        } catch (Exception e) {
            System.err.println("[parlons-node] REFUSING to run in fleet mode: " + e.getMessage());
            System.exit(2);
            return null;
        }
        // Minima flags the operator gave in -Dparlons.node.args win; fleet mode fills the rest.
        if (!MinimaFlags.has("isclient") && !MinimaFlags.has("desktop") && !MinimaFlags.has("mobile")) {
            GeneralParams.IS_ACCEPTING_IN_LINKS = true;                    // -server
        }
        if (!MinimaFlags.has("keets")) GeneralParams.KEETS_ENABLED = true;
        if (!MinimaFlags.has("keetssidecar")) GeneralParams.KEETS_SIDECAR = rt.sidecarIndex.getAbsolutePath();
        if (!MinimaFlags.has("keetsnode")) GeneralParams.KEETS_NODE = rt.nodeBinary.getAbsolutePath();
        String host = publicHost();
        if (!host.isEmpty() && !GeneralParams.IS_HOST_SET) {
            GeneralParams.MINIMA_HOST = host;
            GeneralParams.IS_HOST_SET = true;
        }
        int dhtPort = Integer.getInteger("parlons.keets.dhtport", DEFAULT_DHT_PORT);
        File mailboxDir = new File(zDataFolder, "keets-mailbox");
        String extra = "--serve --dht-port " + dhtPort + " --mailbox-dir " + mailboxDir.getAbsolutePath()
                + (host.isEmpty() ? "" : " --public-host " + host);
        GeneralParams.KEETS_SIDECAR_EXTRA = (GeneralParams.KEETS_SIDECAR_EXTRA + " " + extra).trim();

        System.out.println("[parlons-node] FLEET MODE: Minima server role on, keets transport + DHT bootstrap (udp "
                + dhtPort + ") + keets mailbox in one sidecar (node " + rt.nodeVersion + ", sidecar "
                + rt.sidecarVersion + ", runtime " + rt.runtimeDir + ")"
                + (host.isEmpty() ? " - no -Dparlons.public.host: the DHT learns the address from its bootstraps" : ", public host " + host));
        rt.startBanner(zDataFolder, mailboxDir, dhtPort, host);
        return rt;
    }

    /** Once the sidecar has written its keys, print the lines an operator needs to list this box. */
    private void startBanner(File zDataFolder, File zMailboxDir, int zDhtPort, String zHost) {
        Thread t = new Thread(() -> {
            File keetsPub = new File(GeneralParams.DATA_FOLDER, "keets/pubkey");
            File mailPub = new File(zMailboxDir, "pubkey");
            for (int i = 0; i < 180; i++) {
                if (keetsPub.isFile() && mailPub.isFile()) break;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
            String k = readLine(keetsPub), m = readLine(mailPub);
            System.out.println("[parlons-node] FLEET ENDPOINT");
            System.out.println("[parlons-node]   keets pubkey    " + (k.isEmpty() ? "(sidecar not up yet - see `keets action:info`)" : k));
            System.out.println("[parlons-node]   DHT bootstrap   " + (zHost.isEmpty() ? "<public ip>" : zHost) + ":" + zDhtPort
                    + "   (add to GeneralParams.KEETS_BOOTSTRAP to seed first contact)");
            System.out.println("[parlons-node]   keets mailbox   " + (m.isEmpty() ? "(not up yet)" : m));
            if (!m.isEmpty()) {
                System.out.println("[parlons-node]   publish it:     node keets/tools/mailboxes-sign.js --key ~/.keets/mailbox-signing.key "
                        + "--in mailboxes.json --out mailboxes.json --add " + m + "   then copy mailboxes.json to eurobuddha.com/keets/");
            }
        }, "fleet-banner");
        t.setDaemon(true);
        t.start();
    }

    private static String readLine(File zFile) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(zFile.toPath()), StandardCharsets.UTF_8))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Extract (once) and return the runtime; throws with a human reason when it cannot. */
    static FleetRuntime ensure(File zDataFolder) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            throw new Exception("the embedded runtime is Linux-only (this is " + System.getProperty("os.name") + ")");
        }
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String want = arch.equals("amd64") || arch.equals("x86_64") ? "x64"
                : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : null;
        if (want == null) {
            throw new Exception("no embedded Node.js runtime for " + arch + " (x64 and arm64 only - a 32-bit Pi cannot be a fleet box)");
        }
        Properties v = new Properties();
        try (InputStream in = resource("fleet/VERSIONS")) {
            v.load(in);
        }
        String nodeVersion = v.getProperty("node", "?"), sidecarVersion = v.getProperty("sidecar", "?");
        File runtimeDir = new File(zDataFolder, "runtime/node-" + nodeVersion + "+sidecar-" + sidecarVersion);
        FleetRuntime rt = new FleetRuntime(runtimeDir, nodeVersion, sidecarVersion);
        if (new File(runtimeDir, ".ok").isFile() && rt.nodeBinary.canExecute() && rt.sidecarIndex.isFile()) {
            return rt;
        }
        File tmp = new File(runtimeDir.getParentFile(), runtimeDir.getName() + ".tmp");
        deleteTree(tmp);
        new File(tmp, "node/bin").mkdirs();
        File node = new File(tmp, "node/bin/node");
        try (InputStream in = new GZIPInputStream(resource("fleet/node-linux-" + want + ".gz")); OutputStream out = new FileOutputStream(node)) {
            copy(in, out);
        }
        if (!node.setExecutable(true, false)) throw new Exception("cannot chmod " + node);
        try (ZipInputStream zip = new ZipInputStream(resource("fleet/keets-sidecar.zip"))) {
            File root = tmp.getCanonicalFile();
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                File f = new File(tmp, e.getName()).getCanonicalFile();
                if (!f.getPath().startsWith(root.getPath() + File.separator)) throw new Exception("zip entry escapes the runtime dir: " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) { copy(zip, out); }
            }
        }
        if (!new File(tmp, "keets-sidecar/index.js").isFile()) throw new Exception("embedded sidecar is missing index.js");
        Files.write(new File(tmp, ".ok").toPath(), (nodeVersion + " " + sidecarVersion + "\n").getBytes(StandardCharsets.UTF_8));
        deleteTree(runtimeDir);
        Files.move(tmp.toPath(), runtimeDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
        System.out.println("[parlons-node] fleet runtime extracted to " + runtimeDir);
        return rt;
    }

    private static InputStream resource(String zName) throws Exception {
        InputStream in = FleetRuntime.class.getClassLoader().getResourceAsStream(zName);
        if (in == null) {
            throw new Exception("this jar carries no " + zName + " - fleet mode needs parlons-node-fleet-<ver>.jar (Gradle :node:fleetJar), not the plain parlons-node jar");
        }
        return in;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
