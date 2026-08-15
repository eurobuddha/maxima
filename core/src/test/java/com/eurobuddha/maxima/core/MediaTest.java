package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.media.MediaCodec;
import com.eurobuddha.maxima.core.media.MediaManifest;
import com.eurobuddha.maxima.core.store.BlobStore;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The media layer's promises, tested as promises: what you reassemble is
 * byte-for-byte what was published or you get an exception; chunks fit the
 * wire; the shelf is bounded and self-cleaning.
 */
public class MediaTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== MEDIA LAYER ===\n");

        // ---- round trip, multi-chunk ----
        byte[] media = new byte[500 * 1024];   // 3 chunks worth of "video"
        for (int i = 0; i < media.length; i++) media[i] = (byte) (i * 31 + 7);

        MediaCodec.Encoded enc = MediaCodec.encrypt(media, "video/mp4");
        if (enc.chunks.size() == 3 && enc.manifest.chunkIds.size() == 3) {
            ok("500KB encrypts into 3 chunks of <=192KiB ciphertext");
        } else {
            bad("chunking: " + enc.chunks.size());
        }
        // every chunk under the wire ceiling WITH envelope room
        boolean sized = true;
        for (byte[] c : enc.chunks) sized &= c.length <= MediaCodec.CHUNK_CIPHERTEXT;
        if (sized) {
            ok("every chunk is <= CHUNK_CIPHERTEXT");
        } else {
            bad("oversized chunk");
        }

        // manifest survives its JSON round trip
        MediaManifest mf = MediaManifest.decode(enc.manifest.encode());
        if (mf != null && mf.size == media.length && mf.chunkIds.equals(enc.manifest.chunkIds)
                && mf.keyHex.equals(enc.manifest.keyHex)) {
            ok("manifest round-trips through its JSON");
        } else {
            bad("manifest round trip");
        }

        // in-memory source: reassemble + prove
        Map<String, byte[]> net = new HashMap<>();
        for (byte[] c : enc.chunks) net.put(BlobStore.idOf(c).toUpperCase(), c);
        MediaCodec.ChunkSource src = id -> net.get(id.toUpperCase());

        byte[] back = MediaCodec.decrypt(mf, src);
        if (java.util.Arrays.equals(back, media)) {
            ok("reassembled plaintext is byte-for-byte the original");
        } else {
            bad("round trip mismatch");
        }

        // ---- tamper: a flipped byte inside a chunk must NOT decrypt ----
        byte[] evil = net.get(mf.chunkIds.get(1).toUpperCase()).clone();
        evil[100] ^= 0x01;
        Map<String, byte[]> tampered = new HashMap<>(net);
        tampered.put(mf.chunkIds.get(1).toUpperCase(), evil);
        try {
            MediaCodec.decrypt(mf, id -> tampered.get(id.toUpperCase()));
            bad("tampered chunk was accepted");
        } catch (Exception e) {
            ok("a tampered chunk is rejected (" + e.getClass().getSimpleName() + ")");
        }

        // wrong key in the manifest -> GCM refuses
        MediaManifest wrongKey = new MediaManifest(mf.mime, mf.size,
                "0x" + "AB".repeat(32), mf.nonceHex, mf.sha3Hex, mf.chunkIds, mf.sources);
        try {
            MediaCodec.decrypt(wrongKey, src);
            bad("wrong key decrypted");
        } catch (Exception e) {
            ok("a wrong key cannot decrypt (GCM auth)");
        }

        // a missing chunk is an error, not silence
        Map<String, byte[]> partial = new HashMap<>(net);
        partial.remove(mf.chunkIds.get(2).toUpperCase());
        try {
            MediaCodec.decrypt(mf, id -> partial.get(id.toUpperCase()));
            bad("missing chunk went unnoticed");
        } catch (Exception e) {
            ok("a missing chunk fails loudly");
        }

        // ---- the wire-envelope guarantee ----
        MaximaIdentity a = idFrom(3);
        MaximaIdentity b = idFrom(5);
        byte[] full = new byte[MediaCodec.CHUNK_CIPHERTEXT];
        try {
            MaximaSender.build(a.publicKey(), a.keyPair().getPrivate(),
                    b.publicKey(), "**maxima_blob_put**", full, System.currentTimeMillis());
            ok("a full 192KiB chunk fits a MaximaPackage with envelope room");
        } catch (IllegalArgumentException e) {
            bad("chunk + envelope exceeds the wire ceiling: " + e.getMessage());
        }

        // ---- BlobStore ----
        File dir = Files.createTempDirectory("blobtest").toFile();
        BlobStore store = new BlobStore(dir, 300 * 1024);   // tiny cap: 300KB

        byte[] c1 = enc.chunks.get(0);
        String id1 = store.put(c1);
        if (id1.equals(BlobStore.idOf(c1)) && store.has(id1)
                && java.util.Arrays.equals(store.get(id1), c1)) {
            ok("BlobStore stores and returns a chunk by its content id");
        } else {
            bad("blobstore basic");
        }
        String again = store.put(c1);
        if (again.equals(id1) && store.count() == 1) {
            ok("re-putting identical content is idempotent");
        } else {
            bad("idempotency");
        }
        // cap: adding a second 192KiB chunk to a 300KB store must evict the first
        Thread.sleep(20);   // mtime resolution
        String id2 = store.put(enc.chunks.get(1));
        if (store.has(id2) && !store.has(id1) && store.bytes() <= 300 * 1024) {
            ok("the byte cap holds: least-recently-fetched chunk was evicted");
        } else {
            bad("LRU eviction (has1=" + store.has(id1) + " has2=" + store.has(id2)
                    + " bytes=" + store.bytes() + ")");
        }
        // junk ids are rejected before touching the filesystem
        try {
            store.get("../../etc/passwd");
            bad("path-traversal id accepted");
        } catch (IllegalArgumentException e) {
            ok("a malformed chunk id is rejected outright");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Media layer holds.");
    }

    static MaximaIdentity idFrom(int s) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * s + s);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }
}
