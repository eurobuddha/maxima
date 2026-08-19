package com.eurobuddha.maxima.core.identity;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.DeterministicRsa;

import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * A node's Maxima identity, derived from a seed.
 *
 * Two kinds of key come out of one seed:
 *
 *   IDENTITY  - long-lived, the {@code from} on every message and what contacts
 *               know you by. Never rotates.
 *   PER-HOST  - one throwaway keypair per relay you attach to. It is the routing
 *               key the relay maps to your socket, and it is what appears in the
 *               contact address you publish. Separate keys per host stop two
 *               relays correlating you.
 *
 * Because both derive from the seed, they survive reinstall and are identical on
 * every device holding the same phrase - including a Minima node restored with
 * it, which is what makes identity portable between standalone and paired mode.
 *
 * RSA-1024 keygen costs ~25-80ms, so derived keys are cached.
 */
public final class MaximaIdentity {

    /** Frozen. Changing any of these changes every derived identity. */
    public static final String CTX_IDENTITY = "identity";
    public static final String CTX_HOST_PREFIX = "host|";

    private final MiniData mSeed;
    private final KeyPair mIdentity;
    private final Map<String, KeyPair> mHostKeys = new ConcurrentHashMap<>();

    private MaximaIdentity(MiniData zSeed) {
        mSeed = zSeed;
        mIdentity = DeterministicRsa.derive(zSeed.getBytes(), CTX_IDENTITY);
    }

    /** From a seed phrase - the normal path. Accepts any Minima-valid phrase. */
    public static MaximaIdentity fromPhrase(String zPhrase) {
        return new MaximaIdentity(Bip39.toSeed(zPhrase));
    }

    public static MaximaIdentity fromPhrase(List<String> zWords) {
        return new MaximaIdentity(Bip39.toSeed(zWords));
    }

    /** From raw seed bytes, e.g. a node's {@code vault action:seed} output. */
    public static MaximaIdentity fromSeed(MiniData zSeed) {
        return new MaximaIdentity(zSeed);
    }

    /** Generate a fresh checksummed 24-word phrase and the identity for it. */
    public static Created create() {
        List<String> words = Bip39.generate(24);
        return new Created(words, fromPhrase(words));
    }

    public static final class Created {
        public final List<String> phrase;
        public final MaximaIdentity identity;

        Created(List<String> zPhrase, MaximaIdentity zIdentity) {
            phrase = zPhrase;
            identity = zIdentity;
        }
    }

    public MiniData seed() {
        return mSeed;
    }

    public KeyPair keyPair() {
        return mIdentity;
    }

    /** X.509 DER - the 162 bytes that go on the wire as {@code from}. */
    public byte[] publicKey() {
        return mIdentity.getPublic().getEncoded();
    }

    public MiniData publicKeyData() {
        return new MiniData(publicKey());
    }

    /** Uppercase 0x hex - the form routing comparisons use. */
    public String publicKeyHex() {
        return publicKeyData().to0xString();
    }

    /** The bare {@code Mx...} encoding of the identity key. */
    public String mxIdentity() {
        return MxAddress.make(publicKeyData());
    }

    /** Short SHA-256 fingerprint of our public key (see {@link Keys#fingerprint}). */
    public String fingerprint() {
        return Keys.fingerprint(publicKeyHex());
    }

    /**
     * The throwaway routing keypair for one relay.
     * Keyed by "host:port" so reconnecting to the same relay reuses it.
     */
    public KeyPair hostKey(String zHostPort) {
        return mHostKeys.computeIfAbsent(zHostPort,
                k -> DeterministicRsa.derive(mSeed.getBytes(), CTX_HOST_PREFIX + k));
    }

    /** The address to publish so contacts can reach you through that relay. */
    public String contactAddress(String zHostPort) {
        byte[] pub = hostKey(zHostPort).getPublic().getEncoded();
        return MxAddress.make(new MiniData(pub)) + "@" + zHostPort;
    }
}
