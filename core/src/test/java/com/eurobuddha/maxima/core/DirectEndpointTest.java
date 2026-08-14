package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.DirectEndpoint;
import com.eurobuddha.maxima.core.net.HostConnection;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Tier 2 direct path, in-process over loopback: a sender connects straight
 * to a receiver's DirectEndpoint - no relay - and the message arrives,
 * decrypted and verified, through the same handle() path a relayed message
 * takes.
 *
 * Also proves the architectural cap: a message addressed to SOMEONE ELSE is
 * refused, because a phone is an endpoint for itself and a relay for nobody.
 */
public class DirectEndpointTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static MaximaIdentity idFrom(int zSalt) {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * zSalt + zSalt);
        }
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== DIRECT ENDPOINT (loopback, no relay) ===\n");

        MaximaIdentity receiver = idFrom(61);
        MaximaIdentity sender = idFrom(67);
        MaximaIdentity stranger = idFrom(71);

        final AtomicInteger got = new AtomicInteger();
        final AtomicReference<String> body = new AtomicReference<>();

        DirectEndpoint ep = new DirectEndpoint(receiver, "1.0.48", inb -> {
            got.incrementAndGet();
            body.set(new String(inb.message.mData.getBytes()));
        });
        int port = ep.start(0);
        if (port > 0) {
            ok("endpoint listening on " + port);
        } else {
            bad("endpoint failed to bind");
            System.exit(1);
        }

        // A sender node (no relays needed - sendRaw dials the address directly).
        MaximaNode senderNode = new MaximaNode(sender, "1.0.48", 1);

        // ---- a message sealed to the receiver's identity, delivered direct ----
        String directAddr = receiver.mxIdentity() + "@127.0.0.1:" + port;
        MaximaSender.Result r = senderNode.sendRaw(directAddr, "direct_test_v1",
                "hello over a direct socket".getBytes());
        Thread.sleep(500);
        if (r.isOk()) {
            ok("sender's ack was OK (" + r.statusName + ")");
        } else {
            bad("direct send not OK: " + r.statusName);
        }
        if (got.get() == 1 && "hello over a direct socket".equals(body.get())) {
            ok("receiver got it, decrypted and verified, with no relay in the path");
        } else {
            bad("receiver did not get the direct message: " + got.get() + " " + body.get());
        }

        // ---- a message addressed to a STRANGER must be refused ----
        // Build it sealed to the stranger's key but deliver it to our endpoint.
        String wrongAddr = stranger.mxIdentity() + "@127.0.0.1:" + port;
        MaximaSender.Result r2 = senderNode.sendRaw(wrongAddr, "direct_test_v1",
                "you are not a relay".getBytes());
        Thread.sleep(500);
        if (!r2.isOk() && got.get() == 1) {
            ok("a message for another key is refused (" + r2.statusName
                    + ") - the endpoint relays for nobody");
        } else {
            bad("endpoint accepted a message not addressed to it: "
                    + r2.statusName + " delivered=" + got.get());
        }

        ep.stop();

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Direct endpoint holds.");
    }
}
