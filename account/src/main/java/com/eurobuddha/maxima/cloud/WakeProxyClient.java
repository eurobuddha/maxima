package com.eurobuddha.maxima.cloud;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The account's side of the iOS wake path. When a device with an APNs record is NOT live (iOS
 * put it to sleep) and something worth waking for arrives, we POST a content-free "wake" to the
 * proxy the device chose. The proxy holds the publisher's APNs key and forwards a bare alert;
 * the phone then fetches the message end-to-end from this account. Nothing about the message -
 * not the sender, not a byte of body - leaves here.
 *
 * One wake per device per 20 s, then quiet until its next authorized RPC or 45 s.
 * Requests use 5 s timeouts on a single worker; response bodies are closed without buffering.
 * After three failures a proxy is left alone for 5 minutes. "off" short-circuits everything.
 * Queue and retained-key capacity are bounded; refusal never reserves a device's quiet period.
 */
public final class WakeProxyClient implements AutoCloseable {

    static final long COALESCE_MS = 20_000;
    /** After a wake, no second wake until the device shows up (an authorized RPC) or this
     *  passes. 45 s, not minutes: a wake is one tiny push, and a phone whose fetch failed must
     *  not lose the next five minutes of messages. */
    static final long QUIET_MS = 45_000;
    static final long BACKOFF_MS = 5 * 60_000;
    static final int BACKOFF_AFTER = 3;
    static final int MAX_QUEUED = 256;
    static final int MAX_DEVICES = 4096;
    static final int MAX_PROXIES = 256;

    private final HttpClient mHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    // WakeHandler's bounded admission, retaining this client's single HTTP worker.
    private final java.util.concurrent.ThreadPoolExecutor mExec = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(MAX_QUEUED), r -> {
        Thread t = new Thread(r, "parlons-wake");
        t.setDaemon(true);
        return t;
    });
    // Guarded by this. Never evict an active quiet/backoff window to admit another key.
    private final Map<String, Long> mLastWake = new java.util.HashMap<>();
    private final Map<String, Long> mQuietUntil = new java.util.HashMap<>();
    private final Map<String, ProxyState> mProxies = new java.util.HashMap<>();
    // RateLimit's per-key bucket pattern, with pending work pinning its state through cleanup.
    private static final class ProxyState {
        int pending;
        int failures;
        long lastActivity;
        long backoffUntil;
    }
    private volatile java.util.function.Consumer<String> mLog = s -> { };
    private volatile boolean mClosed;
    /** Test seam: a URL rewrite (e.g. to a local fake proxy). */
    volatile java.util.function.UnaryOperator<String> mUrlRewrite = u -> u;
    /** Test clock for expiry; production uses the same wall clock as before. */
    volatile java.util.function.LongSupplier mNow = System::currentTimeMillis;

    public void setLog(java.util.function.Consumer<String> zLog) {
        mLog = zLog == null ? s -> { } : zLog;
    }

    /** The device made an authorized RPC: it is awake, the quiet period ends. */
    public synchronized void deviceSeen(String zDeviceKey) {
        mQuietUntil.remove(zDeviceKey);
    }

    /**
     * Ask the proxy to wake this device. Returns true when a request was queued (not whether
     * it succeeded - that is fire-and-forget by design).
     */
    public synchronized boolean wake(String zDeviceKey, String zProxy, String zToken, String zEnv, String zKind) {
        if (mClosed || zProxy == null || zProxy.isEmpty() || "off".equalsIgnoreCase(zProxy)
                || zToken == null || zToken.isEmpty()) {
            return false;
        }
        long now = mNow.getAsLong();
        prune(now);
        Long quiet = mQuietUntil.get(zDeviceKey);
        if (quiet != null && now < quiet) {
            return false;
        }
        Long last = mLastWake.get(zDeviceKey);
        if (last != null && now - last < COALESCE_MS) {
            return false;
        }
        ProxyState known = mProxies.get(zProxy);
        if (known != null && now < known.backoffUntil) {
            return false;
        }
        if ((!mLastWake.containsKey(zDeviceKey) && mLastWake.size() >= MAX_DEVICES)
                || (known == null && mProxies.size() >= MAX_PROXIES)) return false;
        final String body = "{\"token\":\"" + esc(zToken) + "\",\"env\":\"" + esc(zEnv) + "\",\"kind\":\""
                + esc(zKind) + "\"}";
        final ProxyState state = known == null ? new ProxyState() : known;
        mProxies.put(zProxy, state);
        state.pending++;
        try {
            mExec.execute(() -> post(zProxy, body, state));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            state.pending--;
            if (known == null) mProxies.remove(zProxy);
            return false;
        }
        state.lastActivity = now;
        mLastWake.put(zDeviceKey, now);
        mQuietUntil.put(zDeviceKey, now + QUIET_MS);
        return true;
    }

    private void post(String zProxy, String zBody, ProxyState state) {
        try {
            synchronized (this) {
                // A queued burst must respect failures learned after it was admitted.
                if (mClosed || mNow.getAsLong() < state.backoffUntil) return;
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(mUrlRewrite.apply(zProxy)))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(zBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> resp = mHttp.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status;
            // Only the status is part of this contract. Close the stream immediately:
            // an oversized or unfinished body must not accumulate in memory or hold the wake worker.
            try (InputStream body = resp.body()) {
                status = resp.statusCode();
            }
            if (status / 100 == 2) {
                synchronized (this) {
                    if (!mClosed) state.failures = 0;
                }
            } else {
                failed(zProxy, state, "HTTP " + status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failed(zProxy, state, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            synchronized (this) {
                state.pending--;
                state.lastActivity = mNow.getAsLong();
            }
        }
    }

    @Override public synchronized void close() {
        mClosed = true;
        mExec.shutdownNow();
        mLastWake.clear();
        mQuietUntil.clear();
        mProxies.clear();
    }

    private void failed(String zProxy, ProxyState state, String zWhy) {
        int n;
        synchronized (this) {
            if (mClosed) return;
            n = ++state.failures;
            if (n >= BACKOFF_AFTER) {
                state.backoffUntil = mNow.getAsLong() + BACKOFF_MS;
                state.failures = 0;
            }
        }
        mLog.accept("wake proxy " + zProxy + " failed (" + n + "): " + zWhy);
        if (n >= BACKOFF_AFTER) {
            mLog.accept("wake proxy " + zProxy + " left alone for " + (BACKOFF_MS / 60_000) + " min");
        }
    }

    /** Expired entries only: overload must not erase another device's quiet or proxy's backoff. */
    private void prune(long now) {
        mQuietUntil.entrySet().removeIf(e -> now >= e.getValue());
        mLastWake.entrySet().removeIf(e -> now - e.getValue() >= COALESCE_MS
                && !mQuietUntil.containsKey(e.getKey()));
        mProxies.entrySet().removeIf(e -> e.getValue().pending == 0
                && now >= e.getValue().backoffUntil
                && now - e.getValue().lastActivity >= BACKOFF_MS);
    }

    /** Wait for queued posts (tests). */
    void drain() throws Exception {
        // A barrier task cannot be submitted while the bounded queue is full.
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try {
                mExec.submit(() -> { }).get(30, java.util.concurrent.TimeUnit.SECONDS);
                return;
            } catch (java.util.concurrent.RejectedExecutionException full) {
                if (mExec.isShutdown() || System.nanoTime() >= until) throw full;
                Thread.sleep(5);
            }
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
