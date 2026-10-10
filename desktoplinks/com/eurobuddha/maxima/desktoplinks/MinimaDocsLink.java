package com.eurobuddha.maxima.desktoplinks;

import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Scoped, explicitly approved companion access. No owner RPC or chat capability. */
public final class MinimaDocsLink implements AutoCloseable {
    public static final String APPLICATION = "com.eurobuddha.minimadocs.invite.v1";
    private static final String ROOT = "/minimadocs/v1/";
    private static final long WEEK = 7L * 24 * 3600_000;
    private static final int BODY_MAX = 32768, RESPONSE_MAX = 262144;
    private final ChatPort port;
    private final String provider, account;
    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Long> codes = new LinkedHashMap<>();
    private JSONObject state;
    private HttpServer server;
    private ExecutorService workers;
    private ScheduledExecutorService deadlines;

    public MinimaDocsLink(ChatPort port, Path directory, String provider) throws Exception {
        this(port, directory, provider, System::currentTimeMillis);
    }
    public MinimaDocsLink(ChatPort port, Path directory, String provider, LongSupplier clock) throws Exception {
        if (!Arrays.asList("desktop", "core").contains(provider)) throw new IllegalArgumentException("provider");
        this.port = port; this.provider = provider; this.clock = clock;
        account = hash(keyBytes(port.publicKeyHex()));
        Files.createDirectories(directory);
        file = directory.resolve("minimadocs-" + provider + "-" + account + ".json");
        if (Files.exists(file)) {
            if (Files.size(file) > 4 * 1024 * 1024) throw new IOException("Companion state too large");
            state = new JSONObject(Files.readString(file));
            if (!account.equals(state.getString("account")) || state.getInt("version") != 1
                    || state.getJSONArray("tokens").length() > 8 || state.getJSONArray("inbox").length() > 64
                    || state.getJSONObject("seen").length() > 1024 || state.getJSONObject("requests").length() > 512)
                throw new IOException("Invalid companion state");
        } else {
            state = new JSONObject().put("version", 1).put("account", account).put("port", 0)
                    .put("tokens", new JSONArray()).put("inbox", new JSONArray())
                    .put("seen", new JSONObject()).put("requests", new JSONObject());
        }
    }

    public synchronized void start() throws Exception {
        if (server != null) return;
        HttpServer next = HttpServer.create(new InetSocketAddress("127.0.0.1", state.getInt("port")), 16);
        try {
            JSONObject saved = copy(); saved.put("port", next.getAddress().getPort()); commit(saved);
            workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> daemon(r, "minimadocs-link"), new ThreadPoolExecutor.AbortPolicy());
            deadlines = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "minimadocs-deadline"));
            next.createContext("/", this::route); next.setExecutor(workers); server = next; next.start();
        } catch (Exception e) { next.stop(0); throw e; }
    }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    public synchronized int port() { return server == null ? state.getInt("port") : server.getAddress().getPort(); }
    public synchronized int approvals() { return state.getJSONArray("tokens").length(); }
    /** Called only by an explicit host UI approval, never by the companion endpoint. */
    public synchronized String approve() {
        if (server == null) throw new IllegalStateException("Companion service unavailable");
        codes.entrySet().removeIf(e -> e.getValue() <= clock.getAsLong());
        if (codes.size() >= 8 || approvals() >= 8) throw new IllegalStateException("Revoke old connections first");
        String code = random(); codes.put(code, clock.getAsLong() + 120_000);
        return "minimadocs://parlons/v1?port=" + port() + "&provider=" + provider + "&account=" + account + "&code=" + code;
    }
    public synchronized void revoke() throws Exception {
        JSONObject next = copy(); next.put("tokens", new JSONArray()); commit(next); codes.clear();
    }
    public synchronized void close() {
        codes.clear();
        if (server != null) server.stop(0);
        server = null;
        if (workers != null) workers.shutdownNow();
        if (deadlines != null) deadlines.shutdownNow();
    }

    private void route(HttpExchange ex) throws IOException {
        ScheduledFuture<?> deadline = deadlines.schedule(ex::close, 20, TimeUnit.SECONDS);
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) throw new Refused(403, "Wrong host");
            if (ex.getRequestHeaders().containsKey("Origin")) throw new Refused(403, "Browser access refused");
            if (!"POST".equals(ex.getRequestMethod())) throw new Refused(405, "POST required");
            String route = ex.getRequestURI().getRawPath();
            if (!route.startsWith(ROOT) || ex.getRequestURI().getRawQuery() != null) throw new Refused(404, "Unknown operation");
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.matches("(?i)application/json(?:\\s*;.*)?")) throw new Refused(415, "JSON required");
            byte[] raw = ex.getRequestBody().readNBytes(BODY_MAX + 1);
            if (raw.length > BODY_MAX) throw new Refused(413, "Request too large");
            JSONObject input;
            try {
                org.json.JSONTokener json = new org.json.JSONTokener(new String(raw, StandardCharsets.UTF_8));
                input = new JSONObject(json);
                if (json.nextClean() != 0) throw new IllegalArgumentException();
            }
            catch (RuntimeException | StackOverflowError bad) { throw new Refused(400, "Invalid JSON"); }
            JSONObject out = dispatch(route.substring(ROOT.length()), ex.getRequestHeaders().getFirst("Authorization"), input);
            reply(ex, 200, out);
        } catch (Refused e) { reply(ex, e.status, new JSONObject().put("ok", false).put("error", e.getMessage())); }
        catch (IllegalArgumentException | org.json.JSONException e) { reply(ex, 400, new JSONObject().put("ok", false).put("error", "Invalid request")); }
        catch (Exception e) { reply(ex, 503, new JSONObject().put("ok", false).put("error", "Account operation unavailable")); }
        finally { deadline.cancel(false); ex.close(); }
    }
    private synchronized JSONObject dispatch(String operation, String auth, JSONObject in) throws Exception {
        if (server == null) throw new Refused(503, "Account closed");
        JSONObject next = copy(); prune(next);
        JSONObject out = new JSONObject().put("ok", true).put("provider", provider).put("account", account);
        if ("connect".equals(operation)) {
            fields(in, "code"); String code = string(in, "code", 64);
            Long until = codes.get(code);
            if (until == null || until <= clock.getAsLong() || approvals() >= 8) throw new Refused(403, "Connection link expired or used");
            String token = random(); next.getJSONArray("tokens").put(hash(token.getBytes(StandardCharsets.UTF_8)));
            commit(next); codes.remove(code);
            return out.put("token", token).put("name", port.name()).put("publicKey", port.publicKeyHex());
        }
        String digest = auth != null && auth.matches("Bearer [0-9a-f]{64}") ? hash(auth.substring(7).getBytes(StandardCharsets.UTF_8)) : "";
        JSONArray tokens = next.getJSONArray("tokens"); int tokenIndex = -1;
        for (int i = 0; i < tokens.length(); i++) if (MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8), tokens.getString(i).getBytes(StandardCharsets.UTF_8))) tokenIndex = i;
        if (tokenIndex < 0) throw new Refused(403, "Access revoked or unknown");
        switch (operation) {
            case "contacts": {
                fields(in); JSONArray contacts = new JSONArray();
                for (Contact c : port.contacts()) {
                    keyBytes(c.publicKey);
                    if (c.name == null || c.name.length() > 1024 || c.shareAddress().length() > 8192) throw new Refused(503, "Invalid contact");
                    contacts.put(new JSONObject().put("key", c.publicKey.toLowerCase(Locale.ROOT)).put("name", c.name).put("address", c.shareAddress()));
                    if (contacts.length() > 4096) throw new Refused(413, "Contact list too large");
                }
                return out.put("contacts", contacts);
            }
            case "invitations": fields(in); if (!state.similar(next)) commit(next); return out.put("invitations", next.getJSONArray("inbox"));
            case "contact/add": {
                fields(in, "address"); String address = string(in, "address", 8192);
                if (!reachable(address) || address.matches("(?s).*[\\x00-\\x20\\x7f].*")) throw new IllegalArgumentException();
                port.introduce(address, true); return out.put("state", "introduced");
            }
            case "contact/remove": {
                fields(in, "key"); String key = string(in, "key", 2050); keyBytes(key);
                return out.put("removed", port.removeContact(key));
            }
            case "dismiss": {
                fields(in, "id"); String id = string(in, "id", 64);
                if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                JSONArray box = next.getJSONArray("inbox");
                for (int i = box.length() - 1; i >= 0; i--) if (id.equals(box.getJSONObject(i).getString("id"))) box.remove(i);
                commit(next); return out;
            }
            case "disconnect": fields(in); tokens.remove(tokenIndex); commit(next); return out;
            case "invite": {
                fields(in, "to", "line", "requestId");
                String to = string(in, "to", 2050).toLowerCase(Locale.ROOT); keyBytes(to);
                String line = string(in, "line", 16384); validateLine(line);
                String id = string(in, "requestId", 64);
                if (!id.matches("[A-Za-z0-9-]{16,64}")) throw new IllegalArgumentException();
                String requestKey = hash((digest + "\n" + id).getBytes(StandardCharsets.UTF_8));
                String payload = hash((to + "\n" + line).getBytes(StandardCharsets.UTF_8));
                JSONObject requests = next.getJSONObject("requests"), previous = requests.optJSONObject(requestKey);
                if (previous != null) {
                    if (!payload.equals(previous.getString("payload"))) throw new Refused(409, "Request id already used");
                    String result = previous.getString("state");
                    if ("pending".equals(result)) throw new Refused(409, "Previous send outcome uncertain; do not resend");
                    return out.put("state", result);
                }
                if (requests.length() >= 512) throw new Refused(429, "Invitation request limit reached");
                Contact contact = port.contact(to);
                if (contact == null) throw new Refused(400, "Unknown contact");
                JSONObject record = new JSONObject().put("payload", payload).put("time", clock.getAsLong()).put("state", "pending");
                requests.put(requestKey, record); commit(next); // reserve before any network side effect
                String result;
                if (port instanceof MaximaNode) {
                    ((MaximaNode) port).sendReliable(contact, APPLICATION, line.getBytes(StandardCharsets.UTF_8)); result = "queued";
                } else {
                    if (!port.sendToContact(contact, APPLICATION, line.getBytes(StandardCharsets.UTF_8)).isOk()) throw new IOException("Relay did not accept invitation");
                    result = "sent";
                }
                record.put("state", result); commit(next); return out.put("state", result);
            }
            default: throw new Refused(404, "Unknown operation");
        }
    }

    /** Returns true for this channel, including malformed payloads that must be discarded.
     * Disk failures propagate to the transport's retry path, never becoming an acknowledgement. */
    public synchronized boolean receive(MaximaMessage message) {
        if (!APPLICATION.equals(message.mApplication.toString())) return false;
        String from = message.mFrom.to0xString().toLowerCase(Locale.ROOT);
        String line = new String(message.mData.getBytes(), StandardCharsets.UTF_8);
        try { keyBytes(from); validateLine(line); }
        catch (IllegalArgumentException bad) { return true; }
        long sent;
        try { sent = message.mTimeMilli.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException bad) { return true; }
        if (sent < clock.getAsLong() - WEEK || sent > clock.getAsLong() + 300_000) return true;
        JSONObject next = copy(); prune(next);
        String id = hash((from + "\n" + line).getBytes(StandardCharsets.UTF_8));
        JSONObject seen = next.getJSONObject("seen");
        if (seen.has(id)) return true;
        if (seen.length() >= 1024) throw new IllegalStateException("Invitation replay capacity reached");
        JSONArray inbox = next.getJSONArray("inbox");
        seen.put(id, clock.getAsLong());
        inbox.put(new JSONObject().put("id", id).put("from", from).put("line", line));
        while (inbox.length() > 64 || inbox.toString().getBytes(StandardCharsets.UTF_8).length > 220000) inbox.remove(0);
        try { commit(next); } catch (Exception disk) { throw new IllegalStateException("Cannot persist invitation", disk); }
        return true;
    }
    private void prune(JSONObject next) {
        long oldest = clock.getAsLong() - WEEK;
        JSONObject seen = next.getJSONObject("seen"), requests = next.getJSONObject("requests");
        for (String id : new HashSet<>(seen.keySet())) if (seen.getLong(id) < oldest) seen.remove(id);
        for (String id : new HashSet<>(requests.keySet())) if (requests.getJSONObject(id).getLong("time") < oldest) requests.remove(id);
        JSONArray box = next.getJSONArray("inbox");
        for (int i = box.length() - 1; i >= 0; i--) if (!seen.has(box.getJSONObject(i).getString("id"))) box.remove(i);
    }
    private JSONObject copy() { return new JSONObject(state.toString()); }
    private void commit(JSONObject next) throws Exception { PrivateFiles.write(file, next.toString().getBytes(StandardCharsets.UTF_8)); state = new JSONObject(next.toString()); }
    private static void fields(JSONObject in, String... keys) { if (!in.keySet().equals(new HashSet<>(Arrays.asList(keys)))) throw new IllegalArgumentException(); }
    private static String string(JSONObject in, String key, int max) { Object value = in.get(key); if (!(value instanceof String) || ((String) value).length() > max) throw new IllegalArgumentException(); return (String)value; }
    private static byte[] keyBytes(String key) {
        if (key == null || !key.matches("0x(?:[a-fA-F0-9]{2}){32,1024}")) throw new IllegalArgumentException("Invalid public key");
        byte[] out = new byte[(key.length() - 2) / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte)Integer.parseInt(key.substring(2 + i * 2, 4 + i * 2), 16);
        return out;
    }
    /** Pairing.read's MN1/MN2 shape and reachable-address checks. Keys/permissions are still
     * validated by minimaDocs Pairing before acceptance; this host never grants document access. */
    public static boolean retainedInvitation(MaximaMessage message) {
        if (!APPLICATION.equals(message.mApplication.toString()) || message.mData.getLength() > 16384) return false;
        try { validateLine(new String(message.mData.getBytes(), StandardCharsets.UTF_8)); return true; }
        catch (IllegalArgumentException bad) { return false; }
    }
    static void validateLine(String line) {
        if (line == null || line.length() > 4096) throw new IllegalArgumentException();
        String one = line.trim(), body;
        if (one.startsWith("MN2.")) body = one.substring(4);
        else if (one.startsWith("MN1.")) body = new String(Base64.getUrlDecoder().decode(one.substring(4)), StandardCharsets.UTF_8);
        else throw new IllegalArgumentException();
        String[] fields = body.split(one.startsWith("MN2.") ? "\\|" : "\n", -1);
        if (fields.length != 4 && fields.length != 6 && fields.length != 8) throw new IllegalArgumentException();
        if (!reachable(fields[1].trim())) throw new IllegalArgumentException();
        if (Base64.getDecoder().decode(fields[2]).length == 0 || Base64.getDecoder().decode(fields[3]).length == 0) throw new IllegalArgumentException();
    }
    private static boolean reachable(String address) {
        if (address.length() > 1024) return false;
        if (address.startsWith("MAX#")) { String[] p = address.split("#", -1); return p.length == 3 && !p[1].trim().isEmpty() && !p[2].trim().isEmpty(); }
        int at = address.lastIndexOf('@');
        return address.startsWith("Mx") && at >= 3 && at < address.length() - 1 && !address.substring(at + 1).startsWith(":");
    }
    private static String random() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return hex(b); }
    private static String hash(byte[] bytes) { try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 255)); return s.toString(); }
    private static void reply(HttpExchange ex, int status, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > RESPONSE_MAX) { status = 413; bytes = "{\"ok\":false,\"error\":\"Response too large\"}".getBytes(StandardCharsets.UTF_8); }
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length); ex.getResponseBody().write(bytes);
    }
    private static final class Refused extends Exception { final int status; Refused(int s, String m) { super(m); status = s; } }
}
