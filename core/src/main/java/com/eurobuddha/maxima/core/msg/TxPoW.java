package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniByte;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The blockchain TxPoW unit, used here purely as a CARRIER for a Maxima
 * package. We only need the header plus a "no body" marker.
 *
 * Why a synthetic carrier is safe and correct:
 *
 *   - Receivers verify {@code customHash == SHA3-256(MaximaPackage)}
 *     ({@code MaxTxPoW.checkValidTxPoW}) AND - per the reference author - the
 *     relay path requires real proof-of-work on the unit. We therefore MINE
 *     every carrier to the protocol minimum (see {@link #mine}); the earlier
 *     claim here that the PoW "buys nothing on the wire" was wrong.
 *   - After handling, a receiver re-injects the unit into the chain pipeline
 *     only {@code if(txpow.isTransaction() || txpow.isBlock())}.
 *       * {@code isTransaction()} returns false when there is no body.
 *       * {@code isBlock()} is {@code txpowid < blockDifficulty}, so we set
 *         blockDifficulty to ZERO and it is always false.
 *     The reference's own default is MAX_HASH, which would make isBlock TRUE -
 *     that would push our carrier into the peer's blockchain pipeline. Getting
 *     this wrong is the single sharp edge in building a carrier.
 *   - {@code calculateTXPOWID()} hashes the header only, so a null body is safe.
 */
public final class TxPoW implements Streamable {

    private TxHeader mHeader = new TxHeader();

    /** We never PRODUCE a body, but classic senders always do. */
    private boolean mHasBody = false;

    /**
     * A classic carrier's body, kept as opaque bytes.
     *
     * The reference builds its carrier with
     * {@code TxPoWGenerator.generateTxPoW(new Transaction(), new Witness())},
     * so every message from a stock node HAS a body - an empty transaction, but
     * present. We have no need to understand it: relaying forwards the frame
     * verbatim, and validation only needs the header's customHash.
     *
     * So we capture it opaquely and re-emit it byte-identically. Refusing to
     * parse it (as this class first did) makes us unable to receive from any
     * classic peer at all - a failure that is invisible until you test against
     * real software.
     */
    private byte[] mRawBody;

    public TxPoW() {
    }

    public TxHeader getHeader() {
        return mHeader;
    }

    public boolean hasBody() {
        return mHasBody;
    }

    public byte[] rawBody() {
        return mRawBody;
    }

    /**
     * A carrier bound to a MaximaPackage.
     *
     * @param zMaximaPackageHash SHA3-256 of the serialised MaximaPackage
     */
    public static TxPoW carrier(MiniData zMaximaPackageHash, long zTimeMilli) {
        TxPoW t = new TxPoW();
        TxHeader h = t.mHeader;

        h.mNonce = new MiniNumber(0);
        h.mChainID = TxHeader.MAIN_NET;
        h.mTimeMilli = new MiniNumber(zTimeMilli);
        h.mBlockNumber = new MiniNumber(0);

        // MUST stay low: isBlock() is txpowid < blockDifficulty, and a high
        // value here would get the carrier treated as a real block.
        h.mBlockDifficulty = TxHeader.ZERO_HASH;

        h.setAllSuperParents(TxHeader.ZERO_HASH);
        h.mMMRRoot = TxHeader.ZERO_HASH;
        h.mMMRTotal = new MiniNumber(0);
        h.mMagic = Magic.defaults();
        h.mCustomHash = zMaximaPackageHash;
        h.mTxBodyHash = TxHeader.ZERO_HASH;

        return t;
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mHeader.writeDataStream(zOut);
        new MiniByte(mHasBody).writeDataStream(zOut);
        if (mHasBody && mRawBody != null) {
            zOut.write(mRawBody);
        }
    }

    /**
     * NOTE: within a MaxTxPoW the TxPoW is the terminal field, so when a body is
     * present the remainder of the stream IS the body. That assumption is what
     * lets us treat it opaquely instead of implementing Transaction, Witness and
     * MMR proof parsing we would never use.
     */
    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mHeader.readDataStream(zIn);
        mHasBody = MiniByte.readFromStream(zIn).isTrue();
        if (mHasBody) {
            java.io.ByteArrayOutputStream rest = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = zIn.read(chunk)) > 0) {
                rest.write(chunk, 0, n);
            }
            mRawBody = rest.toByteArray();
        } else {
            mRawBody = null;
        }
    }

    // ---------------------------------------------------------------
    // proof of work
    //
    // The reference author confirms (and the reference sender implements):
    // Maxima messages carry REAL work - classic mines every carrier to the
    // chain Magic's minimum-TxPoW-work before sending, and un-worked units are
    // rejected on the relay path. The earlier claim in this file that the PoW
    // "buys nothing on the wire" was WRONG. These constants mirror classic
    // exactly: Magic.MIN_HASHES = 10000, MIN_TXPOW_VAL = Crypto.MAX_VAL/10000,
    // TxPoWID = SHA3-256 of the serialised HEADER only (so a body-less carrier
    // can still be mined - the nonce lives in the header).
    // ---------------------------------------------------------------

    /** Classic Crypto.MAX_VAL: 2^256 - 1. */
    public static final java.math.BigInteger MAX_VAL =
            new java.math.BigInteger(
                    "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
                            + "FFFFFFFFFFFFFFFF", 16);

    /** Classic Magic.MIN_HASHES. */
    public static final long MIN_HASHES = 10_000;

    /** Classic Magic.MIN_TXPOW_VAL - the protocol's minimum-work target. */
    public static final java.math.BigInteger MIN_TXPOW_VAL =
            MAX_VAL.divide(java.math.BigInteger.valueOf(MIN_HASHES));

    /** Classic MaxTxPoW.mMaxTimeMilli - the mining time budget. */
    public static final long MINE_BUDGET_MS = 15_000;

    /** The TxPoWID, exactly as classic computes it: SHA3-256(serialised header). */
    public MiniData txPowId() {
        try {
            return new MiniData(com.eurobuddha.maxima.core.crypto.Hashes
                    .sha3(Codec.serialise(mHeader)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when this unit's work meets the protocol minimum. */
    public boolean meetsMinWork() {
        return new java.math.BigInteger(1, txPowId().getBytes())
                .compareTo(MIN_TXPOW_VAL) <= 0;
    }

    /**
     * Mine this carrier: spin the header nonce until the TxPoWID meets the
     * protocol minimum ({@link #MIN_TXPOW_VAL}, ~{@value #MIN_HASHES} expected
     * hashes - milliseconds on any modern CPU), within the classic time budget.
     *
     * @return true when mined; false only if the budget expired (statistically
     *         negligible at the minimum difficulty)
     */
    public boolean mine(long zBudgetMs) {
        long deadline = System.currentTimeMillis() + zBudgetMs;
        // Random start so parallel senders never duplicate nonce walks.
        long nonce = new java.util.Random().nextLong() & 0x7FFFFFFFFFFFL;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < 512; i++) {   // check the clock every 512 hashes
                mHeader.mNonce = new MiniNumber(nonce++);
                if (meetsMinWork()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static TxPoW readFromStream(DataInputStream zIn) throws IOException {
        TxPoW t = new TxPoW();
        t.readDataStream(zIn);
        return t;
    }

    public static TxPoW fromBytes(byte[] zData) throws IOException {
        return Codec.deserialise(new TxPoW(), zData);
    }
}
