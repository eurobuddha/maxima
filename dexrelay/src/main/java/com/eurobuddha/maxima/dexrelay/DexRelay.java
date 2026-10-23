package com.eurobuddha.maxima.dexrelay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Decks DEX relay HTTP surface (SPEC §4) on its own JDK HttpServer, public bind, CORS open, no auth —
 * every message authenticates itself. Long-polls hold a thread, so the server owns a cached pool and caps
 * concurrent holds; a busy relay answers 503 rather than queueing.
 *
 *   POST /dex/publish                      one order envelope → 200 {ok,seq,cursor} | 400 | 409 stale | 503 full
 *   GET  /dex/book?market=&since=&hold=    long-poll ≤ 25 s → {cursor,reset,orders:[…]}
 *   POST /dex/msg                          one msg envelope → 200 {ok} | 400 | 503
 *   GET  /dex/inbox?pk=&since=&hold=       long-poll ≤ 25 s → {cursor,messages:[…]}
 *   GET  /dex/health                       {ok,version,orders,cursor,uptime}
 *   GET  /dex/relays                       {ok,relays:[{url,seen}]}  every DEX relay this host knows (itself included)
 */
public final class DexRelay {
    public static final String VERSION = "0.1.5";
    static final int MAX_HOLD_SECONDS = 25;
    static final int MAX_CONCURRENT_HOLDS = 512;
    static final long SWEEP_MS = 30_000;

    /** Seconds since the epoch; injectable for tests. */
    public interface Clock { long nowSeconds(); }

    private final Clock clock;
    private final RateLimit limiter;
    private final Store store = new Store();
    private final Semaphore holds = new Semaphore(MAX_CONCURRENT_HOLDS);
    private final long startedAt;
    /** Discovery: base URL -> last seen (epoch seconds) of every DEX relay this host knows. The hosts wire it to
     *  the relay mesh ({@code RelayServer.dexRelays()}); the default is just ourselves on the bound address. */
    private volatile java.util.function.Supplier<Map<String, Long>> relays;
    private String bindHost = "";
    private HttpServer server;
    private ExecutorService exec;
    private Thread sweeper;

    public DexRelay() { this(() -> System.currentTimeMillis() / 1000L, 200, 20); }

    public DexRelay(Clock clock, double globalRatePerSec, double perIpRatePerSec) {
        this.clock = clock;
        this.limiter = new RateLimit(globalRatePerSec, perIpRatePerSec);
        this.startedAt = clock.nowSeconds();
    }

    /** Wire the relay list {@code GET /dex/relays} serves (null restores the default: just this host). */
    public void setRelays(java.util.function.Supplier<Map<String, Long>> known) { relays = known; }

    public synchronized void start(String bindHost, int port) throws IOException {
        this.bindHost = bindHost;
        server = HttpServer.create(new InetSocketAddress(bindHost, port), 64);
        AtomicInteger n = new AtomicInteger();
        exec = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "dexrelay-" + n.incrementAndGet()); t.setDaemon(true); return t; });
        server.setExecutor(exec);
        server.createContext("/dex/health", ex -> handle(ex, this::health));
        server.createContext("/dex/relays", ex -> handle(ex, this::relays));
        server.createContext("/dex/publish", ex -> handle(ex, this::publish));
        server.createContext("/dex/book", ex -> handle(ex, this::book));
        server.createContext("/dex/msg", ex -> handle(ex, this::msg));
        server.createContext("/dex/inbox", ex -> handle(ex, this::inbox));
        server.createContext("/", ex -> handle(ex, e -> send(e, 404, "{\"ok\":false,\"error\":\"not found\"}")));
        server.start();
        sweeper = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(SWEEP_MS); } catch (InterruptedException e) { return; }
                store.sweep(clock.nowSeconds());
            }
        }, "dexrelay-sweep");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    public synchronized int port() { return server == null ? -1 : server.getAddress().getPort(); }

    public synchronized void stop() {
        if (sweeper != null) sweeper.interrupt();
        if (server != null) server.stop(0);
        if (exec != null) exec.shutdownNow();
        server = null; exec = null; sweeper = null;
    }

    // ---- handlers ----

    private interface Handler { void run(HttpExchange ex) throws IOException; }

    private void handle(HttpExchange ex, Handler h) throws IOException {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(200, -1); return; }
            String ip = clientIp(ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress(),
                    ex.getRequestHeaders().getFirst("X-Forwarded-For"));
            if (!limiter.allow(ip)) { send(ex, 429, "{\"ok\":false,\"error\":\"rate limited\"}"); return; }
            h.run(ex);
        } catch (Exception e) {
            try { send(ex, 500, "{\"ok\":false,\"error\":\"internal\"}"); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    /** The interface to listen on: an explicit bind wins; a relay with a public (TLS-front) URL defaults to loopback,
     *  so it never also answers plain HTTP on the internet; one without a front must listen publicly to be reached. */
    public static String bindFor(String bind, String publicUrl) {
        if (bind != null && !bind.trim().isEmpty()) return bind.trim();
        return publicUrl != null && !publicUrl.trim().isEmpty() ? "127.0.0.1" : "0.0.0.0";
    }

    /** The address to rate-limit. Behind the TLS front every connection comes from loopback, so there the proxy's own
     *  (rightmost) X-Forwarded-For entry names the client; a direct caller's header is ignored, so it cannot pick a bucket. */
    static String clientIp(String remote, String forwardedFor) {
        boolean loopback = remote.startsWith("127.") || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
        if (!loopback || forwardedFor == null) return remote;
        String last = forwardedFor.substring(forwardedFor.lastIndexOf(',') + 1).trim();
        return last.isEmpty() ? remote : last;
    }

    private void health(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"GET only\"}"); return; }
        send(ex, 200, "{\"ok\":true,\"version\":\"" + VERSION + "\",\"orders\":" + store.liveOrders() + ",\"cursor\":" + store.cursor()
                + ",\"uptime\":" + (clock.nowSeconds() - startedAt) + "}");
    }

    private void relays(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"GET only\"}"); return; }
        java.util.function.Supplier<Map<String, Long>> hook = relays;
        Map<String, Long> known = hook == null ? java.util.Collections.singletonMap("http://" + bindHost + ":" + port(), clock.nowSeconds()) : hook.get();
        StringBuilder b = new StringBuilder(256).append("{\"ok\":true,\"relays\":[");
        boolean first = true;
        for (Map.Entry<String, Long> e : known.entrySet()) {
            if (wildcard(e.getKey())) continue;   // http://0.0.0.0:9602 is where we LISTEN, never where anyone can reach us
            if (!first) b.append(',');
            first = false;
            b.append("{\"url\":\"");
            for (int i = 0; i < e.getKey().length(); i++) { char c = e.getKey().charAt(i); if (c > 0x20 && c <= 0x7E && c != '"' && c != '\\') b.append(c); }
            b.append("\",\"seen\":").append(e.getValue() == null ? 0 : e.getValue()).append('}');
        }
        send(ex, 200, b.append("]}").toString());
    }

    /** A wildcard bind address (0.0.0.0, ::) is not a reachable URL; seen live 2026-10-22 for the first second after boot. */
    static boolean wildcard(String url) {
        String u = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        int s = u.indexOf("://");
        String host = s < 0 ? u : u.substring(s + 3);
        return host.startsWith("0.0.0.0") || host.startsWith("[::]") || host.startsWith("::") || host.isEmpty();
    }

    private void publish(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"POST only\"}"); return; }
        Envelope env = parseBody(ex, Envelope.MAX_ORDER_BYTES);
        if (env == null) return;
        if (env.kind != Envelope.Kind.ORDER) { send(ex, 400, err("not an order")); return; }
        if (store.isFull()) { send(ex, 503, err("relay full")); return; }
        if (!store.publish(env)) { send(ex, 409, err("stale seq")); return; }
        send(ex, 200, "{\"ok\":true,\"seq\":" + env.seq + ",\"cursor\":" + store.cursor() + "}");
    }

    private void book(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"GET only\"}"); return; }
        Map<String, String> q = query(ex);
        String market = q.get("market");
        long since = longParam(q, "since", 0);
        int hold = holdParam(q);
        Store.Changes ch = store.changesSince(market, since);
        if (ch.orders.isEmpty() && !ch.reset && hold > 0) {
            if (!holds.tryAcquire()) { send(ex, 503, err("busy")); return; }
            try { store.await(since, hold * 1000L); } finally { holds.release(); }
            ch = store.changesSince(market, since);
        }
        StringBuilder b = new StringBuilder(512);
        b.append("{\"cursor\":").append(ch.cursor).append(",\"reset\":").append(ch.reset).append(",\"orders\":[");
        joinRaw(b, ch.orders);
        send(ex, 200, b.append("]}").toString());
    }

    private void msg(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"POST only\"}"); return; }
        Envelope env = parseBody(ex, Envelope.MAX_MSG_BYTES);
        if (env == null) return;
        if (env.kind != Envelope.Kind.MSG) { send(ex, 400, err("not a msg")); return; }
        if (!store.deliver(env)) { send(ex, 503, err("inbox full")); return; }
        send(ex, 200, "{\"ok\":true}");
    }

    private void inbox(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"GET only\"}"); return; }
        Map<String, String> q = query(ex);
        String pk = q.get("pk");
        if (pk == null || !Hex.is0x(pk, 64)) { send(ex, 400, err("pk must be a 0x publicId (64 bytes)")); return; }
        long since = longParam(q, "since", 0);
        int hold = holdParam(q);
        List<Envelope> msgs = store.inboxSince(pk, since);
        if (msgs.isEmpty() && hold > 0) {
            if (!holds.tryAcquire()) { send(ex, 503, err("busy")); return; }
            try { store.awaitInbox(pk, since, hold * 1000L); } finally { holds.release(); }
            msgs = store.inboxSince(pk, since);
        }
        StringBuilder b = new StringBuilder(512);
        b.append("{\"cursor\":").append(store.inboxCursor(pk)).append(",\"messages\":[");
        joinRaw(b, msgs);
        send(ex, 200, b.append("]}").toString());
    }

    // ---- helpers ----

    private Envelope parseBody(HttpExchange ex, int maxBytes) throws IOException {
        byte[] body = readBody(ex.getRequestBody(), maxBytes + 1);
        if (body == null) { send(ex, 413, err("body too large")); return null; }
        try {
            return Envelope.parse(new String(body, StandardCharsets.UTF_8), clock.nowSeconds());
        } catch (Envelope.Invalid e) {
            send(ex, 400, err(e.getMessage()));
            return null;
        }
    }

    private static byte[] readBody(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > limit) return null;
        }
        return bo.toByteArray();
    }

    private static void joinRaw(StringBuilder b, List<Envelope> list) {
        boolean first = true;
        for (Envelope e : list) { if (!first) b.append(','); first = false; b.append(e.raw); }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static long longParam(Map<String, String> q, String k, long dflt) {
        try { return q.containsKey(k) ? Long.parseLong(q.get(k)) : dflt; } catch (NumberFormatException e) { return dflt; }
    }

    private static int holdParam(Map<String, String> q) {
        long h = longParam(q, "hold", MAX_HOLD_SECONDS);
        return (int) Math.max(0, Math.min(MAX_HOLD_SECONDS, h));
    }

    private static String err(String why) {
        StringBuilder b = new StringBuilder("{\"ok\":false,\"error\":\"");
        for (int i = 0; i < why.length(); i++) { char c = why.charAt(i); if (c >= 0x20 && c <= 0x7E && c != '"' && c != '\\') b.append(c); }
        return b.append("\"}").toString();
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(out); }
    }

    /** Token buckets: one global, one per IP (bounded map). Same shape as NodeGateway's; copied, not shared, to keep this module dependency-free. */
    static final class RateLimit {
        private final double perIp;
        private final Bucket global;
        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
        private static final int MAX_IPS = 8192;

        RateLimit(double globalRate, double perIpRate) { perIp = perIpRate; global = globalRate > 0 ? new Bucket(globalRate) : null; }

        synchronized boolean allow(String ip) {
            if (global != null && !global.tryAcquire()) return false;
            if (perIp <= 0) return true;
            Bucket b = buckets.get(ip);
            if (b == null) {
                if (buckets.size() >= MAX_IPS) { long now = System.nanoTime(); buckets.entrySet().removeIf(e -> e.getValue().fullyRefilled(now)); }
                if (buckets.size() >= MAX_IPS) return false;
                b = new Bucket(perIp);
                buckets.put(ip, b);
            }
            return b.tryAcquire();
        }
    }

    /** rate tokens/sec, burst = 2 s worth. */
    static final class Bucket {
        private final double capacity, refillPerNano;
        private double tokens;
        private long last;

        Bucket(double ratePerSec) { capacity = ratePerSec * 2; refillPerNano = ratePerSec / 1e9; tokens = capacity; last = System.nanoTime(); }

        boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) * refillPerNano);
            last = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }

        boolean fullyRefilled(long now) { return tokens + (now - last) * refillPerNano >= capacity; }
    }
}
