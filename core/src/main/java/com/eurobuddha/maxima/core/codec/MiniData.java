package com.eurobuddha.maxima.core.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Length-prefixed raw bytes: {@code int32 BE length} followed by that many bytes.
 *
 * Note the frame header on the wire has exactly this shape, which is why a
 * prebuilt NIO body can be written straight onto a socket as a valid frame.
 */
public final class MiniData implements Streamable {

    /** Reference: MiniData.MINIMA_MAX_HASH_LENGTH */
    public static final int MAX_HASH_LENGTH = 64;

    /** Reference: MiniData.MINIMA_MAX_MINIDATA_LENGTH (512MB) */
    public static final int MAX_LENGTH = 1024 * 1024 * 512;

    private byte[] mData;

    public MiniData() {
        this(new byte[0]);
    }

    public MiniData(byte[] zData) {
        if (zData == null) throw new IllegalArgumentException("MiniData data is null");
        mData = zData;
    }

    /** Accepts an optional 0x prefix; odd-length hex is left-padded, matching BaseConverter.decode16. */
    public MiniData(String zHex) {
        this(Hex.decode(zHex));
    }

    public byte[] getBytes() {
        return mData;
    }

    public int getLength() {
        return mData.length;
    }

    /** Uppercase 0x-prefixed hex. Routing keys are compared in exactly this form. */
    public String to0xString() {
        return Hex.encode(mData);
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        zOut.writeInt(mData.length);
        zOut.write(mData);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        int len = zIn.readInt();
        if (len > MAX_LENGTH) {
            throw new IOException("MiniData length larger than maximum allowed: " + len);
        }
        if (len < 0) {
            throw new IOException("MiniData length negative: " + len);
        }
        mData = new byte[len];
        zIn.readFully(mData);
    }

    public static MiniData readFromStream(DataInputStream zIn) throws IOException {
        MiniData d = new MiniData();
        d.readDataStream(zIn);
        return d;
    }

    /**
     * The separate hash form used by TxHeader for customHash / txBodyHash.
     * Same encoding as writeDataStream but capped at 64 bytes.
     */
    public void writeHashToStream(DataOutputStream zOut) throws IOException {
        if (mData.length > MAX_HASH_LENGTH) {
            throw new IOException("HASH length greater than " + MAX_HASH_LENGTH + ": " + mData.length);
        }
        zOut.writeInt(mData.length);
        zOut.write(mData);
    }

    public void readHashFromStream(DataInputStream zIn) throws IOException {
        int len = zIn.readInt();
        if (len > MAX_HASH_LENGTH) {
            throw new IOException("HASH length greater than " + MAX_HASH_LENGTH + ": " + len);
        }
        if (len < 0) {
            throw new IOException("HASH length less than 0: " + len);
        }
        mData = new byte[len];
        zIn.readFully(mData);
    }

    /** Static counterpart of {@link #readHashFromStream(DataInputStream)}. */
    public static MiniData readHash(DataInputStream zIn) throws IOException {
        MiniData d = new MiniData();
        d.readHashFromStream(zIn);
        return d;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MiniData)) return false;
        return Arrays.equals(mData, ((MiniData) o).mData);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mData);
    }

    @Override
    public String toString() {
        return to0xString();
    }
}
