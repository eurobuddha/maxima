package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.msg.MaxTxPoW;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.msg.MaximaInternal;
import com.eurobuddha.maxima.core.msg.MaximaMessage;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPair;

/**
 * A long-lived OUTGOING connection to a public Maxima host.
 *
 * This is the whole NAT-traversal story. A device behind CGNAT can never accept
 * an inbound socket, so instead it dials out and holds the connection open;
 * the host then relays anything addressed to this device back down that same
 * socket.
 *
 * Attachment sequence:
 * <ol>
 *   <li>send our Greeting (required - the reference only raises
 *       MAXIMA_CONNECTED from its greeting handler)</li>
 *   <li>read their Greeting</li>
 *   <li>send CTRL/TYPE_ID carrying our PER-HOST public key, which the host
 *       stores against this socket as the routing key</li>
 * </ol>
 *
 * Our published contact address is then {@code Mx(perHostKey)@host:port} - the
 * Mx half is a key only we hold the private part of, and the host half is where
 * to deliver. That is why a relay can route for us without being able to read
 * anything.
 *
 * A fresh keypair per host is deliberate: it stops two hosts correlating us.
 */
public final class HostConnection implements Closeable {

    /** Anything bigger than this on the wire is chain data we do not want. */
    private static final int MAX_KEEP_BYTES = 1024 * 1024;

    private final String mHost;
    private final int mPort;
    private final KeyPair mPerHostKey;
    private final String mVersion;

    private Socket mSocket;
    private DataOutputStream mOut;
    private DataInputStream mIn;

    private Greeting mTheirGreeting;
    private String mTheirMlsAddress;
    private boolean mAttached;

    public HostConnection(String zHost, int zPort, KeyPair zPerHostKey, String zVersion) {
        mHost = zHost;
        mPort = zPort;
        mPerHostKey = zPerHostKey;
        mVersion = zVersion;
    }

    public boolean isAttached() {
        return mAttached;
    }

    public Greeting getTheirGreeting() {
        return mTheirGreeting;
    }

    /** The MLS server this host offered us, if any (bare key + observed address). */
    public String getTheirMlsAddress() {
        return mTheirMlsAddress;
    }

    public byte[] routingKey() {
        return mPerHostKey.getPublic().getEncoded();
    }

    /** The address to publish to contacts so they can reach us through this host. */
    public String contactAddress() {
        return MxAddress.make(new MiniData(routingKey())) + "@" + mHost + ":" + mPort;
    }

    /**
     * Connect, greet, and announce our routing key.
     *
     * @param zTimeoutMs overall budget for reaching the attached state
     */
    public void attach(int zTimeoutMs) throws Exception {
        mSocket = new Socket();
        mSocket.connect(new InetSocketAddress(mHost, mPort), zTimeoutMs);
        mSocket.setSoTimeout(zTimeoutMs);
        mSocket.setTcpNoDelay(true);
        mSocket.setKeepAlive(true);

        mOut = new DataOutputStream(mSocket.getOutputStream());
        mIn = new DataInputStream(mSocket.getInputStream());

        // 1. Greet first.
        Frame.write(mOut, Frame.body(Frame.MSG_GREETING,
                Greeting.commsOnly(mVersion, mHost, mPort)));

        // 2. Read until we see theirs, discarding chain data.
        long deadline = System.currentTimeMillis() + zTimeoutMs;
        while (mTheirGreeting == null && System.currentTimeMillis() < deadline) {
            byte[] rx = Frame.readOrSkip(mIn, MAX_KEEP_BYTES);
            if (rx == null || rx.length < 1) {
                continue;
            }
            handleControlFrame(rx);
        }
        if (mTheirGreeting == null) {
            throw new IllegalStateException("No greeting from " + mHost + ":" + mPort);
        }

        // 3. Announce our per-host routing key.
        MaximaCTRLMessage id = MaximaCTRLMessage.id(new MiniData(routingKey()));
        Frame.write(mOut, Frame.body(Frame.MSG_MAXIMA_CTRL, id));

        mAttached = true;
    }

    /** Decode greeting / CTRL frames. Returns true if it was one of those. */
    private boolean handleControlFrame(byte[] zBody) throws Exception {
        int type = Frame.typeOf(zBody);
        byte[] payload = new byte[zBody.length - 1];
        System.arraycopy(zBody, 1, payload, 0, payload.length);

        if (type == Frame.MSG_GREETING) {
            mTheirGreeting = Greeting.fromBytes(payload);
            return true;
        }
        if (type == Frame.MSG_MAXIMA_CTRL) {
            MaximaCTRLMessage ctrl = MaximaCTRLMessage.fromBytes(payload);
            if (ctrl.getType().getAsInt() == MaximaCTRLMessage.TYPE_MLS) {
                mTheirMlsAddress = MaximaCTRLMessage.mlsAddressFrom(ctrl, mHost + ":" + mPort);
            }
            return true;
        }
        return false;
    }

    /** A message that arrived for us, already decrypted and verified. */
    public static final class Inbound {
        public final MaximaMessage message;
        public final MiniData msgid;
        public final boolean signatureValid;

        Inbound(MaximaMessage zMsg, MiniData zMsgid, boolean zSigValid) {
            message = zMsg;
            msgid = zMsgid;
            signatureValid = zSigValid;
        }
    }

    /**
     * Decrypt and verify a TXPOW carrier addressed to us, from ANY inbound path
     * - a relay's pump loop or the direct endpoint. Factored out so the two can
     * never drift: a divergence here is a signature check that only runs on one
     * of them.
     *
     * @param zUnit          the carrier
     * @param zExpectedKey   the routing key it must be addressed to (a relay's
     *                       per-host key, or our identity key on a direct link)
     * @param zPrivateDer    the matching private key, DER-encoded
     * @return an ack status; on OK the out-param carries the Inbound
     */
    public static int unwrap(MaxTxPoW zUnit, byte[] zExpectedKey, byte[] zPrivateDer,
                             Inbound[] zOut) {
        try {
            if (!zUnit.checkValidTxPoW()) {
                return Frame.RESPONSE_WRONGHASH;
            }
            if (!new MiniData(zExpectedKey).equals(zUnit.mMaxima.mTo)) {
                // Not for us. On a direct endpoint this means someone tried to
                // use a phone as a relay - refused, we forward for nobody.
                return Frame.RESPONSE_UNKNOWN;
            }
            CryptoPackage cp = CryptoPackage.fromBytes(zUnit.mMaxima.mData.getBytes());
            byte[] plain = MaximaCrypto.decrypt(cp, zPrivateDer);
            MaximaInternal mi = MaximaInternal.fromBytes(plain);
            boolean sigOk = MaximaCrypto.verify(
                    mi.mFrom.getBytes(), mi.mData.getBytes(), mi.mSignature.getBytes());
            MaximaMessage mm = MaximaMessage.fromBytes(mi.mData.getBytes());
            // The receiver's bind check: the signer must be the claimed sender.
            if (!mm.mFrom.equals(mi.mFrom) || !sigOk) {
                return Frame.RESPONSE_FAIL;
            }
            MiniData msgid = new MiniData(
                    com.eurobuddha.maxima.core.crypto.Hashes.sha3(mi.mData.getBytes()));
            zOut[0] = new Inbound(mm, msgid, true);
            return Frame.RESPONSE_OK;
        } catch (Exception e) {
            return Frame.RESPONSE_FAIL;
        }
    }

    /**
     * Block until a relayed Maxima message arrives for us, or the budget runs
     * out. Chain traffic and control frames are consumed and ignored.
     *
     * @return the decrypted message, or null on timeout
     */
    public Inbound receive(int zTimeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + zTimeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int remaining = (int) Math.max(1000, deadline - System.currentTimeMillis());
            mSocket.setSoTimeout(remaining);

            byte[] rx;
            try {
                rx = Frame.readOrSkip(mIn, MAX_KEEP_BYTES);
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
            if (rx == null || rx.length < 1) {
                continue;
            }

            int type = Frame.typeOf(rx);
            if (type != Frame.MSG_MAXIMA_TXPOW) {
                handleControlFrame(rx);
                continue;
            }

            byte[] payload = new byte[rx.length - 1];
            System.arraycopy(rx, 1, payload, 0, payload.length);

            MaxTxPoW unit = MaxTxPoW.fromBytes(payload);

            if (!unit.checkValidTxPoW()) {
                ack(Frame.RESPONSE_WRONGHASH);
                continue;
            }
            // Is it actually addressed to us?
            if (!new MiniData(routingKey()).equals(unit.mMaxima.mTo)) {
                ack(Frame.RESPONSE_UNKNOWN);
                continue;
            }

            CryptoPackage cp = CryptoPackage.fromBytes(unit.mMaxima.mData.getBytes());
            byte[] plain = MaximaCrypto.decrypt(cp, mPerHostKey.getPrivate().getEncoded());

            MaximaInternal mi = MaximaInternal.fromBytes(plain);
            boolean sigOk = MaximaCrypto.verify(
                    mi.mFrom.getBytes(), mi.mData.getBytes(), mi.mSignature.getBytes());

            MaximaMessage mm = MaximaMessage.fromBytes(mi.mData.getBytes());

            // The receiver's bind check: the signer must be the claimed sender.
            if (!mm.mFrom.equals(mi.mFrom)) {
                ack(Frame.RESPONSE_FAIL);
                continue;
            }
            if (!sigOk) {
                ack(Frame.RESPONSE_FAIL);
                continue;
            }

            ack(Frame.RESPONSE_OK);

            MiniData msgid = new MiniData(
                    com.eurobuddha.maxima.core.crypto.Hashes.sha3(mi.mData.getBytes()));
            return new Inbound(mm, msgid, true);
        }
        return null;
    }

    private void ack(int zStatus) throws Exception {
        Frame.write(mOut, Frame.ack(zStatus));
    }

    /** Serialised size of our CTRL/TYPE_ID announcement, for diagnostics. */
    public int idFrameSize() {
        return Frame.body(Frame.MSG_MAXIMA_CTRL,
                MaximaCTRLMessage.id(new MiniData(routingKey()))).length;
    }

    public byte[] serialisedGreeting() {
        return Codec.serialise(Greeting.commsOnly(mVersion, mHost, mPort));
    }

    @Override
    public void close() {
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {
        }
        mAttached = false;
    }
}
