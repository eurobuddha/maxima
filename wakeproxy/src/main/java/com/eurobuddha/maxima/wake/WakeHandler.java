package com.eurobuddha.maxima.wake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * POST /v1/wake {"token":hex,"env":"prod"|"sandbox","kind":"message"|"call"} -> 202 (queued),
 * 400 (malformed), 429 (rate limited). The token is never logged: only the first 8 hex chars of
 * its SHA-256 and the APNs status appear, so a log line identifies nothing.
 */
public final class WakeHandler implements HttpHandler {

    interface Sender {
        ApnsClient.Result wake(String zToken, String zEnv, String zKind) throws Exception;
    }

    private final Sender mSender;
    private final RateLimit mLimit;
    private final java.util.function.Consumer<String> mLog;
    private final java.util.concurrent.ExecutorService mExec =
            java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "wake-send");
                t.setDaemon(true);
                return t;
            });

    public WakeHandler(Sender zSender, RateLimit zLimit, java.util.function.Consumer<String> zLog) {
        mSender = zSender;
        mLimit = zLimit;
        mLog = zLog;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                reply(ex, 405, "POST only");
                return;
            }
            byte[] body = readBounded(ex, 4096);
            if (body == null) {
                reply(ex, 413, "too big");
                return;
            }
            String json = new String(body, StandardCharsets.UTF_8);
            String token = field(json, "token");
            String env = field(json, "env");
            String kind = field(json, "kind");
            if (!token.matches("[0-9A-Fa-f]{32,512}")) {
                reply(ex, 400, "token");
                return;
            }
            if (!env.equals("prod") && !env.equals("sandbox")) {
                env = "prod";
            }
            if (!kind.equals("message") && !kind.equals("call")) {
                kind = "message";
            }
            final String id = idOf(token);
            if (!mLimit.allow(id, System.currentTimeMillis())) {
                reply(ex, 429, "rate limited");
                return;
            }
            final String fenv = env, fkind = kind, ftoken = token;
            mExec.execute(() -> {
                try {
                    ApnsClient.Result r = mSender.wake(ftoken, fenv, fkind);
                    mLog.accept("wake " + id + " " + fkind + " " + r.env + " -> " + r.status + (r.reason.isEmpty() ? "" : " " + r.reason));
                } catch (Exception e) {
                    mLog.accept("wake " + id + " failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            });
            reply(ex, 202, "queued");
        } catch (Exception e) {
            reply(ex, 500, "error");
        }
    }

    static String idOf(String zToken) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(zToken.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%02x", h[i]));
        }
        return sb.toString();
    }

    static String field(String zJson, String zKey) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + zKey + "\"\\s*:\\s*\"([^\"]*)\"").matcher(zJson);
        return m.find() ? m.group(1) : "";
    }

    private static byte[] readBounded(HttpExchange ex, int zMax) throws IOException {
        java.io.InputStream in = ex.getRequestBody();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > zMax) {
                return null;
            }
        }
        return out.toByteArray();
    }

    private static void reply(HttpExchange ex, int zStatus, String zText) throws IOException {
        byte[] b = ("{\"status\":\"" + zText + "\"}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(zStatus, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
