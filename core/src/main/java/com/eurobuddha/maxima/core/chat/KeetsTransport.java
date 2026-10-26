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

    /** Per-transfer serving control. Unsupported nodes must fail honestly. */
    default void sharing(ChatFile file,boolean enabled)throws Exception {throw new java.io.IOException("Update your Keets node to control file sharing");}

    /** Fetch a keets:// url back to bytes (P2P, cached by the sidecar). */
    byte[] fetch(String zUrl) throws Exception;

    /** Small authenticated blocks. Hosts should enforce this limit before allocation. */
    default byte[] fetch(String url, int maxBytes) throws Exception {
        byte[] bytes = fetch(url);
        if (bytes == null || bytes.length > maxBytes) throw new java.io.IOException("Keets media exceeds size limit");
        return bytes;
    }

    /** True iff zUrl is a well-formed keets:// ref (one token, no whitespace).
     *  Inbound refs are attacker-supplied and every transport interpolates
     *  them into a node command whose tokenizer splits on whitespace with
     *  last-key-wins - an embedded space would let a hostile ref rewrite the
     *  command (e.g. into an action:publish of a node file). Enforced both at
     *  the ingest choke point and inside each fetch() implementation. */
    static boolean validRef(String zUrl) {
        return zUrl != null && zUrl.matches("keets://\\S+");
    }
    default void forget(ChatFile file)throws Exception { sharing(file,false); }
}
