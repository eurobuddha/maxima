package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The signed wrapper around a {@link MaximaMessage}.
 *
 * {@code mSignature} is SHA256withRSA over {@code mData} (the serialised
 * MaximaMessage), 128 bytes for RSA-1024. The receiver additionally enforces
 * that the inner message's {@code from} equals this {@code mFrom}.
 */
public final class MaximaInternal implements Streamable {

    public MiniData mFrom;
    public MiniData mData;
    public MiniData mSignature;

    public MaximaInternal() {
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mFrom.writeDataStream(zOut);
        mData.writeDataStream(zOut);
        mSignature.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mFrom = MiniData.readFromStream(zIn);
        mData = MiniData.readFromStream(zIn);
        mSignature = MiniData.readFromStream(zIn);
    }

    public static MaximaInternal readFromStream(DataInputStream zIn) throws IOException {
        MaximaInternal m = new MaximaInternal();
        m.readDataStream(zIn);
        return m;
    }

    public static MaximaInternal fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MaximaInternal(), zData);
    }
}
