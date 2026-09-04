package com.eurobuddha.maxima.node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.minima.system.commands.CommandRunner;
import org.minima.utils.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The operator's admin channel — every node command, LOOPBACK ONLY.
 *
 * <p>Why this exists instead of Minima's own {@code -rpcenable}: the stock RPC server binds
 * every interface and has no bind option, so on a box with no host firewall (Hetzner) it was
 * reachable from the internet for a few minutes on 2026-09-04 — full admin, no auth. This
 * server is bound to {@code 127.0.0.1} by construction; it cannot be exposed by a firewall
 * mistake. Same URL shape as Minima's RPC ({@code GET /<url-encoded command>}, or a POST whose
 * body is the command) so the runbooks and curl one-liners are unchanged.
 *
 * <p>No auth, deliberately: only root/the {@code maxima} user can reach loopback on the box,
 * and anything that can already runs as them. The phone-facing {@link NodeGateway} is the
 * hardened, allow-listed surface — this one is the opposite by design and must never be
 * fronted by a proxy.
 */
final class AdminRpc {

    private static final int MAX_BODY = 64 * 1024;

    private final HttpServer mServer;

    private AdminRpc(HttpServer zServer) { mServer = zServer; }

    /** Start on {@code 127.0.0.1:port}. Throws if the port is taken — the caller decides. */
    static AdminRpc start(int zPort) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", zPort), 16);
        srv.createContext("/", AdminRpc::handle);
        srv.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "parlons-admin-rpc");
            t.setDaemon(true);
            return t;
        }));
        srv.start();
        return new AdminRpc(srv);
    }

    int port() { return mServer.getAddress().getPort(); }

    void stop() { mServer.stop(0); }

    private static void handle(HttpExchange ex) throws IOException {
        String command;
        try {
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                command = readBody(ex.getRequestBody());
            } else {
                String path = ex.getRequestURI().getRawPath();
                command = URLDecoder.decode(path.startsWith("/") ? path.substring(1) : path, "UTF-8");
            }
        } catch (Exception e) {
            reply(ex, 400, "{\"status\":false,\"error\":\"bad request\"}");
            return;
        }
        command = command.trim();
        if (command.isEmpty()) {
            reply(ex, 400, "{\"status\":false,\"error\":\"empty command\"}");
            return;
        }
        String out;
        int code = 200;
        try {
            JSONObject res = CommandRunner.getRunner().runSingleCommand(command);
            out = res.toString();
        } catch (Throwable t) {
            code = 500;
            JSONObject err = new JSONObject();
            err.put("status", false);
            err.put("error", String.valueOf(t));
            out = err.toString();
        }
        reply(ex, code, out);
    }

    private static String readBody(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_BODY + 1];
        int n = 0, r;
        while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        if (n > MAX_BODY) throw new IOException("body too large");
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int zCode, String zJson) throws IOException {
        byte[] b = zJson.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(zCode, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
