package com.eurobuddha.maxima.core.directory;

import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.MLSPacketGETReq;
import com.eurobuddha.maxima.core.msg.MLSPacketGETResp;
import com.eurobuddha.maxima.core.msg.MLSPacketSET;

import java.util.List;

/**
 * Talks to a classic MLS directory.
 *
 * Two classic application strings:
 *   {@code **maxima_mls_set**} - publish my current address
 *   {@code **maxima_mls_get**} - resolve a peer, with the answer returned in
 *                                place of the ack on the same socket
 *
 * SECURITY FIX vs the reference: the async path in classic validates the echoed
 * {@code randomUID}, but its synchronous {@code MAX#} resolve path does NOT
 * ({@code maxextra.java:339-388}). That lets a hostile MLS redirect a
 * resolution to an attacker's address. We ALWAYS validate, on every path.
 */
public final class MlsClient {

    /** A fresh nonce per request, not per session, so replies cannot be replayed. */
    private final MaximaIdentity mIdentity;

    public MlsClient(MaximaIdentity zIdentity) {
        mIdentity = zIdentity;
    }

    /** Publish our address(es) to a directory. */
    public boolean publish(String zMlsAddress, List<String> zMyAddresses,
                           List<String> zAllowedReaders) throws Exception {
        if (zMyAddresses.isEmpty()) {
            return false;
        }
        MLSPacketSET set = new MLSPacketSET(zMyAddresses.get(0));
        for (String pk : zAllowedReaders) {
            set.addValidPublicKey(pk);
        }
        MaximaSender.Result r = sendTo(zMlsAddress, MlsService.APP_SET, Codec.serialise(set));
        return r.isOk();
    }

    /** The outcome of a lookup. */
    public static final class Resolved {
        public final String address;
        public final String error;

        Resolved(String zAddress, String zError) {
            address = zAddress;
            error = zError;
        }

        public boolean ok() {
            return address != null;
        }
    }

    /**
     * Resolve a peer's current address through a directory.
     *
     * @param zMlsAddress the directory's contact address
     * @param zTargetKey  the identity to resolve, uppercase 0x hex
     */
    public Resolved resolve(String zMlsAddress, String zTargetKey) throws Exception {
        // Per-request nonce. Classic reuses one per session; a fresh one per
        // request is strictly stronger and costs nothing.
        String nonce = new MiniData(MaximaCrypto.randomBytes(16)).to0xString();

        MLSPacketGETReq req = new MLSPacketGETReq(zTargetKey, nonce);
        MaximaSender.Result r = sendTo(zMlsAddress, MlsService.APP_GET, Codec.serialise(req));

        if (!r.isOk()) {
            return new Resolved(null, "directory replied " + r.statusName);
        }
        if (!r.hasPayload()) {
            return new Resolved(null, "no directory entry (bare ack)");
        }

        MLSPacketGETResp resp;
        try {
            resp = MLSPacketGETResp.fromBytes(r.replyData.getBytes());
        } catch (Exception e) {
            return new Resolved(null, "unparseable directory reply");
        }

        // THE CHECK CLASSIC SKIPS on its synchronous path.
        if (!nonce.equals(resp.getRandomUID())) {
            return new Resolved(null,
                    "directory reply nonce mismatch - possible redirect attack, rejected");
        }
        if (!zTargetKey.equalsIgnoreCase(resp.getPublicKey())) {
            return new Resolved(null, "directory answered about a different key");
        }
        String addr = resp.getAddress();
        if (addr == null || addr.isEmpty()) {
            return new Resolved(null, "empty address");
        }
        return new Resolved(addr, null);
    }

    private MaximaSender.Result sendTo(String zAddress, String zApplication, byte[] zData)
            throws Exception {
        if (!MxAddress.isValidContactAddress(zAddress)) {
            throw new IllegalArgumentException("Bad MLS address: " + zAddress);
        }
        int at = zAddress.indexOf('@');
        int colon = zAddress.indexOf(':');
        MiniData routing = MxAddress.convert(zAddress.substring(0, at));
        String host = zAddress.substring(at + 1, colon);
        int port = Integer.parseInt(zAddress.substring(colon + 1));

        MaximaSender.Built built = MaximaSender.build(
                mIdentity.publicKey(), mIdentity.keyPair().getPrivate(),
                routing.getBytes(), zApplication, zData, System.currentTimeMillis());

        return MaximaSender.send(host, port, built.unit, built.msgid);
    }
}
