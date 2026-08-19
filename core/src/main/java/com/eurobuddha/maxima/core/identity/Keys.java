package com.eurobuddha.maxima.core.identity;

/**
 * ONE normalisation for identity public keys, used at every boundary where a
 * key is stored or compared.
 *
 * Two schemes used to coexist: String.toUpperCase() gave "0X30819F..." while
 * Group's own helper gave "0x30819F...". Lookups only lined up because
 * MaximaNode uppercased a second time, which is luck rather than design - and
 * it is exactly what caused a group member who could not be removed. Route
 * everything through here instead.
 *
 * The canonical form matches MiniData.to0xString(): a lowercase "0x" prefix
 * followed by uppercase hex.
 */
public final class Keys {

    private Keys() {
    }

    public static String norm(String zKey) {
        if (zKey == null) {
            return "";
        }
        String k = zKey.trim();
        if (k.length() >= 2 && (k.charAt(0) == '0') && (k.charAt(1) == 'x' || k.charAt(1) == 'X')) {
            return "0x" + k.substring(2).toUpperCase();
        }
        return k.toUpperCase();
    }

    public static boolean same(String zA, String zB) {
        return norm(zA).equals(norm(zB));
    }

    /**
     * A short, stable fingerprint of an identity public key: SHA-256 of the raw
     * DER key bytes, as 64 lowercase hex chars.
     *
     * Used where the full key (a 162-byte DER blob → 326-char 0x-hex string) is
     * too large to carry — notably an Android NSD TXT record, which caps a
     * single attribute at key+value &lt; 255 bytes. Both peers derive it from the
     * SAME canonical key form, so a fingerprint match is a reliable identity
     * match. SHA-256 via {@link java.security.MessageDigest} keeps this free of
     * any BouncyCastle version dependency.
     */
    public static String fingerprint(String zPublicKeyHex) {
        String k = norm(zPublicKeyHex);
        if (k.startsWith("0x") || k.startsWith("0X")) {
            k = k.substring(2);
        }
        if (k.isEmpty() || (k.length() % 2) != 0) {
            return "";
        }
        byte[] der = new byte[k.length() / 2];
        for (int i = 0; i < der.length; i++) {
            der[i] = (byte) Integer.parseInt(k.substring(i * 2, i * 2 + 2), 16);
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
