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
}
