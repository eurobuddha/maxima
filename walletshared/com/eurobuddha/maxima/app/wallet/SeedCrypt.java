package com.eurobuddha.maxima.app.wallet;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keystore-wrapped AES/GCM for the OPTIONAL custom wallet phrase — the same
 * protection pattern SeedStore gives the main seed, under its own key alias so
 * neither secret's lifecycle can disturb the other. Wallet-grade material:
 * the phrase spends funds; it never touches disk in plain text.
 */
final class SeedCrypt {

    private static final String ALIAS = "maxima_wallet_key";
    private static final int GCM_TAG_BITS = 128;

    private SeedCrypt() {
    }

    static String encrypt(String zPlain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(zPlain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("wallet phrase encryption failed", e);
        }
    }

    static String decrypt(String zBlob) {
        try {
            byte[] in = Base64.decode(zBlob, Base64.NO_WRAP);
            int ivLen = in[0] & 0xFF;
            byte[] iv = new byte[ivLen];
            byte[] ct = new byte[in.length - 1 - ivLen];
            System.arraycopy(in, 1, iv, 0, ivLen);
            System.arraycopy(in, 1 + ivLen, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;   // unreadable = treated as no custom phrase set
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }
}
