package com.eurobuddha.maxima.app.backup;

import org.bouncycastle.crypto.generators.SCrypt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-encrypted, PORTABLE backup crypto — deliberately NOT the device-bound
 * Android Keystore that {@link com.eurobuddha.maxima.app.SeedStore} uses (a
 * Keystore key can't leave the phone). Key = scrypt(password) via the vendored
 * BouncyCastle 1.69 (memory-hard); payload = AES-GCM (authenticated, so a wrong
 * password fails cleanly rather than yielding garbage).
 *
 * Blob layout: "PARLONSBK" (9) | format (1) | salt (16) | iv (12) | ciphertext+tag.
 */
final class BackupCrypto {

    private static final byte[] MAGIC = "PARLONSBK".getBytes(StandardCharsets.US_ASCII);
    private static final byte FORMAT = 1;
    private static final int SALT_LEN = 16, IV_LEN = 12, KEY_LEN = 32, TAG_BITS = 128;
    // scrypt cost — memory-hard, ~tens of ms on a phone.
    private static final int N = 1 << 15, R = 8, P = 1;

    private BackupCrypto() {
    }

    static byte[] encrypt(byte[] zPlain, char[] zPassword) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        byte[] key = derive(zPassword, salt);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(zPlain);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC);
            out.write(FORMAT);
            out.write(salt);
            out.write(iv);
            out.write(ct);
            return out.toByteArray();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static byte[] decrypt(byte[] zBlob, char[] zPassword) throws Exception {
        int m = MAGIC.length;
        if (zBlob.length < m + 1 + SALT_LEN + IV_LEN + 16
                || !Arrays.equals(Arrays.copyOfRange(zBlob, 0, m), MAGIC)) {
            throw new IllegalArgumentException("Not a Parlons backup file");
        }
        if (zBlob[m] != FORMAT) {
            throw new IllegalArgumentException("Unsupported backup format");
        }
        int p = m + 1;
        byte[] salt = Arrays.copyOfRange(zBlob, p, p + SALT_LEN);
        p += SALT_LEN;
        byte[] iv = Arrays.copyOfRange(zBlob, p, p + IV_LEN);
        p += IV_LEN;
        byte[] ct = Arrays.copyOfRange(zBlob, p, zBlob.length);
        byte[] key = derive(zPassword, salt);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(ct);   // AEADBadTagException on wrong password / tamper
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] derive(char[] zPassword, byte[] zSalt) {
        byte[] pw = new String(zPassword).getBytes(StandardCharsets.UTF_8);
        try {
            return SCrypt.generate(pw, zSalt, N, R, P, KEY_LEN);
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
    }
}
