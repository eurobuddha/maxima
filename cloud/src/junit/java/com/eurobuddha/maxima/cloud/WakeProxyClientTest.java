package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

import org.junit.Test;

/** The account's wake path is bounded: coalesced, quiet after one wake, off means off, a dead proxy is left alone. */
public class WakeProxyClientTest {

    static final class FakeProxy implements AutoCloseable {
        final HttpServer server;
        final List<String> bodies = Collections.synchronizedList(new java.util.ArrayList<>());
        final AtomicInteger status = new AtomicInteger(200);
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        volatile HttpHandler response = ex -> { ex.sendResponseHeaders(status.get(), -1); ex.close(); };
        FakeProxy() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            server.createContext("/v1/wake", ex -> {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                response.handle(ex);
            });
            server.setExecutor(workers);
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/wake"; }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }

    @Test
    public void oneWakeThenQuietUntilTheDeviceIsSeenAgain() throws Exception {
        try (FakeProxy proxy = new FakeProxy(); WakeProxyClient c = new WakeProxyClient()) {
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
        try (WakeProxyClient c = new WakeProxyClient()) {
            assertFalse(c.wake("0xDEV", "off", "ab12", "prod", "message"));
            assertFalse(c.wake("0xDEV", "", "ab12", "prod", "message"));
            assertFalse(c.wake("0xDEV", "https://wake.example/v1/wake", "", "prod", "message"));
        }
    }

    @Test
    public void aFailingProxyIsLeftAloneAfterThreeFailures() throws Exception {
        try (FakeProxy proxy = new FakeProxy(); WakeProxyClient c = new WakeProxyClient()) {
            proxy.status.set(500);
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
    @Test public void aLargeDeclaredBodyCannotHoldUpAnotherProxy() throws Exception { ignoresBody(64L << 20); }
    @Test public void anUnfinishedChunkedBodyCannotHoldUpAnotherProxy() throws Exception { ignoresBody(0); }

    private void ignoresBody(long length) throws Exception {
        CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService observer = Executors.newSingleThreadExecutor();
        try (FakeProxy slow = new FakeProxy(); FakeProxy healthy = new FakeProxy(); WakeProxyClient c = new WakeProxyClient()) {
            slow.response = heldBody(202, length, headers, release);
            c.mUrlRewrite = u -> u.contains("slow.example") ? slow.url() : healthy.url();
            try {
                assertTrue(c.wake("0xSLOW", "https://slow.example/v1/wake", "ab12", "prod", "message"));
                assertTrue(headers.await(5, TimeUnit.SECONDS));
                assertTrue(c.wake("0xNEXT", "https://healthy.example/v1/wake", "cd34", "prod", "message"));
                drained(c, observer);
                assertEquals("another chosen proxy progresses before the first body finishes", 1, healthy.bodies.size());
                assertEquals(1, release.getCount());
            } finally { release.countDown(); }
        } finally { release.countDown(); observer.shutdownNow(); }
    }

    @Test public void errorStatusTriggersBackoffWithoutWaitingForItsBody() throws Exception {
        CountDownLatch headers = new CountDownLatch(3), release = new CountDownLatch(1);
        ExecutorService observer = Executors.newSingleThreadExecutor();
        try (FakeProxy proxy = new FakeProxy(); WakeProxyClient c = new WakeProxyClient()) {
            proxy.response = heldBody(500, 0, headers, release);
            c.mUrlRewrite = u -> proxy.url();
            List<String> log = Collections.synchronizedList(new java.util.ArrayList<>()); c.setLog(log::add);
            try {
                for (int i = 0; i < 3; i++) {
                    assertTrue(c.wake("0xDEV" + i, "https://dead.example/v1/wake", "ab12", "prod", "message"));
                    drained(c, observer);
                }
                assertTrue(headers.await(5, TimeUnit.SECONDS));
                assertFalse(c.wake("0xLAST", "https://dead.example/v1/wake", "ab12", "prod", "message"));
                assertTrue(log.stream().anyMatch(s -> s.contains("HTTP 500")));
            } finally { release.countDown(); }
        } finally { release.countDown(); observer.shutdownNow(); }
    }

    private static void drained(WakeProxyClient c, ExecutorService observer) throws Exception {
        observer.submit(() -> { c.drain(); return null; }).get(2, TimeUnit.SECONDS);
    }

    @Test public void aFullAccountQueueRefusesWithoutQuietingTheRejectedDevice() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (FakeProxy slow = new FakeProxy(); FakeProxy healthy = new FakeProxy(); WakeProxyClient c = new WakeProxyClient()) {
            slow.response = ex -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                ex.sendResponseHeaders(500, -1); ex.close();
            };
            c.mUrlRewrite = u -> u.contains("healthy") ? healthy.url() : slow.url();
            try {
                assertTrue(c.wake("first", "https://slow.example", "ab12", "prod", "message"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < WakeProxyClient.MAX_QUEUED; i++)
                    assertTrue(c.wake("queued" + i, "https://slow.example", "ab12", "prod", "message"));
                assertFalse("account queue is full", c.wake("retry", "https://healthy.example", "ab12", "prod", "message"));
                release.countDown();
                c.drain();
                assertTrue("rejected admission did not reserve a quiet period",
                        c.wake("retry", "https://healthy.example", "ab12", "prod", "message"));
                c.drain();
                assertEquals(1, healthy.bodies.size());
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test public void queuedRequestsRespectBackoffLearnedAfterAdmission() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (FakeProxy proxy = new FakeProxy(); WakeProxyClient c = new WakeProxyClient()) {
            proxy.response = ex -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                ex.sendResponseHeaders(500, -1); ex.close();
            };
            c.mUrlRewrite = u -> proxy.url();
            try {
                assertTrue(c.wake("first", "https://dead.example", "ab12", "prod", "message"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 10; i++)
                    assertTrue(c.wake("queued" + i, "https://dead.example", "ab12", "prod", "message"));
                release.countDown(); c.drain();
                assertEquals("queued requests stop after the third failure", 3, proxy.bodies.size());
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test public void rotatingFailedProxyUrlsCannotGrowStateForever() throws Exception {
        try (WakeProxyClient c = new WakeProxyClient()) {
            java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
            c.mNow = now::get;
            c.mUrlRewrite = u -> "invalid";
            for (int i = 0; i < 3; i++) {
                assertTrue(c.wake("backoff" + i, "https://backoff.example", "ab12", "prod", "message"));
                c.drain();
            }
            for (int i = 0; i < WakeProxyClient.MAX_PROXIES - 1; i++) {
                assertTrue(c.wake("device" + i, "https://proxy" + i + ".example", "ab12", "prod", "message"));
                c.drain();
            }
            assertFalse("retained proxy capacity", c.wake("overflow", "https://next.example", "ab12", "prod", "message"));
            now.addAndGet(WakeProxyClient.BACKOFF_MS - 1);
            assertFalse("capacity pressure must not discard active backoff",
                    c.wake("still-backed-off", "https://backoff.example", "ab12", "prod", "message"));
            assertFalse(c.wake("overflow", "https://next.example", "ab12", "prod", "message"));
            now.incrementAndGet();
            assertTrue("idle state expires and refused devices may retry",
                    c.wake("overflow", "https://next.example", "ab12", "prod", "message"));
            c.drain();
            assertEquals(1, stateMap(c, "mProxies").size());
        }
    }

    @Test public void fullDeviceStateExpiresWithoutEvictingAnActiveQuietPeriod() throws Exception {
        try (FakeProxy proxy = new FakeProxy(); WakeProxyClient c = new WakeProxyClient()) {
            java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
            c.mNow = now::get; c.mUrlRewrite = u -> proxy.url();
            // Seed a full still-active table without thousands of irrelevant HTTP requests.
            for (int i = 0; i < WakeProxyClient.MAX_DEVICES; i++) {
                stateMap(c, "mLastWake").put("device" + i, now.get());
                stateMap(c, "mQuietUntil").put("device" + i, now.get() + WakeProxyClient.QUIET_MS);
            }
            assertFalse(c.wake("next", "https://wake.example", "ab12", "prod", "message"));
            now.addAndGet(WakeProxyClient.QUIET_MS - 1);
            assertFalse(c.wake("next", "https://wake.example", "ab12", "prod", "message"));
            assertFalse(c.wake("device0", "https://wake.example", "ab12", "prod", "message"));
            now.incrementAndGet();
            assertTrue(c.wake("next", "https://wake.example", "ab12", "prod", "message"));
            c.drain();
            assertEquals(1, stateMap(c, "mLastWake").size());
            assertEquals(1, stateMap(c, "mQuietUntil").size());
            assertEquals(1, proxy.bodies.size());
        }
    }

    @Test public void pendingWorkPinsProxyStateAndCloseCannotRepopulateIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (WakeProxyClient c = new WakeProxyClient()) {
            java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
            c.mNow = now::get;
            c.mUrlRewrite = u -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "invalid";
            };
            try {
                assertTrue(c.wake("first", "https://pending.example", "ab12", "prod", "message"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                now.addAndGet(WakeProxyClient.BACKOFF_MS * 2);
                assertTrue(c.wake("second", "https://other.example", "ab12", "prod", "message"));
                assertEquals("pending endpoint survives the expiry sweep", 2, stateMap(c, "mProxies").size());
                c.close(); release.countDown();
                java.lang.reflect.Field f = WakeProxyClient.class.getDeclaredField("mExec"); f.setAccessible(true);
                assertTrue(((ExecutorService) f.get(c)).awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(stateMap(c, "mProxies").isEmpty());
                assertTrue(stateMap(c, "mLastWake").isEmpty());
                assertTrue(stateMap(c, "mQuietUntil").isEmpty());
                assertFalse(c.wake("closed", "https://next.example", "ab12", "prod", "message"));
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> stateMap(WakeProxyClient c, String name) throws Exception {
        java.lang.reflect.Field f = WakeProxyClient.class.getDeclaredField(name); f.setAccessible(true);
        return (java.util.Map<String, Object>) f.get(c);
    }

    private static HttpHandler heldBody(int status, long length, CountDownLatch headers, CountDownLatch release) {
        return ex -> {
            try {
                ex.sendResponseHeaders(status, length); headers.countDown();
                ex.getResponseBody().write('x'); ex.getResponseBody().flush();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } finally { ex.close(); }
        };
    }

}
