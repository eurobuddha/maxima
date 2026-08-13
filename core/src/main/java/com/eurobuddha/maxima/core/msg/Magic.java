package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * The chain's "magic" parameters, embedded in every TxPoW header.
 *
 * Nothing here is validated on the Maxima path, but the eight fields must be
 * present and well formed or the header will not deserialise.
 */
public final class Magic implements Streamable {

    /** Reference defaults (Magic.DEFAULT_*). */
    public static final MiniNumber DEFAULT_TXPOW_SIZE = new MiniNumber(64 * 1024);
    public static final MiniNumber DEFAULT_KISSVM_OPERATIONS = new MiniNumber(1024);
    public static final MiniNumber DEFAULT_TXPOW_TXNS = new MiniNumber(256);

    /** MAX_VAL / 10000, per Magic.MIN_TXPOW_VAL. */
    public static final MiniData MIN_TXPOW_WORK = minTxPoWWork();

    public MiniNumber mCurrentMaxTxPoWSize;
    public MiniNumber mCurrentMaxKISSVMOps;
    public MiniNumber mCurrentMaxTxnPerBlock;
    public MiniData mCurrentMinTxPoWWork;

    public MiniNumber mDesiredMaxTxPoWSize;
    public MiniNumber mDesiredMaxKISSVMOps;
    public MiniNumber mDesiredMaxTxnPerBlock;
    public MiniData mDesiredMinTxPoWWork;

    public Magic() {
    }

    public static Magic defaults() {
        Magic m = new Magic();
        m.mCurrentMaxTxPoWSize = DEFAULT_TXPOW_SIZE;
        m.mCurrentMaxKISSVMOps = DEFAULT_KISSVM_OPERATIONS;
        m.mCurrentMaxTxnPerBlock = DEFAULT_TXPOW_TXNS;
        m.mCurrentMinTxPoWWork = MIN_TXPOW_WORK;
        m.mDesiredMaxTxPoWSize = DEFAULT_TXPOW_SIZE;
        m.mDesiredMaxKISSVMOps = DEFAULT_KISSVM_OPERATIONS;
        m.mDesiredMaxTxnPerBlock = DEFAULT_TXPOW_TXNS;
        m.mDesiredMinTxPoWWork = MIN_TXPOW_WORK;
        return m;
    }

    /** MAX_VAL is a 256-bit all-ones hash; MIN_HASHES is 10000. */
    private static MiniData minTxPoWWork() {
        BigInteger maxVal = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        BigInteger min = maxVal.divide(BigInteger.valueOf(10000));
        byte[] raw = min.toByteArray();
        // Strip a BigInteger sign byte if present.
        if (raw.length > 32 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            raw = trimmed;
        }
        return new MiniData(raw);
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mCurrentMaxTxPoWSize.writeDataStream(zOut);
        mCurrentMaxKISSVMOps.writeDataStream(zOut);
        mCurrentMaxTxnPerBlock.writeDataStream(zOut);
        mCurrentMinTxPoWWork.writeDataStream(zOut);

        mDesiredMaxTxPoWSize.writeDataStream(zOut);
        mDesiredMaxKISSVMOps.writeDataStream(zOut);
        mDesiredMaxTxnPerBlock.writeDataStream(zOut);
        mDesiredMinTxPoWWork.writeDataStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mCurrentMaxTxPoWSize = MiniNumber.readFromStream(zIn);
        mCurrentMaxKISSVMOps = MiniNumber.readFromStream(zIn);
        mCurrentMaxTxnPerBlock = MiniNumber.readFromStream(zIn);
        mCurrentMinTxPoWWork = MiniData.readFromStream(zIn);

        mDesiredMaxTxPoWSize = MiniNumber.readFromStream(zIn);
        mDesiredMaxKISSVMOps = MiniNumber.readFromStream(zIn);
        mDesiredMaxTxnPerBlock = MiniNumber.readFromStream(zIn);
        mDesiredMinTxPoWWork = MiniData.readFromStream(zIn);
    }

    public static Magic readFromStream(DataInputStream zIn) throws IOException {
        Magic m = new Magic();
        m.readDataStream(zIn);
        return m;
    }
}
