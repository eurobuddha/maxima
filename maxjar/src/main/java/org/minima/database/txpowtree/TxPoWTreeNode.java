package org.minima.database.txpowtree;

import org.minima.objects.TxPoW;
import org.minima.objects.base.MiniNumber;

/**
 * maxjar FACADE for classic TxPoWTreeNode: a constant "tip" with zeroed chain
 * details. Maxima reads the tip only for the topblock/checkblock/checkhash
 * fields of the contact-intro JSON - values a chainless node cannot know, and
 * which peers accept as zeros (proven live: our interop line has sent zeros to
 * stock nodes since the first gate).
 */
public class TxPoWTreeNode {

	private final TxPoW mTxPoW = new TxPoW();

	public MiniNumber getBlockNumber() {
		return MiniNumber.ZERO;
	}

	public TxPoWTreeNode getParent(int zGenerations) {
		return this;
	}

	public TxPoW getTxPoW() {
		return mTxPoW;
	}
}
