package com.eurobuddha.maxima.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class WakeProxyTest {

    static byte[] freshP8() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] der = g.generateKeyPair().getPrivate().getEncoded();
        return ("-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void jwtHasTheAppleShapeAndARawEs256Signature() throws Exception {
        ApnsJwt jwt = new ApnsJwt(freshP8(), "ABC123DEFG", "Z4JD286WF4");
        String t = jwt.token();
        String[] parts = t.split("\\.");
        assertEquals(3, parts.length);
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertEquals("{\"alg\":\"ES256\",\"kid\":\"ABC123DEFG\"}", header);
        assertTrue(claims.startsWith("{\"iss\":\"Z4JD286WF4\",\"iat\":"));
        assertEquals("raw r||s, 64 bytes", 64, Base64.getUrlDecoder().decode(parts[2]).length);
        assertEquals("cached", t, jwt.token());
    }

    @Test
    public void derToJoseHandlesLeadingZerosAndShortIntegers() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        java.security.KeyPair kp = g.generateKeyPair();
        for (int i = 0; i < 20; i++) {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(kp.getPrivate());
            s.update(("m" + i).getBytes());
            byte[] raw = ApnsJwt.derToJose(s.sign(), 32);
            assertEquals(64, raw.length);
        }
    }

    @Test
    public void rateLimitIsPerTokenAndGlobal() {
        RateLimit rl = new RateLimit();
        long t = 1_000_000L;
        assertTrue(rl.allow("a", t));
        assertFalse("within 10 s", rl.allow("a", t + 5_000));
        assertTrue(rl.allow("b", t + 5_000));
        assertTrue(rl.allow("a", t + 10_001));
        long h = t + 100_000;
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            if (rl.allow("c", h + i * 11_000L)) ok++;
        }
        assertEquals("60 per hour", 60, ok);
    }

    @Test
    public void handlerValidatesQueuesAndNeverLogsTheToken() throws Exception {
        List<String> log = Collections.synchronizedList(new java.util.ArrayList<>());
        List<String> sent = Collections.synchronizedList(new java.util.ArrayList<>());
        WakeHandler h = new WakeHandler((token, env, kind) -> {
            sent.add(token + "|" + env + "|" + kind);
            return new ApnsClient.Result(200, "");
        }, new RateLimit(), log::add);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/v1/wake", h);
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/wake";
            HttpClient c = HttpClient.newHttpClient();
            String token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\",\"env\":\"sandbox\",\"kind\":\"call\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, r.statusCode());
            long until = System.currentTimeMillis() + 5000;
            while (sent.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
            assertEquals(token + "|sandbox|call", sent.get(0));
            while (log.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
            assertFalse("the token never appears in a log line", log.get(0).contains(token.substring(0, 12)));
            assertTrue(log.get(0).contains("-> 200"));
            HttpResponse<String> again = c.send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("rate limited", 429, again.statusCode());
            HttpResponse<String> bad = c.send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"nope\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());
            HttpResponse<String> get = c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void payloadIsContentFreeAndMutable() {
        String p = ApnsClient.payload("message");
        assertTrue(p.contains("\"mutable-content\":1"));
        assertTrue(p.contains("\"body\":\"New message\""));
        assertTrue(ApnsClient.payload("call").contains("Incoming call"));
        assertFalse(p.contains("sender"));
    }
}
