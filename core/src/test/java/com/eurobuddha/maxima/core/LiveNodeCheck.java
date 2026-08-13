package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Hex;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.Base32;
import com.eurobuddha.maxima.core.identity.MxAddress;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LIVE INTEROP CHECK - validates :core against a REAL running Maxima node.
 *
 * The synthetic golden vectors prove we match the reference implementation's
 * code. This proves we match a node actually running on the live network,
 * which is a stronger claim: the node publishes both its raw RSA public key
 * and the Mx encoding of it, so it hands us a real-world vector pair for free.
 *
 * Usage:
 *   curl -s "http://127.0.0.1:4446/maxima%20action:info" > /tmp/maxinfo.json
 *   java ... LiveNodeCheck /tmp/maxinfo.json
 */
public class LiveNodeCheck {

    static int passed = 0;
    static int failed = 0;

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"(.*?)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static void ok(String msg) {
        passed++;
        System.out.println("  ok  " + msg);
    }

    static void bad(String msg) {
        failed++;
        System.out.println("  XX  " + msg);
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/tmp/maxinfo.json";
        String json = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);

        String name = field(json, "name");
        String pubkeyHex = field(json, "publickey");
        String mxpublickey = field(json, "mxpublickey");
        String contact = field(json, "contact");
        String mls = field(json, "mls");
        String localidentity = field(json, "localidentity");

        System.out.println("Live node: " + name);
        System.out.println("Validating :core against its published identity...\n");

        // 1. The headline check: does our Mx encoder reproduce the node's own
        //    published mxpublickey from its own published raw public key?
        MiniData pubkey = new MiniData(pubkeyHex);
        System.out.println("  RSA public key is " + pubkey.getLength() + " bytes"
                + (pubkey.getLength() == 162 ? " (expected 162 for RSA-1024 X.509 DER)" : " (UNEXPECTED)"));
        if (pubkey.getLength() == 162) passed++; else failed++;

        String ourMx = MxAddress.make(pubkey);
        if (ourMx.equals(mxpublickey)) {
            ok("MxAddress.make(publickey) reproduces the node's mxpublickey exactly");
            System.out.println("      " + ourMx.substring(0, 60) + "...");
        } else {
            bad("mxpublickey MISMATCH");
            System.out.println("      node: " + mxpublickey);
            System.out.println("      ours: " + ourMx);
        }

        // 2. Decode the node's Mx form back to the raw key.
        try {
            byte[] back = MxAddress.convert(mxpublickey).getBytes();
            if (Arrays.equals(pubkey.getBytes(), back)) {
                ok("MxAddress.convert(mxpublickey) recovers the raw key (checksum verified)");
            } else {
                bad("Mx decode did not recover the raw key");
            }
        } catch (Exception e) {
            bad("Mx decode threw: " + e.getMessage());
        }

        // 3. Uppercase 0x-hex round-trip - routing keys are compared in this exact form.
        if (Hex.encode(pubkey.getBytes()).equals(pubkeyHex.toUpperCase().replace("0X", "0x"))) {
            ok("Hex round-trip matches the node's 0x-hex form (uppercase)");
        } else {
            bad("Hex form differs: ours=" + Hex.encode(pubkey.getBytes()).substring(0, 20) + "...");
        }

        // 4. Parse the real contact / mls / local addresses.
        for (String[] pair : new String[][]{
                {"contact", contact}, {"mls", mls}, {"localidentity", localidentity}}) {
            String label = pair[0];
            String addr = pair[1];
            if (addr == null || addr.isEmpty()) {
                System.out.println("  --  " + label + " not set, skipping");
                continue;
            }
            if (!MxAddress.isValidContactAddress(addr)) {
                bad(label + " rejected by isValidContactAddress");
                continue;
            }
            // Classic parses on the FIRST '@' and the FIRST ':'.
            int at = addr.indexOf('@');
            int colon = addr.indexOf(':');
            String mxPart = addr.substring(0, at);
            String host = addr.substring(at + 1, colon);
            int port = Integer.parseInt(addr.substring(colon + 1));
            try {
                MiniData routingKey = MxAddress.convert(mxPart);
                ok(label + " parsed + checksum ok -> host " + host + ":" + port
                        + ", routing key " + routingKey.getLength() + " bytes");
            } catch (Exception e) {
                bad(label + " Mx part failed checksum: " + e.getMessage());
            }
        }

        // 5. Base32 alphabet sanity against a real address.
        String body = mxpublickey.substring(2);
        if (body.matches("[0-9A-HJKMNP-WYZ]+")) {
            ok("live address uses only the substituted alphabet (no I, L or O)");
        } else {
            bad("live address contains unexpected characters");
        }

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("=====================================");
        if (failed > 0) {
            System.out.println("LIVE INTEROP CHECK FAILED");
            System.exit(1);
        }
        System.out.println("Verified against a live Maxima node on the real network.");
    }
}
