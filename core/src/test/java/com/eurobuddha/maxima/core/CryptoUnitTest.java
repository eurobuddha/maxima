package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.crypto.Hkdf;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.CryptoPackage;

import java.security.KeyPair;
import java.util.Arrays;

/**
 * The crypto layer: signatures, the sealed envelope, key derivation and hashes.
 *
 * These are the guarantees the whole security model rests on - a signature that
 * verifies over the wrong bytes, or a decrypt that leaks, or a non-deterministic
 * key derivation, each breaks a different promise. Verify the promises directly.
 */
public class CryptoUnitTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static MaximaIdentity idFrom(int s) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * s + s);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== CRYPTO ===\n");

        MaximaIdentity a = idFrom(1);
        MaximaIdentity b = idFrom(2);
        byte[] data = "the message under signature".getBytes();

        // ---- sign / verify ----
        byte[] sig = MaximaCrypto.sign(a.keyPair().getPrivate(), data);
        if (MaximaCrypto.verify(a.publicKey(), data, sig)) {
            ok("a signature verifies under the signer's public key");
        } else {
            bad("valid signature did not verify");
        }
        if (!MaximaCrypto.verify(b.publicKey(), data, sig)) {
            ok("the signature does NOT verify under a different key");
        } else {
            bad("signature verified under the wrong key");
        }
        byte[] tampered = data.clone();
        tampered[0] ^= 0x01;
        if (!MaximaCrypto.verify(a.publicKey(), tampered, sig)) {
            ok("the signature does NOT verify over tampered data");
        } else {
            bad("signature verified over tampered data");
        }
        // verify must FAIL CLOSED on garbage, never throw-through as accept
        if (!MaximaCrypto.verify(new byte[]{1, 2, 3}, data, sig)
                && !MaximaCrypto.verify(a.publicKey(), data, new byte[]{9, 9})) {
            ok("verify fails closed on a malformed key or signature (no exception-accept)");
        } else {
            bad("verify accepted malformed input");
        }

        // ---- encrypt / decrypt (the sealed envelope) ----
        byte[] plain = "sealed to a public key".getBytes();
        CryptoPackage cp = MaximaCrypto.encrypt(plain, b.publicKey());
        byte[] out = MaximaCrypto.decrypt(cp, b.keyPair().getPrivate().getEncoded());
        if (Arrays.equals(out, plain)) {
            ok("encrypt->decrypt round-trips with the right private key");
        } else {
            bad("encrypt/decrypt round trip");
        }
        // wrong key cannot decrypt, and fails the same way regardless of padding
        boolean wrongFailed = false;
        try {
            MaximaCrypto.decrypt(cp, a.keyPair().getPrivate().getEncoded());
        } catch (IllegalStateException e) {
            wrongFailed = "Maxima decrypt failed".equals(e.getMessage());
        }
        if (wrongFailed) {
            ok("decrypt with the wrong key fails uniformly (no padding oracle)");
        } else {
            bad("decrypt with wrong key did not fail uniformly");
        }
        // fresh IV + key per encryption: two seals of the same plaintext differ
        CryptoPackage cp2 = MaximaCrypto.encrypt(plain, b.publicKey());
        if (!Arrays.equals(cp.mIvParam.getBytes(), cp2.mIvParam.getBytes())
                && !Arrays.equals(cp.mData.getBytes(), cp2.mData.getBytes())) {
            ok("each seal uses a fresh IV and key (no CBC IV reuse)");
        } else {
            bad("IV or ciphertext repeated across two seals");
        }

        // ---- HKDF ----
        byte[] seed = "a-secret-seed".getBytes();
        byte[] k1 = Hkdf.derive(seed, "info-A", 32);
        byte[] k1b = Hkdf.derive(seed, "info-A", 32);
        byte[] k2 = Hkdf.derive(seed, "info-B", 32);
        if (k1.length == 32 && Arrays.equals(k1, k1b) && !Arrays.equals(k1, k2)) {
            ok("HKDF is deterministic per (seed,info) and diverges on a different info");
        } else {
            bad("HKDF determinism/divergence");
        }
        if (Hkdf.derive(seed, "x", 16).length == 16 && Hkdf.derive(seed, "x", 100).length == 100) {
            ok("HKDF returns exactly the requested length");
        } else {
            bad("HKDF length");
        }

        // ---- deterministic RSA ----
        KeyPair kp1 = DeterministicRsa.derive(seed, "ctx-1");
        KeyPair kp2 = DeterministicRsa.derive(seed, "ctx-1");
        KeyPair kp3 = DeterministicRsa.derive(seed, "ctx-2");
        if (Arrays.equals(kp1.getPublic().getEncoded(), kp2.getPublic().getEncoded())) {
            ok("deterministic RSA reproduces the SAME key for the same (seed,context)");
        } else {
            bad("deterministic RSA not reproducible");
        }
        if (!Arrays.equals(kp1.getPublic().getEncoded(), kp3.getPublic().getEncoded())) {
            ok("a different context derives a different key");
        } else {
            bad("deterministic RSA did not diverge on context");
        }

        // ---- hashes ----
        // SHA3-256("") known answer
        String sha3empty = new com.eurobuddha.maxima.core.codec.MiniData(
                Hashes.sha3(new byte[0])).to0xString();
        if (sha3empty.equalsIgnoreCase(
                "0xA7FFC6F8BF1ED76651C14756A061D662F580FF4DE43B49FA82D80A4B80F8434A")) {
            ok("SHA3-256(\"\") matches the known-answer vector");
        } else {
            bad("SHA3-256 known answer: " + sha3empty);
        }
        // determinism
        byte[] m = "abc".getBytes();
        if (Arrays.equals(Hashes.sha3(m), Hashes.sha3(m))) {
            ok("SHA3 is deterministic");
        } else {
            bad("SHA3 nondeterministic");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Crypto holds.");
    }
}
