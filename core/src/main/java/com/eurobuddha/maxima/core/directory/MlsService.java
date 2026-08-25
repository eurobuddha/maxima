package com.eurobuddha.maxima.core.directory;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.msg.MLSPacketGETReq;
import com.eurobuddha.maxima.core.msg.MLSPacketGETResp;
import com.eurobuddha.maxima.core.msg.MLSPacketSET;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;

import java.util.Collections;
import java.util.List;

/**
 * Serves the directory - over the classic ack channel AND over our RPC layer.
 *
 * Classic only ever serves a lookup by replacing the socket ack, which requires
 * direct reachability. That single fact is why every MLS in the network today
 * is a public server, and why the directory is the most centralised part of
 * Maxima.
 *
 * Registering the same logic as an RPC method lets a PHONE serve lookups: the
 * query arrives via its relay and the answer goes out on a fresh socket. Put
 * the directory on thousands of handsets and that centralisation point stops
 * existing.
 */
public final class MlsService {

    public static final String APP_SET = "**maxima_mls_set**";
    public static final String APP_GET = "**maxima_mls_get**";

    /** Our RPC method name for the same lookup. */
    public static final String RPC_LOOKUP = "directory.lookup";
    public static final String RPC_PUBLISH = "directory.publish";

    private final MlsStore mStore;

    public MlsService(MlsStore zStore) {
        mStore = zStore;
    }

    public MlsStore store() {
        return mStore;
    }

    /**
     * Verify a forwarded directory proof and return the Maxima address it publishes for
     * {@code zExpectedKeyHex}, or null if it does not verify. The trust rule for the
     * Phase-B mesh: a relay accepts a peer's answer ONLY when the PUBLISHER's own signature
     * checks out and the signed identity is exactly the key we asked for — so a forwarding
     * relay can withhold an answer, but can never forge or redirect one. The worst a hostile
     * peer can do is serve a genuine, still-signed address (bounded by the entry TTL).
     */
    public static String verifiedAddress(String zExpectedKeyHex, byte[] zProofFrom,
                                         byte[] zProofPayload, byte[] zProofSig) {
        try {
            if (zProofFrom == null || zProofPayload == null || zProofSig == null) {
                return null;
            }
            if (!MaximaCrypto.verify(zProofFrom, zProofPayload, zProofSig)) {
                return null;
            }
            MaximaMessage mm = MaximaMessage.fromBytes(zProofPayload);
            // The signing key (proofFrom) must be exactly the identity inside the signed
            // message — otherwise a valid signature could be lifted onto a foreign 'from'.
            if (!java.util.Arrays.equals(zProofFrom, mm.mFrom.getBytes())) {
                return null;
            }
            String signer = mm.mFrom.to0xString();
            if (zExpectedKeyHex != null
                    && !signer.trim().equalsIgnoreCase(zExpectedKeyHex.trim())) {
                return null;
            }
            if (!APP_SET.equals(mm.mApplication.toString())) {
                return null;
            }
            MLSPacketSET set = MLSPacketSET.fromBytes(mm.mData.getBytes());
            String addr = set.getMaximaAddress();
            return (addr == null || addr.isEmpty()) ? null : addr;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Handle a classic inbound directory message.
     *
     * @return the MiniData to send back on the ack channel: a bare status byte
     *         for a SET, or a serialised MLSPacketGETResp for a successful GET
     */
    public MiniData handleClassic(MaximaMessage zMsg, int zOkStatus, int zUnknownStatus) {
        return handleClassic(zMsg, null, null, null, zOkStatus, zUnknownStatus);
    }

    /**
     * As {@link #handleClassic(MaximaMessage, int, int)}, but the caller also supplies the
     * verified signed envelope triplet ({@code zProofFrom}=publisher key DER,
     * {@code zProofPayload}=the signed MaximaMessage bytes, {@code zProofSig}=the signature).
     * A SET retains this proof on its entry so the Phase-B mesh can forward and re-verify it.
     */
    public MiniData handleClassic(MaximaMessage zMsg, byte[] zProofFrom, byte[] zProofPayload,
                                  byte[] zProofSig, int zOkStatus, int zUnknownStatus) {
        String app = zMsg.mApplication.toString();
        String from = zMsg.mFrom.to0xString();

        try {
            if (APP_SET.equals(app)) {
                MLSPacketSET set = MLSPacketSET.fromBytes(zMsg.mData.getBytes());
                // Keyed by the SIGNER, never by packet contents. The proof triplet (when
                // present) rides along so another relay can verify this entry after forwarding.
                mStore.put(from,
                        Collections.singletonList(set.getMaximaAddress()),
                        set.getValidPublicKeys(),
                        zProofFrom, zProofPayload, zProofSig, MlsStore.DEFAULT_TTL_MS);
                return new MiniData(new byte[]{(byte) zOkStatus});
            }

            if (APP_GET.equals(app)) {
                MLSPacketGETReq req = MLSPacketGETReq.fromBytes(zMsg.mData.getBytes());
                MlsStore.Entry e = mStore.get(req.getPublicKey(), from);
                if (e == null || e.primary() == null) {
                    return new MiniData(new byte[]{(byte) zUnknownStatus});
                }
                MLSPacketGETResp resp = new MLSPacketGETResp(
                        req.getPublicKey(), e.primary(), req.getRandomUID());
                return new MiniData(Codec.serialise(resp));
            }
        } catch (Exception ex) {
            return new MiniData(new byte[]{(byte) zUnknownStatus});
        }
        return null;
    }

    /**
     * Register the same directory over RPC, so a NAT'd node can serve it.
     *
     * lookup  : payload = target public key hex   -> address, or empty
     * publish : payload = comma-separated addresses, stored for the caller
     */
    public void registerRpc(ServiceRegistry zRegistry) {
        zRegistry.register(RPC_LOOKUP, req -> {
            String target = req.payloadAsString().trim();
            String requester = new MiniData(req.fromPublicKey).to0xString();
            MlsStore.Entry e = mStore.get(target, requester);
            if (e == null || e.primary() == null) {
                return new byte[0];
            }
            return e.primary().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        });

        zRegistry.register(RPC_PUBLISH, req -> {
            String requester = new MiniData(req.fromPublicKey).to0xString();
            List<String> addrs = com.eurobuddha.maxima.core.contacts.ContactCtrl
                    .splitCsv(req.payloadAsString());
            // A publisher can always resolve itself; the reply-to set is the
            // natural allow-list seed.
            mStore.put(requester, addrs, Collections.singletonList(requester));
            return "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        });
    }
}
