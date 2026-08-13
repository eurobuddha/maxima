package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Hybrid RSA+AES envelope.
 *
 * A fresh AES-128 key is generated per message and RSA-wrapped to the
 * recipient; the payload is AES-128-CBC-PKCS5 encrypted under it.
 *
 * Field widths for RSA-1024: iv = 16 bytes, secret = 128 bytes.
 * There is no AEAD and no MAC over the ciphertext - integrity comes solely from
 * the RSA signature inside {@link MaximaInternal}.
 */
public final class CryptoPackage implements Streamable {

    public MiniData mIvParam;
    public MiniData mSecret;
    public MiniData mData;

    public CryptoPackage() {
    }

    public CryptoPackage(MiniData zIv, MiniData zSecret, MiniData zData) {
        mIvParam = zIv;
        mSecret = zSecret;
        mData = zData;
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mIvParam.writeDataStream(zOut);
        mSecret.writeDataStream(zOut);
        mData.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mIvParam = MiniData.readFromStream(zIn);
        mSecret = MiniData.readFromStream(zIn);
        mData = MiniData.readFromStream(zIn);
    }

    public static CryptoPackage readFromStream(DataInputStream zIn) throws IOException {
        CryptoPackage c = new CryptoPackage();
        c.readDataStream(zIn);
        return c;
    }

    public static CryptoPackage fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new CryptoPackage(), zData);
    }
}
