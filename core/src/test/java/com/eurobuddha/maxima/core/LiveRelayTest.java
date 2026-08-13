package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.net.HostConnection;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

/**
 * MILESTONE 5 - THE SECOND LIVE INTEROP GATE: RELAYED RECEIVE.
 *
 * Proves a device with no public IP can RECEIVE, which is the half that makes
 * phones viable at all.
 *
 * The test is self-contained - it needs no second party, because it uses the
 * same self-probe the reference uses to confirm host attachment:
 *
 *   1. Attach to a public host over a long-lived OUTGOING socket
 *      (greeting, then CTRL/TYPE_ID announcing our per-host routing key).
 *   2. From a SEPARATE, short-lived socket, send a message addressed to that
 *      routing key.
 *   3. The host looks the key up in its socket map and relays the frame down
 *      connection 1.
 *   4. We decrypt it with the per-host private key and verify the signature.
 *
 * If step 4 succeeds we have proven the full inbound path: attachment,
 * routing-key registration, relaying, and end-to-end decryption - from behind
 * NAT, having never accepted an inbound connection.
 */
public class LiveRelayTest {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "eurobuddha.com";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9001;
        String version = args.length > 2 ? args[2] : "1.0.48";

        System.out.println("=== MILESTONE 5: RELAYED RECEIVE ===");
        System.out.println("relay: " + host + ":" + port + "\n");

        // Per-host key: a fresh identity for THIS host, so hosts cannot
        // correlate us with each other.
        byte[] seed = "maxima-core-relay-test-seed-v1".getBytes(StandardCharsets.UTF_8);
        KeyPair perHost = DeterministicRsa.derive(seed, "host|" + host + ":" + port);
        KeyPair identity = DeterministicRsa.derive(seed, "identity");

        try (HostConnection conn = new HostConnection(host, port, perHost, version)) {

            System.out.println("[1] attaching (greeting + CTRL/TYPE_ID)...");
            long t0 = System.currentTimeMillis();
            conn.attach(30000);
            long dt = System.currentTimeMillis() - t0;

            System.out.println("    attached in " + dt + "ms");
            System.out.println("    their version : " + conn.getTheirGreeting().getVersion());
            System.out.println("    their topBlock: " + conn.getTheirGreeting().getTopBlock());
            if (conn.getTheirMlsAddress() != null) {
                System.out.println("    offered MLS   : "
                        + conn.getTheirMlsAddress().substring(0, 44) + "...");
            }
            System.out.println("    CTRL/TYPE_ID  : " + conn.idFrameSize() + " byte frame");
            System.out.println();
            System.out.println("    OUR CONTACT ADDRESS (publishable):");
            String addr = conn.contactAddress();
            System.out.println("      " + addr.substring(0, Math.min(70, addr.length())) + "...");
            System.out.println("      @" + host + ":" + port);
            System.out.println();

            // Give the host a moment to register the routing key.
            Thread.sleep(1500);

            System.out.println("[2] sending ourselves a probe from a SEPARATE socket...");
            String payload = "relayed-receive-probe";
            long now = System.currentTimeMillis();

            MaximaSender.Built built = MaximaSender.build(
                    identity.getPublic().getEncoded(),
                    identity.getPrivate(),
                    conn.routingKey(),               // route to our per-host key
                    "**maxima_check_connect**",
                    payload.getBytes(StandardCharsets.UTF_8),
                    now);

            MiniData sentMsgid = built.msgid;
            MaximaSender.Result res = MaximaSender.send(host, port, built.unit, sentMsgid);
            System.out.println("    relay ack: " + res.statusName
                    + "  (" + res.sentBytes + " bytes)");

            if (!res.isOk()) {
                System.out.println();
                System.out.println("    relay did not accept the probe: " + res.statusName);
                if (res.status == 0x02) {
                    System.out.println("    UNKNOWN = the host has no socket for our routing key,");
                    System.out.println("    i.e. our CTRL/TYPE_ID was not registered.");
                }
                System.exit(1);
            }

            System.out.println();
            System.out.println("[3] waiting for the relay to deliver it to us...");

            HostConnection.Inbound in = conn.receive(30000);

            System.out.println();
            System.out.println("=====================================");
            if (in == null) {
                System.out.println("  NO RELAYED MESSAGE RECEIVED");
                System.out.println("  The host acked the send but nothing came down");
                System.out.println("  the long-lived socket within 30s.");
                System.out.println("=====================================");
                System.exit(1);
            }

            String got = new String(in.message.mData.getBytes(), StandardCharsets.UTF_8);
            System.out.println("  RELAYED MESSAGE RECEIVED");
            System.out.println("    application : " + in.message.mApplication);
            System.out.println("    payload     : \"" + got + "\"");
            System.out.println("    signature   : " + (in.signatureValid ? "VALID" : "INVALID"));
            System.out.println("    msgid       : " + in.msgid.to0xString().substring(0, 34) + "...");
            System.out.println("    msgid match : " + in.msgid.equals(sentMsgid)
                    + "  (sender and receiver derive it identically)");

            boolean good = payload.equals(got) && in.signatureValid && in.msgid.equals(sentMsgid);
            System.out.println();
            if (good) {
                System.out.println("  MILESTONE 5 PASSED - RELAYED RECEIVE WORKS");
                System.out.println("  We received a message from behind NAT without ever");
                System.out.println("  accepting an inbound connection.");
            } else {
                System.out.println("  RECEIVED BUT INCONSISTENT - see fields above");
            }
            System.out.println("=====================================");
            if (!good) System.exit(1);
        }
    }
}
