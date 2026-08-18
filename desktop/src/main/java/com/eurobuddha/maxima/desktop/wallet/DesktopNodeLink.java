package com.eurobuddha.maxima.desktop.wallet;

import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.prefs.Preferences;

/**
 * A tiny RPC client for a running <b>Minima Core Desktop</b> node — the desktop's
 * bridge to a real Minima wallet, the way the phone talks to its bundled node. The
 * user points Maxima at their node's RPC endpoint (e.g. {@code http://127.0.0.1:9005})
 * and this issues Minima commands ({@code balance}, {@code coins}, {@code getaddress},
 * {@code send}, {@code history}) over HTTP, returning the node's JSON.
 *
 * A raw, lenient socket client on purpose: Minima's RPC can emit LF-only header
 * terminators that Java's HttpURLConnection rejects (HPE_INVALID_HEADER_TOKEN), so
 * we parse the response ourselves.
 */
public final class DesktopNodeLink {

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/eurobuddha/maxima/desktop");
    private static final String KEY_URL = "rpcUrl";
    private static final String KEY_PW = "rpcPassword";

    public static final String DEFAULT_URL = "http://127.0.0.1:9005";

    private final String base;      // e.g. http://127.0.0.1:9005
    private final String password;  // optional

    private DesktopNodeLink(String zBase, String zPassword) {
        base = zBase;
        password = zPassword;
    }

    /** The configured link, or null if the user hasn't set an RPC URL yet. */
    public static DesktopNodeLink configured() {
        String url = PREFS.get(KEY_URL, "").trim();
        if (url.isEmpty()) return null;
        return new DesktopNodeLink(stripSlash(url), PREFS.get(KEY_PW, ""));
    }

    public static String configuredUrl() { return PREFS.get(KEY_URL, ""); }
    public static String configuredPassword() { return PREFS.get(KEY_PW, ""); }

    public static void save(String url, String password) {
        PREFS.put(KEY_URL, url == null ? "" : url.trim());
        PREFS.put(KEY_PW, password == null ? "" : password);
    }

    public static void clear() {
        PREFS.remove(KEY_URL);
        PREFS.remove(KEY_PW);
    }

    /** Run a Minima command, returning the parsed JSON reply. Blocks; call off-EDT. */
    public JSONObject cmd(String command) throws Exception {
        URI u = URI.create(base);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String path = "/" + command.trim().replace(" ", "%20");

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setSoTimeout(60000);   // sends grind PoW; give the node time
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(':').append(port).append("\r\n");
            if (password != null && !password.isEmpty()) {
                String tok = Base64.getEncoder().encodeToString(
                        ("minima:" + password).getBytes(StandardCharsets.UTF_8));
                req.append("Authorization: Basic ").append(tok).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            String raw = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            String body = splitBody(raw);
            Object parsed = new JSONParser().parse(body);
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
            JSONObject wrap = new JSONObject();
            wrap.put("status", Boolean.TRUE);
            wrap.put("response", parsed);
            return wrap;
        }
    }

    /** Cheap reachability check: run {@code status} and see if it parses. */
    public boolean ping() {
        try {
            JSONObject r = cmd("status");
            return r != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- helpers ----

    private static String splitBody(String raw) {
        int i = raw.indexOf("\r\n\r\n");
        int cut = i >= 0 ? i + 4 : -1;
        if (cut < 0) {
            int j = raw.indexOf("\n\n");
            cut = j >= 0 ? j + 2 : 0;
        }
        String body = raw.substring(cut).trim();
        // Chunked-transfer bodies begin with a hex length line; if the body doesn't
        // start with { or [, try to find the first JSON object.
        if (!body.startsWith("{") && !body.startsWith("[")) {
            int b = body.indexOf('{');
            if (b >= 0) body = body.substring(b);
        }
        return body;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
