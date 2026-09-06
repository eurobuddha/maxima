package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Relay-plane directory REPLICATION: a pool relay that just accepted a signed SET hands the
 * publisher's proof triplet to a few random pool peers so the entry exists in several places
 * before its anchor can go down. The receiver re-verifies the signature exactly as it would
 * a forwarded answer ({@code MlsService.verifiedAddress}) - a relay can push junk that is
 * refused, never an entry it did not sign for. Same triplet {@link DirAnswer} carries, no nonce
 * (the reply is a plain ack on the same socket).
 */
public final class DirPublish implements Streamable {

    private MiniData mProofFrom;
    private MiniData mProofPayload;
    private MiniData mProofSig;

    public DirPublish() {
        mProofFrom = new MiniData(new byte[0]);
        mProofPayload = new MiniData(new byte[0]);
        mProofSig = new MiniData(new byte[0]);
    }

    public DirPublish(byte[] zProofFrom, byte[] zProofPayload, byte[] zProofSig) {
        mProofFrom = new MiniData(zProofFrom);
        mProofPayload = new MiniData(zProofPayload);
        mProofSig = new MiniData(zProofSig);
    }

    public byte[] getProofFrom() {
        return mProofFrom.getBytes();
    }

    public byte[] getProofPayload() {
        return mProofPayload.getBytes();
    }

    public byte[] getProofSig() {
        return mProofSig.getBytes();
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mProofFrom.writeDataStream(zOut);
        mProofPayload.writeDataStream(zOut);
        mProofSig.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mProofFrom = MiniData.readFromStream(zIn);
        mProofPayload = MiniData.readFromStream(zIn);
        mProofSig = MiniData.readFromStream(zIn);
    }

    public static DirPublish fromBytes(byte[] zBytes) throws IOException {
        return Codec.deserialise(new DirPublish(), zBytes);
    }
}
