package com.eurobuddha.maxima.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.minima.system.commands.CommandRunner;
import org.minima.utils.json.JSONObject;

/**
 * Parlons Node gateway — the hardened, read+relay-only {@code /cmd} proxy that lets phones use a
 * Parlons Node as their wallet gateway, replacing the single hosted {@code privateprivate.org}. It
 * is the server side of the phone's {@link com.eurobuddha.maxima.app.wallet.GatewayNode} contract:
 * {@code POST /cmd}, {@code Authorization: Bearer <token>}, body {@code {"command":"…"}}, the node's
 * JSON straight back.
 *
 * <p>The raw node RPC is full-admin with only weak basic-auth ({@code CMDHandler}); it must NEVER be
 * exposed. This proxy exposes ONLY an allow-list of non-admin commands — reads ({@code status},
 * {@code balance}/{@code coins} with {@code megammr:true} so ONE node serves any address), track-only
 * ({@code newscript}/{@code coinexport}/{@code coinimport} — none can move funds, no key is ever
 * here), and relay of a phone's PRE-SIGNED txn ({@code txnimport}/{@code txnbasics}/{@code txnpost}/
 * {@code txndelete}). Anything that could spend the node's own funds or touch its seed ({@code send},
 * {@code vault}, {@code keys}, {@code sign}, {@code quit}, …) is refused. Binds loopback by default —
 * a VPS terminates TLS (Caddy) and proxies to it.
 */
public final class NodeGateway {

    /** Non-admin commands a phone wallet may run through the gateway. Allow-list, not deny-list. */
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            // reads
            "status", "tokens", "balance", "coins", "checkaddress", "tokenvalidate", "block",
            // track-only: register a public script / pull+import a coin proof — cannot move funds
            "newscript", "coinexport", "coinimport",
            // relay a locally-signed txn the phone built (it signed with ITS keys; we only broadcast)
            "txnimport", "txnbasics", "txnpost", "txndelete", "txncheck"
    ));

    private final int mPort;
    private final String mBindHost;
    private final String mToken;
    private final RateLimiter mLimiter;
    private HttpServer mServer;
    private java.util.concurrent.ExecutorService mExecutor;

    private NodeGateway(String zBindHost, int zPort, String zToken, RateLimiter zLimiter) {
        mBindHost = zBindHost; mPort = zPort; mToken = zToken; mLimiter = zLimiter;
    }

    /**
     * Build a gateway. Token precedence: {@code -Dparlons.gateway.token}, else a persisted
     * {@code <dataDir>/gateway-token.txt}, else a freshly generated 256-bit token written there.
     * Bind host defaults to loopback ({@code -Dparlons.gateway.bind} to override, e.g. 0.0.0.0).
     */
    public static NodeGateway create(Path zDataDir, int zPort) throws IOException {
        String bind = System.getProperty("parlons.gateway.bind", "127.0.0.1").trim();
        String token = System.getProperty("parlons.gateway.token", "").trim();
        if (token.isEmpty()) {
            Path tokenFile = zDataDir.resolve("gateway-token.txt");
            if (Files.exists(tokenFile)) {
                token = new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).trim();
            } else {
                token = newToken();
                Files.createDirectories(zDataDir);
                Files.write(tokenFile, (token + "\n").getBytes(StandardCharsets.UTF_8));
                // Owner-only: a read+relay token can't move funds, but keep it off other users' eyes.
                try {
                    Files.setPosixFilePermissions(tokenFile,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) { /* non-POSIX FS */ }
            }
        }
        // Rate limits (requests/sec; 0 disables). The GLOBAL bucket is the backstop that matters
        // behind a loopback TLS front (all traffic looks like 127.0.0.1 there, so per-IP is moot —
        // Caddy's own rate_limit does the per-client work; see NODE-SETUP.md). The PER-IP bucket
        // protects --gateway-public / direct-exposure mode.
        double globalRate = doubleProp("parlons.gateway.rate.global", 50);
        double perIpRate  = doubleProp("parlons.gateway.rate.perip", 10);
        return new NodeGateway(bind, zPort, token, new RateLimiter(globalRate, perIpRate));
    }

    private static double doubleProp(String zKey, double zDefault) {
        try {
            String v = System.getProperty(zKey);
            return (v == null || v.trim().isEmpty()) ? zDefault : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) { return zDefault; }
    }

    public String token() { return mToken; }
    public int port()     { return mPort; }
    public String bindHost() { return mBindHost; }

    public void start() throws IOException {
        mServer = HttpServer.create(new InetSocketAddress(mBindHost, mPort), 0);
        mServer.createContext("/cmd", this::handleCmd);
        mExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        mServer.setExecutor(mExecutor);
        mServer.start();
    }

    public void stop() {
        if (mServer != null) mServer.stop(0);
        // HttpServer.stop() does NOT shut down a user-supplied executor — do it ourselves.
        if (mExecutor != null) mExecutor.shutdownNow();
    }

    // ── request handling ──────────────────────────────────────────────────────────────────────────

    private void handleCmd(HttpExchange ex) throws IOException {
        try {
            // Throttle BEFORE auth so an unauthenticated flood is cheap to shed.
            String clientIp = ex.getRemoteAddress() == null ? "?"
                    : ex.getRemoteAddress().getAddress().getHostAddress();
            if (!mLimiter.allow(clientIp)) { fail(ex, 429, "rate limit exceeded"); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
            // Bearer auth — constant-time compare so a wrong token leaks no timing.
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ") || !constantTimeEquals(auth.substring(7).trim(), mToken)) {
                fail(ex, 401, "unauthorized");
                return;
            }
            String body = readAll(ex.getRequestBody());
            String command;
            try {
                Object parsed = new org.minima.utils.json.parser.JSONParser().parse(body);
                command = String.valueOf(((JSONObject) parsed).get("command"));
            } catch (Exception pe) { fail(ex, 400, "bad json body"); return; }
            if (command == null || command.trim().isEmpty() || "null".equals(command)) {
                fail(ex, 400, "empty command"); return;
            }
            String trimmed = command.trim();
            // CRITICAL: the node executes EVERY ';'-separated segment (runMultiCommand splits on
            // ';' with a StringTokenizer and runs each as a full admin command). So checking only
            // the first verb is a fund-drain bypass: "status ; send address:<attacker> amount:<all>"
            // passes a first-verb check and then DRAINS the wallet. Validate EVERY segment's verb.
            for (String segment : trimmed.split(";")) {
                String seg = segment.trim();
                if (seg.isEmpty()) continue;
                String verb = seg.split("\\s+", 2)[0].toLowerCase();
                if (!ALLOWED.contains(verb)) {
                    fail(ex, 403, "command not permitted through the gateway: " + verb);
                    return;
                }
                // coins/balance must target a specific address — never let a client enumerate the
                // operator's OWN wallet coins (a privacy leak; the phone always passes address:).
                // A node WITHOUT the MegaMMR only knows its own coins: a megammr:true read here would
                // answer "0" for any funded address — a wrong answer, not an error. Refuse with a
                // 503 so a fleet client fails over to a MegaMMR node instead of trusting it.
                if (seg.contains("megammr:true") && !org.minima.system.params.GeneralParams.IS_MEGAMMR) {
                    fail(ex, 503, "this node has no MegaMMR - use a MegaMMR gateway node"); return;
                }
                if (("coins".equals(verb) || "balance".equals(verb)) && !seg.contains("address:")) {
                    fail(ex, 403, verb + " through the gateway requires an address: parameter");
                    return;
                }
            }
            // Forward to the in-process node and hand its JSON straight back.
            JSONObject result = CommandRunner.getRunner().runSingleCommand(trimmed);
            byte[] out = result.toString().getBytes(StandardCharsets.UTF_8);
            // Ceiling on what we'll push back (above the phone's 8 MB read cap; a bigger response is
            // pathological — e.g. coins for an address with an enormous UTXO set).
            if (out.length > MAX_RESP) { fail(ex, 502, "node response too large"); return; }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        } catch (Throwable t) {
            // Don't echo internal exception detail to the client — log it, return a generic message.
            System.out.println("[parlons-node] gateway request error: " + t);
            try { fail(ex, 500, "gateway error"); } catch (IOException ignored) {}
        }
    }

    private static void fail(HttpExchange ex, int code, String msg) throws IOException {
        JSONObject err = new JSONObject();
        err.put("status", false);
        err.put("error", msg);
        byte[] out = err.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────

    private static final int MAX_BODY = 1 * 1024 * 1024;    // 1 MB: a signed txnimport is the largest
    private static final int MAX_RESP = 12 * 1024 * 1024;   // 12 MB: above the phone's 8 MB read cap
    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n, total = 0;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY) throw new IOException("request body too large");
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String newToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int r = 0;
        for (int i = 0; i < ab.length; i++) r |= ab[i] ^ bb[i];
        return r == 0;
    }

    // ── rate limiting ─────────────────────────────────────────────────────────────────────────────

    /**
     * Token-bucket limiter: a GLOBAL bucket (a hard ceiling on total request rate, the backstop that
     * works even when every request arrives from the loopback TLS front) plus an optional PER-IP
     * bucket (meaningful only when the gateway is exposed directly). Also bounds the tracked-command
     * DB-growth abuse (newscript/coinimport spam) by capping request rate. Thread-safe.
     */
    static final class RateLimiter {
        private final double mPerIpRate;
        private final Bucket mGlobal;
        private final java.util.concurrent.ConcurrentHashMap<String, Bucket> mPerIp =
                new java.util.concurrent.ConcurrentHashMap<>();
        private static final int MAX_IPS = 8192;

        RateLimiter(double zGlobalRate, double zPerIpRate) {
            mPerIpRate = zPerIpRate;
            mGlobal = zGlobalRate > 0 ? new Bucket(zGlobalRate) : null;
        }

        boolean allow(String zIp) {
            if (mGlobal != null && !mGlobal.tryAcquire()) return false;
            if (mPerIpRate > 0) {
                Bucket b = mPerIp.get(zIp);
                if (b == null) {
                    if (mPerIp.size() >= MAX_IPS) mPerIp.clear();   // bound memory; crude periodic reset
                    b = mPerIp.computeIfAbsent(zIp, k -> new Bucket(mPerIpRate));
                }
                if (!b.tryAcquire()) return false;
            }
            return true;
        }
    }

    /** A single token bucket: {@code rate} tokens/sec, burst capacity = 2s worth. Monotonic clock. */
    static final class Bucket {
        private final double mCapacity;
        private final double mRefillPerNano;
        private double mTokens;
        private long mLastNanos;

        Bucket(double zRatePerSec) {
            mCapacity = Math.max(1.0, zRatePerSec * 2.0);
            mRefillPerNano = zRatePerSec / 1_000_000_000.0;
            mTokens = mCapacity;
            mLastNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            mTokens = Math.min(mCapacity, mTokens + (now - mLastNanos) * mRefillPerNano);
            mLastNanos = now;
            if (mTokens >= 1.0) { mTokens -= 1.0; return true; }
            return false;
        }
    }
}
