package com.eurobuddha.maxima.desktop.wallet;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.wallet.KeyUses;
import com.eurobuddha.wallet.WalletCore;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;

import java.io.File;

/**
 * The desktop's fully-LOCAL Minima wallet — the same code the phone's
 * {@code MaximaWallet} runs, Derivation V3: the node's own key scheme at
 * {@link #KEY_INDEX}. The seed, the Winternitz key walk, address derivation, txn
 * building and signing all happen ON THIS MACHINE; a node (relay gateway or a
 * connected node) is only ever used to READ balances and to RELAY a pre-signed
 * transaction. Nothing about the wallet relies on a node.
 *
 * Derived from THIS desktop's own seed, so it is this device's own independent
 * wallet with its own one-time-signature counter — no key-reuse risk (the same
 * wallet running on two devices would require counter reconciliation; that is the
 * linked-device concern, not this one).
 */
public final class DesktopWallet {

    /** Derivation V3: the node-scheme key index this wallet owns (matches the phone). */
    public static final int KEY_INDEX = 1000;
    public static final String USES_NAMESPACE = "v3";

    private final WalletCore mCore;
    private volatile Address mAddress;   // derived once, cached (the WOTS walk is heavy)

    private DesktopWallet(WalletCore zCore) {
        mCore = zCore;
    }

    /** Open the wallet for a 24-word phrase, with a durable key-use counter under
     *  {@code walletDir}. Heavy on first address derivation — call off the EDT. */
    public static DesktopWallet open(String zPhrase, File walletDir) {
        // Node-canonical seed (SHA3-256 of the UPPERCASE phrase, exactly as a node
        // derives it) via :core — NOT WalletCore's own phrase path, so the key space
        // matches the node and the phone's wallet for the same seed.
        byte[] seedBytes = MaximaIdentity.fromPhrase(zPhrase).seed().getBytes();
        MiniData seed = new MiniData(seedBytes);
        KeyUses uses = new DesktopKeyUses(walletDir, USES_NAMESPACE);
        return new DesktopWallet(new WalletCore(seed, uses));
    }

    /** Derive (and cache) the receive address for KEY_INDEX. Heavy; call off-EDT. */
    public Address ensureAddress() {
        Address a = mAddress;
        if (a == null) {
            a = mCore.getAddress(KEY_INDEX);
            mAddress = a;
        }
        return a;
    }

    /** Mx… receive address (null until ensureAddress ran). */
    public String mxAddress() {
        Address a = mAddress;
        return a == null ? null : a.getMinimaAddress();
    }

    /** 0x… script address (null until ensureAddress ran). */
    public String hexAddress() {
        Address a = mAddress;
        return a == null ? null : a.getAddressData().to0xString();
    }

    /** The plain address script, for newscript tracking on a gateway/node. */
    public String script() {
        return mCore.getScript(KEY_INDEX);
    }

    public WalletCore core() {
        return mCore;
    }

    /** Signatures consumed so far on our key (the sacred one-time-signature counter). */
    public int uses(File walletDir) {
        return new DesktopKeyUses(walletDir, USES_NAMESPACE).currentUses(KEY_INDEX);
    }

    /** Total signatures a Winternitz TreeKey leaf set allows for this key. */
    public static final int MAX_USES = 262144;   // 64^3, matches the phone's key budget
}
