package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniByte;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Control message (NIO type 9). Two uses, and their payloads are ASYMMETRIC:
 *
 *   TYPE_ID  (0) - announces your per-host routing public key.
 *                  Payload is the RAW 162-byte X.509 DER key.
 *   TYPE_MLS (1) - offers yourself as an MLS server.
 *                  Payload is the RAW UTF-8 BYTES OF AN "Mx...@ip:port" STRING.
 *
 * Getting these two the same way round is a classic interop trap.
 */
public final class MaximaCTRLMessage implements Streamable {

    public static final int TYPE_ID = 0;
    public static final int TYPE_MLS = 1;

    /** Reference silently drops a CTRL frame larger than this. */
    public static final int MAX_FRAME_SIZE = 65535;

    private MiniByte mType;
    private MiniData mData;

    public MaximaCTRLMessage() {
    }

    public MaximaCTRLMessage(int zType) {
        mType = new MiniByte(zType);
    }

    /** TYPE_ID: the raw DER public key. */
    public static MaximaCTRLMessage id(MiniData zPublicKeyDer) {
        MaximaCTRLMessage m = new MaximaCTRLMessage(TYPE_ID);
        m.setData(zPublicKeyDer);
        return m;
    }

    /**
     * TYPE_MLS: offer yourself as an MLS server.
     *
     * IMPORTANT - the payload is the BARE {@code Mx...} encoding of your MLS
     * public key with NO {@code @host:port} suffix, carried as raw UTF-8 bytes
     * (not a MiniString, so there is no inner length prefix).
     *
     * The receiver appends the address it observes the connection coming from:
     * {@code setMaximaMLS(mlspubkey + "@" + nioc.getFullAddress())}. Sending a
     * full address here would produce a doubled host on the peer's side.
     *
     * Confirmed against live traffic: a real node sent exactly 271 bytes, the
     * bare Mx key, with no '@'.
     *
     * @param zMlsPublicKeyMx bare Mx encoding of the MLS public key
     */
    public static MaximaCTRLMessage mls(String zMlsPublicKeyMx) {
        if (zMlsPublicKeyMx.indexOf('@') >= 0) {
            throw new IllegalArgumentException(
                    "TYPE_MLS payload must be the BARE Mx key with no @host - the peer appends it");
        }
        MaximaCTRLMessage m = new MaximaCTRLMessage(TYPE_MLS);
        m.setData(new MiniData(zMlsPublicKeyMx.getBytes(MiniString.MAXIMA_CHARSET)));
        return m;
    }

    /**
     * Receiver side: reconstruct the peer's MLS address the way the reference
     * does, by appending the address we observed the connection from.
     */
    public static String mlsAddressFrom(MaximaCTRLMessage zCtrl, String zObservedHostPort) {
        String mx = new String(zCtrl.getData().getBytes(), MiniString.MAXIMA_CHARSET);
        return mx + "@" + zObservedHostPort;
    }

    public MiniByte getType() {
        return mType;
    }

    public void setData(MiniData zData) {
        mData = zData;
    }

    public MiniData getData() {
        return mData;
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mType.writeDataStream(zOut);
        mData.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mType = MiniByte.readFromStream(zIn);
        mData = MiniData.readFromStream(zIn);
    }

    public static MaximaCTRLMessage readFromStream(DataInputStream zIn) throws IOException {
        MaximaCTRLMessage m = new MaximaCTRLMessage();
        m.readDataStream(zIn);
        return m;
    }

    public static MaximaCTRLMessage fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MaximaCTRLMessage(), zData);
    }
}
