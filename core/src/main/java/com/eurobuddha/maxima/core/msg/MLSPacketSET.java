package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Publish your current contact address to an MLS directory.
 *
 * {@code validPublicKeys} is the allow-list of identities permitted to resolve
 * you. The timestamp is deliberately NOT on the wire - the server stamps it on
 * receipt so it cannot be spoofed.
 */
public final class MLSPacketSET implements Streamable {

    private MiniString mMaximaIdentity;
    private final List<String> mValidPublicKeys = new ArrayList<>();

    public MLSPacketSET() {
        mMaximaIdentity = new MiniString("");
    }

    public MLSPacketSET(String zIdentity) {
        mMaximaIdentity = new MiniString(zIdentity);
    }

    public String getMaximaAddress() {
        return mMaximaIdentity.toString();
    }

    public void addValidPublicKey(String zPublicKey) {
        mValidPublicKeys.add(zPublicKey);
    }

    public List<String> getValidPublicKeys() {
        return mValidPublicKeys;
    }

    public boolean isValidPublicKey(String zPublicKey) {
        return mValidPublicKeys.contains(zPublicKey);
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mMaximaIdentity.writeDataStream(zOut);
        MiniNumber.writeToStream(zOut, mValidPublicKeys.size());
        for (String pk : mValidPublicKeys) {
            new MiniString(pk).writeDataStream(zOut);
        }
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mMaximaIdentity = MiniString.readFromStream(zIn);
        mValidPublicKeys.clear();
        int count = MiniNumber.readFromStream(zIn).getAsInt();
        for (int i = 0; i < count; i++) {
            mValidPublicKeys.add(MiniString.readFromStream(zIn).toString());
        }
    }

    public static MLSPacketSET readFromStream(DataInputStream zIn) throws IOException {
        MLSPacketSET p = new MLSPacketSET();
        p.readDataStream(zIn);
        return p;
    }

    public static MLSPacketSET fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MLSPacketSET(), zData);
    }
}
