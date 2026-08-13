package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;
import com.eurobuddha.maxima.core.crypto.Hashes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The innermost, end-to-end encrypted payload.
 *
 * WIRE ORDER: random, from, to, timeMilli, application, data.
 * Note timeMilli is FOURTH, not second - a natural-looking reordering here
 * would silently break interop.
 */
public final class MaximaMessage implements Streamable {

    /** Exactly 32 bytes, so every message hash is unique. */
    public MiniData mRandom;

    public MiniData mFrom;
    public MiniData mTo;
    public MiniNumber mTimeMilli;
    public MiniString mApplication;
    public MiniData mData;

    public MaximaMessage() {
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mRandom.writeDataStream(zOut);
        mFrom.writeDataStream(zOut);
        mTo.writeDataStream(zOut);
        mTimeMilli.writeDataStream(zOut);
        mApplication.writeDataStream(zOut);
        mData.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mRandom = MiniData.readFromStream(zIn);
        mFrom = MiniData.readFromStream(zIn);
        mTo = MiniData.readFromStream(zIn);
        mTimeMilli = MiniNumber.readFromStream(zIn);
        mApplication = MiniString.readFromStream(zIn);
        mData = MiniData.readFromStream(zIn);
    }

    public static MaximaMessage readFromStream(DataInputStream zIn) throws IOException {
        MaximaMessage m = new MaximaMessage();
        m.readDataStream(zIn);
        return m;
    }

    public static MaximaMessage fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MaximaMessage(), zData);
    }

    /**
     * msgid = SHA3-256 of the serialised message. Sender and receiver compute
     * this identically, which is what makes it usable as a dedup key.
     */
    public MiniData msgid() {
        return new MiniData(Hashes.sha3(Codec.serialise(this)));
    }
}
