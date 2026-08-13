package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.Hex;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.msg.TxPoW;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;

/**
 * IDENTITY STABILITY GATE.
 *
 * A user's Maxima identity is derived from their seed, so if the derivation
 * ever changes they silently lose that identity - contacts can no longer reach
 * them and nothing obviously breaks at build time. These pinned vectors turn
 * that into a loud test failure.
 *
 * If a vector below stops matching, DO NOT regenerate it. Find out what changed
 * (JDK BigInteger primality behaviour, our HKDF labels, the prime search) and
 * decide deliberately, because every existing identity depends on it.
 */
public class IdentityTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    // seed | context | expected X.509 DER public key
    static final String[][] PINNED = {
            {"maxima-identity-test-vector-1", "identity",
                    "0x30819F300D06092A864886F70D010101050003818D0030818902818100EEA5EEB30E426B0FBA4334E2C14CA3F1C645E0DCD5DF9E56DC62F449F6F73AB422745A2BF32AE5E14DD4C560692054EAECA89B55789A32207A84F37CDA87148EF7C9C188974C22289B4390D6CB48C276EB02DCCCCB7E0F2EE471BCB4E5BE731D5781B451FA7F7F6F3A71618AB7C50C08C0473ADD1F4DA534384D5DD96B34A9110203010001"},
            {"maxima-identity-test-vector-1", "host-1",
                    "0x30819F300D06092A864886F70D010101050003818D0030818902818100A3CEA3FE83665124788D6D16391547A42C4B33A6A6BFE8E0E55BF25EF297F81547479699B88448E9423BA190F583B56472C19FAC38BB3BB4B610AA2F3C74B912230E0A13F7AA37BFB34969989BB60855BE5126C21D5C4DF1C3A52B0A46E6658CF0965667712148D93CA4E8CAA85994EE819ECA1C77F081F00A84E7A0761732CF0203010001"},
            {"maxima-identity-test-vector-2", "identity",
                    "0x30819F300D06092A864886F70D010101050003818D0030818902818100FB538210550A7C11A170CEFA62DF522002DA88E6EA6C4842684529D870C619415A0E49490A1EC7F47699F56EC0E1E1CDAB02072A9C84777D1AA34EE7CB9935AF5F08B2BE313E8E2992A9D0776F2BE989CD5B0F57B4F9BF053977FDD3A6F799FDB4E50A1035687766C8860131D1ABB9C3E3D1F82F142C2A30CA50F92FFE66E0870203010001"},
    };

    public static void main(String[] args) throws Exception {
        System.out.println("Identity + crypto + carrier gate\n");

        // ---- pinned derivations ----
        for (String[] v : PINNED) {
            byte[] seed = v[0].getBytes(StandardCharsets.UTF_8);
            KeyPair kp = DeterministicRsa.derive(seed, v[1]);
            String der = Hex.encode(kp.getPublic().getEncoded());
            if (der.equals(v[2])) {
                ok("pinned identity stable: seed=\"" + v[0] + "\" ctx=\"" + v[1] + "\"");
            } else {
                bad("IDENTITY DRIFT for seed=\"" + v[0] + "\" ctx=\"" + v[1] + "\"");
                System.out.println("      expected " + v[2].substring(0, 60) + "...");
                System.out.println("      actual   " + der.substring(0, 60) + "...");
            }
        }

        // ---- key shape ----
        KeyPair kp = DeterministicRsa.derive(
                "maxima-identity-test-vector-1".getBytes(StandardCharsets.UTF_8), "identity");
        byte[] pub = kp.getPublic().getEncoded();
        if (pub.length == 162) ok("public key is 162 bytes (RSA-1024 X.509 DER, as on the wire)");
        else bad("public key is " + pub.length + " bytes, expected 162");

        int bits = ((RSAPrivateKey) kp.getPrivate()).getModulus().bitLength();
        if (bits == 1024) ok("modulus is exactly 1024 bits");
        else bad("modulus is " + bits + " bits");

        // ---- distinct contexts ----
        byte[] other = DeterministicRsa.derive(
                "maxima-identity-test-vector-1".getBytes(StandardCharsets.UTF_8), "host-1")
                .getPublic().getEncoded();
        if (!Arrays.equals(pub, other)) ok("distinct contexts yield distinct identities");
        else bad("context is not separating identities");

        // ---- sign / verify ----
        byte[] payload = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        byte[] sig = MaximaCrypto.sign(kp.getPrivate(), payload);
        if (sig.length == 128) ok("signature is 128 bytes (SHA256withRSA / RSA-1024)");
        else bad("signature is " + sig.length + " bytes");
        if (MaximaCrypto.verify(pub, payload, sig)) ok("signature verifies");
        else bad("signature does not verify");

        byte[] tampered = payload.clone();
        tampered[0] ^= 0x01;
        if (!MaximaCrypto.verify(pub, tampered, sig)) ok("tampered payload rejected");
        else bad("tampered payload ACCEPTED - verification is broken");

        // ---- encrypt / decrypt ----
        byte[] secret = "sealed message".getBytes(StandardCharsets.UTF_8);
        byte[] round = MaximaCrypto.decrypt(
                MaximaCrypto.encrypt(secret, pub), kp.getPrivate().getEncoded());
        if (Arrays.equals(secret, round)) ok("RSA+AES hybrid round-trips");
        else bad("hybrid encryption round-trip failed");

        // IV and wrapped-key widths are fixed by the suite.
        com.eurobuddha.maxima.core.msg.CryptoPackage cp = MaximaCrypto.encrypt(secret, pub);
        if (cp.mIvParam.getLength() == 16) ok("IV is 16 bytes");
        else bad("IV is " + cp.mIvParam.getLength() + " bytes");
        if (cp.mSecret.getLength() == 128) ok("RSA-wrapped AES key is 128 bytes");
        else bad("wrapped key is " + cp.mSecret.getLength() + " bytes");

        // ---- carrier ----
        MaximaSender.Built builtUnit = MaximaSender.build(pub, kp.getPrivate(), pub,
                "test_app", "payload".getBytes(StandardCharsets.UTF_8), 1755000000000L);
        MaxTxPoW unit = builtUnit.unit;

        // msgid vs customHash: two SHA3-256 values of DIFFERENT objects, one
        // nested in the other. Confusing them silently breaks dedup and every
        // delivery receipt, so pin the distinction.
        MiniData customHashOf = unit.mTxPoW.getHeader().mCustomHash;
        MiniData hashOfPackage = new MiniData(
                com.eurobuddha.maxima.core.crypto.Hashes.sha3(Codec.serialise(unit.mMaxima)));
        if (customHashOf.equals(hashOfPackage))
            ok("customHash == SHA3(MaximaPackage)");
        else bad("customHash is not SHA3(MaximaPackage)");

        if (!builtUnit.msgid.equals(customHashOf))
            ok("msgid is DISTINCT from customHash (different objects hashed)");
        else bad("msgid equals customHash - wrong object was hashed");

        if (unit.checkValidTxPoW()) ok("carrier customHash binds the MaximaPackage");
        else bad("carrier customHash does not bind the package");

        byte[] unitBytes = Codec.serialise(unit);
        MaxTxPoW back = MaxTxPoW.fromBytes(unitBytes);
        if (Arrays.equals(unitBytes, Codec.serialise(back)))
            ok("MaxTxPoW round-trips byte-identically (" + unitBytes.length + " bytes)");
        else bad("MaxTxPoW round-trip mismatch");

        if (back.checkValidTxPoW()) ok("decoded carrier still validates");
        else bad("decoded carrier fails validation");

        // blockDifficulty MUST stay low or peers treat the carrier as a block
        // and push it into their blockchain pipeline.
        MiniData diff = unit.mTxPoW.getHeader().mBlockDifficulty;
        if (diff.getBytes().length <= 1 || isAllZero(diff)) {
            ok("blockDifficulty is minimal - carrier can never look like a block");
        } else {
            bad("blockDifficulty is " + diff + " - carrier risks chain re-injection");
        }

        if (!unit.mTxPoW.hasBody()) ok("carrier has no body - isTransaction() is false");
        else bad("carrier unexpectedly has a body");

        // REGRESSION: every CLASSIC sender's carrier HAS a body (the reference
        // builds it from an empty Transaction+Witness). Refusing to parse one
        // makes us unable to receive from any stock node - a failure that stays
        // invisible until you test against real software, because our own
        // carriers never have a body. Found live; guarded here.
        {
            byte[] withBody = buildCarrierWithBody(unit);
            try {
                MaxTxPoW parsed = MaxTxPoW.fromBytes(withBody);
                if (!parsed.mTxPoW.hasBody()) {
                    bad("body flag lost when parsing a classic-style carrier");
                } else if (!Arrays.equals(withBody, Codec.serialise(parsed))) {
                    bad("classic-style carrier does not re-serialise byte-identically");
                } else if (!parsed.checkValidTxPoW()) {
                    bad("classic-style carrier fails customHash validation");
                } else {
                    ok("classic-style carrier WITH a body parses and round-trips exactly");
                }
            } catch (Exception e) {
                bad("cannot parse a classic-style carrier with a body: " + e.getMessage());
            }
        }

        // TxHeader alone must round-trip (RLE super-parents are easy to break).
        byte[] hdr = Codec.serialise(unit.mTxPoW.getHeader());
        com.eurobuddha.maxima.core.msg.TxHeader h2 =
                Codec.deserialise(new com.eurobuddha.maxima.core.msg.TxHeader(), hdr);
        if (Arrays.equals(hdr, Codec.serialise(h2)))
            ok("TxHeader round-trips (RLE super-parents correct)");
        else bad("TxHeader round-trip mismatch");

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.out.println("IDENTITY/CRYPTO GATE FAILED");
            System.exit(1);
        }
        System.out.println("Identity derivation is stable and the crypto suite is correct.");
    }

    /**
     * Splice a fake body onto a carrier, mimicking what a classic sender emits:
     * identical bytes up to the hasBody flag, then flag=1 followed by body bytes.
     */
    static byte[] buildCarrierWithBody(MaxTxPoW zUnit) {
        byte[] original = Codec.serialise(zUnit);
        // Our carrier ends with the hasBody byte (0x00). Flip it and append.
        byte[] fakeBody = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] out = new byte[original.length + fakeBody.length];
        System.arraycopy(original, 0, out, 0, original.length);
        out[original.length - 1] = 0x01; // hasBody = true
        System.arraycopy(fakeBody, 0, out, original.length, fakeBody.length);
        return out;
    }

    static boolean isAllZero(MiniData d) {
        for (byte b : d.getBytes()) if (b != 0) return false;
        return true;
    }
}
