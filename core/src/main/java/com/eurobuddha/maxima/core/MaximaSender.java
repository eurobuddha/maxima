package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.msg.MaximaInternal;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.msg.MaximaPackage;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * The complete Maxima send path.
 *
 * Sending needs NO greeting and no inbound reachability - open a socket, write
 * one type-10 frame, read the ack, close. That asymmetry is what lets a phone
 * behind CGNAT act as a service provider: it can always answer by dialling out.
 *
 * Pipeline:
 * <pre>
 *   MaximaMessage{random32, from, to, time, application, data}
 *     -> sign SHA256withRSA over its bytes
 *     -> MaximaInternal{from, data, signature}
 *     -> CryptoPackage: AES-128-CBC under a fresh key, RSA-wrapped to the recipient
 *     -> MaximaPackage{"1.0", to (plaintext routing key), ciphertext}
 *     -> MaxTxPoW{"1.0", package, synthetic carrier bound by customHash}
 *     -> frame type 10
 * </pre>
 */
public final class MaximaSender {

    /** Reference socket timeouts (MaxMsgHandler). */
    public static final int CONNECT_TIMEOUT_MS = 20000;
    public static final int READ_TIMEOUT_MS = 20000;

    public static final class Result {
        public final int status;
        public final String statusName;
        public final MiniData msgid;
        public final int sentBytes;

        /**
         * The raw MiniData payload of the peer's MSG_PING reply.
         *
         * Normally one status byte. An MLS GET is the exception: the reference
         * sends the lookup RESULT in place of the ack, so this carries a
         * serialised MLSPacketGETResp. Null if no reply arrived.
         */
        public final MiniData replyData;

        Result(int zStatus, MiniData zMsgid, int zSent) {
            this(zStatus, zMsgid, zSent, null);
        }

        Result(int zStatus, MiniData zMsgid, int zSent, MiniData zReply) {
            status = zStatus;
            msgid = zMsgid;
            sentBytes = zSent;
            replyData = zReply;
            statusName = name(zStatus);
        }

        /** True when the reply is a data payload rather than a bare status. */
        public boolean hasPayload() {
            return replyData != null && replyData.getLength() > 1;
        }

        public boolean isOk() {
            return status == Frame.RESPONSE_OK;
        }

        /** Factory for alternative engines (the maxjar adapter) whose sends
         *  complete outside this class but must speak the same Result. */
        public static Result of(int zStatus) {
            return new Result(zStatus, null, 0);
        }

        static String name(int s) {
            switch (s) {
                case Frame.RESPONSE_FAIL: return "FAIL";
                case Frame.RESPONSE_OK: return "OK";
                case Frame.RESPONSE_UNKNOWN: return "UNKNOWN";
                case Frame.RESPONSE_TOOBIG: return "TOOBIG";
                case Frame.RESPONSE_WRONGHASH: return "WRONGHASH";
                default: return "UNRECOGNISED(" + s + ")";
            }
        }
    }

    private MaximaSender() {
    }

    /**
     * A built unit together with its msgid.
     *
     * These travel together deliberately. The msgid is SHA3-256 of the
     * serialised MaximaMessage, but the outermost object also contains a
     * SHA3-256 of the MaximaPackage (the carrier's customHash) - two same-shaped
     * hashes of different things, one nested inside the other. Returning the
     * msgid here removes the temptation to recompute it from the wrong object.
     */
    public static final class Built {
        public final MaxTxPoW unit;
        /** SHA3-256 of the serialised MaximaMessage. Both peers derive this identically. */
        public final MiniData msgid;

        Built(MaxTxPoW zUnit, MiniData zMsgid) {
            unit = zUnit;
            msgid = zMsgid;
        }
    }

    /**
     * Build the full wire unit for a message.
     *
     * @param zToRoutingKey the recipient's routing public key (DER). For a
     *                      direct send this is their Maxima identity key; for a
     *                      relayed send it is their per-host key.
     */
    public static Built build(byte[] zFromPublicDer,
                              PrivateKey zFromPrivate,
                              byte[] zToRoutingKey,
                              String zApplication,
                              byte[] zData,
                              long zTimeMilli) {

        MaximaMessage mm = new MaximaMessage();
        mm.mRandom = new MiniData(MaximaCrypto.randomBytes(32));
        mm.mFrom = new MiniData(zFromPublicDer);
        mm.mTo = new MiniData(zToRoutingKey);
        mm.mTimeMilli = new MiniNumber(zTimeMilli);
        mm.mApplication = new MiniString(zApplication);
        mm.mData = new MiniData(zData);

        byte[] maxdata = Codec.serialise(mm);
        byte[] sig = MaximaCrypto.sign(zFromPrivate, maxdata);

        MaximaInternal mi = new MaximaInternal();
        mi.mFrom = new MiniData(zFromPublicDer);
        mi.mData = new MiniData(maxdata);
        mi.mSignature = new MiniData(sig);

        CryptoPackage cp = MaximaCrypto.encrypt(Codec.serialise(mi), zToRoutingKey);

        MaximaPackage mp = new MaximaPackage(
                new MiniData(zToRoutingKey), new MiniData(Codec.serialise(cp)));

        int size = Codec.serialise(mp).length;
        if (size > MaximaPackage.MAX_SIZE) {
            throw new IllegalArgumentException("MaximaPackage too big: " + size
                    + " > " + MaximaPackage.MAX_SIZE + " (peer would reply TOOBIG)");
        }

        // msgid is over the MaximaMessage bytes - NOT the MaximaPackage, whose
        // hash is the carrier's customHash.
        MiniData msgid = new MiniData(Hashes.sha3(maxdata));

        return new Built(MaxTxPoW.create(mp, zTimeMilli), msgid);
    }

    /**
     * Open a fresh socket, send one frame, read until a recognised status.
     * No greeting is sent - the reference does not require one to accept a
     * type-10 frame.
     */
    public static Result send(String zHost, int zPort, MaxTxPoW zUnit, MiniData zMsgid)
            throws Exception {
        return send(zHost, zPort, zUnit, zMsgid, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /**
     * As {@link #send(String, int, MaxTxPoW, MiniData)} but with caller-chosen
     * socket timeouts. Chat wants the patient 20s default; blob replication uses
     * a short leash so one slow/unreachable relay cannot stall a publish - the
     * owner already holds every chunk, so a relay is redundancy, not a gate.
     */
    public static Result send(String zHost, int zPort, MaxTxPoW zUnit, MiniData zMsgid,
                              int zConnectTimeoutMs, int zReadTimeoutMs)
            throws Exception {

        byte[] body = Frame.body(Frame.MSG_MAXIMA_TXPOW, zUnit);

        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(zHost, zPort), zConnectTimeoutMs);
            sock.setSoTimeout(zReadTimeoutMs);
            sock.setTcpNoDelay(true);

            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            Frame.write(out, body);

            long deadline = System.currentTimeMillis() + zReadTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                byte[] rx;
                try {
                    rx = Frame.read(in);
                } catch (Exception e) {
                    break;
                }
                if (Frame.typeOf(rx) == Frame.MSG_PING) {
                    // Body is: type byte + MiniData(payload).
                    byte[] after = new byte[rx.length - 1];
                    System.arraycopy(rx, 1, after, 0, after.length);
                    MiniData payload;
                    try {
                        payload = Codec.deserialise(new MiniData(), after);
                    } catch (Exception e) {
                        continue;
                    }
                    byte[] pb = payload.getBytes();
                    if (pb.length == 1) {
                        // Ordinary ack.
                        return new Result(pb[0] & 0xFF, zMsgid, body.length, payload);
                    }
                    // A data reply - an MLS GET result standing in for the ack.
                    return new Result(Frame.RESPONSE_OK, zMsgid, body.length, payload);
                }
                // Anything else (greeting, IBD, CTRL) is not our ack - keep reading.
            }
            return new Result(-1, zMsgid, body.length);
        }
    }

    public static byte[] utf8(String zText) {
        return zText.getBytes(StandardCharsets.UTF_8);
    }
}
