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

    private final Set<String> mCaps = new LinkedHashSet<>();

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

    public boolean has(String zCap) {
        return mCaps.contains(zCap);
    }

    public Set<String> all() {
        return mCaps;
    }

    public boolean isClassic() {
        return mCaps.isEmpty();
    }

    /** Comma-separated, for the extradata value. */
    public String encode() {
        return String.join(",", mCaps);
    }

    public static Capabilities decode(String zValue) {
        Capabilities c = new Capabilities();
        if (zValue == null || zValue.trim().isEmpty()) {
            return c;
        }
        for (String s : zValue.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                c.mCaps.add(t);
            }
        }
        return c;
    }

    @Override
    public String toString() {
        return mCaps.isEmpty() ? "(classic)" : encode();
    }
}
