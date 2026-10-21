package com.eurobuddha.maxima.dexrelay;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The HTTP surface (SPEC §4) on an ephemeral port with an injected clock. */
public class HttpTest {

    static final long NOW = 1800000000L - 3600;
    DexRelay relay;
    String base;
    final TestSigner signer = new TestSigner();

    @Before public void up() throws Exception {
        relay = new DexRelay(() -> NOW, 1000, 1000);
        relay.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + relay.port();
    }

    @After public void down() { relay.stop(); }

    @Test public void healthPublishBookAndCursor() throws Exception {
        Resp h = get("/dex/health");
        assertEquals(200, h.code);
        assertTrue(h.body, h.body.contains("\"ok\":true") && h.body.contains("\"version\":\"" + DexRelay.VERSION + "\""));

        Resp p = post("/dex/publish", JsonTest.VECTOR_WITH_SIG);
        assertEquals(200, p.code);
        assertTrue(p.body, p.body.contains("\"ok\":true"));
        assertEquals("*", p.headers);

        Resp b = get("/dex/book?market=MINIMA/MxUSD&since=0&hold=1");
        assertEquals(200, b.code);
        assertTrue(b.body, b.body.contains("\"orderId\":\"0x0102030405060708\"") && b.body.contains("\"cursor\":1") && b.body.contains("\"reset\":true"));

        Resp dup = post("/dex/publish", JsonTest.VECTOR_WITH_SIG);
        assertEquals(409, dup.code);                       // equal seq: stale
        Resp forged = post("/dex/publish", JsonTest.VECTOR_WITH_SIG.replace("0xa5ee8230", "0xa5ee8231"));
        assertEquals(400, forged.code);
        assertTrue(forged.body, forged.body.contains("bad signature"));
    }

    @Test public void longPollWakesOnPublish() throws Exception {
        AtomicLong ms = new AtomicLong(-1);
        Thread t = new Thread(() -> {
            try { long t0 = System.nanoTime(); Resp r = get("/dex/book?market=MINIMA/MxUSD&since=0&hold=5"); ms.set((System.nanoTime() - t0) / 1_000_000); assertEquals(200, r.code); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        t.start();
        Thread.sleep(300);
        assertEquals(200, post("/dex/publish", JsonTest.VECTOR_WITH_SIG).code);
        t.join(3000);
        assertTrue("woke after " + ms.get() + "ms", ms.get() >= 250 && ms.get() < 1500);
    }

    @Test public void inboxRoundTrip() throws Exception {
        String to = "0x" + "22".repeat(64);
        Envelope m = signer.msg(to, "aabbcc", NOW);
        assertEquals(200, post("/dex/msg", m.raw).code);
        Resp in = get("/dex/inbox?pk=" + to + "&since=0&hold=1");
        assertEquals(200, in.code);
        assertTrue(in.body, in.body.contains("\"blob\":\"aabbcc\""));
        Resp other = get("/dex/inbox?pk=0x" + "33".repeat(64) + "&since=0&hold=1");
        assertFalse(other.body, other.body.contains("aabbcc"));
    }

    @Test public void rateLimitAndBadRoutes() throws Exception {
        relay.stop();
        relay = new DexRelay(() -> NOW, 1000, 3);          // 3 req/s per IP
        relay.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + relay.port();
        int limited = 0;
        for (int i = 0; i < 12; i++) if (get("/dex/health").code == 429) limited++;
        assertTrue("expected some 429s, got " + limited, limited >= 4);
        relay.stop(); up();
        assertEquals(404, get("/dex/nothing").code);
        assertEquals(405, get("/dex/publish").code);
        assertEquals(400, post("/dex/publish", "not json").code);
        assertEquals(200, options("/dex/publish").code);
    }

    // --- tiny client ---
    static final class Resp { int code; String body; String headers; }

    Resp get(String path) throws Exception { return req("GET", path, null); }
    Resp post(String path, String body) throws Exception { return req("POST", path, body); }
    Resp options(String path) throws Exception { return req("OPTIONS", path, null); }

    Resp req(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(3000); c.setReadTimeout(30000);
        if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.headers = c.getHeaderField("Access-Control-Allow-Origin");
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); }
        r.body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }
}
