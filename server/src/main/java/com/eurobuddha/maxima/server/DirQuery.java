package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Relay-plane directory query (Phase-B MLS mesh): "do you hold a signed entry for this
 * identity key?" Sent by a pool relay that missed a resolve to a peer pool relay. The
 * nonce lets the asker match the {@link DirAnswer} to its request on a shared socket.
 */
public final class DirQuery implements Streamable {

    private MiniString mTargetKey;
    private MiniData mNonce;

    public DirQuery() {
        mTargetKey = new MiniString("");
        mNonce = new MiniData(new byte[0]);
    }

    public DirQuery(String zTargetKeyHex, byte[] zNonce) {
        mTargetKey = new MiniString(zTargetKeyHex);
        mNonce = new MiniData(zNonce);
    }

    public String getTargetKey() {
        return mTargetKey.toString();
    }

    public byte[] getNonce() {
        return mNonce.getBytes();
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mTargetKey.writeDataStream(zOut);
        mNonce.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mTargetKey = MiniString.readFromStream(zIn);
        mNonce = MiniData.readFromStream(zIn);
    }

    public static DirQuery fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new DirQuery(), zData);
    }
}
