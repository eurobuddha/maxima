package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Ask an MLS directory for a peer's current contact address.
 *
 * The {@code randomUID} must be echoed back in the response. The reference's
 * asynchronous path validates it but its synchronous MAX# path does NOT, which
 * lets a hostile MLS redirect a resolution. We always validate.
 */
public final class MLSPacketGETReq implements Streamable {

    private MiniString mPublicKey;
    private MiniString mRandomUID;

    public MLSPacketGETReq() {
        mPublicKey = new MiniString("");
        mRandomUID = new MiniString("");
    }

    public MLSPacketGETReq(String zPublicKey, String zRandomUID) {
        mPublicKey = new MiniString(zPublicKey);
        mRandomUID = new MiniString(zRandomUID);
    }

    public String getPublicKey() {
        return mPublicKey.toString();
    }

    public String getRandomUID() {
        return mRandomUID.toString();
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mPublicKey.writeDataStream(zOut);
        mRandomUID.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mPublicKey = MiniString.readFromStream(zIn);
        mRandomUID = MiniString.readFromStream(zIn);
    }

    public static MLSPacketGETReq readFromStream(DataInputStream zIn) throws IOException {
        MLSPacketGETReq p = new MLSPacketGETReq();
        p.readDataStream(zIn);
        return p;
    }

    public static MLSPacketGETReq fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MLSPacketGETReq(), zData);
    }
}
