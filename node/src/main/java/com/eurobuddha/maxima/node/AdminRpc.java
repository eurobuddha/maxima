package com.eurobuddha.maxima.node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

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
 * <p>No auth, deliberately: this trusts LOCAL processes (loopback is not a per-user access
 * control). Browser requests are restricted by Host, Origin and Fetch Metadata, following
 * the account panel's rebinding protection. The phone-facing {@link NodeGateway} is the
 * hardened, allow-listed surface — this one is the opposite by design and must never be
 * fronted by a proxy.
 */
final class AdminRpc {

    private static final int MAX_BODY = 64 * 1024;

    private final HttpServer mServer;
    private final ExecutorService mExec;

    private AdminRpc(HttpServer zServer, ExecutorService zExec) { mServer = zServer; mExec = zExec; }

    /** Start on {@code 127.0.0.1:port}. Throws if the port is taken — the caller decides. */
    static AdminRpc start(int zPort) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", zPort), 16);
        srv.createContext("/", AdminRpc::handle);
        ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "parlons-admin-rpc");
            t.setDaemon(true);
            return t;
        });
        srv.setExecutor(exec);
        srv.start();
        return new AdminRpc(srv, exec);
    }

    int port() { return mServer.getAddress().getPort(); }

    void stop() { mServer.stop(0); mExec.shutdownNow(); }

    private static void handle(HttpExchange ex) throws IOException {
        if (!localRequest(ex)) {
            reply(ex, 403, "{\"status\":false,\"error\":\"cross-origin admin request refused\"}");
            return;
        }
        if (!"GET".equals(ex.getRequestMethod()) && !"POST".equals(ex.getRequestMethod())) {
            reply(ex, 405, "{\"status\":false,\"error\":\"GET or POST only\"}");
            return;
        }
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
            // Minima's `quit` stops the node's own threads but never exits the JVM (Minima.main did
            // that, and this jar does not run it): the cape, gateway and account would linger with
            // no chain under them. A desktop app stops its node with `quit` and expects the process
            // to end - so end it, through the shutdown hook, once the reply has left.
            if ("quit".equals(command.split("\\s+", 2)[0].toLowerCase())) {
                Thread exit = new Thread(() -> {
                    try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                    System.out.println("[parlons-node] quit: node stopped - exiting");
                    System.exit(0);
                }, "parlons-admin-quit");
                exit.setDaemon(true);
                exit.start();
            }
        } catch (Throwable t) {
            code = 500;
            JSONObject err = new JSONObject();
            err.put("status", false);
            err.put("error", String.valueOf(t));
            out = err.toString();
        }
        reply(ex, code, out);
    }

    /** Adapted from ParlonsLocal.hostOk/originOk. Unlike its cookie-bound panel, this
     * endpoint must also reject opaque origins and cross-site GETs before dispatch. */
    private static boolean localRequest(HttpExchange ex) {
        com.sun.net.httpserver.Headers h = ex.getRequestHeaders();
        String suffix = ":" + ex.getLocalAddress().getPort();
        String host = h.getFirst("Host");
        if (h.get("Host") == null || h.get("Host").size() != 1
                || !( ("127.0.0.1" + suffix).equals(host) || ("localhost" + suffix).equals(host))) return false;
        String origin = h.getFirst("Origin");
        if (origin != null && (h.get("Origin").size() != 1 || !origin.equals("http://" + host))) return false;
        String site = h.getFirst("Sec-Fetch-Site");
        return site == null || "same-origin".equals(site) || "none".equals(site);
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
