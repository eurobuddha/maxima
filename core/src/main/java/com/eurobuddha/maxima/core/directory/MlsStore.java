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

        Entry(String zKey, List<String> zAddresses, List<String> zAllowed, long zStoredAt) {
            publicKey = zKey;
            addresses = zAddresses;
            allowedReaders = zAllowed;
            storedAt = zStoredAt;
        }

        public String primary() {
            return addresses.isEmpty() ? null : addresses.get(0);
        }
    }

    private final Map<String, Entry> mEntries = new ConcurrentHashMap<>();
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
        mEntries.put(norm(zSignerPublicKey), new Entry(
                norm(zSignerPublicKey),
                new ArrayList<>(zAddresses),
                new ArrayList<>(zAllowedReaders),
                System.currentTimeMillis()));
    }

    /**
     * Resolve, subject to authorisation.
     *
     * @param zRequester who is asking, uppercase 0x hex
     * @return the entry, or null if unknown, expired, or not permitted
     */
    public Entry get(String zTargetPublicKey, String zRequester) {
        Entry e = mEntries.get(norm(zTargetPublicKey));
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.storedAt > mTtlMs) {
            mEntries.remove(norm(zTargetPublicKey));
            return null;
        }
        if (isPermanent(e.publicKey)) {
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
        int n = 0;
        for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
            if (now - e.getValue().storedAt > mTtlMs) {
                mEntries.remove(e.getKey());
                n++;
            }
        }
        return n;
    }

    public int size() {
        return mEntries.size();
    }

    private static String norm(String zKey) {
        return zKey == null ? "" : zKey.trim().toUpperCase();
    }
}
