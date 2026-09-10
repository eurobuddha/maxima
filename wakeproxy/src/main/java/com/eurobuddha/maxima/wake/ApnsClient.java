package com.eurobuddha.maxima.wake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One HTTP/2 client to Apple's APNs (production and sandbox). Sends a CONTENT-FREE alert with
 * mutable-content so the app's Notification Service Extension can fetch the real message from
 * the user's own account and rewrite the banner. On BadDeviceToken from production the same
 * push is tried once on sandbox (a development build of the app).
 */
public final class ApnsClient {

    public static final String PROD = "https://api.push.apple.com";
    public static final String SANDBOX = "https://api.sandbox.push.apple.com";
    static final int MAX_RESPONSE_BYTES = 4096;
    static final long RESPONSE_TIMEOUT_MS = 10_000;

    /** What every wake carries - the same bytes for everyone, nothing about the message. */
    static String payload(String zKind) {
        boolean call = "call".equals(zKind);
        return "{\"aps\":{\"alert\":{\"title\":\"Parlons\",\"body\":\"" + (call ? "Incoming call" : "New message")
                + "\"},\"mutable-content\":1,\"sound\":\"pssst.caf\",\"thread-id\":\"parlons\","
                + "\"interruption-level\":\"" + (call ? "time-sensitive" : "active") + "\"},\"wake\":1}";
    }

    public static final class Result {
        public final int status;
        public final String reason;
        /** Which gateway gave the final answer: prod, sandbox, or one of them "(retried)". */
        public String env = "";
        Result(int s, String r) { status = s; reason = r; }
        public boolean ok() { return status == 200; }
    }

    private final HttpClient mHttp;
    private final ApnsJwt mJwt;
    private final String mBundle;
    private final String mProdBase;
    private final String mSandboxBase;

    public ApnsClient(ApnsJwt zJwt, String zBundle) {
        this(zJwt, zBundle, PROD, SANDBOX);
    }

    ApnsClient(ApnsJwt zJwt, String zBundle, String zProdBase, String zSandboxBase) {
        mJwt = zJwt;
        mBundle = zBundle;
        mProdBase = zProdBase;
        mSandboxBase = zSandboxBase;
        mHttp = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Result wake(String zToken, String zEnv, String zKind) throws Exception {
        boolean sandboxFirst = "sandbox".equalsIgnoreCase(zEnv);
        Result r = post(sandboxFirst ? mSandboxBase : mProdBase, zToken, zKind);
        r.env = sandboxFirst ? "sandbox" : "prod";
        if (!r.ok() && "BadDeviceToken".equals(r.reason)) {
            r = post(sandboxFirst ? mProdBase : mSandboxBase, zToken, zKind);
            r.env = (sandboxFirst ? "prod" : "sandbox") + "(retried)";
        }
        return r;
    }

    private Result post(String zBase, String zToken, String zKind) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("APNs request interrupted");
        HttpRequest req = HttpRequest.newBuilder(URI.create(zBase + "/3/device/" + zToken))
                .timeout(Duration.ofMillis(RESPONSE_TIMEOUT_MS))
                .header("authorization", "bearer " + mJwt.token())
                .header("apns-topic", mBundle)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("apns-expiration", Long.toString(System.currentTimeMillis() / 1000 + 3600))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(zKind), StandardCharsets.UTF_8))
                .build();
        ResponseBody body = new ResponseBody();
        CompletableFuture<HttpResponse<byte[]>> pending = mHttp.sendAsync(req, info -> {
            if (info.statusCode() == 200) {
                body.ignore(); // success needs only the status, like the account's wake client
            } else if (info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_RESPONSE_BYTES) {
                body.abort(new IOException("APNs response body exceeds " + MAX_RESPONSE_BYTES + " bytes"));
            }
            return body;
        });
        HttpResponse<byte[]> resp;
        try {
            // HttpRequest.timeout alone did not bound an unfinished body. Wait for the
            // complete bounded response, retaining one existing WakeHandler worker per call.
            resp = pending.get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            HttpTimeoutException timeout = new HttpTimeoutException("APNs response timed out");
            body.abort(timeout);
            throw timeout;
        } catch (InterruptedException e) {
            body.abort(e);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("APNs response failed", cause);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
        String reason = "";
        if (resp.statusCode() != 200) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(new String(resp.body(), StandardCharsets.UTF_8));
            reason = m.find() ? m.group(1) : ("HTTP " + resp.statusCode());
        }
        return new Result(resp.statusCode(), reason);
    }

    /** A small error body only; cancellation also works before a late subscription arrives. */
    static final class ResponseBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> mBody = new CompletableFuture<>();
        private final ByteArrayOutputStream mBytes = new ByteArrayOutputStream();
        private Flow.Subscription mSubscription;

        @Override public CompletionStage<byte[]> getBody() { return mBody; }

        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (mSubscription != null || mBody.isDone()) {
                subscription.cancel();
                return;
            }
            mSubscription = subscription;
            subscription.request(1);
        }

        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (mBody.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - mBytes.size()) {
                    abort(new IOException("APNs response body exceeds " + MAX_RESPONSE_BYTES + " bytes"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                mBytes.write(bytes, 0, bytes.length);
            }
            mSubscription.request(1);
        }

        @Override public synchronized void onComplete() {
            if (!mBody.isDone()) mBody.complete(mBytes.toByteArray());
        }

        @Override public synchronized void onError(Throwable failure) { abort(failure); }

        synchronized void abort(Throwable failure) {
            if (mBody.isDone()) return;
            mBody.completeExceptionally(failure);
            if (mSubscription != null) mSubscription.cancel();
        }

        synchronized void ignore() {
            mBody.complete(new byte[0]);
            if (mSubscription != null) mSubscription.cancel();
        }
    }
}
