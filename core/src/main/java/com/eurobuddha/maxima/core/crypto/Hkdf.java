package com.eurobuddha.maxima.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * HKDF-SHA256 (RFC 5869), pure JDK.
 *
 * Same construction the fleet already uses for seed-derived identities
 * (comms/Hkdf.java), including the 32 zero-byte default salt, so derivations
 * stay consistent across the Minima app family.
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    public static byte[] derive(byte[] zSeed, String zInfo, int zLength) {
        return derive(zSeed, new byte[HASH_LEN], zInfo.getBytes(StandardCharsets.UTF_8), zLength);
    }

    public static byte[] derive(byte[] zSeed, byte[] zSalt, byte[] zInfo, int zLength) {
        try {
            // Extract
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(zSalt, HMAC));
            byte[] prk = mac.doFinal(zSeed);

            // Expand
            mac.init(new SecretKeySpec(prk, HMAC));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] t = new byte[0];
            int counter = 1;
            while (out.size() < zLength) {
                mac.reset();
                mac.update(t);
                mac.update(zInfo);
                mac.update((byte) counter);
                t = mac.doFinal();
                out.write(t, 0, t.length);
                counter++;
                if (counter > 255) {
                    throw new IllegalArgumentException("HKDF length too large: " + zLength);
                }
            }
            byte[] full = out.toByteArray();
            byte[] result = new byte[zLength];
            System.arraycopy(full, 0, result, 0, zLength);
            return result;

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF failed", e);
        }
    }
}
