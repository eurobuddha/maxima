package com.eurobuddha.maxima.core.media;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;

import java.nio.charset.StandardCharsets;

/**
 * The relay blob service's wire vocabulary + client sends — how chunks park on
 * and return from a relay's {@code BlobStore}.
 *
 * Three application strings, answered by a relay for messages addressed to it
 * (the probe/MLS/gossip pattern — no new message types):
 *
 *   put   payload = the raw ciphertext chunk. Gated: the putting connection
 *         must hold a registered route (attached users only — the same
 *         anti-flood posture as the mailbox), rate-limited per source.
 *         Reply: the chunk id in the ping body (sender verifies it matches).
 *   get   payload = chunk id. Reply: the chunk in the ping body, or UNKNOWN.
 *         Unauthenticated by design, like MaxLite's /blob: an id is only ever
 *         learned from a sealed manifest, and the bytes are ciphertext.
 *   has   payload = chunk id. OK / UNKNOWN ack.
 *
 * Chunks are ciphertext addressed by their own hash: the relay can neither
 * read nor substitute anything, and re-put is idempotent.
 */
public final class MediaWire {

    public static final String APP_PUT = "**maxima_blob_put**";
    public static final String APP_GET = "**maxima_blob_get**";
    public static final String APP_HAS = "**maxima_blob_has**";

    private MediaWire() {
    }

    /** Park one chunk on a relay. Returns true when the relay confirmed the id. */
    public static boolean put(MaximaNode zNode, String zRelayAddr, byte[] zChunk) {
        try {
            MaximaSender.Result r = zNode.sendRaw(zRelayAddr, APP_PUT, zChunk);
            if (!r.isOk() || !r.hasPayload()) {
                return false;
            }
            String confirmed = new String(r.replyData.getBytes(), StandardCharsets.UTF_8).trim();
            return confirmed.equalsIgnoreCase(
                    com.eurobuddha.maxima.core.store.BlobStore.idOf(zChunk));
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetch one chunk from a relay; null when it does not hold it. */
    public static byte[] get(MaximaNode zNode, String zRelayAddr, String zChunkId) {
        try {
            MaximaSender.Result r = zNode.sendRaw(zRelayAddr, APP_GET,
                    zChunkId.getBytes(StandardCharsets.UTF_8));
            if (r.isOk() && r.hasPayload()) {
                byte[] chunk = r.replyData.getBytes();
                // never accept bytes that do not hash to the id we asked for
                if (com.eurobuddha.maxima.core.store.BlobStore.idOf(chunk)
                        .equalsIgnoreCase(norm(zChunkId))) {
                    return chunk;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Does the relay hold this chunk? */
    public static boolean has(MaximaNode zNode, String zRelayAddr, String zChunkId) {
        try {
            return zNode.sendRaw(zRelayAddr, APP_HAS,
                    zChunkId.getBytes(StandardCharsets.UTF_8)).isOk();
        } catch (Exception e) {
            return false;
        }
    }

    private static String norm(String zId) {
        String id = zId.trim();
        return id.startsWith("0x") || id.startsWith("0X") ? id : "0x" + id;
    }

    /** Is this one of ours? (Reserved from external IPC apps by the transport.) */
    public static boolean isMediaApp(String zApplication) {
        return APP_PUT.equals(zApplication) || APP_GET.equals(zApplication)
                || APP_HAS.equals(zApplication);
    }
}
