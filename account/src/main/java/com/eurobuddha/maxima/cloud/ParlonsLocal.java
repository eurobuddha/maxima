package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.media.MediaManifest;
import com.eurobuddha.maxima.core.media.MediaService;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.minima.utils.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The account's LOCAL web panel and API: a loopback HTTP server owned by whoever runs a
 * {@link ParlonsCore} (parlons-cloud, a tenants host, a Parlons Node, a desktop app), giving
 * every desktop and every headless box the same interface a paired phone has - without pairing
 * over the network, because the caller is on the same machine as the account.
 *
 * <p><b>Who may call.</b> A browser gets in with a one-time <i>ticket</i>: opening
 * {@code http://127.0.0.1:<port>/open?ticket=…} sets an HttpOnly, SameSite=Strict session cookie
 * and lands on the panel, served from that same origin. Tickets come from two places: the file
 * {@code <data>/panel-ticket.txt} (owner-only mode; the account always keeps ONE unused ticket
 * there and mints the next as soon as it is used - a desktop app or {@code parlons panel} on the
 * same box reads it), and {@code parlons.panel.ticket} over the paired channel (a paired device
 * asking for a URL, 60 s life). No token ever sits in page JavaScript or a URL after that first hop.
 *
 * <p><b>What the caller is to the account.</b> A LOCAL DEVICE ({@link DevicePairing#authorizeLocal}):
 * every request is dispatched in-process through the same {@link ServiceRegistry} handlers a
 * phone reaches over Maxima, as that device - so every {@code requireAuth} path stays honest, the
 * device is listed and revocable, and it has no reply address, so it never joins the push fan-out.
 * Instead the control channel hands every push event to {@link #sink()} and the browser reads
 * them as server-sent events on {@code /events}.
 *
 * <p><b>What it refuses.</b> Requests whose {@code Host} is not this loopback origin (DNS
 * rebinding), whose {@code Origin} is another site, or whose method is outside the panel
 * allow-list: the seed, the backup, wallet sends, payments, node console commands and NFT
 * hosting stay on paired phones and the CLI - a cookie on a desktop is weaker than a phone in
 * your hand.
 */
public final class ParlonsLocal {

    public static final int DEFAULT_PORT = 9587;
    public static final String TICKET_FILE = "panel-ticket.txt";
    public static final String PANEL_FILE = "panel.txt";
    static final String COOKIE = "parlons_session";
    static final long TICKET_TTL_MS = 60_000L;
    static final long SESSION_IDLE_MS = 7L * 24 * 3600_000L;
    static final int MAX_BODY = 2 * 1024 * 1024;
    private static final int SSE_QUEUE = 256;
    private static final long SSE_PING_MS = 20_000L;

    /** Methods the panel may call ({@code parlons.} prefix implied). Allow-list, not deny-list. */
    static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ping", "pair.list", "pair.approve", "pair.revoke", "pair.newcode",
            "node.status", "node.log", "node.figures", "node.hosts", "node.mls",
            "identity.setname", "settings.get", "settings.set",
            "contacts.list", "contacts.add", "contacts.rename", "contacts.resolve",
            "contacts.remove", "contacts.info",
            "chat.summaries", "chat.conversation", "chat.send", "chat.markread", "chat.clear",
            "chat.search", "chat.since", "media.up",
            "group.create", "group.info", "group.update"
    )));

    private static final SecureRandom RAND = new SecureRandom();

    private final ServiceRegistry mReg;
    private final byte[] mLocalKey;
    private final Path mDataDir;
    private final int mPort;
    private final Supplier<String> mPermanent;
    private final DevicePairing mPairing;
    private final MediaService mMedia;
    private final Consumer<String> mLog;

    private HttpServer mServer;
    private ExecutorService mExec;
    /** The ticket currently in {@code panel-ticket.txt} (unused). */
    private volatile String mFileTicket = "";
    /** Tickets minted for paired devices: ticket → expiry. */
    private final Map<String, Long> mMinted = new ConcurrentHashMap<>();
    /** Session cookie → last-seen millis. */
    private final Map<String, Long> mSessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<LinkedBlockingQueue<String>> mClients = new CopyOnWriteArrayList<>();

    /**
     * @param zReg       the account's service registry (the same handlers phones reach)
     * @param zLocalKey  this host's local device key (already authorized, or revoked - then every
     *                   call answers 403 with the way back)
     * @param zPermanent the account's permanent MAX# (null until attached)
     * @param zMedia     for {@code /media} (null = no media endpoint)
     */
    public ParlonsLocal(ServiceRegistry zReg, byte[] zLocalKey, Path zDataDir, int zPort,
                        Supplier<String> zPermanent, DevicePairing zPairing, MediaService zMedia,
                        Consumer<String> zLog) {
        mReg = zReg;
        mLocalKey = zLocalKey;
        mDataDir = zDataDir;
        mPort = zPort;
        mPermanent = zPermanent;
        mPairing = zPairing;
        mMedia = zMedia;
        mLog = zLog == null ? s -> { } : zLog;
    }

    public void start() throws IOException {
        mServer = HttpServer.create(new InetSocketAddress("127.0.0.1", mPort), 0);
        mServer.createContext("/", this::route);
        // SSE clients hold a thread each for the life of the tab: a cached pool, not a fixed one.
        mExec = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "parlons-panel");
            t.setDaemon(true);
            return t;
        });
        mServer.setExecutor(mExec);
        mServer.start();
        refreshFileTicket();
        try {
            AccountFiles.writePrivate(mDataDir.resolve(PANEL_FILE), (base() + "/\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            mLog.accept("panel: could not write " + PANEL_FILE + ": " + e.getMessage());
        }
        mLog.accept("web panel on " + base() + "/ (open it with the one-time link in "
                + mDataDir.resolve(TICKET_FILE) + ", or: parlons panel)");
    }

    public void stop() {
        if (mServer != null) {
            mServer.stop(0);
        }
        if (mExec != null) {
            mExec.shutdownNow();
        }
    }

    /** The port actually bound (useful when constructed with port 0). */
    public int port() {
        return mServer == null ? mPort : mServer.getAddress().getPort();
    }

    private String base() {
        return "http://127.0.0.1:" + port();
    }

    /** Mint a one-time URL (60 s) - what a paired device gets from {@code parlons.panel.ticket}. */
    public String newTicketUrl() {
        String t = token();
        mMinted.put(t, System.currentTimeMillis() + TICKET_TTL_MS);
        sweep();
        return base() + "/open?ticket=" + t;
    }

    /** The current unused file ticket's URL (what a desktop app on this box opens). */
    public String fileTicketUrl() {
        return base() + "/open?ticket=" + mFileTicket;
    }

    /** The local event feed: hand it to {@link ParlonsControl#setLocalSink}. */
    public Consumer<JSONObject> sink() {
        return ev -> {
            String line = ev.toString();
            for (LinkedBlockingQueue<String> q : mClients) {
                if (!q.offer(line)) {
                    q.poll();          // a stalled tab loses its oldest event, never blocks the account
                    q.offer(line);
                }
            }
        };
    }

    /** Browser tabs currently listening on {@code /events}. */
    public int listeners() {
        return mClients.size();
    }

    // ---- routing ----

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!hostOk(ex)) {
                fail(ex, 403, "wrong host");   // DNS rebinding: a name that resolves here is not us
                return;
            }
            if (!originOk(ex)) {
                fail(ex, 403, "cross-origin request refused");
                return;
            }
            if ("/open".equals(path)) {
                open(ex);
            } else if ("/api/health".equals(path)) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                json(ex, 200, o);
            } else if (path.startsWith("/api/")) {
                api(ex, path.substring(5));
            } else if ("/events".equals(path)) {
                events(ex);
            } else if ("/media".equals(path)) {
                media(ex);
            } else {
                page(ex, path);
            }
        } catch (Throwable t) {
            mLog.accept("panel: request error: " + t);
            try { fail(ex, 500, "panel error"); } catch (IOException ignored) { }
        }
    }

    private boolean hostOk(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String p = ":" + port();
        return host.equals("127.0.0.1" + p) || host.equals("localhost" + p);
    }

    private boolean originOk(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isEmpty() || "null".equals(origin)) {
            return true;   // same-origin fetches and navigations carry no Origin; the cookie is SameSite=Strict
        }
        String p = ":" + port();
        return origin.equals("http://127.0.0.1" + p) || origin.equals("http://localhost" + p);
    }

    private void open(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String t = q.get("ticket");
        if (t == null || !consumeTicket(t)) {
            html(ex, 403, "<h1>This link has been used or has expired</h1>"
                    + "<p>Ask the account for a new one: <code>parlons panel</code> on its machine, "
                    + "or open the link in <code>panel-ticket.txt</code> beside its data.</p>");
            return;
        }
        String session = token();
        mSessions.put(session, System.currentTimeMillis());
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + session
                + "; Path=/; HttpOnly; SameSite=Strict");
        // Optional landing route (a desktop app opening straight into a chat): a hash only,
        // limited to route characters, so nothing else can be injected into the redirect.
        String to = q.get("to");
        String hash = to == null || !to.matches("[A-Za-z0-9/%._~-]{1,600}") ? "" : "#" + to;
        ex.getResponseHeaders().set("Location", "/" + hash);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private synchronized boolean consumeTicket(String zTicket) {
        sweep();
        if (!mFileTicket.isEmpty() && MessageDigest.isEqual(
                mFileTicket.getBytes(StandardCharsets.UTF_8), zTicket.getBytes(StandardCharsets.UTF_8))) {
            refreshFileTicket();   // used once: the file gets the next one at once
            return true;
        }
        Long exp = mMinted.remove(zTicket);
        return exp != null && exp > System.currentTimeMillis();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        mMinted.entrySet().removeIf(e -> e.getValue() < now);
        mSessions.entrySet().removeIf(e -> now - e.getValue() > SESSION_IDLE_MS);
    }

    private synchronized void refreshFileTicket() {
        mFileTicket = token();
        try {
            AccountFiles.writePrivate(mDataDir.resolve(TICKET_FILE),
                    (fileTicketUrl() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            mLog.accept("panel: could not write " + TICKET_FILE + ": " + e.getMessage());
        }
    }

    /** The session behind this request, or null (a 401 has been sent). */
    private String session(HttpExchange ex) throws IOException {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        String s = null;
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    s = p.substring(COOKIE.length() + 1);
                }
            }
        }
        Long seen = s == null ? null : mSessions.get(s);
        if (seen == null || System.currentTimeMillis() - seen > SESSION_IDLE_MS) {
            fail(ex, 401, "not signed in - open the panel with a fresh link (parlons panel)");
            return null;
        }
        mSessions.put(s, System.currentTimeMillis());
        return s;
    }

    private void api(HttpExchange ex, String zName) throws IOException {
        String session = session(ex);
        if (session == null) {
            return;
        }
        String method = ex.getRequestMethod();
        switch (zName) {
            case "logout": {
                mSessions.remove(session);
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                JSONObject o = new JSONObject();
                o.put("ok", true);
                json(ex, 200, o);
                return;
            }
            case "ticket": {
                if (!"POST".equalsIgnoreCase(method)) { fail(ex, 405, "POST only"); return; }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("url", newTicketUrl());
                json(ex, 200, o);
                return;
            }
            case "invite": {
                // The invite a QR of pairs a phone: the permanent address + the outstanding
                // one-time code (null when no code is outstanding - mint one with pair.newcode).
                JSONObject o = new JSONObject();
                o.put("ok", true);
                String perm = mPermanent == null ? null : mPermanent.get();
                o.put("address", perm == null ? "" : perm);
                String code = null;
                try {
                    Path f = mPairing.codeFile();
                    if (Files.isRegularFile(f)) {
                        code = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
                    }
                } catch (Exception ignored) { }
                String inv = AccountFiles.invite(perm, code);
                o.put("invite", inv == null ? "" : inv);
                json(ex, 200, o);
                return;
            }
            default:
                break;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            fail(ex, 405, "POST only");
            return;
        }
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) {
            fail(ex, 415, "application/json only");   // a cross-site form cannot send this without a preflight
            return;
        }
        String name = zName.startsWith("parlons.") ? zName.substring(8) : zName;
        if (!ALLOWED.contains(name)) {
            fail(ex, 403, "not available from the panel: " + name);
            return;
        }
        byte[] body = readAll(ex.getRequestBody());
        if (body.length == 0) {
            body = "{}".getBytes(StandardCharsets.UTF_8);
        }
        ServiceRegistry.Request req = new ServiceRegistry.Request("parlons." + name, body, mLocalKey,
                Collections.emptyList());   // no reply address: never in the push fan-out
        byte[] out;
        try {
            out = mReg.dispatchLocal(req);
        } catch (SecurityException se) {
            fail(ex, 403, "this computer's access to the account was revoked from a paired device"
                    + " - to re-enable it, delete " + mDataDir.resolve(ParlonsCore.LOCAL_KEY_FILE)
                    + " and restart the account");
            return;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            fail(ex, 400, msg);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private void events(HttpExchange ex) throws IOException {
        if (session(ex) == null) {
            return;
        }
        LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>(SSE_QUEUE);
        mClients.add(q);
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            // The client catches up with chat.since from the time in this hello.
            os.write(("event: hello\ndata: {\"time\":" + System.currentTimeMillis()
                    + ",\"listeners\":" + mClients.size() + "}\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            while (!Thread.currentThread().isInterrupted()) {
                String line = q.poll(SSE_PING_MS, TimeUnit.MILLISECONDS);
                if (line == null) {
                    os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    String id = "";
                    try {
                        Object eid = ((JSONObject) new org.minima.utils.json.parser.JSONParser().parse(line)).get("eid");
                        id = eid == null ? "" : String.valueOf(eid);
                    } catch (Exception ignored) { }
                    os.write(("id: " + id + "\nevent: push\ndata: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
            }
        } catch (IOException | InterruptedException gone) {
            // the tab closed or the server stopped
        } finally {
            mClients.remove(q);
            try { ex.close(); } catch (Exception ignored) { }
        }
    }

    private void media(HttpExchange ex) throws IOException {
        if (session(ex) == null) {
            return;
        }
        if (mMedia == null) {
            fail(ex, 404, "no media service");
            return;
        }
        String m = query(ex).get("m");
        MediaManifest manifest = m == null ? null : MediaManifest.decode(m);
        if (manifest == null) {
            fail(ex, 400, "m must be a media manifest");
            return;
        }
        byte[] plain;
        try {
            plain = mMedia.fetch(manifest);
        } catch (Exception e) {
            fail(ex, 502, "media not available: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return;
        }
        String mime = manifest.mime == null || manifest.mime.isEmpty() ? "application/octet-stream" : manifest.mime;
        String ext = mime.startsWith("image/jpeg") ? "jpg" : mime.startsWith("image/png") ? "png" : mime.startsWith("image/") ? "img"
                : mime.startsWith("audio/") ? "m4a" : mime.startsWith("video/") ? "mp4" : "bin";
        ex.getResponseHeaders().set("Content-Disposition", "inline; filename=\"parlons-media." + ext + "\"");
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; sandbox");
        ex.sendResponseHeaders(200, plain.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(plain);
        }
    }

    /** The panel's static files, from the jar ({@code panel/…}); the root is the panel page. */
    private void page(HttpExchange ex, String zPath) throws IOException {
        String rel = "/".equals(zPath) ? "index.html" : zPath.substring(1);
        if (!rel.matches("[A-Za-z0-9_./-]+") || rel.contains("..")) {
            fail(ex, 404, "not found");
            return;
        }
        InputStream in = ParlonsLocal.class.getResourceAsStream("/panel/" + rel);
        if (in == null) {
            fail(ex, 404, "not found");
            return;
        }
        byte[] bytes;
        try (InputStream i = in) {
            bytes = readAll(i);
        }
        ex.getResponseHeaders().set("Content-Type", contentType(rel));
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; "
                + "style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentType(String rel) {
        String r = rel.toLowerCase();
        if (r.endsWith(".html")) return "text/html; charset=utf-8";
        if (r.endsWith(".css"))  return "text/css; charset=utf-8";
        if (r.endsWith(".js"))   return "text/javascript; charset=utf-8";
        if (r.endsWith(".svg"))  return "image/svg+xml";
        if (r.endsWith(".png"))  return "image/png";
        if (r.endsWith(".ico"))  return "image/x-icon";
        if (r.endsWith(".woff2")) return "font/woff2";
        if (r.endsWith(".mp3"))  return "audio/mpeg";
        if (r.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ---- plumbing ----

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new java.util.HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return out;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                out.put(k, v);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY) {
                throw new IOException("request body too large");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void json(HttpExchange ex, int code, JSONObject o) throws IOException {
        byte[] out = o.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static void fail(HttpExchange ex, int code, String msg) throws IOException {
        JSONObject err = new JSONObject();
        err.put("ok", false);
        err.put("error", msg);
        json(ex, code, err);
    }

    private static void html(HttpExchange ex, int code, String body) throws IOException {
        byte[] out = ("<!doctype html><meta charset=utf-8><title>Parlons</title>"
                + "<body style=\"font-family:system-ui;max-width:40em;margin:4em auto;color:#222\">"
                + body + "</body>").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static String token() {
        byte[] b = new byte[32];
        RAND.nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
