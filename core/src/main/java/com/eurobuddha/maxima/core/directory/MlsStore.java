package com.eurobuddha.maxima.core.directory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The directory: identity public key -> current contact address(es).
 *
 * Entries expire, because an address published by a node that has since
 * vanished is worse than no answer at all - it sends people to a dead relay.
 *
 * Authorisation is per-entry: the publisher lists which identities may resolve
 * it. That keeps the directory from becoming a public map of who exists.
 */
public final class MlsStore {

    /** Reference flushes after 24h. */
    public static final long DEFAULT_TTL_MS = 24L * 60 * 60 * 1000;

    public static final class Entry {
        public final String publicKey;
        public final List<String> addresses;
        public final List<String> allowedReaders;
        public final long storedAt;
        /** Absolute expiry (per-entry, so a forwarded/cached entry can expire sooner
         *  than a locally-published one). */
        public final long expiresAt;
        /**
         * The signed proof this entry was published with, so ANY relay can verify it
         * without having received the original SET directly (the Phase-B mesh). Null
         * for entries published without a proof (e.g. the RPC publish path) — those
         * are usable locally but cannot be trusted/forwarded across relays. The triplet
         * is the verified MaximaInternal envelope: {@code proofFrom} = publisher key DER,
         * {@code proofPayload} = the signed MaximaMessage bytes, {@code proofSig} = the
         * signature over the payload. Re-verify with
         * {@code MaximaCrypto.verify(proofFrom, proofPayload, proofSig)}.
         */
        public final byte[] proofFrom;
        public final byte[] proofPayload;
        public final byte[] proofSig;

        Entry(String zKey, List<String> zAddresses, List<String> zAllowed,
              long zStoredAt, long zExpiresAt,
              byte[] zProofFrom, byte[] zProofPayload, byte[] zProofSig) {
            publicKey = zKey;
            addresses = zAddresses;
            allowedReaders = zAllowed;
            storedAt = zStoredAt;
            expiresAt = zExpiresAt;
            proofFrom = zProofFrom;
            proofPayload = zProofPayload;
            proofSig = zProofSig;
        }

        public String primary() {
            return addresses.isEmpty() ? null : addresses.get(0);
        }

        /** True when this entry carries a signed proof another relay can verify. */
        public boolean hasProof() {
            return proofFrom != null && proofPayload != null && proofSig != null;
        }
    }

    /**
     * Cap on directory entries, LRU-evicted.
     *
     * Each SET is signed by a FRESH identity (free to mint, PoW unverified), so
     * without a cap an attacker publishes unboundedly many entries that are
     * never resolved and never expire (flushExpired must be driven, and the TTL
     * only matters on read) - a slow memory-exhaustion. LRU + the maintenance
     * flush keep it bounded.
     */
    public static final int DEFAULT_MAX_ENTRIES = 200_000;
    /** Cap on addresses per entry, so one SET cannot carry tens of thousands. */
    public static final int MAX_ADDRESSES = 8;
    /** Allowed READERS are a different thing from addresses: one per contact. Capping them at
     *  MAX_ADDRESSES (as before) meant only the first 8 contacts could ever resolve you on a
     *  directory that is not in open-resolve mode. */
    public static final int MAX_READERS = 2048;

    /** Access-order for LRU; eviction is manual in {@link #put} (see the cap). */
    private final Map<String, Entry> mEntries = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(1024, 0.75f, true));
    private final long mTtlMs;
    /** Identities allowed to be resolved by anyone (the "permanent" list). */
    private final List<String> mPermanent = new ArrayList<>();

    public MlsStore() {
        this(DEFAULT_TTL_MS);
    }

    public MlsStore(long zTtlMs) {
        mTtlMs = zTtlMs;
    }

    /**
     * Publish. Keyed by the SIGNER's key, never by anything inside the packet -
     * otherwise anyone could overwrite anyone's directory entry.
     */
    public void put(String zSignerPublicKey, List<String> zAddresses, List<String> zAllowedReaders) {
        put(zSignerPublicKey, zAddresses, zAllowedReaders, null, null, null, mTtlMs);
    }

    /**
     * Signed publish with an explicit TTL. Retains the proof triplet (the verified
     * MaximaInternal envelope) so another relay can verify this entry after it is
     * forwarded (the Phase-B mesh); {@code zTtlMs} lets a forwarded/cached entry expire
     * sooner than a locally-published one. Pass null proof + {@link #DEFAULT_TTL_MS}
     * for an ordinary unsigned local publish.
     */
    public void put(String zSignerPublicKey, List<String> zAddresses, List<String> zAllowedReaders,
                    byte[] zProofFrom, byte[] zProofPayload, byte[] zProofSig, long zTtlMs) {
        // Bound the per-entry lists so one SET cannot carry a huge address blob.
        List<String> addrs = zAddresses.size() > MAX_ADDRESSES
                ? new ArrayList<>(zAddresses.subList(0, MAX_ADDRESSES))
                : new ArrayList<>(zAddresses);
        List<String> readers = zAllowedReaders.size() > MAX_READERS
                ? new ArrayList<>(zAllowedReaders.subList(0, MAX_READERS))
                : new ArrayList<>(zAllowedReaders);
        long now = System.currentTimeMillis();
        synchronized (mEntries) {
            mEntries.put(norm(zSignerPublicKey), new Entry(
                    norm(zSignerPublicKey), addrs, readers, now, now + zTtlMs,
                    zProofFrom, zProofPayload, zProofSig));
            // LRU cap: evict the least-recently-accessed while over the limit.
            java.util.Iterator<String> it = mEntries.keySet().iterator();
            while (mEntries.size() > DEFAULT_MAX_ENTRIES && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /**
     * Resolve, subject to authorisation.
     *
     * @param zRequester who is asking, uppercase 0x hex
     * @return the entry, or null if unknown, expired, or not permitted
     */
    /**
     * OPEN-RESOLVE mode: every stored identity resolves for ANY requester -
     * the semantics of a public pool MLS (like DNS). Off by default; a relay
     * operator opts in. Users opt in per-identity by PINNING an open server
     * as their static MLS - a rotating user never publishes here at all.
     */
    private volatile boolean mOpenResolve = false;

    public void setOpenResolve(boolean zOpen) {
        mOpenResolve = zOpen;
    }

    public Entry get(String zTargetPublicKey, String zRequester) {
        Entry e = mEntries.get(norm(zTargetPublicKey));
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() > e.expiresAt) {
            mEntries.remove(norm(zTargetPublicKey));
            return null;
        }
        if (mOpenResolve || isPermanent(e.publicKey)) {
            return e;
        }
        for (String a : e.allowedReaders) {
            if (norm(a).equals(norm(zRequester))) {
                return e;
            }
        }
        return null;
    }

    /** Lookup with no authorisation check - for our own local cache only. */
    public Entry peek(String zTargetPublicKey) {
        return mEntries.get(norm(zTargetPublicKey));
    }

    public void addPermanent(String zPublicKey) {
        mPermanent.add(norm(zPublicKey));
    }

    public boolean isPermanent(String zPublicKey) {
        return mPermanent.contains(norm(zPublicKey));
    }

    public int flushExpired() {
        long now = System.currentTimeMillis();
        // mEntries is a synchronizedMap over an access-order LinkedHashMap:
        // iterating without the lock and removing mid-iteration throws CME
        // (and even a concurrent get() is a structural mod here). Collect
        // expired keys under the lock, then remove - so expiry actually runs.
        java.util.List<String> expired = new java.util.ArrayList<>();
        synchronized (mEntries) {
            for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
                if (now > e.getValue().expiresAt) {
                    expired.add(e.getKey());
                }
            }
            for (String k : expired) {
                mEntries.remove(k);
            }
        }
        return expired.size();
    }

    public int size() {
        return mEntries.size();
    }

    private static String norm(String zKey) {
        return zKey == null ? "" : zKey.trim().toUpperCase();
    }
}
