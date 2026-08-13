package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * An MLS directory's answer.
 *
 * WIRE ORDER is publicKey, currentAddress, randomUID - which differs from the
 * reference constructor's argument order. Easy to get wrong; the golden vector
 * pins it.
 */
public final class MLSPacketGETResp implements Streamable {

    private MiniString mPublicKey;
    private MiniString mCurrentAddress;
    private MiniString mRandomUID;

    public MLSPacketGETResp() {
        mPublicKey = new MiniString("");
        mCurrentAddress = new MiniString("");
        mRandomUID = new MiniString("");
    }

    public MLSPacketGETResp(String zPublicKey, String zMaximaAddress, String zRandomUID) {
        mPublicKey = new MiniString(zPublicKey);
        mCurrentAddress = new MiniString(zMaximaAddress);
        mRandomUID = new MiniString(zRandomUID);
    }

    public String getPublicKey() {
        return mPublicKey.toString();
    }

    public String getAddress() {
        return mCurrentAddress.toString();
    }

    public String getRandomUID() {
        return mRandomUID.toString();
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mPublicKey.writeDataStream(zOut);
        mCurrentAddress.writeDataStream(zOut);
        mRandomUID.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mPublicKey = MiniString.readFromStream(zIn);
        mCurrentAddress = MiniString.readFromStream(zIn);
        mRandomUID = MiniString.readFromStream(zIn);
    }

    public static MLSPacketGETResp readFromStream(DataInputStream zIn) throws IOException {
        MLSPacketGETResp p = new MLSPacketGETResp();
        p.readDataStream(zIn);
        return p;
    }

    public static MLSPacketGETResp fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MLSPacketGETResp(), zData);
    }
}
