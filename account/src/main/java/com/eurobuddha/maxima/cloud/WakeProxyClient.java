package com.eurobuddha.maxima.cloud;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The account's side of the iOS wake path. When a device with an APNs record is NOT live (iOS
 * put it to sleep) and something worth waking for arrives, we POST a content-free "wake" to the
 * proxy the device chose. The proxy holds the publisher's APNs key and forwards a bare alert;
 * the phone then fetches the message end-to-end from this account. Nothing about the message -
 * not the sender, not a byte of body - leaves here.
 *
 * Bounded so a dead or hostile proxy costs nothing: one wake per device per 20 s, then quiet
 * until the device's next authorized RPC or 5 minutes; 5 s timeouts on a single thread; after
 * three failures a proxy is left alone for 5 minutes. "off" short-circuits everything.
 */
public final class WakeProxyClient {

    static final long COALESCE_MS = 20_000;
    static final long QUIET_MS = 5 * 60_000;
    static final long BACKOFF_MS = 5 * 60_000;
    static final int BACKOFF_AFTER = 3;

    private final HttpClient mHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ExecutorService mExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "parlons-wake");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> mLastWake = new ConcurrentHashMap<>();
    private final Map<String, Long> mQuietUntil = new ConcurrentHashMap<>();
    private final Map<String, Integer> mProxyFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> mProxyBackoffUntil = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<String> mLog = s -> { };
    /** Test seam: a URL rewrite (e.g. to a local fake proxy). */
    volatile java.util.function.UnaryOperator<String> mUrlRewrite = u -> u;

    public void setLog(java.util.function.Consumer<String> zLog) {
        mLog = zLog == null ? s -> { } : zLog;
    }

    /** The device made an authorized RPC: it is awake, the quiet period ends. */
    public void deviceSeen(String zDeviceKey) {
        mQuietUntil.remove(zDeviceKey);
    }

    /**
     * Ask the proxy to wake this device. Returns true when a request was queued (not whether
     * it succeeded - that is fire-and-forget by design).
     */
    public boolean wake(String zDeviceKey, String zProxy, String zToken, String zEnv, String zKind) {
        if (zProxy == null || zProxy.isEmpty() || "off".equalsIgnoreCase(zProxy)
                || zToken == null || zToken.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long quiet = mQuietUntil.get(zDeviceKey);
        if (quiet != null && now < quiet) {
            return false;
        }
        Long last = mLastWake.get(zDeviceKey);
        if (last != null && now - last < COALESCE_MS) {
            return false;
        }
        Long backoff = mProxyBackoffUntil.get(zProxy);
        if (backoff != null && now < backoff) {
            return false;
        }
        mLastWake.put(zDeviceKey, now);
        mQuietUntil.put(zDeviceKey, now + QUIET_MS);
        final String body = "{\"token\":\"" + esc(zToken) + "\",\"env\":\"" + esc(zEnv) + "\",\"kind\":\""
                + esc(zKind) + "\"}";
        mExec.execute(() -> post(zProxy, body));
        return true;
    }

    private void post(String zProxy, String zBody) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(mUrlRewrite.apply(zProxy)))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(zBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = mHttp.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                mProxyFailures.remove(zProxy);
            } else {
                failed(zProxy, "HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            failed(zProxy, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void failed(String zProxy, String zWhy) {
        int n = mProxyFailures.merge(zProxy, 1, Integer::sum);
        mLog.accept("wake proxy " + zProxy + " failed (" + n + "): " + zWhy);
        if (n >= BACKOFF_AFTER) {
            mProxyBackoffUntil.put(zProxy, System.currentTimeMillis() + BACKOFF_MS);
            mProxyFailures.remove(zProxy);
            mLog.accept("wake proxy " + zProxy + " left alone for " + (BACKOFF_MS / 60_000) + " min");
        }
    }

    /** Wait for queued posts (tests). */
    void drain() throws Exception {
        mExec.submit(() -> { }).get();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
