package com.eurobuddha.maxima.core.media;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.store.BlobStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Publish and fetch media the self-hosted way — "your phone is the server".
 *
 * PUBLISH: encrypt once, keep every chunk in the OWNER's own {@link BlobStore}
 * (the source of truth), and replicate to a few attached relays so the media
 * survives the phone sleeping. The manifest's {@code src} lists those relays,
 * best-first, plus the owner's own address for a direct fetch when reachable.
 *
 * FETCH: try, per chunk, the owner's direct endpoint first (no relay, no
 * internet if on the same LAN), then each relay in the manifest, then any relay
 * we happen to be attached to. Every chunk is hash-checked on arrival and the
 * whole file is GCM+SHA3 verified by {@link MediaCodec} — a bad source can cost
 * a retry but can never corrupt the result. k-of-n redundancy without trust.
 */
public final class MediaService {

    /** How many relays a publish replicates each chunk to (owner + this many). */
    public static final int REPLICAS = 2;

    private final MaximaNode mNode;
    private final BlobStore mLocal;

    public MediaService(MaximaNode zNode, BlobStore zLocalStore) {
        mNode = zNode;
        mLocal = zLocalStore;
    }

    /**
     * Encrypt, keep locally, replicate, and return a manifest ready to seal into
     * a message. Blocking (network + crypto) — call off the main thread.
     */
    public MediaManifest publish(byte[] zPlain, String zMime) throws Exception {
        MediaCodec.Encoded enc = MediaCodec.encrypt(zPlain, zMime);

        // 1. keep everything ourselves — we are the source of truth.
        if (mLocal != null) {
            for (byte[] chunk : enc.chunks) {
                mLocal.put(chunk);
            }
        }

        // 2. replicate to a few attached relays (best-effort).
        List<String> relayAddrs = attachedRelayAddresses();
        List<String> usedRelays = new ArrayList<>();
        for (String relay : relayAddrs) {
            if (usedRelays.size() >= REPLICAS) {
                break;
            }
            boolean allOk = true;
            for (byte[] chunk : enc.chunks) {
                allOk &= MediaWire.put(mNode, relay, chunk);
            }
            if (allOk) {
                usedRelays.add(relay);
            }
        }

        // 3. sources: our own address (direct fetch) first, then the replicas.
        List<String> sources = new ArrayList<>();
        String mine = ownAddress();
        if (mine != null) {
            sources.add(mine);
        }
        sources.addAll(usedRelays);

        return new MediaManifest(enc.manifest.mime, enc.manifest.size,
                enc.manifest.keyHex, enc.manifest.nonceHex, enc.manifest.sha3Hex,
                enc.manifest.chunkIds, sources);
    }

    /** Fetch + verify + decrypt. Blocking — call off the main thread. */
    public byte[] fetch(MediaManifest zManifest) throws Exception {
        return MediaCodec.decrypt(zManifest, id -> fetchChunk(id, zManifest.sources));
    }

    // ---------------------------------------------------------------

    /** One chunk, trying local, then the manifest's sources, then any relay. */
    private byte[] fetchChunk(String zChunkId, List<String> zSources) {
        // local first (we published it, or already cached it)
        if (mLocal != null) {
            byte[] b = mLocal.get(zChunkId);
            if (b != null) {
                return b;
            }
        }
        // the manifest's sources, in order (owner-direct first, then replicas)
        for (String src : zSources) {
            byte[] b = MediaWire.get(mNode, src, zChunkId);
            if (b != null) {
                cacheLocally(b);
                return b;
            }
        }
        // last resort: any relay we're attached to might hold a replica
        for (String relay : attachedRelayAddresses()) {
            byte[] b = MediaWire.get(mNode, relay, zChunkId);
            if (b != null) {
                cacheLocally(b);
                return b;
            }
        }
        return null;
    }

    private void cacheLocally(byte[] zChunk) {
        if (mLocal != null) {
            try {
                mLocal.put(zChunk);
            } catch (Exception ignored) {
            }
        }
    }

    private List<String> attachedRelayAddresses() {
        List<String> out = new ArrayList<>();
        for (String hp : mNode.pool().activeHosts()) {
            com.eurobuddha.maxima.core.net.HostConnection c = mNode.pool().connection(hp);
            if (c != null && c.getTheirMlsAddress() != null) {
                out.add(c.getTheirMlsAddress());
            }
        }
        return out;
    }

    /** Our own reachable address to advertise as a direct source, or null. */
    private String ownAddress() {
        String direct = mNode.directAddress();
        if (direct != null && !direct.isEmpty()) {
            // A proven public endpoint - peers can dial us for chunks directly.
            return direct;
        }
        List<String> mine = mNode.myAddresses();
        return mine.isEmpty() ? null : mine.get(0);
    }
}
