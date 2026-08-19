package com.eurobuddha.maxima.core.crypto;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Deterministic RSA-1024 derived from a BIP39 seed.
 *
 * Classic Maxima generates a random RSA keypair once and stores it in the node
 * database, so the identity is trapped on that device. We derive it from the
 * seed instead, which means the SAME identity restores on a new phone and can
 * be shared between a handset and a server.
 *
 * DETERMINISM IS THE WHOLE POINT, so we do NOT delegate to a provider's key
 * generator. Bouncy Castle and the JDK are each free to change their internal
 * prime-search strategy between versions, which would silently produce a
 * different key from the same seed and orphan the user's identity. Instead we
 * run our own fully specified search:
 *
 *   1. Expand the seed with HKDF into a deterministic byte stream.
 *   2. Take 512-bit candidates from that stream, force the top two bits and the
 *      low bit, then step upward by 2 until probably prime.
 *   3. p is the first such prime, q the next (from a distinct HKDF label).
 *   4. Reject and continue if p == q, if the modulus is not exactly 1024 bits,
 *      or if gcd(e, phi) != 1.
 *
 * Only step 2's primality test comes from the JDK, and Miller-Rabin never
 * rejects an actual prime - so the accepted candidate sequence is stable.
 *
 * The resulting key is a standard RSA key: 162-byte X.509 DER public,
 * 635-byte PKCS#8 private, exactly matching what classic Maxima puts on the
 * wire.
 */
public final class DeterministicRsa {

    public static final int KEY_BITS = 1024;
    public static final int PRIME_BITS = KEY_BITS / 2;
    public static final BigInteger E = BigInteger.valueOf(65537);

    /** BigInteger.valueOf(2), NOT BigInteger.TWO: TWO was only added to Android's
     *  java.math.BigInteger in API 33, so referencing it NoSuchFieldError-crashes the
     *  whole app at identity creation on every device below Android 13 (minSdk is 28). */
    private static final BigInteger TWO = BigInteger.valueOf(2);

    /** Change this and every derived identity changes. Treat as frozen. */
    public static final String INFO_P = "maxima-identity-v1-p";
    public static final String INFO_Q = "maxima-identity-v1-q";

    /** Miller-Rabin certainty. Higher is slower but never wrong-rejects a prime. */
    private static final int CERTAINTY = 100;

    private DeterministicRsa() {
    }

    /**
     * Derive the Maxima identity keypair for a seed.
     *
     * @param zSeed    raw seed bytes (e.g. from the node's BIP39 seed)
     * @param zContext extra label, so one seed can yield distinct identities
     *                 (for example a per-host routing key)
     */
    public static KeyPair derive(byte[] zSeed, String zContext) {
        BigInteger p = derivePrime(zSeed, zContext + "|" + INFO_P);
        BigInteger q = derivePrime(zSeed, zContext + "|" + INFO_Q);

        // Vanishingly unlikely, but must be handled or the key is invalid.
        int salt = 0;
        while (p.equals(q)) {
            q = derivePrime(zSeed, zContext + "|" + INFO_Q + "|" + (++salt));
        }

        // Canonical ordering so p and q cannot swap between runs.
        if (p.compareTo(q) < 0) {
            BigInteger t = p;
            p = q;
            q = t;
        }

        BigInteger n = p.multiply(q);
        if (n.bitLength() != KEY_BITS) {
            // Both primes had the top bits forced, so this should not happen.
            throw new IllegalStateException("Derived modulus is " + n.bitLength()
                    + " bits, expected " + KEY_BITS);
        }

        BigInteger p1 = p.subtract(BigInteger.ONE);
        BigInteger q1 = q.subtract(BigInteger.ONE);
        BigInteger phi = p1.multiply(q1);
        if (!phi.gcd(E).equals(BigInteger.ONE)) {
            throw new IllegalStateException("gcd(e, phi) != 1 for derived primes");
        }

        BigInteger d = E.modInverse(phi);
        BigInteger dp = d.mod(p1);
        BigInteger dq = d.mod(q1);
        BigInteger qinv = q.modInverse(p);

        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(n, E));
            PrivateKey priv = kf.generatePrivate(
                    new RSAPrivateCrtKeySpec(n, E, d, p, q, dp, dq, qinv));
            return new KeyPair(pub, priv);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build RSA key from derived primes", e);
        }
    }

    /**
     * A deterministic 512-bit probable prime.
     *
     * The candidate is taken from HKDF output with the top two bits set (so the
     * product of two such primes is exactly 1024 bits) and the low bit set (so
     * it is odd), then stepped by 2 until prime.
     */
    private static BigInteger derivePrime(byte[] zSeed, String zInfo) {
        byte[] bytes = Hkdf.derive(zSeed, zInfo, PRIME_BITS / 8);

        // Force top two bits: guarantees the 1024-bit product.
        bytes[0] |= (byte) 0xC0;
        // Force odd.
        bytes[bytes.length - 1] |= (byte) 0x01;

        BigInteger candidate = new BigInteger(1, bytes);

        while (!candidate.isProbablePrime(CERTAINTY)) {
            candidate = candidate.add(TWO);
            // Stepping up could in principle overflow into 513 bits.
            if (candidate.bitLength() != PRIME_BITS) {
                throw new IllegalStateException("Prime search overflowed for " + zInfo);
            }
        }
        return candidate;
    }
}
