package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** The account's wake path is bounded: coalesced, quiet after one wake, off means off, a dead proxy is left alone. */
public class WakeProxyClientTest {

    static final class FakeProxy implements AutoCloseable {
        final HttpServer server;
        final List<String> bodies = Collections.synchronizedList(new java.util.ArrayList<>());
        final AtomicInteger status = new AtomicInteger(200);
        FakeProxy() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            server.createContext("/v1/wake", ex -> {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.sendResponseHeaders(status.get(), -1);
                ex.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/wake"; }
        public void close() { server.stop(0); }
    }

    @Test
    public void oneWakeThenQuietUntilTheDeviceIsSeenAgain() throws Exception {
        try (FakeProxy proxy = new FakeProxy()) {
            WakeProxyClient c = new WakeProxyClient();
            c.mUrlRewrite = u -> proxy.url();
            assertTrue(c.wake("0xDEV", "https://wake.example/v1/wake", "ab12", "prod", "message"));
            assertFalse("coalesced", c.wake("0xDEV", "https://wake.example/v1/wake", "ab12", "prod", "message"));
            c.drain();
            assertEquals(1, proxy.bodies.size());
            assertTrue(proxy.bodies.get(0).contains("\"token\":\"ab12\""));
            assertTrue(proxy.bodies.get(0).contains("\"kind\":\"message\""));
            assertFalse("no content ever travels", proxy.bodies.get(0).contains("body"));
            // the device woke and made an RPC: it may be woken again (after the coalesce window)
            c.deviceSeen("0xDEV");
            assertFalse("still inside the 20 s coalesce window", c.wake("0xDEV", "https://wake.example/v1/wake", "ab12", "prod", "call"));
        }
    }

    @Test
    public void offAndEmptyNeverWake() throws Exception {
        WakeProxyClient c = new WakeProxyClient();
        assertFalse(c.wake("0xDEV", "off", "ab12", "prod", "message"));
        assertFalse(c.wake("0xDEV", "", "ab12", "prod", "message"));
        assertFalse(c.wake("0xDEV", "https://wake.example/v1/wake", "", "prod", "message"));
    }

    @Test
    public void aFailingProxyIsLeftAloneAfterThreeFailures() throws Exception {
        try (FakeProxy proxy = new FakeProxy()) {
            proxy.status.set(500);
            WakeProxyClient c = new WakeProxyClient();
            c.mUrlRewrite = u -> proxy.url();
            List<String> log = Collections.synchronizedList(new java.util.ArrayList<>());
            c.setLog(log::add);
            for (int i = 0; i < 3; i++) {
                assertTrue(c.wake("0xDEV" + i, "https://dead.example/v1/wake", "ab12", "prod", "message"));
                c.drain();
            }
            assertEquals(3, proxy.bodies.size());
            assertFalse("backoff: not even queued", c.wake("0xDEV9", "https://dead.example/v1/wake", "ab12", "prod", "message"));
            assertTrue(log.stream().anyMatch(s -> s.contains("left alone")));
        }
    }
}
