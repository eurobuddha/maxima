package com.eurobuddha.maxima.desktop.keets;

import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.sun.net.httpserver.HttpServer;

import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Inbound keets chat for Parlons Desktop: minimaCore Desktop (0.18.4+) owns the
 * linked node's inbox and POSTs every drained message here - the desktop twin
 * of the Android KEETS NOTIFY. Loopback only, authenticated by a per-boot token
 * we hand over through the registration file in the NODE's data dir (both apps
 * discover that dir from the node's own `status`, so there is no configuration).
 * One-shot best-effort semantics: if this app is closed when an event fires,
 * the node app's KeetChat still holds the durable copy.
 */
public final class KeetsCompanionServer {

    private final ChatEngine mChat;
    private final String mToken;
    private HttpServer mServer;
    private final ExecutorService mWork = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "keets-companion-inbound");
        t.setDaemon(true);
        return t;
    });

    public KeetsCompanionServer(ChatEngine zChat) {
        mChat = zChat;
        byte[] tok = new byte[24];
        new SecureRandom().nextBytes(tok);
        StringBuilder sb = new StringBuilder();
        for (byte b : tok) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        mToken = sb.toString();
    }

    /** Bind 127.0.0.1:<ephemeral>; returns the URL to register. */
    public String start() throws Exception {
        mServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        mServer.createContext("/keets", ex -> {
            int code = 204;
            try {
                if (!"POST".equals(ex.getRequestMethod())
                        || !mToken.equals(ex.getRequestHeaders().getFirst("x-keets-token"))) {
                    code = 403;
                    return;
                }
                byte[] body;
                try (InputStream is = ex.getRequestBody()) {
                    body = is.readAllBytes();
                }
                JSONObject ev = (JSONObject) new JSONParser()
                        .parse(new String(body, StandardCharsets.UTF_8));
                if (!"KEETS".equals(ev.get("event"))) {
                    return;
                }
                JSONObject data = (JSONObject) ev.get("data");
                String from = String.valueOf(data.get("from"));
                String id = String.valueOf(data.getOrDefault("id", ""));
                String payload = new String(Base64.getDecoder()
                        .decode(String.valueOf(data.get("payload"))), StandardCharsets.UTF_8);
                // onKeetsInbound blocks on the history load - never on this socket thread
                mWork.execute(() -> {
                    try {
                        mChat.onKeetsInbound(from, payload, id);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception bad) {
                code = 400;
            } finally {
                ex.sendResponseHeaders(code, -1);
                ex.close();
            }
        });
        mServer.start();
        return "http://127.0.0.1:" + mServer.getAddress().getPort() + "/keets";
    }

    /**
     * Write our registration into {@code <nodeDataDir>/keets-companions.json},
     * keeping other companions' rows. The node app re-reads it on change.
     */
    @SuppressWarnings("unchecked")
    public void register(String zNodeDataDir, String zUrl) throws Exception {
        File f = new File(zNodeDataDir, "keets-companions.json");
        org.minima.utils.json.JSONArray list = new org.minima.utils.json.JSONArray();
        if (f.isFile()) {
            try {
                Object cur = new JSONParser().parse(new String(
                        Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (cur instanceof org.minima.utils.json.JSONArray) {
                    for (Object o : (org.minima.utils.json.JSONArray) cur) {
                        if (o instanceof JSONObject
                                && !zUrl.equals(String.valueOf(((JSONObject) o).get("url")))
                                && !String.valueOf(((JSONObject) o).get("app")).equals("parlons-desktop")) {
                            list.add(o);
                        }
                    }
                }
            } catch (Exception stale) {
                // unreadable file - rewrite it
            }
        }
        JSONObject me = new JSONObject();
        me.put("app", "parlons-desktop");
        me.put("url", zUrl);
        me.put("token", mToken);
        list.add(me);
        Files.write(f.toPath(), list.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void stop() {
        if (mServer != null) {
            mServer.stop(0);
        }
        mWork.shutdownNow();
    }
}
