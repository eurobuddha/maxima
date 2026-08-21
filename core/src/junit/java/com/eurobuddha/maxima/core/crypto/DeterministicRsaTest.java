package com.eurobuddha.maxima.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import org.junit.Test;

/** The identity keypair is DERIVED from the seed, not stored - so it MUST be a
 *  pure function of (seed, context). If derivation ever became non-deterministic
 *  or context-blind, a user restoring from their seed would get a different
 *  Maxima identity and silently lose every existing contact and route. */
public class DeterministicRsaTest {

    private static byte[] seed(int fill) {
        byte[] s = new byte[32];
        Arrays.fill(s, (byte) fill);
        return s;
    }

    private static RSAPublicKey pub(KeyPair kp) {
        return (RSAPublicKey) kp.getPublic();
    }

    @Test
    public void sameSeedAndContextIsByteIdentical() {
        KeyPair a = DeterministicRsa.derive(seed(0x42), DeterministicRsa.INFO_P);
        KeyPair b = DeterministicRsa.derive(seed(0x42), DeterministicRsa.INFO_P);
        assertEquals("same seed+context -> identical modulus",
                pub(a).getModulus(), pub(b).getModulus());
        assertEquals("same seed+context -> identical private key",
                a.getPrivate(), b.getPrivate());
    }

    @Test
    public void differentSeedDivergesModulus() {
        KeyPair a = DeterministicRsa.derive(seed(0x01), DeterministicRsa.INFO_P);
        KeyPair b = DeterministicRsa.derive(seed(0x02), DeterministicRsa.INFO_P);
        assertFalse("a different seed must yield a different key",
                pub(a).getModulus().equals(pub(b).getModulus()));
    }

    @Test
    public void differentContextDivergesModulus() {
        // Domain separation: the same seed under two contexts (INFO_P vs INFO_Q)
        // must produce independent keys.
        KeyPair p = DeterministicRsa.derive(seed(0x42), DeterministicRsa.INFO_P);
        KeyPair q = DeterministicRsa.derive(seed(0x42), DeterministicRsa.INFO_Q);
        assertFalse(pub(p).getModulus().equals(pub(q).getModulus()));
    }

    @Test
    public void keyShapeIsRsa1024WithE65537() {
        RSAPublicKey k = pub(DeterministicRsa.derive(seed(0x42), DeterministicRsa.INFO_P));
        assertEquals("public exponent is 65537", DeterministicRsa.E, k.getPublicExponent());
        int bits = k.getModulus().bitLength();
        // The top prime bit is fixed so the modulus is a full KEY_BITS wide.
        assertEquals("modulus is " + DeterministicRsa.KEY_BITS + " bits",
                DeterministicRsa.KEY_BITS, bits);
    }
}
