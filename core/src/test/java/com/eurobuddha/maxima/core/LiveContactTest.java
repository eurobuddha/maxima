package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.Hex;
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
 * NODE-SIDE OBSERVABLE INTEROP PROOF.
 *
 * The MAXIMA_OK ack already proves decrypt + signature + bind check passed, but
 * it is still our own reading of a status byte. This goes further: it speaks
 * the real contact protocol, so success is visible in the node's own
 * `maxcontacts action:list` output - independent of anything we assert.
 *
 * It also exercises a distinct layer the ack does not: the contact-ctrl JSON
 * schema and the receiver's {@code contactjson.publickey == maxmsg.from} bind
 * check.
 *
 * This DOES write a contact row on the target node. Remove it afterwards with
 *   maxcontacts action:remove id:&lt;id&gt;
 *
 * The advertised address deliberately points at port 1 so the node's automatic
 * reciprocation attempt fails immediately and harmlessly rather than dialling
 * something real.
 */
public class LiveContactTest {

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"(.*?)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static void main(String[] args) throws Exception {
        String infoPath = args.length > 0 ? args[0] : "/tmp/maxinfo.json";
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 4442;

        String json = new String(Files.readAllBytes(Paths.get(infoPath)), StandardCharsets.UTF_8);
        MiniData nodePub = new MiniData(field(json, "publickey"));

        byte[] seed = "maxima-core-live-send-seed-v1".getBytes(StandardCharsets.UTF_8);
        KeyPair kp = DeterministicRsa.derive(seed, "identity");
        byte[] ourPub = kp.getPublic().getEncoded();

        // The receiver enforces contactjson.publickey == maxmsg.from, and it
        // compares the uppercase 0x-hex form.
        String ourPubHex = Hex.encode(ourPub);
        String ourMx = MxAddress.make(new MiniData(ourPub));

        // Unroutable on purpose - the node will try to reciprocate.
        String ourAddress = ourMx + "@127.0.0.1:1";

        String contactJson = "{"
                + "\"delete\":false,"
                + "\"intro\":true,"
                + "\"publickey\":\"" + ourPubHex + "\","
                + "\"address\":\"" + ourAddress + "\","
                + "\"name\":\"maxima-core-interop\","
                + "\"icon\":\"0x00\","
                + "\"minimaaddress\":\"" + ourMx + "\","
                + "\"topblock\":\"0\","
                + "\"checkblock\":\"0\","
                + "\"checkhash\":\"0x00\","
                + "\"mls\":\"\""
                + "}";

        System.out.println("=== LIVE CONTACT PROTOCOL TEST ===");
        System.out.println("our publickey : " + ourPubHex.substring(0, 40) + "...");
        System.out.println("advertised as : maxima-core-interop");
        System.out.println("contact JSON  : " + contactJson.length() + " bytes");
        System.out.println();

        long now = System.currentTimeMillis();
        MaximaSender.Built built = MaximaSender.build(
                ourPub, kp.getPrivate(), nodePub.getBytes(),
                "**maxima_contact_ctrl**",
                contactJson.getBytes(StandardCharsets.UTF_8), now);

        MaximaSender.Result res = MaximaSender.send(host, port, built.unit, built.msgid);

        System.out.println("ack: " + res.statusName + "  (" + res.sentBytes + " bytes sent)");
        System.out.println();
        if (res.isOk()) {
            System.out.println("Accepted. Now verify from the NODE's side:");
            System.out.println("  curl -s \"http://127.0.0.1:4446/maxcontacts%20action:list\"");
            System.out.println("Then clean up with:");
            System.out.println("  curl -s \"http://127.0.0.1:4446/maxcontacts%20action:remove%20id:<id>\"");
        } else {
            System.out.println("Not accepted: " + res.statusName);
            System.exit(1);
        }
    }
}
