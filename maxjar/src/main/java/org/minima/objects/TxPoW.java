package org.minima.objects;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Random;

import org.minima.objects.base.MiniByte;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.Crypto;
import org.minima.utils.Streamable;

/**
 * maxjar CHAIN-FREE replacement for classic org.minima.objects.TxPoW.
 *
 * On the Maxima path a TxPoW is purely the CARRIER of a MaximaPackage: the
 * receiver checks {@code customHash == SHA3(MaximaPackage)} and that the unit
 * carries the protocol-minimum proof-of-work. The full classic class drags in
 * Transaction/Witness/MMR - all chain machinery. This class keeps the classic
 * WIRE FORMAT exactly (header via the vendored {@link TxHeader}, then a
 * has-body byte, then the body) and the classic PoW rules, nothing else.
 *
 * Wire-compat notes, learned live against stock nodes (0.5.2x line):
 *  - TxPoWID = SHA3-256 of the serialised HEADER only, so a body-less carrier
 *    mines fine - the nonce lives in the header.
 *  - blockDifficulty MUST be zero-hash: a receiver treats the unit as a BLOCK
 *    when txpowid &lt; blockDifficulty, and classic TxHeader's default is
 *    MAX_HASH, which would push our carrier into the peer's chain pipeline.
 *  - Classic senders always attach a body (an empty Transaction) - we keep it
 *    as opaque bytes and re-emit byte-identically; inside a MaxTxPoW the TxPoW
 *    is the terminal field, so "the rest of the stream" IS the body.
 */
public class TxPoW implements Streamable {

	TxHeader mHeader = new TxHeader();

	/** We never produce a body; classic peers always send one - kept opaque. */
	boolean mHasBody = false;
	byte[] mRawBody  = null;

	/** Cached TxPoWID, classic-style. */
	String  mTxPoWID = "0x00";

	public TxPoW() {
		// A carrier must NEVER look like a block (see class comment).
		mHeader.mBlockDifficulty = new MiniData("0x00");
		mHeader.mMMRRoot         = new MiniData("0x00");
		mHeader.mMMRTotal        = MiniNumber.ZERO;
		mHeader.mBlockNumber     = MiniNumber.ZERO;
		mHeader.mTimeMilli       = new MiniNumber(System.currentTimeMillis());
		mHeader.mSuperParents    = new MiniData[org.minima.system.params.GlobalParams.MINIMA_CASCADE_LEVELS];
		for (int i = 0; i < mHeader.mSuperParents.length; i++) {
			mHeader.mSuperParents[i] = new MiniData("0x00");
		}
	}

	public TxHeader getTxHeader() {
		return mHeader;
	}

	public MiniData getCustomHash() {
		return mHeader.mCustomHash;
	}

	public void setCustomHash(MiniData zCustomHash) {
		mHeader.mCustomHash = zCustomHash;
	}

	/** The difficulty we MINE to (kept off-header; classic stores it in the body). */
	private MiniData mTxDifficulty = Crypto.MAX_HASH;

	public void setTxDifficulty(MiniData zDifficulty) {
		mTxDifficulty = zDifficulty;
	}

	public String getTxPoWID() {
		return mTxPoWID;
	}

	/** Classic: TxPoWID = SHA3-256(serialised header). */
	public void calculateTXPOWID() {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(bos);
			mHeader.writeDataStream(dos);
			dos.flush();
			MiniData hash = Crypto.getInstance().hashObject(new MiniData(bos.toByteArray()));
			mTxPoWID = hash.to0xString();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private BigInteger idValue() {
		calculateTXPOWID();
		return new MiniData(mTxPoWID).getDataValue();
	}

	/** True when this unit's work meets the protocol minimum (Magic floor). */
	public boolean checkMinimumWork() {
		return idValue().compareTo(Magic.MIN_TXPOW_VAL) <= 0;
	}

	/**
	 * Mine to the requested difficulty within the budget - the chain-free stand-in
	 * for classic TxPoWMiner.MineMaxTxPoW. ~10k expected hashes at the floor:
	 * milliseconds on any modern CPU.
	 */
	public boolean mineMaxTxPoW(long zBudgetMs) {
		BigInteger target = mTxDifficulty.getDataValue();
		long deadline = System.currentTimeMillis() + zBudgetMs;
		// Random start so parallel senders never duplicate nonce walks.
		long nonce = new Random().nextLong() & 0x7FFFFFFFFFFFL;
		while (System.currentTimeMillis() < deadline) {
			for (int i = 0; i < 512; i++) {   // check the clock every 512 hashes
				mHeader.mNonce = new MiniNumber(nonce++);
				if (idValue().compareTo(target) <= 0) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void writeDataStream(DataOutputStream zOut) throws IOException {
		mHeader.writeDataStream(zOut);
		if (mHasBody && mRawBody != null) {
			MiniByte.TRUE.writeDataStream(zOut);
			zOut.write(mRawBody);
		} else {
			MiniByte.FALSE.writeDataStream(zOut);
		}
	}

	@Override
	public void readDataStream(DataInputStream zIn) throws IOException {
		mHeader = TxHeader.ReadFromStream(zIn);
		mHasBody = MiniByte.ReadFromStream(zIn).isTrue();
		if (mHasBody) {
			// Terminal field of a MaxTxPoW: the remainder of the stream is the
			// body - captured opaquely, re-emitted byte-identically.
			ByteArrayOutputStream rest = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int n;
			while ((n = zIn.read(chunk)) > 0) {
				rest.write(chunk, 0, n);
			}
			mRawBody = rest.toByteArray();
		} else {
			mRawBody = null;
		}
		calculateTXPOWID();
	}

	public static TxPoW ReadFromStream(DataInputStream zIn) throws IOException {
		TxPoW txpow = new TxPoW();
		txpow.readDataStream(zIn);
		return txpow;
	}
}
