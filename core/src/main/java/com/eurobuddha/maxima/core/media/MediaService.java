package com.eurobuddha.maxima.core.media;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.store.BlobStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * The honest per-file ceiling for MESH publishing — well under the codec's
     * 80 MB absolute ceiling. Three structural walls make larger files a bad fit
     * for a user-hosted mesh (not a tunable "just raise it" limit):
     *   - relay put rate is capped (anti-flood) — a big file can't replicate in
     *     the publish budget, so it never gets a durable off-phone replica;
     *   - the relay blob shelf is small and shared, evicted least-recently-fetched
     *     first — the mesh is redundancy, not archival storage;
     *   - the owner's phone holds the whole file in heap while chunking, and is
     *     the only guaranteed source when relays evict.
     * Above this, callers must steer the user to server hosting (unlimited,
     * always-on). Enforced in {@link #publish} so it holds for every caller.
     */
    public static final long MAX_MESH_FILE_BYTES = 16L * 1024 * 1024;

    /**
     * Overall wall-clock ceiling for the replication step, well under the IPC
     * client's 90s latch. Normal case returns in a few seconds once REPLICAS
     * confirm; this only bites when the mesh is degraded, and then we return
     * with whatever replicas we have (owner is always a source) rather than
     * letting the client time out.
     */
    public static final long PUBLISH_BUDGET_MS = 55_000L;

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
        // Reject oversize BEFORE any encrypt/chunk/replicate work: the mesh
        // cannot durably host it, so fail in microseconds with a clear message
        // instead of grinding into a client timeout. Callers steer to a server.
        if (zPlain.length > MAX_MESH_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Maxima mesh supports files up to " + (MAX_MESH_FILE_BYTES >> 20)
                    + " MB. This file is " + mib(zPlain.length)
                    + " — host it on a server (SFTP/IPFS/GitHub) or compress it first.");
        }
        MediaCodec.Encoded enc = MediaCodec.encrypt(zPlain, zMime);

        // 1. keep everything ourselves — we are the source of truth.
        if (mLocal != null) {
            for (byte[] chunk : enc.chunks) {
                mLocal.put(chunk);
            }
        }

        // 2. replicate to a few attached relays IN PARALLEL, bounded by an overall
        //    budget. Serial-per-relay blocking replication used to sit in the
        //    client's critical path: chunks x relays round trips, each up to the
        //    chat-grade 20s+20s, so one slow relay or a large image blew the 90s
        //    IPC latch ("maxima media put timeout"). Now relays race each other on
        //    short blob timeouts and we stop as soon as REPLICAS confirm; whatever
        //    hasn't finished by the budget just keeps going best-effort in the
        //    background - the owner already holds every chunk (step 1).
        List<String> usedRelays = replicate(enc);

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

    /**
     * Push every chunk to attached relays in parallel and return the addresses
     * that confirmed a FULL copy within {@link #PUBLISH_BUDGET_MS}. Never throws:
     * an empty result just means the media is local-only for now (still fetchable
     * while the owner is online; background workers may add replicas after we
     * return). One worker per candidate relay, chunks serial within a relay.
     */
    private List<String> replicate(MediaCodec.Encoded zEnc) {
        List<String> candidates = attachedRelayAddresses();
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        // A couple of spares beyond REPLICAS so a single dead relay doesn't cost
        // us the redundancy target.
        final int workers = Math.min(candidates.size(), REPLICAS + 2);
        List<String> confirmed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger finished = new AtomicInteger(0);
        // Settled when EITHER we have REPLICAS confirmed OR every worker has
        // finished (so we never wait the full budget for a relay that can't
        // exist - e.g. only one relay is attached but REPLICAS is 2).
        CountDownLatch settled = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                final String relay = candidates.get(i);
                pool.submit(() -> {
                    try {
                        // Stop early if we already have enough replicas.
                        if (done.get() >= REPLICAS) {
                            return;
                        }
                        boolean allOk = true;
                        for (byte[] chunk : zEnc.chunks) {
                            if (done.get() >= REPLICAS) {
                                allOk = false;   // no longer needed; don't advertise partial
                                break;
                            }
                            if (!MediaWire.put(mNode, relay, chunk)) {
                                allOk = false;
                                break;
                            }
                        }
                        if (allOk) {
                            confirmed.add(relay);
                            if (done.incrementAndGet() >= REPLICAS) {
                                settled.countDown();
                            }
                        }
                    } finally {
                        if (finished.incrementAndGet() >= workers) {
                            settled.countDown();
                        }
                    }
                });
            }
            pool.shutdown();
            //noinspection ResultOfMethodCallIgnored
            settled.await(PUBLISH_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            // Let any winners already recorded stand; interrupt stragglers so we
            // don't leak threads. Their sockets carry the short blob timeout, so
            // they exit within seconds regardless.
            pool.shutdownNow();
        }
        // Snapshot under the lock the synchronizedList guards.
        synchronized (confirmed) {
            return new ArrayList<>(confirmed);
        }
    }

    /** Human "12.3 MB" for an error message. */
    private static String mib(long zBytes) {
        double m = zBytes / (1024.0 * 1024.0);
        return (m >= 10 ? Math.round(m) + "" : (Math.round(m * 10) / 10.0) + "") + " MB";
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
