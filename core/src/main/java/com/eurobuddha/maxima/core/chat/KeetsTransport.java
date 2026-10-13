package com.eurobuddha.maxima.core.chat;

/**
 * The keets side-channel a host may give {@link ChatEngine}: pubkey-addressed
 * E2E messages over the keets Hyperswarm transport (direct when the peer is
 * online, sealed fleet-mailbox store-and-forward when not). Implemented by the
 * Parlons Node over its embedded node's {@code keets} command; absent on hosts
 * without a node, where keets-only contacts are simply unreachable.
 */
public interface KeetsTransport {

    /** Send a wire payload (KeetsWire JSON) to a keets pubkey (0x + 64 hex). */
    boolean send(String zPubkey, String zPayload) throws Exception;

    /** This node's own keets pubkey (0x + 64 hex) - what a KeetChat user adds
     *  as a contact. Null until known. */
    default String myPubkey() {
        return null;
    }

    /** Publish bytes on the keets media mesh; returns the keets:// url. */
    String publish(String zPath, byte[] zBytes) throws Exception;

    /** Fetch a keets:// url back to bytes (P2P, cached by the sidecar). */
    byte[] fetch(String zUrl) throws Exception;
}
