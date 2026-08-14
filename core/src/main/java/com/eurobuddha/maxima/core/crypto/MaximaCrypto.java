package com.eurobuddha.maxima.core.crypto;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.msg.CryptoPackage;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * The Maxima cipher suite. Every parameter here is load-bearing for interop -
 * the live network's Cipher calls assume exactly these transforms.
 *
 * <pre>
 *   asymmetric  RSA/ECB/PKCS1Padding      (RSA-1024)
 *   symmetric   AES/CBC/PKCS5Padding      (AES-128, 16-byte random IV)
 *   signature   SHA256withRSA             (128 bytes for RSA-1024)
 *   key encode  X.509 (public, 162B) / PKCS#8 (private, 635B)
 * </pre>
 *
 * Note there is no AEAD and no MAC over the ciphertext: integrity comes solely
 * from the signature inside MaximaInternal, which is why the receiver's
 * from/signer bind check matters.
 */
public final class MaximaCrypto {

    public static final String RSA_TRANSFORM = "RSA/ECB/PKCS1Padding";
    public static final String AES_TRANSFORM = "AES/CBC/PKCS5Padding";
    public static final String SIGN_ALGO = "SHA256withRSA";
    public static final int AES_BITS = 128;
    public static final int IV_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private MaximaCrypto() {
    }

    // ---------- key encoding ----------

    public static PublicKey publicKeyFromDer(byte[] zDer) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(zDer));
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad X.509 RSA public key", e);
        }
    }

    public static PrivateKey privateKeyFromDer(byte[] zDer) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(zDer));
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad PKCS#8 RSA private key", e);
        }
    }

    // ---------- signing ----------

    public static byte[] sign(PrivateKey zPrivate, byte[] zData) {
        try {
            Signature sig = Signature.getInstance(SIGN_ALGO);
            sig.initSign(zPrivate);
            sig.update(zData);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    public static boolean verify(byte[] zPublicDer, byte[] zData, byte[] zSignature) {
        try {
            Signature sig = Signature.getInstance(SIGN_ALGO);
            sig.initVerify(publicKeyFromDer(zPublicDer));
            sig.update(zData);
            return sig.verify(zSignature);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- hybrid encryption ----------

    /**
     * Encrypt to a recipient's RSA public key: fresh AES-128 key per message,
     * RSA-wrapped, payload under AES-CBC with a random IV.
     */
    public static CryptoPackage encrypt(byte[] zData, byte[] zRsaPublicDer) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_BITS, RANDOM);
            SecretKey aesKey = kg.generateKey();

            Cipher aes = Cipher.getInstance(AES_TRANSFORM);
            aes.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] ciphertext = aes.doFinal(zData);

            Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.ENCRYPT_MODE, publicKeyFromDer(zRsaPublicDer));
            byte[] wrapped = rsa.doFinal(aesKey.getEncoded());

            return new CryptoPackage(
                    new MiniData(iv), new MiniData(wrapped), new MiniData(ciphertext));

        } catch (Exception e) {
            throw new IllegalStateException("Maxima encrypt failed", e);
        }
    }

    /** Reverse of {@link #encrypt}. Re-parses the key from DER each call. */
    public static byte[] decrypt(CryptoPackage zPackage, byte[] zRsaPrivateDer) {
        return decrypt(zPackage, privateKeyFromDer(zRsaPrivateDer));
    }

    /**
     * Reverse of {@link #encrypt}, with a PRE-PARSED key.
     *
     * CONSTANT-BEHAVIOUR w.r.t. RSA padding, the TLS Bleichenbacher
     * countermeasure. A server that decrypts attacker-chosen ciphertext with its
     * private key is a padding oracle if the code does visibly different work on
     * conformant vs non-conformant PKCS#1 padding. Here, if the RSA unwrap throws
     * (bad padding) we substitute a RANDOM 16-byte AES key and continue through
     * the AES step regardless, so the two paths run the same operations and take
     * the same time; the caller sees a decrypt failure either way (the random key
     * makes the AES/PKCS5 unpad fail), never a distinguishable padding signal.
     */
    public static byte[] decrypt(CryptoPackage zPackage, java.security.PrivateKey zPrivate) {
        byte[] aesKeyBytes;
        boolean rsaOk;
        try {
            Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.DECRYPT_MODE, zPrivate);
            aesKeyBytes = rsa.doFinal(zPackage.mSecret.getBytes());
            rsaOk = true;
        } catch (Exception paddingOrKeyError) {
            // Do NOT branch out here - fall through with a random key so the AES
            // work below happens identically whether or not padding was valid.
            aesKeyBytes = randomBytes(16);
            rsaOk = false;
        }
        // A recovered key of a non-AES length would make Cipher.init throw and
        // reveal (via a different exception path) that RSA succeeded - normalise
        // it to 16 bytes so init always runs the same way.
        if (aesKeyBytes.length != 16 && aesKeyBytes.length != 24 && aesKeyBytes.length != 32) {
            aesKeyBytes = java.util.Arrays.copyOf(aesKeyBytes, 16);
        }
        try {
            Cipher aes = Cipher.getInstance(AES_TRANSFORM);
            aes.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKeyBytes, "AES"),
                    new IvParameterSpec(zPackage.mIvParam.getBytes()));
            byte[] out = aes.doFinal(zPackage.mData.getBytes());
            if (!rsaOk) {
                // We did the AES work for timing symmetry; the result is
                // meaningless. Fail the same as a genuine decrypt failure.
                throw new IllegalStateException("Maxima decrypt failed");
            }
            return out;
        } catch (IllegalStateException rethrow) {
            throw rethrow;
        } catch (Exception e) {
            throw new IllegalStateException("Maxima decrypt failed", e);
        }
    }

    public static byte[] randomBytes(int zLen) {
        byte[] b = new byte[zLen];
        RANDOM.nextBytes(b);
        return b;
    }
}
