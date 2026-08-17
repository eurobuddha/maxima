package com.eurobuddha.maxima.app.wallet;

import android.content.Context;

import com.eurobuddha.maxima.app.SeedStore;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.wallet.KeyUses;
import com.eurobuddha.wallet.PrefsKeyUses;
import com.eurobuddha.wallet.WalletCore;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;

/**
 * The Maxima chat app's Minima wallet — Derivation V3: the NODE'S OWN key
 * scheme at modifier {@link #KEY_INDEX}.
 *
 * <pre>
 *   privseed = hashObjects(canonicalSeed, MiniData(1000))   // Wallet.java:509
 *   treekey  = TreeKey.createDefault(privseed)
 * </pre>
 *
 * Why index 1000: a node mints modifiers 0-63 by default and {@code keys
 * action:new} counts upward, so reaching 1000 takes ~937 deliberate creations —
 * the node and this wallet can share ONE seed phrase yet never sign with each
 * other's stateful Winternitz keys (one active signer per key, ever). And
 * because it is the node's own math, the funds are recoverable on ANY stock
 * node: restore the phrase, mint keys past 1000, done. No proprietary
 * derivation anywhere.
 *
 * THE SEED-CASE TRAP this class exists to get right: a real node's seed is
 * SHA3-256 of the UPPERCASE phrase ({@code utils/BIP39.java}); FreezePeach's
 * SeedDerive lowercased and therefore never actually matched a normally
 * restored node. We take the canonical bytes from {@link MaximaIdentity#seed()},
 * which :core derives the node's way — same phrase, same seed, same key space.
 *
 * Seed source is a choice: the Maxima seed itself (default — ONE phrase backs
 * up chain wallet + chat wallet + comms identity), or an independent phrase
 * (full compartmentalisation), same index rule either way. Signing is guarded
 * by {@link PrefsKeyUses} (namespace "v3") reserve-before-sign; the counter is
 * part of any backup, and the invariant is one active signer per key.
 */
public final class MaximaWallet {

    /** Derivation V3: the node-scheme key index this wallet owns. */
    public static final int KEY_INDEX = 1000;

    /** PrefsKeyUses namespace — never shared with any other derivation. */
    public static final String USES_NAMESPACE = "v3";

    private static final String PREFS = "maxima_wallet";
    private static final String KEY_CUSTOM_PHRASE = "custom_phrase_ct";

    private final WalletCore mCore;
    private volatile Address mAddress;   // derived once, cached (WOTS walk is heavy)

    private MaximaWallet(WalletCore zCore) {
        mCore = zCore;
    }

    /**
     * The wallet for this install: the custom phrase when one is set, else the
     * Maxima seed. Heavy on first address derivation — construct off-main.
     */
    public static MaximaWallet open(Context zCtx) {
        String custom = customPhrase(zCtx);
        MiniData seed = canonicalSeed(zCtx, custom);
        KeyUses uses = new PrefsKeyUses(zCtx, namespaceFor(zCtx, custom, seed));
        return new MaximaWallet(new WalletCore(seed, uses));
    }

    /**
     * The key-use counter namespace for the ACTIVE seed - FUND-CRITICAL.
     *
     * The default Maxima seed keeps the legacy {@link #USES_NAMESPACE} ("v3"),
     * so its already-accumulated count is preserved untouched (never reset to 0,
     * which would reuse spent one-time keys). Each IMPORTED seed instead gets its
     * own namespace fingerprinted from its seed bytes, so two different seeds can
     * never share a counter - each imported seed has its own fresh 0/262144
     * budget, and re-importing the SAME seed returns to the SAME counter (the
     * fingerprint is deterministic), so it can never reuse a leaf.
     */
    private static String namespaceFor(Context zCtx, String zCustom, MiniData zSeed) {
        if (zCustom == null || zCustom.isEmpty()) {
            return USES_NAMESPACE;   // default Maxima seed: legacy counter, preserved
        }
        // 8 hex of the seed hash (the seed is already SHA3-256 of the phrase, so
        // its prefix is a stable, non-reversible fingerprint). App-private.
        String hex = zSeed.to0xString();   // 0x + 64 hex
        String fp = hex.length() >= 10 ? hex.substring(2, 10) : "unknown";
        return USES_NAMESPACE + "_" + fp;
    }

    /** The active counter namespace, for callers that need to read/label it. */
    public static String activeNamespace(Context zCtx) {
        String custom = customPhrase(zCtx);
        return namespaceFor(zCtx, custom, canonicalSeed(zCtx, custom));
    }

    /**
     * The canonical Minima seed bytes for the wallet: SHA3-256(UPPERCASE
     * phrase), exactly as a node derives it — via :core's identity machinery.
     */
    private static MiniData canonicalSeed(Context zCtx, String zCustomPhrase) {
        String phrase = (zCustomPhrase != null && !zCustomPhrase.isEmpty())
                ? zCustomPhrase
                : SeedStore.revealPhrase(zCtx);
        // MaximaIdentity.fromPhrase computes the node-canonical seed; its seed()
        // is the exact bytes a node would produce for these words.
        byte[] seedBytes = MaximaIdentity.fromPhrase(phrase).seed().getBytes();
        return new MiniData(seedBytes);
    }

    // ---------------------------------------------------------------

    /** Derive (and cache) the receive address for KEY_INDEX. Heavy; call off-main. */
    public Address ensureAddress() {
        Address a = mAddress;
        if (a == null) {
            a = mCore.getAddress(KEY_INDEX);
            mAddress = a;
        }
        return a;
    }

    /** Mx... receive address (null until ensureAddress ran). */
    public String mxAddress() {
        Address a = mAddress;
        return a == null ? null : a.getMinimaAddress();
    }

    /** 0x... script address (null until ensureAddress ran). */
    public String hexAddress() {
        Address a = mAddress;
        return a == null ? null : a.getAddressData().to0xString();
    }

    /** The plain address script, for newscript tracking on a gateway. */
    public String script() {
        return mCore.getScript(KEY_INDEX);
    }

    public WalletCore core() {
        return mCore;
    }

    /** Signatures consumed so far on our key (the sacred counter), read from the
     *  ACTIVE seed's namespace. */
    public int uses(Context zCtx) {
        return new PrefsKeyUses(zCtx, activeNamespace(zCtx)).currentUses(KEY_INDEX);
    }

    // ---------------------------------------------------------------
    // custom wallet phrase (optional compartmentalisation)
    // ---------------------------------------------------------------

    /** Is this wallet driven by an independent phrase rather than the Maxima seed? */
    public static boolean hasCustomPhrase(Context zCtx) {
        String p = customPhrase(zCtx);
        return p != null && !p.isEmpty();
    }

    /**
     * Set (or clear, with null/"") an independent wallet phrase. Stored with the
     * SAME Keystore-backed encryption as the main seed. Switching seed source
     * changes the wallet ADDRESS — the old one remains recoverable by switching
     * back (or on any node): nothing is deleted, only which key is active.
     */
    public static void setCustomPhrase(Context zCtx, String zPhrase) {
        android.content.SharedPreferences p =
                zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (zPhrase == null || zPhrase.trim().isEmpty()) {
            p.edit().remove(KEY_CUSTOM_PHRASE).apply();
            return;
        }
        p.edit().putString(KEY_CUSTOM_PHRASE,
                SeedCrypt.encrypt(zPhrase.trim())).apply();
    }

    private static String customPhrase(Context zCtx) {
        String ct = zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_PHRASE, null);
        return ct == null ? null : SeedCrypt.decrypt(ct);
    }
}
