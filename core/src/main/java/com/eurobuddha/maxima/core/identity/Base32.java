package com.eurobuddha.maxima.core.identity;

import com.eurobuddha.maxima.core.codec.Hex;

import java.math.BigInteger;

/**
 * Minima's "Mx" base32 - NOT RFC 4648 and NOT Crockford.
 *
 * It is a positional big-integer conversion: {@code new BigInteger(1, data).toString(32)},
 * lowercased, with the visually ambiguous characters substituted
 * ({@code i->w, l->y, o->z}), uppercased, and prefixed "Mx".
 *
 * Two consequences that must be preserved exactly:
 *   - Leading zero bytes are DESTROYED (0x00 and 0x000001 both collapse).
 *     This is precisely why {@link MxAddress} prepends a 0x01 guard byte.
 *   - The effective alphabet after substitution is 0-9 A-H J K M N P-W Y Z
 *     (no I, no L, no O).
 */
public final class Base32 {

    private Base32() {
    }

    public static String encode(byte[] zData) {
        BigInteger bigint = new BigInteger(1, zData);

        String b32 = bigint.toString(32).toLowerCase();

        b32 = b32.replaceAll("i", "w");
        b32 = b32.replaceAll("l", "y");
        b32 = b32.replaceAll("o", "z");

        return "Mx" + b32.toUpperCase();
    }

    public static byte[] decode(String zBase32) {
        String b32 = zBase32.toLowerCase();
        if (b32.startsWith("mx")) {
            b32 = b32.substring(2);
        }

        // Reverse the substitution.
        b32 = b32.replaceAll("w", "i");
        b32 = b32.replaceAll("y", "l");
        b32 = b32.replaceAll("z", "o");

        BigInteger bigint = new BigInteger(b32, 32);

        // Reference round-trips through a hex string, which left-pads odd lengths.
        return Hex.decode("0x" + bigint.toString(16));
    }
}
