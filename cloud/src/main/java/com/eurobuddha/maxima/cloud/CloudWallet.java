package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.wallet.KeyUses;
import com.eurobuddha.wallet.WalletCore;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;

import java.io.File;

/**
 * The cloud account's OWN fully-local Minima wallet — the Parlons pattern: the one seed phrase
 * IS the node, the wallet AND the Maxima identity. Same engine as the phone and desktop
 * (Derivation V3, node-scheme key at {@link #KEY_INDEX}): derivation, the Winternitz key walk,
 * txn building and signing all happen ON THIS NODE; the gateway only reads balances and relays
 * pre-signed transactions.
 *
 * SINGLE-HOLDER RULE (fund-critical): the account seed must live on exactly ONE node — the same
 * rule the comms identity already enforces. A user migrating a phone identity here should first
 * "wallet resync" the phone to a NEW seed; two independent key-use counters over one seed would
 * eventually reuse a one-time-signature leaf and disclose the key.
 */
public final class CloudWallet {

    /** Derivation V3: the node-scheme key index (matches the phone + desktop). */
    public static final int KEY_INDEX = 1000;
    public static final String USES_NAMESPACE = "v3";
    public static final int MAX_USES = 262144;   // 64^3

    private final WalletCore mCore;
    private final File mWalletDir;
    private volatile Address mAddress;   // derived once, cached (the WOTS walk is heavy)

    private CloudWallet(WalletCore zCore, File zWalletDir) {
        mCore = zCore;
        mWalletDir = zWalletDir;
    }

    /** Open the account's wallet with a durable key-use counter under {@code walletDir}.
     *  First address derivation is heavy — call off the pump/maintenance threads. */
    public static CloudWallet open(MaximaIdentity zIdentity, File zWalletDir) {
        // Node-canonical seed bytes (SHA3-256 of the UPPERCASE phrase) via :core — the same
        // key space as a node/phone restoring this phrase.
        MiniData seed = new MiniData(zIdentity.seed().getBytes());
        KeyUses uses = new CloudKeyUses(zWalletDir, USES_NAMESPACE);
        return new CloudWallet(new WalletCore(seed, uses), zWalletDir);
    }

    /** Derive (and cache) the receive address. Heavy on first call. */
    public Address ensureAddress() {
        Address a = mAddress;
        if (a == null) {
            a = mCore.getAddress(KEY_INDEX);
            mAddress = a;
        }
        return a;
    }

    public String mxAddress() {
        Address a = ensureAddress();
        return a.getMinimaAddress();
    }

    public String hexAddress() {
        Address a = ensureAddress();
        return a.getAddressData().to0xString();
    }

    /** The plain address script, for newscript tracking on the gateway. */
    public String script() {
        return mCore.getScript(KEY_INDEX);
    }

    public WalletCore core() {
        return mCore;
    }

    /** Signatures consumed so far (the sacred one-time-signature counter). */
    public int uses() {
        return new CloudKeyUses(mWalletDir, USES_NAMESPACE).currentUses(KEY_INDEX);
    }
}
