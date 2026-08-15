package com.eurobuddha.maxima.core.media;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.store.BlobStore;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypt-once, chunk, and reassemble-with-proof — the media layer's maths.
 *
 * ONE AES-256/GCM pass over the whole file (fresh key + IV per file), then the
 * ciphertext is split at {@link #CHUNK_CIPHERTEXT} so every chunk fits a Maxima
 * package (262144 wire ceiling) with envelope room to spare. GCM's tag
 * authenticates the ciphertext; the manifest's plaintext SHA3 closes the loop
 * after reassembly; and each chunk id IS its hash, so a corrupted or swapped
 * chunk cannot even be accepted from the network, let alone decrypt.
 *
 * FreezePeach precedent (MediaTransport): whole-file secretbox then 256KiB
 * splits, proven to 30MB on phones. We shrink the chunk to fit Maxima's
 * envelope and swap libsodium for JDK GCM — :core stays zero-dependency.
 */
public final class MediaCodec {

    /** Ciphertext bytes per chunk: fits the 262144 wire ceiling + RPC envelope. */
    public static final int CHUNK_CIPHERTEXT = 192 * 1024;

    /** Same protective cap as FreezePeach's reader (Salon reads to 80MB). */
    public static final long MAX_MEDIA_BYTES = 80L * 1024 * 1024;

    public static final int MAX_CHUNKS =
            (int) (MAX_MEDIA_BYTES / CHUNK_CIPHERTEXT) + 2;

    private MediaCodec() {
    }

    /** What {@link #encrypt} hands back: the manifest (sources not yet filled)
     *  plus every ciphertext chunk, in order, each id = sha3(chunk). */
    public static final class Encoded {
        public final MediaManifest manifest;
        public final List<byte[]> chunks;

        Encoded(MediaManifest zManifest, List<byte[]> zChunks) {
            manifest = zManifest;
            chunks = zChunks;
        }
    }

    /** Fetches one chunk by id; null/throw = not available here. */
    public interface ChunkSource {
        byte[] fetch(String zChunkId) throws Exception;
    }

    // ---------------------------------------------------------------

    /** Encrypt + chunk a file. Sources on the returned manifest are EMPTY —
     *  the publisher fills them in after parking the chunks somewhere. */
    public static Encoded encrypt(byte[] zPlain, String zMime) throws Exception {
        if (zPlain == null || zPlain.length == 0) {
            throw new IllegalArgumentException("empty media");
        }
        if (zPlain.length > MAX_MEDIA_BYTES) {
            throw new IllegalArgumentException("media over " + MAX_MEDIA_BYTES + " bytes");
        }
        byte[] key = MaximaCrypto.randomBytes(32);
        byte[] iv = MaximaCrypto.randomBytes(12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] cipherAll = c.doFinal(zPlain);

        List<byte[]> chunks = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int off = 0; off < cipherAll.length; off += CHUNK_CIPHERTEXT) {
            int len = Math.min(CHUNK_CIPHERTEXT, cipherAll.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(cipherAll, off, chunk, 0, len);
            chunks.add(chunk);
            ids.add(BlobStore.idOf(chunk));
        }

        MediaManifest mf = new MediaManifest(zMime, zPlain.length,
                new MiniData(key).to0xString(), new MiniData(iv).to0xString(),
                new MiniData(Hashes.sha3(zPlain)).to0xString(),
                ids, new ArrayList<>());
        return new Encoded(mf, chunks);
    }

    /**
     * Reassemble + decrypt + PROVE. Every chunk is verified against its id as
     * it arrives, GCM authenticates the whole ciphertext, and the plaintext
     * must hash to the manifest's sha3 at the declared size — a lie anywhere
     * is an exception, never bad bytes handed onward.
     */
    public static byte[] decrypt(MediaManifest zManifest, ChunkSource zSource) throws Exception {
        if (zManifest.size > MAX_MEDIA_BYTES || zManifest.chunkIds.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("manifest exceeds media caps");
        }
        java.io.ByteArrayOutputStream cipherAll = new java.io.ByteArrayOutputStream();
        for (String id : zManifest.chunkIds) {
            byte[] chunk = zSource.fetch(id);
            if (chunk == null) {
                throw new IllegalStateException("chunk unavailable: " + id);
            }
            if (!BlobStore.idOf(chunk).equalsIgnoreCase(id)) {
                throw new SecurityException("chunk content does not match its id");
            }
            cipherAll.write(chunk);
            if (cipherAll.size() > MAX_MEDIA_BYTES + (1 << 20)) {
                throw new IllegalStateException("ciphertext exceeds caps");
            }
        }

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(new MiniData(zManifest.keyHex).getBytes(), "AES"),
                new GCMParameterSpec(128, new MiniData(zManifest.nonceHex).getBytes()));
        byte[] plain = c.doFinal(cipherAll.toByteArray());   // throws on any tamper

        if (plain.length != zManifest.size) {
            throw new SecurityException("plaintext size mismatch");
        }
        byte[] digest = Hashes.sha3(plain);
        byte[] expect = new MiniData(zManifest.sha3Hex).getBytes();
        if (!MessageDigest.isEqual(digest, expect)) {
            throw new SecurityException("plaintext hash mismatch");
        }
        return plain;
    }
}
