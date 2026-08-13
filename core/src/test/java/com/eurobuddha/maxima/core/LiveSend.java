package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MILESTONE 4 - THE FIRST LIVE INTEROP GATE.
 *
 * Sends a real, signed, encrypted Maxima message to a running node and waits
 * for its ack.
 *
 * Why the ack is meaningful here: on a DIRECT send (not relayed) the receiver
 * only replies MAXIMA_OK after it has decrypted the CryptoPackage, verified the
 * SHA256withRSA signature, and passed the inner from == signer bind check. So
 * an OK proves the entire stack - framing, envelope order, cipher suite,
 * signature and carrier binding - end to end.
 *
 * Failure codes tell us exactly which layer broke:
 *   WRONGHASH - customHash does not bind the MaximaPackage
 *   TOOBIG    - package over 256KB
 *   FAIL      - bad signature or from/signer mismatch
 *   UNKNOWN   - no route for the destination key
 *
 * Default application string is our own, so the node simply notifies its apps
 * and NOTHING is persisted. Pass an application argument to override.
 */
public class LiveSend {

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"(.*?)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static void main(String[] args) throws Exception {
        String infoPath = args.length > 0 ? args[0] : "/tmp/maxinfo.json";
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 4442;
        String application = args.length > 3 ? args[3] : "maxima_core_interop_test";

        String json = new String(Files.readAllBytes(Paths.get(infoPath)), StandardCharsets.UTF_8);
        String nodeName = field(json, "name");
        String nodePubHex = field(json, "publickey");

        if (nodePubHex == null) {
            System.out.println("Could not read publickey from " + infoPath);
            System.exit(1);
        }

        MiniData nodePub = new MiniData(nodePubHex);

        // Our identity, deterministically derived. A real deployment derives
        // this from the node's BIP39 seed; here a fixed test seed.
        byte[] seed = "maxima-core-live-send-seed-v1".getBytes(StandardCharsets.UTF_8);
        KeyPair kp = DeterministicRsa.derive(seed, "identity");
        byte[] ourPub = kp.getPublic().getEncoded();

        System.out.println("=== LIVE MAXIMA SEND ===");
        System.out.println("target node   : " + nodeName + " @ " + host + ":" + port);
        System.out.println("their key     : " + nodePub.getLength() + " bytes, "
                + MxAddress.make(nodePub).substring(0, 40) + "...");
        System.out.println("our identity  : " + ourPub.length + " bytes, "
                + MxAddress.make(new MiniData(ourPub)).substring(0, 40) + "...");
        System.out.println("application   : " + application);

        String payload = "Hello from an original Maxima implementation.";
        long now = System.currentTimeMillis();

        MaximaSender.Built built = MaximaSender.build(
                ourPub, kp.getPrivate(), nodePub.getBytes(),
                application, payload.getBytes(StandardCharsets.UTF_8), now);
        MaxTxPoW unit = built.unit;

        MiniData customHash = unit.mTxPoW.getHeader().mCustomHash;

        System.out.println("payload       : \"" + payload + "\"");
        System.out.println("unit size     : " + Codec.serialise(unit).length + " bytes");
        System.out.println("customHash    : " + customHash);
        System.out.println("self-check    : checkValidTxPoW="
                + unit.checkValidTxPoW() + " (must be true)");
        System.out.println();

        MaximaSender.Result res = MaximaSender.send(host, port, unit, built.msgid);

        System.out.println("sent " + res.sentBytes + " bytes -> ack: " + res.statusName
                + (res.status >= 0 ? " (0x0" + res.status + ")" : ""));
        System.out.println();
        System.out.println("=====================================");
        if (res.isOk()) {
            System.out.println("  LIVE INTEROP CONFIRMED");
            System.out.println("  The node decrypted our message, verified our RSA");
            System.out.println("  signature and accepted it. Full stack works.");
        } else {
            System.out.println("  SEND NOT ACCEPTED: " + res.statusName);
            switch (res.status) {
                case 0x04: System.out.println("  -> customHash does not bind the MaximaPackage"); break;
                case 0x03: System.out.println("  -> package exceeded 256KB"); break;
                case 0x00: System.out.println("  -> signature invalid or from/signer mismatch"); break;
                case 0x02: System.out.println("  -> no route for that destination key"); break;
                default:   System.out.println("  -> no recognised ack within the timeout"); break;
            }
        }
        System.out.println("=====================================");
        if (!res.isOk()) System.exit(1);
    }
}
