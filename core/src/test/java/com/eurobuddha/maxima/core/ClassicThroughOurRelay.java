package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.HostConnection;

import java.nio.charset.StandardCharsets;

/**
 * CLASSIC INTEROP FOR OUR RELAY.
 *
 * Attaches one of our clients to OUR relay, prints the contact address, and
 * waits. A stock Minima node is then told to
 * {@code maxima action:send to:&lt;that address&gt;}.
 *
 * If the message arrives, the path proven is:
 *
 *   stock Minima node  ->  OUR RelayServer  ->  our NAT'd client
 *
 * i.e. our relay is a drop-in Maxima host for unmodified software.
 *
 * Note this deliberately tests SENDING through our relay rather than a stock
 * node ADOPTING it as its own host: the reference refuses any host on a
 * 127./10./192./172. address unless started with -allowallip
 * (MaximaManager.java:525-533), so host adoption cannot be demonstrated on
 * loopback at all. Sending has no such restriction.
 */
public class ClassicThroughOurRelay {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 19001;
        int waitSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 60;

        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * 3 + 29);
        }
        MaximaIdentity me = MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));

        try (HostConnection conn = new HostConnection(host, port,
                me.hostKey(host + ":" + port), "1.0.48")) {

            conn.attach(20000);
            String addr = conn.contactAddress();

            System.out.println("ATTACHED to our relay at " + host + ":" + port);
            System.out.println("ADDRESS=" + addr);
            System.out.println("waiting up to " + waitSeconds + "s for a message...");
            System.out.flush();

            long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                HostConnection.Inbound in = conn.receive(5000);
                if (in == null) {
                    continue;
                }
                System.out.println();
                System.out.println("=====================================");
                System.out.println("  RECEIVED VIA OUR RELAY");
                System.out.println("    application : " + in.message.mApplication);
                System.out.println("    from        : "
                        + in.message.mFrom.to0xString().substring(0, 30) + "...");
                System.out.println("    payload     : "
                        + new String(in.message.mData.getBytes(), StandardCharsets.UTF_8));
                System.out.println("    signature   : "
                        + (in.signatureValid ? "VALID" : "INVALID"));
                System.out.println("=====================================");
                System.out.println(in.signatureValid
                        ? "  CLASSIC -> OUR RELAY -> OUR CLIENT confirmed."
                        : "  signature failed");
                System.exit(in.signatureValid ? 0 : 1);
            }
            System.out.println("TIMEOUT - nothing arrived");
            System.exit(1);
        }
    }
}
