package com.eurobuddha.maxima.core.crypto;

import java.security.MessageDigest;
import java.util.function.Function;

/**
 * SHA3-256 - the Maxima default hash.
 *
 * Used for the message id, the TxPoW customHash binding, and the Mx address
 * checksum. The reference uses Bouncy Castle's SHA3Digest(256), which is
 * standard SHA3-256.
 *
 * The implementation is INJECTABLE as a plain function rather than a
 * {@code MessageDigest} supplier, and that detail is load-bearing. Android does
 * not provide SHA3-256 in its default providers (confirmed absent as late as
 * API 36), and it ALSO ships its own cut-down provider registered under the
 * name "BC" - so {@code Security.addProvider(new BouncyCastleProvider())} is a
 * silent no-op and {@code getInstance("SHA3-256", "BC")} then resolves to the
 * crippled built-in and throws. Going through JCE at all is the trap. A host
 * that needs Bouncy Castle should call the lightweight API directly.
 *
 * A wrong hash here would not crash - it would silently produce a different
 * identity and a different customHash, making us invisible on the network for
 * no visible reason. So {@link #setSha3} verifies any injected implementation
 * against a known answer before accepting it.
 */
public final class Hashes {

    /** SHA3-256("abc"), the published NIST value. */
    private static final String KAT_INPUT = "abc";
    private static final String KAT_EXPECT =
            "3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532";

    /** Default: the JDK provider. Correct on desktop, absent on Android. */
    private static volatile Function<byte[], byte[]> SHA3 = zData -> {
        try {
            return MessageDigest.getInstance("SHA3-256").digest(zData);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "SHA3-256 unavailable from the JDK provider. On Android call "
                            + "Hashes.setSha3() with a Bouncy Castle SHA3Digest - and do NOT "
                            + "route it through JCE, because Android already occupies the "
                            + "provider name \"BC\".", e);
        }
    };

    private Hashes() {
    }

    /**
     * Install a platform implementation.
     *
     * @throws IllegalStateException if it fails the known-answer test, because
     *                               a subtly wrong hash is far worse than none
     */
    public static void setSha3(Function<byte[], byte[]> zImpl) {
        if (zImpl == null) {
            throw new IllegalArgumentException("null SHA3 implementation");
        }
        byte[] out = zImpl.apply(KAT_INPUT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (out == null || out.length != 32 || !hex(out).equals(KAT_EXPECT)) {
            throw new IllegalStateException(
                    "Rejected SHA3-256 implementation: SHA3-256(\"abc\") gave "
                            + (out == null ? "null" : hex(out)) + ", expected " + KAT_EXPECT);
        }
        SHA3 = zImpl;
    }

    public static byte[] sha3(byte[] zData) {
        return SHA3.apply(zData);
    }

    /** True if SHA3-256 works right now, whichever implementation is installed. */
    public static boolean isAvailable() {
        try {
            return hex(sha3(KAT_INPUT.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .equals(KAT_EXPECT);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
