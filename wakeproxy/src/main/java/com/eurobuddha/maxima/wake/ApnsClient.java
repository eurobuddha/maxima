package com.eurobuddha.maxima.wake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One HTTP/2 client to Apple's APNs (production and sandbox). Sends a CONTENT-FREE alert with
 * mutable-content so the app's Notification Service Extension can fetch the real message from
 * the user's own account and rewrite the banner. On BadDeviceToken from production the same
 * push is tried once on sandbox (a development build of the app).
 */
public final class ApnsClient {

    public static final String PROD = "https://api.push.apple.com";
    public static final String SANDBOX = "https://api.sandbox.push.apple.com";

    /** What every wake carries - the same bytes for everyone, nothing about the message. */
    static String payload(String zKind) {
        boolean call = "call".equals(zKind);
        return "{\"aps\":{\"alert\":{\"title\":\"Parlons\",\"body\":\"" + (call ? "Incoming call" : "New message")
                + "\"},\"mutable-content\":1,\"sound\":\"default\",\"thread-id\":\"parlons\","
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
        HttpRequest req = HttpRequest.newBuilder(URI.create(zBase + "/3/device/" + zToken))
                .timeout(Duration.ofSeconds(10))
                .header("authorization", "bearer " + mJwt.token())
                .header("apns-topic", mBundle)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("apns-expiration", Long.toString(System.currentTimeMillis() / 1000 + 3600))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(zKind), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = mHttp.send(req, HttpResponse.BodyHandlers.ofString());
        String reason = "";
        if (resp.statusCode() != 200) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(resp.body() == null ? "" : resp.body());
            reason = m.find() ? m.group(1) : ("HTTP " + resp.statusCode());
        }
        return new Result(resp.statusCode(), reason);
    }
}
