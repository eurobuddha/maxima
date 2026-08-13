package com.eurobuddha.maxima.core.msg;

import com.eurobuddha.maxima.core.codec.MiniByte;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.Streamable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * TxPoW header. Field order is exact and unforgiving:
 *
 * <pre>
 *   nonce            MiniNumber
 *   chainID          MiniData
 *   timeMilli        MiniNumber
 *   blockNumber      MiniNumber
 *   blockDifficulty  MiniData
 *   superParents     RLE: repeated (MiniByte count, MiniData hash) until 32 covered
 *   mmrRoot          MiniData  (writeHashToStream)
 *   mmrTotal         MiniNumber
 *   magic            Magic     (8 fields)
 *   customHash       MiniData  (writeHashToStream)  <- binds the MaximaPackage
 *   txBodyHash       MiniData  (writeHashToStream)
 * </pre>
 *
 * Note mmrRoot / customHash / txBodyHash use the HASH form (capped at 64 bytes)
 * rather than the ordinary MiniData form. The encoding is identical on the
 * wire; the cap is the difference.
 */
public final class TxHeader implements Streamable {

    public static final int CASCADE_LEVELS = 32;
    public static final MiniData MAIN_NET = new MiniData("0x00");
    public static final MiniData ZERO_HASH = new MiniData("0x00");

    public MiniNumber mNonce = new MiniNumber(0);
    public MiniData mChainID = MAIN_NET;
    public MiniNumber mTimeMilli = new MiniNumber(0);
    public MiniNumber mBlockNumber = new MiniNumber(0);
    public MiniData mBlockDifficulty = ZERO_HASH;
    public MiniData[] mSuperParents = new MiniData[CASCADE_LEVELS];
    public MiniData mMMRRoot = ZERO_HASH;
    public MiniNumber mMMRTotal = new MiniNumber(0);
    public Magic mMagic = Magic.defaults();
    public MiniData mCustomHash = ZERO_HASH;
    public MiniData mTxBodyHash = ZERO_HASH;

    public TxHeader() {
        setAllSuperParents(ZERO_HASH);
    }

    public void setAllSuperParents(MiniData zValue) {
        for (int i = 0; i < CASCADE_LEVELS; i++) {
            mSuperParents[i] = zValue;
        }
    }

    @Override
    public void writeDataStream(DataOutputStream zOut) throws IOException {
        mNonce.writeDataStream(zOut);
        mChainID.writeDataStream(zOut);
        mTimeMilli.writeDataStream(zOut);
        mBlockNumber.writeDataStream(zOut);
        mBlockDifficulty.writeDataStream(zOut);

        // Run-length encode the super parents.
        MiniData sparent = null;
        int counter = 0;
        for (int i = 0; i < CASCADE_LEVELS; i++) {
            MiniData curr = mSuperParents[i];
            if (sparent == null) {
                sparent = curr;
                counter++;
            } else if (sparent.equals(curr)) {
                counter++;
            } else {
                new MiniByte(counter).writeDataStream(zOut);
                sparent.writeHashToStream(zOut);
                sparent = curr;
                counter = 1;
            }
            if (i == CASCADE_LEVELS - 1) {
                new MiniByte(counter).writeDataStream(zOut);
                sparent.writeHashToStream(zOut);
            }
        }

        mMMRRoot.writeHashToStream(zOut);
        mMMRTotal.writeDataStream(zOut);
        mMagic.writeDataStream(zOut);
        mCustomHash.writeHashToStream(zOut);
        mTxBodyHash.writeHashToStream(zOut);
    }

    @Override
    public void readDataStream(DataInputStream zIn) throws IOException {
        mNonce = MiniNumber.readFromStream(zIn);
        mChainID = MiniData.readFromStream(zIn);
        mTimeMilli = MiniNumber.readFromStream(zIn);
        mBlockNumber = MiniNumber.readFromStream(zIn);
        mBlockDifficulty = MiniData.readFromStream(zIn);

        int tot = 0;
        while (tot < CASCADE_LEVELS) {
            MiniByte len = MiniByte.readFromStream(zIn);
            MiniData sup = MiniData.readHash(zIn);
            int count = len.getValue();
            if (count <= 0) {
                throw new IOException("Bad super-parent RLE count: " + count);
            }
            for (int i = 0; i < count && tot < CASCADE_LEVELS; i++) {
                mSuperParents[tot++] = sup;
            }
        }

        mMMRRoot = MiniData.readHash(zIn);
        mMMRTotal = MiniNumber.readFromStream(zIn);
        mMagic = Magic.readFromStream(zIn);
        mCustomHash = MiniData.readHash(zIn);
        mTxBodyHash = MiniData.readHash(zIn);
    }

    public static TxHeader readFromStream(DataInputStream zIn) throws IOException {
        TxHeader h = new TxHeader();
        h.readDataStream(zIn);
        return h;
    }
}
