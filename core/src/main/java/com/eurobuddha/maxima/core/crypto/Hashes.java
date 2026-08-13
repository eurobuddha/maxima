package com.eurobuddha.maxima.core.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;

/**
 * SHA3-256 - the Maxima default hash.
 *
 * Used for the message id, the TxPoW customHash binding, and the Mx address
 * checksum. The reference uses Bouncy Castle SHA3Digest(256), which is standard
 * SHA3-256, so the JDK provider produces identical output.
 *
 * The digest is pluggable because Android below API 29 has no SHA3-256 in its
 * default provider - the Android module injects a Bouncy Castle supplier there.
 * :core itself stays dependency-free.
 */
public final class Hashes {

    private static volatile Supplier<MessageDigest> SHA3_SUPPLIER = () -> {
        try {
            return MessageDigest.getInstance("SHA3-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA3-256 unavailable. On Android < API 29 call Hashes.setSha3Supplier() "
                            + "with a Bouncy Castle backed digest.", e);
        }
    };

    private Hashes() {
    }

    /** Inject a platform digest (e.g. Bouncy Castle on old Android). */
    public static void setSha3Supplier(Supplier<MessageDigest> zSupplier) {
        SHA3_SUPPLIER = zSupplier;
    }

    public static byte[] sha3(byte[] zData) {
        return SHA3_SUPPLIER.get().digest(zData);
    }

    /** True if SHA3-256 is usable on this platform right now. */
    public static boolean isAvailable() {
        try {
            SHA3_SUPPLIER.get();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
