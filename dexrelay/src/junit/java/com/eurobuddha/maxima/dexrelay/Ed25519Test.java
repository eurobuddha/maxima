package com.eurobuddha.maxima.dexrelay;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import org.junit.Test;

/** The pure-Java verifier (needed on the JDK 11 fleet boxes) against the P0-c vector and, where the JDK has EdDSA, against JCA. */
public class Ed25519Test {

    static final byte[] PK  = Hex.decode("782e728cb923b3ce148059e2d063fa37756130c78df1478c817b6713f1a6c850");
    static final byte[] SIG = Hex.decode("744e68c5a3637b03a6733a544751e99313881de01b25d9ea273af4bc8422028f3fdbaeadd7b425e7595fdc2c7f48beea045c05ef94fc58493fc488c5e71e580e");
    static final byte[] MSG = JsonTest.VECTOR_CANONICAL.getBytes(StandardCharsets.UTF_8);

    @Test public void acceptsTheSpikeVector() {
        assertTrue(Ed25519.verify(PK, MSG, SIG));
    }

    @Test public void rejectsFlippedSignatureTamperedMessageWrongKeyAndBadLengths() {
        byte[] s = SIG.clone(); s[7] ^= 1;
        assertFalse(Ed25519.verify(PK, MSG, s));
        byte[] m = MSG.clone(); m[3] ^= 1;
        assertFalse(Ed25519.verify(PK, m, SIG));
        byte[] k = PK.clone(); k[0] ^= 1;
        assertFalse(Ed25519.verify(k, MSG, SIG));
        assertFalse(Ed25519.verify(Arrays.copyOf(PK, 31), MSG, SIG));
        assertFalse(Ed25519.verify(PK, MSG, Arrays.copyOf(SIG, 63)));
        // S >= L (non-canonical scalar) must be rejected even if it would verify under a lax check.
        byte[] big = SIG.clone(); big[63] |= (byte) 0x80;
        assertFalse(Ed25519.verify(PK, MSG, big));
    }

    @Test public void matchesJcaOnRandomKeysWhenAvailable() throws Exception {
        KeyPairGenerator g;
        try { g = KeyPairGenerator.getInstance("Ed25519"); }
        catch (java.security.NoSuchAlgorithmException e) { System.out.println("JCA has no Ed25519 here; cross-check skipped"); return; }
        SecureRandom rnd = new SecureRandom();
        for (int i = 0; i < 64; i++) {
            KeyPair kp = g.generateKeyPair();
            byte[] msg = new byte[rnd.nextInt(300)];
            rnd.nextBytes(msg);
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(kp.getPrivate()); s.update(msg);
            byte[] sig = s.sign();
            byte[] pk = rawPublicKey(kp.getPublic());
            assertTrue("round " + i, Ed25519.verify(pk, msg, sig));
            byte[] bad = sig.clone(); bad[rnd.nextInt(64)] ^= (byte) (1 + rnd.nextInt(255));
            assertFalse("round " + i + " mutated", Ed25519.verify(pk, msg, bad));
        }
    }

    /** Tests compile at --release 11 (no EdECPublicKey type), so take the raw 32 bytes off the SPKI DER encoding (RFC 8410: 12-byte prefix). */
    static byte[] rawPublicKey(java.security.PublicKey k) {
        byte[] spki = k.getEncoded();
        return Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }
}
