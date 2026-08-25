package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Relay-plane directory answer (Phase-B MLS mesh): the reply to a {@link DirQuery}. When
 * the peer holds a signed entry it returns the publisher's proof triplet
 * ({@code proofFrom}=key DER, {@code proofPayload}=signed MaximaMessage bytes,
 * {@code proofSig}=signature) so the ASKER verifies the address itself — a peer can
 * withhold but never forge. An empty payload means "no entry" ({@link #isPresent()} false).
 * The nonce echoes the query's nonce so it can be matched on a shared socket.
 */
public final class DirAnswer implements Streamable {

    private MiniData mNonce;
    private MiniData mProofFrom;
    private MiniData mProofPayload;
    private MiniData mProofSig;

    public DirAnswer() {
        mNonce = new MiniData(new byte[0]);
        mProofFrom = new MiniData(new byte[0]);
        mProofPayload = new MiniData(new byte[0]);
        mProofSig = new MiniData(new byte[0]);
    }

    /** A present answer carrying the publisher's signed proof. */
    public DirAnswer(byte[] zNonce, byte[] zProofFrom, byte[] zProofPayload, byte[] zProofSig) {
        mNonce = new MiniData(zNonce);
        mProofFrom = new MiniData(zProofFrom);
        mProofPayload = new MiniData(zProofPayload);
        mProofSig = new MiniData(zProofSig);
    }

    /** An absent answer: "I hold no (forwardable) entry for that key." */
    public static DirAnswer absent(byte[] zNonce) {
        return new DirAnswer(zNonce, new byte[0], new byte[0], new byte[0]);
    }

    public boolean isPresent() {
        return mProofPayload.getBytes().length > 0
                && mProofFrom.getBytes().length > 0
                && mProofSig.getBytes().length > 0;
    }

    public byte[] getNonce() {
        return mNonce.getBytes();
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
        mNonce.writeDataStream(zOut);
        mProofFrom.writeDataStream(zOut);
        mProofPayload.writeDataStream(zOut);
        mProofSig.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mNonce = MiniData.readFromStream(zIn);
        mProofFrom = MiniData.readFromStream(zIn);
        mProofPayload = MiniData.readFromStream(zIn);
        mProofSig = MiniData.readFromStream(zIn);
    }

    public static DirAnswer fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new DirAnswer(), zData);
    }
}
