package com.eurobuddha.maxima.cloud;

import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONObject;

/**
 * The account's wallet, as the control channel sees it — behind an interface because there are
 * two very different implementations and the account layer must not know which one it has:
 *
 * <ul>
 *   <li><b>Cloud</b> ({@code parlons-cloud.jar}): a fully-local key-#1000 signer over the seed
 *       (the grafted phone wallet stack) that reads balances and relays pre-signed txns through a
 *       remote MegaMMR gateway.</li>
 *   <li><b>Node</b> ({@code parlons-node.jar}): the embedded full Minima node's OWN wallet — the
 *       node IS the chain, so a send is one in-process command and there is no gateway at all.</li>
 * </ul>
 *
 * Fund-critical contract: {@link #build} either signs (cloud) or signs+broadcasts (node); a
 * failure that is provably "nothing moved" is thrown as {@link Rejected}, anything else is
 * outcome-unknown and callers must NOT invite a re-send.
 */
public interface AccountWallet {

    /** A payment the wallet has produced. On the cloud the txn is signed but not yet broadcast
     *  ({@link #publish} does that); on the node it is already on the chain and only {@link #txid}
     *  is meaningful. Never truncated — a txid exists to be copied. */
    final class Payment {
        public final String txid;
        public final String importCmd;   // "" when the wallet broadcasts itself
        public final String postCmd;     // "" when the wallet broadcasts itself
        public Payment(String zTxid, String zImportCmd, String zPostCmd) {
            txid = zTxid == null ? "" : zTxid;
            importCmd = zImportCmd == null ? "" : zImportCmd;
            postCmd = zPostCmd == null ? "" : zPostCmd;
        }
    }

    /** The wallet (or its gateway/node) REPORTED the failure before anything was broadcast —
     *  a safe ✗. Every other exception is outcome-unknown. */
    final class Rejected extends Exception {
        public Rejected(String zWhy) { super(zWhy); }
    }

    /** Derive/open. Heavy on the cloud (first WOTS walk takes seconds) — never on the pump. */
    void open() throws Exception;
    boolean isOpen();

    String mxAddress();
    String hexAddress();
    /** The address's spend script (a device tracks it for coin reads). */
    String script();

    /** One-time-signature uses so far (-1 if not open). */
    int uses();
    int maxUses();
    /** Raise-only counter adjust. Throws {@link UnsupportedOperationException} where the counter
     *  is owned by the node itself. */
    void raiseUsesTo(int zTo) throws Exception;

    /** A gateway-shaped READ for any address — e.g. {@code balance megammr:true address:Mx…}.
     *  May block on I/O (cloud) — call off the pump. */
    JSONObject cmd(String zCommand) throws Exception;

    /** A device-chosen cold address to WATCH instead of our own ("" = none). Persisted. */
    String watchAddress();
    void setWatchAddress(String zAddress) throws Exception;

    /** Build (and on the node: broadcast) a native-Minima send. Blocking — send lane only. */
    Payment build(String zToAddress, MiniNumber zAmount) throws Exception;
    /** Broadcast a built payment (no-op where {@link #build} already did). */
    void publish(Payment zPayment) throws Exception;
    /** True if {@link #build} returns a signed-but-unpublished txn a device could broadcast. */
    boolean canBuildWithoutPublish();

    /** Periodic upkeep (script tracking, coin backfill…). Blocking allowed — runs on its own
     *  thread. Log lines go to the account's operator log. */
    void upkeep(java.util.function.Consumer<String> zLog);

    /** Can this wallet be re-pointed at a NEW seed phrase while the account identity stays? */
    boolean canResync();

    /**
     * Re-point the wallet at a new 24-word phrase (identity untouched): the way out when a
     * wallet's one-time-signature keys are used up, or the user wants funds on a fresh seed.
     * On a node this resyncs the node wallet from an archive host and then RESTARTS the node;
     * the call returns once the resync has been started. Funds at the old phrase's addresses
     * stay with the old phrase.
     */
    void resyncTo(String zPhrase) throws Exception;
}
