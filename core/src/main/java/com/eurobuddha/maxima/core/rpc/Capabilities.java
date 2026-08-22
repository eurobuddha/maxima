package com.eurobuddha.maxima.core.rpc;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a peer can do beyond classic Maxima.
 *
 * Advertised in the {@code extradata} blob of the contact-ctrl JSON, which
 * classic nodes carry verbatim and ignore. A peer that advertises nothing IS a
 * classic peer, so every extension degrades to classic behaviour automatically -
 * we never have to probe or guess.
 *
 * Encoded as a compact comma-separated list under one key to keep the contact
 * JSON small (it rides inside a 256KB message alongside everything else).
 */
public final class Capabilities {

    /** The extradata key. Unknown keys are preserved by classic nodes. */
    public static final String KEY = "mxcaps";

    /** Reply-as-new-message request/response. */
    public static final String RPC = "rpc";
    /** End-to-end acks, retry, dedup. */
    public static final String RELIABLE = "rel";
    /** Will hold messages for an offline contact and deliver on reconnect. */
    public static final String MAILBOX = "box";
    /** Serves identity -> current-address lookups. */
    public static final String DIRECTORY = "dir";
    /** Shares learned addresses for mutual contacts. */
    public static final String GOSSIP = "gos";
    /** Stores content-addressed encrypted blobs. */
    public static final String STORAGE = "sto";
    /** Countersigns delivery receipts. */
    public static final String WITNESS = "wit";

    /**
     * Advertised host capacity - how many peers this node is willing to host as
     * a relay/directory. Rides as a {@code cap:<n>} token in the same encoded
     * list, so it degrades to "unspecified" on classic (which ignores mxcaps
     * entirely). It is a MERIT input, not a type flag: a big VPS advertises a
     * large number, a phone a small one, and selection weights by it - the
     * network never asks "phone or server", only "how much can you carry".
     */
    private static final String CAP_PREFIX = "cap:";

    /** Advertised capacity when a peer specifies none (classic / older peer).
     *  Deliberately small: an unknown node is assumed low-capacity until it
     *  proves otherwise, so it is never over-loaded on the strength of nothing. */
    public static final int UNSPECIFIED_CAPACITY = 0;

    private final Set<String> mCaps = new LinkedHashSet<>();
    /** 0 = unspecified (see {@link #capacity()}). */
    private int mCapacity = UNSPECIFIED_CAPACITY;

    public Capabilities(String... zCaps) {
        mCaps.addAll(Arrays.asList(zCaps));
    }

    public static Capabilities none() {
        return new Capabilities();
    }

    /** What a phone can offer from the deepest CGNAT - all of Tier 1. */
    public static Capabilities phoneDefaults() {
        return new Capabilities(RPC, RELIABLE, MAILBOX, DIRECTORY, GOSSIP, STORAGE, WITNESS);
    }

    public Capabilities add(String zCap) {
        mCaps.add(zCap);
        return this;
    }

    /** Advertise how many peers we are willing to host. Fluent. */
    public Capabilities withCapacity(int zCapacity) {
        mCapacity = Math.max(0, zCapacity);
        return this;
    }

    /** Advertised host capacity, or {@link #UNSPECIFIED_CAPACITY} (0) if the
     *  peer said nothing - the scorer treats 0 as a small conservative default. */
    public int capacity() {
        return mCapacity;
    }

    public boolean has(String zCap) {
        return mCaps.contains(zCap);
    }

    public Set<String> all() {
        return mCaps;
    }

    public boolean isClassic() {
        return mCaps.isEmpty();
    }

    /** Comma-separated, for the extradata value. Capacity (when set) rides as a
     *  {@code cap:<n>} token; classic ignores the whole value anyway. */
    public String encode() {
        String flags = String.join(",", mCaps);
        if (mCapacity <= 0) {
            return flags;
        }
        return flags.isEmpty() ? CAP_PREFIX + mCapacity
                : flags + "," + CAP_PREFIX + mCapacity;
    }

    public static Capabilities decode(String zValue) {
        Capabilities c = new Capabilities();
        if (zValue == null || zValue.trim().isEmpty()) {
            return c;
        }
        for (String s : zValue.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith(CAP_PREFIX)) {
                try {
                    c.mCapacity = Math.max(0, Integer.parseInt(t.substring(CAP_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // A malformed capacity is simply "unspecified" - never fatal.
                }
                continue;
            }
            c.mCaps.add(t);
        }
        return c;
    }

    @Override
    public String toString() {
        return mCaps.isEmpty() ? "(classic)" : encode();
    }
}
