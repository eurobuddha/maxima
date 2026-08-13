package com.eurobuddha.maxima.core.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** A single raw byte. Carries the NIO message type and the CTRL message type. */
public final class MiniByte implements Streamable {

    private byte mVal;

    public MiniByte() {
        mVal = 0;
    }

    public MiniByte(int zVal) {
        mVal = (byte) zVal;
    }

    public MiniByte(boolean zVal) {
        mVal = (byte) (zVal ? 1 : 0);
    }

    public byte getValue() {
        return mVal;
    }

    public int getAsInt() {
        return mVal & 0xFF;
    }

    public boolean isTrue() {
        return mVal == 1;
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        zOut.writeByte(mVal);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mVal = zIn.readByte();
    }

    public static MiniByte readFromStream(DataInputStream zIn) throws IOException {
        MiniByte b = new MiniByte();
        b.readDataStream(zIn);
        return b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MiniByte)) return false;
        return mVal == ((MiniByte) o).mVal;
    }

    @Override
    public int hashCode() {
        return mVal;
    }

    @Override
    public String toString() {
        return Integer.toString(getAsInt());
    }
}
