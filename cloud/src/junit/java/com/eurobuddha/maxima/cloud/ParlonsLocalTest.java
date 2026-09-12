package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.rpc.ServiceRegistry;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.minima.utils.json.JSONObject;

/**
 * The local web panel's door: a one-time ticket becomes a cookie, the cookie is the only way in,
 * the allow-list is the only way through, and every event the phones are pushed reaches the
 * browser as a server-sent event.
 */
public class ParlonsLocalTest {

    private Path dir;
    private ServiceRegistry reg;
    private DevicePairing pairing;
    private ParlonsLocal local;
    private byte[] key;
    private final AtomicReference<ServiceRegistry.Request> seen = new AtomicReference<>();

    @Before
    public void up() throws Exception {
        dir = Files.createTempDirectory("parlons-local");
        reg = new ServiceRegistry();
        pairing = new DevicePairing(dir);
        key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7);
        assertTrue(pairing.authorizeLocal(key, "this computer (test)", true));
        reg.register("parlons.ping", req -> {
            seen.set(req);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("name", "Test");
            return o.toString().getBytes(StandardCharsets.UTF_8);
        });
        reg.register("parlons.seed.reveal", req -> "SECRET".getBytes(StandardCharsets.UTF_8));
        reg.register("parlons.chat.conversation", req -> {
            byte[] big = new byte[ServiceRegistry.MAX_REPLY_BYTES + 1000];   // over the wire cap
            java.util.Arrays.fill(big, (byte) 'x');
            return big;
        });
        local = new ParlonsLocal(reg, key, dir, 0, () -> "MAX#0xAB#Mx1@h:1", pairing, null, s -> { });
        local.start();
    }

    @After
    public void down() {
        local.stop();
    }

    private String base() {
        return "http://127.0.0.1:" + local.port();
    }

    private static final class Resp {
        int code;
        String body;
        String cookie;
        String location;
    }

    private Resp call(String method, String path, String cookie, String origin, String body, String host) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (host != null) c.setRequestProperty("Host", host);
        if (cookie != null) c.setRequestProperty("Cookie", ParlonsLocal.COOKIE + "=" + cookie);
        if (origin != null) c.setRequestProperty("Origin", origin);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        r.body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        String sc = c.getHeaderField("Set-Cookie");
        if (sc != null && sc.startsWith(ParlonsLocal.COOKIE + "=")) {
            r.cookie = sc.substring(ParlonsLocal.COOKIE.length() + 1, sc.indexOf(';'));
            assertTrue("HttpOnly", sc.contains("HttpOnly"));
            assertTrue("SameSite=Strict", sc.contains("SameSite=Strict"));
        }
        r.location = c.getHeaderField("Location");
        return r;
    }

    private String fileTicket() throws Exception {
        String url = new String(Files.readAllBytes(dir.resolve(ParlonsLocal.TICKET_FILE)), StandardCharsets.UTF_8).trim();
        return url.substring(url.indexOf("ticket=") + 7);
    }

    private String signIn() throws Exception {
        Resp r = call("GET", "/open?ticket=" + fileTicket(), null, null, null, null);
        assertEquals(302, r.code);
        assertEquals("/", r.location);
        assertNotNull(r.cookie);
        return r.cookie;
    }

    @Test
    public void theFileTicketSignsInOnceAndIsReplacedAtOnce() throws Exception {
        String t1 = fileTicket();
        String cookie = signIn();
        assertNotEquals("a used ticket is replaced in the file", t1, fileTicket());
        Resp again = call("GET", "/open?ticket=" + t1, null, null, null, null);
        assertEquals("used once", 403, again.code);
        assertEquals(200, call("POST", "/api/ping", cookie, null, "{}", null).code);
    }

    @Test
    public void aMintedTicketWorksOnceToo() throws Exception {
        String url = local.newTicketUrl();
        String t = url.substring(url.indexOf("ticket=") + 7);
        assertEquals(302, call("GET", "/open?ticket=" + t, null, null, null, null).code);
        assertEquals(403, call("GET", "/open?ticket=" + t, null, null, null, null).code);
        assertEquals(403, call("GET", "/open?ticket=nope", null, null, null, null).code);
    }

    @Test
    public void noCookieNoApi() throws Exception {
        assertEquals(401, call("POST", "/api/ping", null, null, "{}", null).code);
        assertEquals(401, call("POST", "/api/ping", "bogus", null, "{}", null).code);
        assertEquals(401, call("GET", "/events", null, null, null, null).code);
        assertEquals("health needs no cookie", 200, call("GET", "/api/health", null, null, null, null).code);
    }

    @Test
    public void theCallReachesTheHandlerAsTheLocalDevice() throws Exception {
        String cookie = signIn();
        Resp r = call("POST", "/api/ping", cookie, "http://127.0.0.1:" + local.port(), "{}", null);
        assertEquals(200, r.code);
        assertTrue(r.body.contains("\"name\":\"Test\""));
        ServiceRegistry.Request req = seen.get();
        assertNotNull(req);
        assertEquals("parlons.ping", req.method);
        assertTrue(java.util.Arrays.equals(key, req.fromPublicKey));
        assertTrue("no reply address: never in the push fan-out", req.replyTo.isEmpty());
        assertTrue(pairing.isAuthorized(key));
        assertTrue(pairing.isLocal(new com.eurobuddha.maxima.core.codec.MiniData(key).to0xString()));
        // the parlons. prefix is accepted too
        assertEquals(200, call("POST", "/api/parlons.ping", cookie, null, "{}", null).code);
    }

    @Test
    public void theAllowListRefusesTheSeed() throws Exception {
        String cookie = signIn();
        Resp r = call("POST", "/api/seed.reveal", cookie, null, "{}", null);
        assertEquals(403, r.code);
        assertFalse(r.body.contains("SECRET"));
    }

    /** HttpURLConnection silently drops Origin and Host overrides: raw HTTP for the refusal cases. */
    private String raw(String zRequest) throws Exception {
        try (java.net.Socket sock = new java.net.Socket("127.0.0.1", local.port())) {
            sock.getOutputStream().write(zRequest.getBytes(StandardCharsets.UTF_8));
            return new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream(),
                    StandardCharsets.UTF_8)).readLine();
        }
    }

    @Test
    public void crossOriginAndWrongHostAreRefused() throws Exception {
        String cookie = signIn();
        String me = "127.0.0.1:" + local.port();
        String post = "POST /api/ping HTTP/1.1\r\nCookie: " + ParlonsLocal.COOKIE + "=" + cookie
                + "\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n";
        assertTrue(raw(post + "Host: " + me + "\r\nOrigin: http://" + me + "\r\n\r\n{}").contains(" 200 "));
        assertTrue(raw(post + "Host: " + me + "\r\nOrigin: http://evil.example\r\n\r\n{}").contains(" 403 "));
        assertTrue(raw(post + "Host: evil.example:" + local.port() + "\r\n\r\n{}").contains(" 403 "));
        assertTrue(raw("GET /open?ticket=" + fileTicket() + " HTTP/1.1\r\nHost: " + me
                + "\r\nOrigin: https://evil.example\r\nConnection: close\r\n\r\n").contains(" 403 "));
    }

    @Test
    public void inProcessRepliesAreNotCappedLikeTheWire() throws Exception {
        String cookie = signIn();
        Resp r = call("POST", "/api/chat.conversation", cookie, null, "{}", null);
        assertEquals(200, r.code);
        assertEquals(ServiceRegistry.MAX_REPLY_BYTES + 1000, r.body.length());
    }

    @Test
    public void aRevokedLocalDeviceIsToldHowToComeBack() throws Exception {
        String cookie = signIn();
        reg.register("parlons.ping", req -> { throw new SecurityException("unpaired device"); });
        Resp r = call("POST", "/api/ping", cookie, null, "{}", null);
        assertEquals(403, r.code);
        assertTrue(r.body.contains(ParlonsCore.LOCAL_KEY_FILE));
    }

    @Test
    public void pushEventsReachTheBrowserAsServerSentEvents() throws Exception {
        String cookie = signIn();
        HttpURLConnection c = (HttpURLConnection) new URL(base() + "/events").openConnection();
        c.setRequestProperty("Cookie", ParlonsLocal.COOKIE + "=" + cookie);
        c.setReadTimeout(5000);
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getContentType().startsWith("text/event-stream"));
        InputStream in = c.getInputStream();
        // hello first
        String hello = readEvent(in);
        assertTrue(hello, hello.startsWith("event: hello"));
        // wait for the listener to register, then push
        long until = System.currentTimeMillis() + 2000;
        while (local.listeners() == 0 && System.currentTimeMillis() < until) Thread.sleep(20);
        JSONObject ev = new JSONObject();
        ev.put("type", "message");
        ev.put("eid", "e-1");
        ev.put("body", "hi");
        local.sink().accept(ev);
        String pushed = readEvent(in);
        assertTrue(pushed, pushed.contains("id: e-1"));
        assertTrue(pushed, pushed.contains("event: push"));
        assertTrue(pushed, pushed.contains("\"body\":\"hi\""));
        c.disconnect();
    }

    @Test
    public void callSignalsRequireAnActiveEventClientBoundToTheSameSession() throws Exception {
        String cookie = signIn(), other = signIn();
        reg.register("parlons.call.signal", req -> { seen.set(req); return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8); });
        String client = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String body = "{\"client\":\"" + client + "\",\"localClient\":\"forged\"}";
        assertEquals(403, call("POST", "/api/call.signal", cookie, null, body, null).code);
        HttpURLConnection c = (HttpURLConnection) new URL(base() + "/events?client=" + client).openConnection();
        c.setRequestProperty("Cookie", ParlonsLocal.COOKIE + "=" + cookie);
        c.setReadTimeout(5000); assertEquals(200, c.getResponseCode());
        assertTrue(readEvent(c.getInputStream()).contains(client)); assertTrue(local.callsLive());
        assertEquals(403, call("POST", "/api/call.signal", other, null, body, null).code);
        assertEquals(200, call("POST", "/api/call.signal", cookie, null, body, null).code);
        String forwarded = new String(seen.get().payload, StandardCharsets.UTF_8);
        assertTrue(forwarded.contains("\"localClient\":\"" + client + "\""));
        assertFalse(forwarded.contains("forged"));
        assertTrue(pairing.revoke(key, new com.eurobuddha.maxima.core.codec.MiniData(key).to0xString()));
        assertFalse(local.callsLive()); c.disconnect();
    }

    private static String readEvent(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b, nl = 0;
        while ((b = in.read()) >= 0) {
            sb.append((char) b);
            nl = b == '\n' ? nl + 1 : 0;
            if (nl == 2) break;
        }
        return sb.toString();
    }

    @Test
    public void theInviteEndpointJoinsAddressAndCode() throws Exception {
        String cookie = signIn();
        Resp none = call("GET", "/api/invite", cookie, null, null, null);
        assertEquals(200, none.code);
        assertTrue(none.body.contains("\"invite\":\"\""));
        String code = pairing.newBootstrapCode();
        Resp some = call("GET", "/api/invite", cookie, null, null, null);
        assertTrue(some.body, some.body.contains("MAX#0xAB#Mx1@h:1?code=" + code));
    }

    @Test
    public void thePanelPageIsServedFromTheJar() throws Exception {
        Resp r = call("GET", "/", null, null, null, null);
        assertEquals(200, r.code);
        assertTrue(r.body.contains("<title>Parlons</title>"));
        assertEquals(404, call("GET", "/../etc/passwd", null, null, null, null).code);
        assertEquals(404, call("GET", "/nothing.html", null, null, null, null).code);
    }

    @Test
    public void localDeviceRulesInDevicePairing() throws Exception {
        DevicePairing p = new DevicePairing(Files.createTempDirectory("parlons-pairing"));
        byte[] k = new byte[] {1, 2, 3, 4};
        String hex = new com.eurobuddha.maxima.core.codec.MiniData(k).to0xString();
        assertFalse("an unknown key that is not fresh was revoked: stays out", p.authorizeLocal(k, "pc", false));
        assertTrue(p.authorizeLocal(k, "pc", true));
        assertTrue(p.isLocal(hex));
        assertTrue("idempotent", p.authorizeLocal(k, "pc", false));
        assertTrue(p.revoke(k, hex));
        assertFalse(p.isAuthorized(k));
        assertFalse("revoked stays revoked until a fresh key is minted", p.authorizeLocal(k, "pc", false));
    }
}
