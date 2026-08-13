package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * FRAME-LEVEL LIVE INTEROP PROBE.
 *
 * Opens a real TCP connection to a running Minima node, sends a Greeting frame,
 * and decodes whatever comes back. This proves our framing, our primitives and
 * our Greeting layout all agree with a node on the live network - the layer the
 * synthetic vectors cannot exercise.
 *
 * Read-only with respect to the node: an INCOMING connection is never adopted
 * as a Maxima host by the reference, so this leaves no lasting state. We read a
 * bounded number of frames and disconnect.
 *
 * Usage: java ... WireProbe [host] [port]
 */
public class WireProbe {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 4442;
        // Must not contain "TEST" on mainnet, otherwise the peer rejects us.
        String version = args.length > 2 ? args[2] : "1.0.48";

        System.out.println("Connecting to " + host + ":" + port + " ...");

        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 20000);
            sock.setSoTimeout(20000);
            sock.setTcpNoDelay(true);

            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            // The outgoing side must greet first, before anything else.
            Greeting greet = Greeting.commsOnly(version, host, port);
            byte[] body = Frame.body(Frame.MSG_GREETING, greet);
            Frame.write(out, body);
            System.out.println("Sent GREETING (type 0), " + body.length + " byte body, version " + version);

            int frames = 0;
            boolean sawGreeting = false;

            while (frames < 6) {
                byte[] rx;
                try {
                    rx = Frame.read(in);
                } catch (Exception e) {
                    System.out.println("\nStream ended: " + e.getClass().getSimpleName()
                            + (e.getMessage() != null ? " - " + e.getMessage() : ""));
                    break;
                }
                frames++;
                int type = Frame.typeOf(rx);
                System.out.println("\n<- frame " + frames + ": type=" + type
                        + " (" + typeName(type) + "), body=" + rx.length + " bytes");

                if (type == Frame.MSG_GREETING) {
                    // Decode the payload after the single type byte.
                    byte[] payload = new byte[rx.length - 1];
                    System.arraycopy(rx, 1, payload, 0, payload.length);

                    Greeting g = Greeting.fromBytes(payload);
                    sawGreeting = true;
                    System.out.println("   DECODED GREETING");
                    System.out.println("     version   : " + g.getVersion());
                    System.out.println("     topBlock  : " + g.getTopBlock());
                    System.out.println("     chain ids : " + g.getChain().size());
                    String extra = g.getExtraData();
                    System.out.println("     extraData : "
                            + (extra.length() > 200 ? extra.substring(0, 200) + "..." : extra));

                    // Re-serialise and compare: proves we read every field and
                    // consumed exactly the right number of bytes.
                    byte[] re = Codec.serialise(g);
                    if (java.util.Arrays.equals(payload, re)) {
                        System.out.println("     PARITY    : re-serialised byte-identical ("
                                + re.length + " bytes)");
                    } else {
                        System.out.println("     PARITY    : MISMATCH! ours=" + re.length
                                + " theirs=" + payload.length);
                        System.exit(1);
                    }
                    // A greeting is all we need to prove the frame layer.
                    break;
                } else if (type == Frame.MSG_MAXIMA_CTRL) {
                    // An INCOMING peer is offered the node's MLS identity, per
                    // MaximaManager: "if incoming, send CTRL/TYPE_MLS".
                    byte[] payload = new byte[rx.length - 1];
                    System.arraycopy(rx, 1, payload, 0, payload.length);

                    MaximaCTRLMessage ctrl = MaximaCTRLMessage.fromBytes(payload);
                    int ctype = ctrl.getType().getAsInt();
                    System.out.println("   DECODED MAXIMA_CTRL");
                    System.out.println("     type      : " + ctype
                            + (ctype == MaximaCTRLMessage.TYPE_ID ? " (TYPE_ID - raw DER pubkey)"
                            : ctype == MaximaCTRLMessage.TYPE_MLS ? " (TYPE_MLS - raw UTF-8 Mx string)"
                            : " (unknown)"));

                    byte[] data = ctrl.getData().getBytes();
                    System.out.println("     data len  : " + data.length + " bytes");

                    if (ctype == MaximaCTRLMessage.TYPE_MLS) {
                        // Asymmetric payload: this is TEXT, not a DER key. And it
                        // is the BARE Mx key - the receiver appends "@host:port"
                        // from the observed socket address.
                        String mls = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        System.out.println("     as UTF-8  : "
                                + (mls.length() > 90 ? mls.substring(0, 90) + "..." : mls));
                        System.out.println("     has '@'   : " + mls.contains("@")
                                + "  (expected false - bare key, peer appends host)");
                        try {
                            MiniData key = MxAddress.convert(mls);
                            System.out.println("     Mx decode : checksum OK, "
                                    + key.getLength() + "-byte MLS public key");
                            System.out.println("     we would store: "
                                    + MaximaCTRLMessage.mlsAddressFrom(ctrl, host + ":" + port)
                                    .substring(0, 40) + "...@" + host + ":" + port);
                        } catch (Exception ex) {
                            System.out.println("     Mx decode : FAILED - " + ex.getMessage());
                            System.exit(1);
                        }
                    }

                    byte[] re = Codec.serialise(ctrl);
                    if (java.util.Arrays.equals(payload, re)) {
                        System.out.println("     PARITY    : re-serialised byte-identical ("
                                + re.length + " bytes)");
                    } else {
                        System.out.println("     PARITY    : MISMATCH!");
                        System.exit(1);
                    }
                } else {
                    System.out.println("   (first bytes: "
                            + new MiniData(java.util.Arrays.copyOf(rx, Math.min(16, rx.length)))
                            + ")");
                }
            }

            System.out.println("\n=====================================");
            if (sawGreeting) {
                System.out.println("  FRAME-LEVEL INTEROP CONFIRMED");
                System.out.println("  Our greeting was accepted and we decoded theirs exactly.");
            } else {
                System.out.println("  NO GREETING RECEIVED - frame layer unproven");
                System.exit(1);
            }
            System.out.println("=====================================");
        }
    }

    static String typeName(int t) {
        switch (t) {
            case 0: return "GREETING";
            case 1: return "IBD";
            case 8: return "PING/ack";
            case 9: return "MAXIMA_CTRL";
            case 10: return "MAXIMA_TXPOW";
            default: return "other";
        }
    }
}
