package com.eurobuddha.maxima.wake;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic provider key and loopback gateways only; never Apple's live service. */
public class ApnsClientTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final class Gateway implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final AtomicInteger calls = new AtomicInteger();
        volatile HttpHandler response = ex -> { ex.sendResponseHeaders(200, -1); ex.close(); };
        Gateway() throws Exception {
            server.createContext("/3/device/", ex -> {
                ex.getRequestBody().readAllBytes(); calls.incrementAndGet(); response.handle(ex);
            });
            server.setExecutor(workers); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }

    private static ApnsClient client(Gateway prod, Gateway sandbox) throws Exception {
        return new ApnsClient(new ApnsJwt(WakeProxyTest.freshP8(), "TESTKEY", "TESTTEAM"),
                "test.parlons", prod.url(), sandbox.url());
    }
    private static HttpHandler reply(int status, String body) {
        return ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes); ex.close();
        };
    }

    @Test public void badTokenRetriesOnlyOnceInEitherDirection() throws Exception {
        try (Gateway prod = new Gateway(); Gateway sandbox = new Gateway()) {
            prod.response = reply(400, "{\"reason\":\"BadDeviceToken\"}");
            ApnsClient c = client(prod, sandbox);
            ApnsClient.Result r = c.wake(TOKEN, "prod", "message");
            assertTrue(r.ok()); assertEquals("sandbox(retried)", r.env);
            prod.response = reply(200, "");
            sandbox.response = reply(400, "{\"reason\":\"BadDeviceToken\"}");
            r = c.wake(TOKEN, "sandbox", "call");
            assertTrue(r.ok()); assertEquals("prod(retried)", r.env);
            prod.response = reply(400, "{\"reason\":\"BadDeviceToken\"}");
            r = c.wake(TOKEN, "prod", "message");
            assertEquals(400, r.status); assertEquals("BadDeviceToken", r.reason);
            assertEquals(3, prod.calls.get()); assertEquals(3, sandbox.calls.get());
        }
    }

    @Test public void ordinaryErrorAndAnExactLimitBodyRemainUsableWithoutRetry() throws Exception {
        try (Gateway prod = new Gateway(); Gateway sandbox = new Gateway()) {
            String json = "{\"reason\":\"Unregistered\"}";
            prod.response = reply(410, json + " ".repeat(4096 - json.length()));
            ApnsClient.Result r = client(prod, sandbox).wake(TOKEN, "prod", "message");
            assertEquals(410, r.status); assertEquals("Unregistered", r.reason);
            assertEquals(0, sandbox.calls.get());
        }
    }

    @Test public void anOversizedDeclaredErrorFailsBeforeItsBodyFinishes() throws Exception { heldError(64L << 20, 1, 2); }
    @Test public void anOversizedChunkedErrorFailsBeforeItsBodyFinishes() throws Exception { heldError(0, 4097, 2); }
    @Test public void anUnfinishedSmallErrorCannotOutliveTheRequestDeadline() throws Exception { heldError(0, 1, 12); }

    @Test public void successDoesNotWaitForAnUnusedBody() throws Exception { heldSuccessOrInterrupt(false); }
    @Test public void interruptionCancelsAnUnfinishedResponse() throws Exception { heldSuccessOrInterrupt(true); }

    private void heldSuccessOrInterrupt(boolean interrupt) throws Exception {
        CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Gateway prod = new Gateway(); Gateway sandbox = new Gateway()) {
            prod.response = ex -> {
                try {
                    ex.sendResponseHeaders(interrupt ? 400 : 200, 0); headers.countDown();
                    ex.getResponseBody().write('x'); ex.getResponseBody().flush();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } finally { ex.close(); }
            };
            ApnsClient c = client(prod, sandbox);
            CompletableFuture<Object> result = new CompletableFuture<>();
            Thread caller = new Thread(() -> {
                try { result.complete(c.wake(TOKEN, "prod", "message")); }
                catch (Throwable e) { result.complete(e); }
            }, "apns-test-caller");
            caller.start();
            try {
                assertTrue(headers.await(5, TimeUnit.SECONDS));
                if (interrupt) caller.interrupt();
                Object outcome = result.get(2, TimeUnit.SECONDS);
                if (interrupt) assertTrue(outcome.toString(), outcome instanceof InterruptedException);
                else assertTrue(((ApnsClient.Result) outcome).ok());
                assertEquals(1, release.getCount());
                assertEquals(0, sandbox.calls.get());
            } finally {
                release.countDown(); caller.interrupt(); caller.join(5000);
                assertFalse("caller released", caller.isAlive());
            }
        } finally { release.countDown(); }
    }

    @Test public void byteLimitCountsEveryChunkAndCancelsWithoutAcceptingAPartialBody() throws Exception {
        ApnsClient.ResponseBody body = new ApnsClient.ResponseBody();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(java.util.Collections.singletonList(java.nio.ByteBuffer.wrap(new byte[2048])));
        body.onNext(java.util.Collections.singletonList(java.nio.ByteBuffer.wrap(new byte[2048])));
        assertFalse(body.getBody().toCompletableFuture().isDone());
        body.onNext(java.util.Collections.singletonList(java.nio.ByteBuffer.wrap(new byte[1])));
        assertTrue(cancelled.get());
        body.onComplete(); // a late terminal callback cannot turn the rejected prefix into success
        try { body.getBody().toCompletableFuture().get(); fail("oversized body accepted"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof java.io.IOException); }
    }

    @Test public void cancellationBeforeSubscriptionAlsoCancelsTheLateSubscription() throws Exception {
        ApnsClient.ResponseBody body = new ApnsClient.ResponseBody();
        body.abort(new java.io.IOException("synthetic timeout before headers"));
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) { fail("a cancelled response must not request bytes"); }
            public void cancel() { cancelled.set(true); }
        });
        assertTrue(cancelled.get());
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    private void heldError(long length, int sent, int waitSeconds) throws Exception {
        CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService observer = Executors.newSingleThreadExecutor();
        try (Gateway prod = new Gateway(); Gateway sandbox = new Gateway()) {
            prod.response = ex -> {
                try {
                    ex.sendResponseHeaders(400, length); headers.countDown();
                    ex.getResponseBody().write(new byte[sent]); ex.getResponseBody().flush();
                    try { release.await(20, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } finally { ex.close(); }
            };
            ApnsClient c = client(prod, sandbox);
            Future<ApnsClient.Result> result = observer.submit(() -> c.wake(TOKEN, "prod", "message"));
            try {
                assertTrue(headers.await(5, TimeUnit.SECONDS));
                try { result.get(waitSeconds, TimeUnit.SECONDS); fail("oversized or unfinished body must fail"); }
                catch (ExecutionException expected) {
                    assertTrue("client reports an I/O failure: " + expected.getCause(), expected.getCause() instanceof java.io.IOException);
                }
                assertEquals("body still held by the gateway", 1, release.getCount());
                assertEquals("failed reads must not trigger environment fallback", 0, sandbox.calls.get());
                assertTrue("another gateway still works", c.wake(TOKEN, "sandbox", "message").ok());
            } finally { release.countDown(); result.cancel(true); }
        } finally { release.countDown(); observer.shutdownNow(); }
    }
}
