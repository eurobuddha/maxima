package com.eurobuddha.maxima.core.media;

import com.eurobuddha.maxima.core.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The small thing that travels so the big thing doesn't.
 *
 * A media file never rides the message channel: it is encrypted once, split
 * into content-addressed chunks, and parked on the owner's node + a couple of
 * relays. What the recipient receives is THIS — key, nonce, the chunk ids, and
 * where to look — sealed end-to-end inside a normal Maxima message. Holding a
 * chunk without the manifest is holding noise.
 *
 * Fields (flat JSON via util/Json):
 *   v       1
 *   mime    content type
 *   size    plaintext byte count
 *   key     AES-256 key, 0x hex   (fresh per file)
 *   nonce   GCM IV, 0x hex        (fresh per file)
 *   sha3    SHA3-256 of the PLAINTEXT - end-to-end integrity after reassembly
 *   chunks  CSV of chunk ids (SHA3-256 of each ciphertext chunk)
 *   src     CSV of places to fetch from: the owner's contact/direct address
 *           and the relay addresses holding replicas (best-first)
 */
public final class MediaManifest {

    public final String mime;
    public final long size;
    public final String keyHex;
    public final String nonceHex;
    public final String sha3Hex;
    public final List<String> chunkIds;
    public final List<String> sources;

    public MediaManifest(String zMime, long zSize, String zKeyHex, String zNonceHex,
                         String zSha3Hex, List<String> zChunkIds, List<String> zSources) {
        mime = zMime;
        size = zSize;
        keyHex = zKeyHex;
        nonceHex = zNonceHex;
        sha3Hex = zSha3Hex;
        chunkIds = zChunkIds;
        sources = zSources;
    }

    public String encode() {
        return new Json.Writer()
                .put("v", "1")
                .put("mime", mime)
                .put("size", Long.toString(size))
                .put("key", keyHex)
                .put("nonce", nonceHex)
                .put("sha3", sha3Hex)
                .put("chunks", String.join(",", chunkIds))
                .put("src", String.join(",", sources))
                .done();
    }

    /** Parse; null on anything malformed (never throws into a caller). */
    public static MediaManifest decode(String zJson) {
        try {
            Map<String, String> m = Json.parse(zJson);
            if (!"1".equals(m.get("v"))) {
                return null;
            }
            long size = Long.parseLong(m.get("size"));
            List<String> chunks = csv(m.get("chunks"));
            if (size <= 0 || chunks.isEmpty()) {
                return null;
            }
            return new MediaManifest(m.getOrDefault("mime", "application/octet-stream"),
                    size, m.get("key"), m.get("nonce"), m.get("sha3"),
                    chunks, csv(m.get("src")));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> csv(String zCsv) {
        List<String> out = new ArrayList<>();
        if (zCsv == null || zCsv.isEmpty()) {
            return out;
        }
        for (String s : zCsv.split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
