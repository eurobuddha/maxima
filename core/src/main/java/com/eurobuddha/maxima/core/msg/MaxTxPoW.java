package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.codec.Streamable;
import com.eurobuddha.maxima.core.crypto.Hashes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The outermost Maxima unit, carried in a type-10 frame.
 *
 * {@code version, MaximaPackage, TxPoW} - where the TxPoW exists only to bind
 * the package via its customHash. The version is written and read but never
 * validated by the reference.
 */
public final class MaxTxPoW implements Streamable {

    public static final String VERSION = "1.0";

    public MiniString mVersion = new MiniString(VERSION);
    public MaximaPackage mMaxima;
    public TxPoW mTxPoW;

    public MaxTxPoW() {
    }

    public MaxTxPoW(MaximaPackage zMaxima, TxPoW zTxPoW) {
        mMaxima = zMaxima;
        mTxPoW = zTxPoW;
    }

    /**
     * Build a complete unit around a package, with a synthetic carrier.
     * No mining: receivers never verify the work.
     */
    public static MaxTxPoW create(MaximaPackage zMaxima, long zTimeMilli) {
        MiniData hash = new MiniData(Hashes.sha3(Codec.serialise(zMaxima)));
        return new MaxTxPoW(zMaxima, TxPoW.carrier(hash, zTimeMilli));
    }

    /** The check every receiver performs, and the only one. */
    public boolean checkValidTxPoW() {
        byte[] want = Hashes.sha3(Codec.serialise(mMaxima));
        return new MiniData(want).equals(mTxPoW.getHeader().mCustomHash);
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mVersion.writeDataStream(zOut);
        mMaxima.writeDataStream(zOut);
        mTxPoW.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mVersion = MiniString.readFromStream(zIn);
        mMaxima = MaximaPackage.readFromStream(zIn);
        mTxPoW = TxPoW.readFromStream(zIn);
    }

    public static MaxTxPoW readFromStream(DataInputStream zIn) throws IOException {
        MaxTxPoW m = new MaxTxPoW();
        m.readDataStream(zIn);
        return m;
    }

    public static MaxTxPoW fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new MaxTxPoW(), zData);
    }
}
