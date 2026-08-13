package com.eurobuddha.maxima.core.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A UTF-8 string encoded exactly as a {@link MiniData} of its UTF-8 bytes.
 *
 * The `application` field of every Maxima message is a MiniString, so the magic
 * service strings (**maxima_contact_ctrl** etc) must encode byte-identically.
 */
public final class MiniString implements Streamable {

    public static final Charset MAXIMA_CHARSET = StandardCharsets.UTF_8;

    private String mString;

    public MiniString(String zString) {
        mString = zString;
    }

    public MiniString(byte[] zBytes) {
        mString = new String(zBytes, MAXIMA_CHARSET);
    }

    public byte[] getData() {
        return mString.getBytes(MAXIMA_CHARSET);
    }

    @Override
    public String toString() {
        return mString;
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        new MiniData(getData()).writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mString = new String(MiniData.readFromStream(zIn).getBytes(), MAXIMA_CHARSET);
    }

    public static MiniString readFromStream(DataInputStream zIn) throws IOException {
        MiniString s = new MiniString("");
        s.readDataStream(zIn);
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MiniString)) return false;
        return mString.equals(((MiniString) o).mString);
    }

    @Override
    public int hashCode() {
        return mString.hashCode();
    }
}
