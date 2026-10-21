package com.eurobuddha.maxima.dexrelay;

/** Lowercase hex, strict decode (even length, no prefix). */
final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    static String encode(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = DIGITS[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = DIGITS[b[i] & 0xF];
        }
        return new String(out);
    }

    static byte[] decode(String h) {
        if (h == null || (h.length() & 1) != 0) throw new IllegalArgumentException("bad hex length");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16), lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex char");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** "0x" + exactly {@code bytes} bytes of hex, either case. */
    static boolean is0x(String s, int bytes) {
        if (s == null || s.length() != 2 + bytes * 2 || s.charAt(0) != '0' || s.charAt(1) != 'x') return false;
        for (int i = 2; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    /** Bare hex (no prefix), even length, 1..maxBytes bytes. */
    static boolean isBareHex(String s, int maxBytes) {
        if (s == null || s.isEmpty() || (s.length() & 1) != 0 || s.length() > maxBytes * 2) return false;
        for (int i = 0; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }
}
