package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.rpc.Capabilities;
import com.eurobuddha.maxima.core.rpc.RpcEnvelope;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/**
 * The reply-as-message RPC layer - the mechanism that lets a NAT'd phone answer
 * a request by dialling out. Round-trip the envelope, dispatch through the
 * registry, and check the capability advertisement classic peers ignore.
 */
public class RpcUnitTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== RPC ===\n");

        // ---- envelope round trips ----
        RpcEnvelope req = RpcEnvelope.request("id-1", "ping",
                Collections.singletonList("Mx...@1.2.3.4:9501"), "hi".getBytes());
        RpcEnvelope reqBack = RpcEnvelope.fromBytes(req.toBytes());
        if (reqBack.isRequest() && reqBack.getId().equals("id-1")
                && reqBack.getMethod().equals("ping")
                && reqBack.getPayloadAsString().equals("hi")
                && reqBack.getReplyTo().size() == 1) {
            ok("a request envelope round-trips (id, method, replyTo, payload)");
        } else {
            bad("request round trip");
        }

        RpcEnvelope resp = RpcEnvelope.response("id-1", "pong".getBytes());
        RpcEnvelope respBack = RpcEnvelope.fromBytes(resp.toBytes());
        if (respBack.isResponse() && respBack.getId().equals("id-1")
                && respBack.getPayloadAsString().equals("pong")) {
            ok("a response envelope round-trips, correlated by id");
        } else {
            bad("response round trip");
        }

        RpcEnvelope err = RpcEnvelope.error("id-1", "no such method");
        RpcEnvelope errBack = RpcEnvelope.fromBytes(err.toBytes());
        if (errBack.isError() && errBack.getId().equals("id-1")
                && errBack.getPayloadAsString().equals("no such method")) {
            ok("an error envelope round-trips");
        } else {
            bad("error round trip");
        }

        // ---- ServiceRegistry dispatch ----
        ServiceRegistry reg = new ServiceRegistry();
        reg.register("echo", r -> r.payload);
        reg.register("upper", r -> r.payloadAsString().toUpperCase().getBytes());
        if (reg.has("echo") && reg.methods().contains("upper")) {
            ok("registered methods are advertised");
        } else {
            bad("registry advertise");
        }
        RpcEnvelope ok1 = reg.dispatch("c1",
                new ServiceRegistry.Request("echo", "abc".getBytes(), new byte[]{1}, null));
        if (ok1.isResponse() && ok1.getId().equals("c1") && ok1.getPayloadAsString().equals("abc")) {
            ok("dispatch routes to the handler and returns a correlated response");
        } else {
            bad("dispatch response: " + ok1.getPayloadAsString());
        }
        RpcEnvelope ok2 = reg.dispatch("c2",
                new ServiceRegistry.Request("upper", "abc".getBytes(), new byte[]{1}, null));
        if (ok2.getPayloadAsString().equals("ABC")) {
            ok("a second handler runs independently");
        } else {
            bad("second handler");
        }
        // unknown method -> error, not crash
        RpcEnvelope unk = reg.dispatch("c3",
                new ServiceRegistry.Request("nope", new byte[0], new byte[]{1}, null));
        if (unk.isError()) {
            ok("an unknown method returns an error envelope, not a crash");
        } else {
            bad("unknown method not an error");
        }
        // a handler that throws -> error, not a leaked exception
        reg.register("boom", r -> { throw new RuntimeException("kaboom"); });
        RpcEnvelope boom = reg.dispatch("c4",
                new ServiceRegistry.Request("boom", new byte[0], new byte[]{1}, null));
        if (boom.isError()) {
            ok("a throwing handler is contained as an error envelope");
        } else {
            bad("throwing handler leaked");
        }
        // unregister
        reg.unregister("echo");
        if (!reg.has("echo")) {
            ok("unregister removes a method");
        } else {
            bad("unregister");
        }

        // ---- Capabilities ----
        Capabilities caps = new Capabilities(Capabilities.RPC, Capabilities.MAILBOX);
        Capabilities back = Capabilities.decode(caps.encode());
        if (back.has(Capabilities.RPC) && back.has(Capabilities.MAILBOX)
                && !back.has(Capabilities.WITNESS)) {
            ok("capabilities encode/decode round-trips");
        } else {
            bad("caps round trip: " + back);
        }
        if (Capabilities.none().isClassic() && !Capabilities.phoneDefaults().isClassic()) {
            ok("empty caps read as a classic peer; phone defaults do not");
        } else {
            bad("isClassic");
        }
        if (Capabilities.phoneDefaults().has(Capabilities.MAILBOX)) {
            ok("phone defaults advertise the mailbox capability");
        } else {
            bad("phone defaults missing mailbox");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("RPC holds.");
    }
}
