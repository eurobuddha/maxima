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

    /** Transactions imported through this gateway (bounded, oldest dropped; kept in gateway-relay-rows.txt): the only
     *  rows a client's txndelete may remove. The node refuses txndelete to a non-admin caller (the txn table is shared, and the bearer token is
     *  shared with every phone), so a phone's failure-path cleanup of ITS relay row runs here as the node itself. */
    private final Set<String> mRelayRows = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> zEldest) {
                    return size() > 4096;
                }
            }));

    /** Where the relay rows are kept across a restart (one id per line) - or the cleanup txndelete of a row
     *  imported before the restart is refused and the row stays in the node's txn table for good. */
    private Path mRelayFile;

    private void loadRelayRows(Path zFile) {
        mRelayFile = zFile;
        try {
            if (Files.exists(zFile)) {
                for (String line : Files.readAllLines(zFile, StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty()) mRelayRows.add(line.trim());
                }
            }
        } catch (IOException e) {
            System.out.println("[parlons-node] gateway relay rows not read: " + e);
        }
    }

    private void saveRelayRows() {
        if (mRelayFile == null) return;
        synchronized (mRelayRows) {
            try {
                Files.createDirectories(mRelayFile.getParent());
                Path tmp = mRelayFile.resolveSibling(mRelayFile.getFileName() + ".tmp");
                Files.write(tmp, mRelayRows, StandardCharsets.UTF_8);
                Files.move(tmp, mRelayFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.out.println("[parlons-node] gateway relay rows not saved: " + e);
            }
        }
    }

    /** Remove a relay row as the node itself (the node refuses txndelete to a non-admin caller) and forget it */
    private JSONObject deleteRelayRow(String zId) {
        JSONObject result = CommandRunner.getRunner().runCommand("txndelete", "id", zId);
        mRelayRows.remove(zId);
        saveRelayRows();
        return result;
    }

    private final int mPort;
    private final String mBindHost;
    private final String mToken;
    private final RateLimiter mLimiter;
    private HttpServer mServer;
    private java.util.concurrent.ExecutorService mExecutor;
    private volatile NftStore mNft;   // hosted NFT art, served read-only at /nft/<path>
    /** Static art has its own budget: a hot-linked collection or a crawler must never spend the
     *  wallet /cmd buckets. Immutable caching keeps repeats off the node anyway. */
    private final RateLimiter mNftLimiter = new RateLimiter(
            doubleProp("parlons.gateway.rate.nft.global", 400), doubleProp("parlons.gateway.rate.nft.perip", 40));

    /** Plug in the NFT store before {@link #start()}; without it /nft answers 404. */
    public void setNftStore(NftStore zStore) { mNft = zStore; }

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
        NodeGateway gateway = new NodeGateway(bind, zPort, token, new RateLimiter(globalRate, perIpRate));
        gateway.loadRelayRows(zDataDir.resolve("gateway-relay-rows.txt"));
        return gateway;
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
        mServer.createContext("/nft", this::handleNft);
        // Handler threads (-Dparlons.gateway.threads, default 8): each blocks for one node
        // command; four was the ceiling on concurrent wallet reads per gateway.
        int threads = Math.max(1, Integer.getInteger("parlons.gateway.threads", 8));
        mExecutor = java.util.concurrent.Executors.newFixedThreadPool(threads);
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
            // ONE command per request, and no ';' at all (2026-11-24). The node runs every command of a ';' chain,
            // and WHERE it splits is the node's business: this handler used to split at every ';' itself and check
            // each piece, while a node whose splitter keeps a ';' inside a quoted value runs
            //   txnimport id:a data:"0x00;txncheck "x file:secret.txn
            // as ONE txnimport with file:secret.txn - past the "no file:" rule below, which only saw two harmless
            // pieces. No phone client sends a chain (GatewayNode / WalletPublisher / TxnFactory send one command per
            // request), so any ';' is refused - whatever the embedded node's splitter does with it.
            if (trimmed.indexOf(';') != -1) {
                fail(ex, 403, "one command per request: a ';' is not permitted through the gateway");
                return;
            }
            // The command name and parameters as the NODE's own parser reads them (a line without ';' is one
            // command to every node version) - every rule below is applied to what will actually run.
            org.minima.system.commands.Command parsed = CommandRunner.getRunner().getCommand(trimmed);
            String verb = parsed.getName();
            JSONObject params = parsed.getParams();
            if (!ALLOWED.contains(verb)) {
                fail(ex, 403, "command not permitted through the gateway: " + trimmed.split("\\s+", 2)[0].toLowerCase());
                return;
            }
            // coins/balance must target a specific address — never let a client enumerate the
            // operator's OWN wallet coins (a privacy leak; the phone always passes address:).
            // A node WITHOUT the MegaMMR only knows its own coins: a megammr:true read here would
            // answer "0" for any funded address — a wrong answer, not an error. Refuse with a
            // 503 so a fleet client fails over to a MegaMMR node instead of trusting it.
            if ("true".equals(String.valueOf(params.get("megammr"))) && !org.minima.system.params.GeneralParams.IS_MEGAMMR) {
                fail(ex, 503, "this node has no MegaMMR - use a MegaMMR gateway node"); return;
            }
            if (("coins".equals(verb) || "balance".equals(verb)) && !params.containsKey("address")) {
                fail(ex, 403, verb + " through the gateway requires an address: parameter");
                return;
            }
            // txnpost burn: makes the NODE build and sign a burn paid from ITS OWN coins. The bearer token is
            // shared with every phone that uses this gateway, so no client may ask for it (the phone wallet
            // never does: its burn is inside its own signed txn).
            if ("txnpost".equals(verb) && params.containsKey("burn")) {
                fail(ex, 403, "txnpost burn: is not permitted through the gateway (it is paid from this node's coins)");
                return;
            }
            // file: reads or writes a file on THIS machine (txnimport file:, and any later command with one).
            // No phone client sends one (they send txnimport data:), so the gateway refuses every file:.
            if (params.containsKey("file")) {
                fail(ex, 403, "file: is not permitted through the gateway (it names a file on this node)");
                return;
            }
            String id = params.get("id") == null ? null : params.get("id").toString();
            // a relay row is remembered by its id (also on disk): the id must be plain, and given
            if ("txnimport".equals(verb) && (id == null || !id.matches("[\\x21-\\x7E]{1,128}"))) {
                fail(ex, 403, "txnimport through the gateway needs an id: of 1-128 printable characters");
                return;
            }
            // txndelete: only a row imported through this gateway (see mRelayRows)
            if ("txndelete".equals(verb) && (id == null || !mRelayRows.contains(id))) {
                fail(ex, 403, "txndelete through the gateway only removes a transaction imported through it, on its own");
                return;
            }
            // Forward to the in-process node and hand its JSON straight back.
            // Run as a NON-ADMIN caller (0xFF): the bearer token is shared with every phone, so the node's
            // non-admin gates apply too (a txnimport file: is confined to a plain .txn in the base folder, an
            // overwrite of an existing file needs ADMIN..). Every command the phone clients send stays open to
            // 0xFF on this line (see NodeGatewayNonAdminTest). The reply keeps runSingleCommand's shape.
            JSONObject result;
            if ("txndelete".equals(verb)) {
                result = deleteRelayRow(id);
            } else {
                result = firstResult(CommandRunner.getRunner().runMultiCommand("0xFF", trimmed));
                boolean ok = Boolean.TRUE.equals(result.get("status"));
                if ("txnimport".equals(verb) && ok) {
                    // remember the relay row this request imported
                    mRelayRows.add(id);
                    saveRelayRows();
                } else if (id != null && mRelayRows.contains(id)
                        && ("txnpost".equals(verb) || ("txnbasics".equals(verb) && !ok))) {
                    // The gateway cleans its relay rows up itself: once a row was posted (the clients never
                    // delete after a successful publish), and when txnbasics / txnpost failed (the client's own
                    // cleanup txndelete may never arrive). A txndelete for a row already gone gets the 403 above.
                    deleteRelayRow(id);
                }
            }
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

    /**
     * Public, read-only NFT art: {@code GET /nft/<sha256>.<ext>} or
     * {@code GET /nft/c/<collection>/<index>.<ext>}. No token (the files are what the on-chain
     * metadata links to, meant for anyone), strict path shapes (nothing else on disk is
     * addressable), immutable caching (content-addressed), nosniff.
     */
    private void handleNft(HttpExchange ex) throws IOException {
        try {
            String clientIp = ex.getRemoteAddress() == null ? "?"
                    : ex.getRemoteAddress().getAddress().getHostAddress();
            if (!mNftLimiter.allow(clientIp)) { fail(ex, 429, "rate limit exceeded"); return; }
            String method = ex.getRequestMethod();
            boolean head = "HEAD".equalsIgnoreCase(method);
            if (!head && !"GET".equalsIgnoreCase(method)) { fail(ex, 405, "GET only"); return; }
            NftStore store = mNft;
            String path = ex.getRequestURI().getPath();
            String rel = path.startsWith("/nft/") ? path.substring(5) : "";
            java.nio.file.Path file = store == null ? null : store.resolve(rel);
            if (file == null || !java.nio.file.Files.isRegularFile(file)) { fail(ex, 404, "not hosted here"); return; }
            long len = java.nio.file.Files.size(file);
            ex.getResponseHeaders().set("Content-Type", NftStore.contentType(rel));
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            // Served from the operator's web origin: an SVG (or anything) navigated to directly
            // must not be able to run script there. Renders fine inside <img>.
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
            ex.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + rel.substring(rel.lastIndexOf('/') + 1) + "\"");
            if (head) {
                ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, len);
            try (OutputStream os = ex.getResponseBody();
                 java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } catch (Throwable t) {
            System.out.println("[parlons-node] nft request error: " + t);
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
        private long mLastPruneNanos;
        private boolean mPruned;

        RateLimiter(double zGlobalRate, double zPerIpRate) {
            mPerIpRate = zPerIpRate;
            mGlobal = zGlobalRate > 0 ? new Bucket(zGlobalRate) : null;
        }

        synchronized boolean allow(String zIp) {
            if (mGlobal != null && !mGlobal.tryAcquire()) return false;
            if (mPerIpRate > 0) {
                Bucket b = mPerIp.get(zIp);
                if (b == null) {
                    // Retire only fully refilled buckets: recreating one then grants no
                    // extra tokens. Keep admission, acquisition and retirement under the
                    // same monitor so a bucket cannot be used after it has been retired.
                    long now = System.nanoTime();
                    if (mPerIp.size() >= MAX_IPS
                            && (!mPruned || now - mLastPruneNanos >= 1_000_000_000L)) {
                        mPruned = true;
                        mLastPruneNanos = now; // at most one bounded scan per second
                        mPerIp.entrySet().removeIf(e -> e.getValue().fullyRefilled(now));
                    }
                    if (mPerIp.size() >= MAX_IPS) return false;
                    b = new Bucket(mPerIpRate);
                    mPerIp.put(zIp, b);
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

        synchronized boolean fullyRefilled(long zNow) {
            return mTokens + (zNow - mLastNanos) * mRefillPerNano >= mCapacity;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            mTokens = Math.min(mCapacity, mTokens + (now - mLastNanos) * mRefillPerNano);
            mLastNanos = now;
            if (mTokens >= 1.0) { mTokens -= 1.0; return true; }
            return false;
        }
    }

    /** What runSingleCommand returned: the first reply, or an empty object */
    static JSONObject firstResult(org.minima.utils.json.JSONArray zResults) {
        return zResults.size() > 0 ? (JSONObject) zResults.get(0) : new JSONObject();
    }

}
