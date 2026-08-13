package com.eurobuddha.maxima.core.codec;

/**
 * Hex encoding matching org.minima.utils.BaseConverter encode16/decode16.
 *
 * Two quirks that matter: output is UPPERCASE with a 0x prefix, and an EMPTY
 * input encodes to "" rather than "0x".
 */
public final class Hex {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] zBytes) {
        if (zBytes.length == 0) {
            // Reference returns empty string, NOT "0x", for zero-length input.
            return "";
        }
        char[] out = new char[zBytes.length * 2];
        for (int i = 0; i < zBytes.length; i++) {
            int v = zBytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return "0x" + new String(out);
    }

    public static byte[] decode(String zHex) {
        String hex = zHex;
        if (hex.toLowerCase().startsWith("0x")) {
            hex = hex.substring(2);
        }
        hex = hex.toUpperCase();

        if (hex.length() == 0) {
            return new byte[0];
        }
        if (!hex.matches("[0-9A-F]+")) {
            throw new NumberFormatException("Invalid HEX string: " + zHex);
        }
        // Odd length gets a leading zero, matching the reference.
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }

        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
