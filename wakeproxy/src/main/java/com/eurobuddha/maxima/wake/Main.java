package com.eurobuddha.maxima.wake;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * parlons-wake: the stateless APNs wake proxy. Binds loopback by default (Caddy terminates TLS
 * in front, exactly like the wallet gateway). Holds the .p8 in memory only.
 */
public final class Main {

    public static final String VERSION = "0.1.2";

    public static void main(String[] args) throws Exception {
        int port = 8090;
        String bind = "127.0.0.1";
        String key = null, keyId = null, teamId = null, bundle = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--bind": bind = args[++i]; break;
                case "--key": key = args[++i]; break;
                case "--key-id": keyId = args[++i]; break;
                case "--team-id": teamId = args[++i]; break;
                case "--bundle": bundle = args[++i]; break;
                case "-v": case "--version": System.out.println("parlons-wake " + VERSION); return;
                default: usage(); return;
            }
        }
        if (key == null || keyId == null || teamId == null || bundle == null) {
            usage();
            System.exit(2);
        }
        ApnsJwt jwt = new ApnsJwt(Files.readAllBytes(Paths.get(key)), keyId, teamId);
        jwt.token();   // fail at boot, not on the first wake, if the key is wrong
        ApnsClient apns = new ApnsClient(jwt, bundle);
        java.util.function.Consumer<String> log = s -> System.out.println("[wake] " + s);
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        server.createContext("/v1/wake", new WakeHandler(apns::wake, new RateLimit(), log));
        server.createContext("/healthz", ex -> {
            byte[] b = ("{\"ok\":true,\"version\":\"" + VERSION + "\"}").getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("parlons-wake " + VERSION + " on " + bind + ":" + port + " for " + bundle
                + " (team " + teamId + ", key " + keyId + "); stateless, content-free, per-token rate limits");
    }

    static void usage() {
        System.out.println("parlons-wake " + VERSION + " - the stateless APNs wake proxy for Parlons Cloud on iOS");
        System.out.println("  --key <AuthKey_XXXX.p8>  --key-id <XXXX>  --team-id <TEAM>  --bundle <com.eurobuddha.parlons>");
        System.out.println("  [--port 8090] [--bind 127.0.0.1]   put Caddy (TLS) in front; POST /v1/wake, GET /healthz");
    }
}
