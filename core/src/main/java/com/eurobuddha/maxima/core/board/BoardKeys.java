package com.eurobuddha.maxima.core.board;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.security.KeyPair;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two keys a board runs on.
 *
 * PSEUDONYM - one RSA keypair per (seed, board), derived exactly like the per-relay routing keys
 * ({@link MaximaIdentity#hostKey}). It signs every post and is the only key a relay ever sees, so
 * a relay cannot link a member to a MAX# identity nor to the same person on another board. Being
 * derived, it is identical on every device holding the phrase.
 *
 * BOARD KEY - a 32-byte AES-256-GCM key shared by the members, numbered by epoch. The admin hands
 * it out in an invite link or sealed to a member's pseudonym, and rotates it (next epoch) when a
 * member is removed. Relays only ever hold ciphertext.
 */
public final class BoardKeys {

    /** Frozen: changing it changes every member's pseudonym on every board. */
    public static final String CTX_BOARD_PREFIX = "board|";
    public static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private BoardKeys() {
    }

    /** The pseudonym this identity uses on one board. ~25-80 ms; callers cache it. */
    public static KeyPair pseudonym(MaximaIdentity zIdentity, byte[] zBoardId) {
        return DeterministicRsa.derive(zIdentity.seed().getBytes(),
                CTX_BOARD_PREFIX + new MiniData(zBoardId).to0xString());
    }

    /** The one reader pseudonym per identity, used to sign pulls: relays see a reader key, never
     *  the identity, and cannot tie it to the per-board posting pseudonyms. */
    public static KeyPair reader(MaximaIdentity zIdentity) {
        return DeterministicRsa.derive(zIdentity.seed().getBytes(), CTX_BOARD_PREFIX + "reader");
    }

    public static byte[] newBoardId() {
        return MaximaCrypto.randomBytes(BoardPost.BOARD_BYTES);
    }

    public static byte[] newPostId() {
        return MaximaCrypto.randomBytes(BoardPost.ID_BYTES);
    }

    public static byte[] newBoardKey() {
        return MaximaCrypto.randomBytes(KEY_BYTES);
    }

    /** iv || ciphertext+tag. A fresh random IV per post; GCM authenticates the whole blob. */
    public static byte[] seal(byte[] zKey, byte[] zPlain) {
        try {
            byte[] iv = MaximaCrypto.randomBytes(IV_BYTES);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(zKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(zPlain);
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("board seal failed", e);
        }
    }

    /** Reverse of {@link #seal}; null when the key is wrong or the blob was altered. */
    public static byte[] open(byte[] zKey, byte[] zBlob) {
        if (zKey == null || zBlob == null || zBlob.length <= IV_BYTES) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(zKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, zBlob, 0, IV_BYTES));
            return c.doFinal(zBlob, IV_BYTES, zBlob.length - IV_BYTES);
        } catch (Exception e) {
            return null;
        }
    }
}
