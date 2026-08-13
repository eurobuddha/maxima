package com.eurobuddha.maxima.core.rpc;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniByte;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * REPLY-AS-NEW-MESSAGE - the change that lets a phone provide services.
 *
 * Classic Maxima sends a service reply *in place of* the socket-level ack. Acks
 * do not traverse a relay (a relay treats MSG_PING as a no-op), so in classic
 * ANY request/response service must be directly reachable - which is exactly
 * why MLS lives only on public nodes and why phones are excluded.
 *
 * Here a reply is a FRESH OUTBOUND Maxima message carrying a correlation id.
 * Inbound queries reach a phone through its relay; the phone answers by dialling
 * out, which it can always do (sending needs no greeting and no reachability).
 *
 * This is NOT wire-visible: it is an ordinary Maxima message on our own
 * application string, so classic nodes are entirely unaffected. Peers that do
 * not advertise the capability simply never receive one.
 *
 * Encoded as a Streamable rather than JSON - same primitives as the rest of the
 * protocol, and no parser dependency in :core.
 *
 * <pre>
 *   MiniByte    type       0=REQUEST 1=RESPONSE 2=ERROR
 *   MiniString  id         correlation id, echoed on the reply
 *   MiniString  method     service name (empty on a reply)
 *   MiniNumber  replyCount number of reply-to addresses
 *   MiniString  []         reply-to addresses (multi-homed senders list all)
 *   MiniData    payload    opaque to this layer
 * </pre>
 */
public final class RpcEnvelope implements Streamable {

    /** Our application string. Wire-invisible to classic peers. */
    public static final String APPLICATION = "maxima_core_rpc_v1";

    public static final int TYPE_REQUEST = 0;
    public static final int TYPE_RESPONSE = 1;
    public static final int TYPE_ERROR = 2;

    private MiniByte mType = new MiniByte(TYPE_REQUEST);
    private MiniString mId = new MiniString("");
    private MiniString mMethod = new MiniString("");
    private final List<String> mReplyTo = new ArrayList<>();
    private MiniData mPayload = new MiniData(new byte[0]);

    public RpcEnvelope() {
    }

    /**
     * @param zReplyTo every address the sender can be reached on. A multi-homed
     *                 sender lists all of them so the responder can fail over.
     */
    public static RpcEnvelope request(String zId, String zMethod,
                                      List<String> zReplyTo, byte[] zPayload) {
        RpcEnvelope e = new RpcEnvelope();
        e.mType = new MiniByte(TYPE_REQUEST);
        e.mId = new MiniString(zId);
        e.mMethod = new MiniString(zMethod);
        e.mReplyTo.addAll(zReplyTo);
        e.mPayload = new MiniData(zPayload);
        return e;
    }

    public static RpcEnvelope response(String zId, byte[] zPayload) {
        RpcEnvelope e = new RpcEnvelope();
        e.mType = new MiniByte(TYPE_RESPONSE);
        e.mId = new MiniString(zId);
        e.mPayload = new MiniData(zPayload);
        return e;
    }

    public static RpcEnvelope error(String zId, String zMessage) {
        RpcEnvelope e = new RpcEnvelope();
        e.mType = new MiniByte(TYPE_ERROR);
        e.mId = new MiniString(zId);
        e.mPayload = new MiniData(new MiniString(zMessage).getData());
        return e;
    }

    public int getType() {
        return mType.getAsInt();
    }

    public boolean isRequest() {
        return getType() == TYPE_REQUEST;
    }

    public boolean isResponse() {
        return getType() == TYPE_RESPONSE;
    }

    public boolean isError() {
        return getType() == TYPE_ERROR;
    }

    public String getId() {
        return mId.toString();
    }

    public String getMethod() {
        return mMethod.toString();
    }

    public List<String> getReplyTo() {
        return mReplyTo;
    }

    public byte[] getPayload() {
        return mPayload.getBytes();
    }

    public String getPayloadAsString() {
        return new String(mPayload.getBytes(), MiniString.MAXIMA_CHARSET);
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mType.writeDataStream(zOut);
        mId.writeDataStream(zOut);
        mMethod.writeDataStream(zOut);
        MiniNumber.writeToStream(zOut, mReplyTo.size());
        for (String a : mReplyTo) {
            new MiniString(a).writeDataStream(zOut);
        }
        mPayload.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mType = MiniByte.readFromStream(zIn);
        mId = MiniString.readFromStream(zIn);
        mMethod = MiniString.readFromStream(zIn);
        mReplyTo.clear();
        int n = MiniNumber.readFromStream(zIn).getAsInt();
        if (n < 0 || n > 32) {
            throw new IOException("Implausible reply-to count: " + n);
        }
        for (int i = 0; i < n; i++) {
            mReplyTo.add(MiniString.readFromStream(zIn).toString());
        }
        mPayload = MiniData.readFromStream(zIn);
    }

    public static RpcEnvelope fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new RpcEnvelope(), zData);
    }

    public byte[] toBytes() {
        return Codec.serialise(this);
    }
}
