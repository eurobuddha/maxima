package com.eurobuddha.maxima.dexrelay;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only envelope factory: signs with a JCA Ed25519 key (the Gradle JDK is 21). Production clients sign with
 * tweetnacl; the P0-c vector pins cross-implementation parity, this just makes many envelopes cheaply.
 */
final class TestSigner {
    final KeyPair kp;
    final String signPk;
    final String publicId;   // boxPk (fake, zeros) || signPk — only the signPk half is ever verified by the relay

    TestSigner() {
        try {
            kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            signPk = "0x" + Hex.encode(Ed25519Test.rawPublicKey(kp.getPublic()));
            publicId = "0x" + "00".repeat(32) + signPk.substring(2);
        } catch (Exception e) { throw new AssertionError("JDK without Ed25519: run tests on JDK 15+", e); }
    }

    String sign(Map<String, Object> fields) {
        try {
            String canon = Json.canonicalWithout(fields, "sig");
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(kp.getPrivate()); s.update(canon.getBytes(StandardCharsets.UTF_8));
            fields.put("sig", "0x" + Hex.encode(s.sign()));
            return Json.canonicalWithout(fields, null);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    Envelope order(String orderId, long seq, String amount, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1L); m.put("t", "order"); m.put("market", "MINIMA/MxUSD"); m.put("side", "sell");
        m.put("price", "0.01234567"); m.put("amount", amount);
        m.put("coins", java.util.Arrays.asList("0xF19FE115E66277A5785D69CA53BDA431B0D7CDB5B7DB1C4AE8C3CA01558C045F"));
        m.put("address", "0x0CDCD61692F186EB0BBCFA289F438043F586FF7B3F6864193358E29166E8454A");
        m.put("orderId", orderId); m.put("seq", seq); m.put("expiry", now + 3600); m.put("pubId", publicId); m.put("signPk", signPk);
        return Envelope.parse(sign(m), now);
    }

    Envelope msg(String to, String blobHex, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1L); m.put("t", "msg"); m.put("to", to); m.put("from", publicId); m.put("blob", blobHex); m.put("expiry", now + 300);
        return Envelope.parse(sign(m), now);
    }
}
