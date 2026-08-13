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

    /** Reverse of {@link #encrypt}. */
    public static byte[] decrypt(CryptoPackage zPackage, byte[] zRsaPrivateDer) {
        try {
            Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.DECRYPT_MODE, privateKeyFromDer(zRsaPrivateDer));
            byte[] aesKeyBytes = rsa.doFinal(zPackage.mSecret.getBytes());

            Cipher aes = Cipher.getInstance(AES_TRANSFORM);
            aes.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKeyBytes, "AES"),
                    new IvParameterSpec(zPackage.mIvParam.getBytes()));
            return aes.doFinal(zPackage.mData.getBytes());

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
