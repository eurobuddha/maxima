package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The plaintext routing header. This is the ONLY part a relay reads.
 *
 * {@code mTo} is the recipient's routing public key in the clear - the relay
 * matches it against its socket map. Everything else is inside {@code mData}
 * (a serialised CryptoPackage) and is opaque to the relay.
 *
 * The version field is written and read but never validated by the reference.
 */
public final class MaximaPackage implements Streamable {

    public static final String VERSION = "1.0";

    /** Reference: NIOMessage rejects a serialised package over this size with TOOBIG. */
    public static final int MAX_SIZE = 262144;

    public MiniString mVersion = new MiniString(VERSION);
    public MiniData mTo;
    public MiniData mData;

    public MaximaPackage() {
    }

    public MaximaPackage(MiniData zTo, MiniData zData) {
        mTo = zTo;
        mData = zData;
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mVersion.writeDataStream(zOut);
        mTo.writeDataStream(zOut);
        mData.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mVersion = MiniString.readFromStream(zIn);
        mTo = MiniData.readFromStream(zIn);
        mData = MiniData.readFromStream(zIn);
    }

    public static MaximaPackage readFromStream(DataInputStream zIn) throws IOException {
        MaximaPackage m = new MaximaPackage();
        m.readDataStream(zIn);
        return m;
    }

    public static MaximaPackage fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MaximaPackage(), zData);
    }
}
