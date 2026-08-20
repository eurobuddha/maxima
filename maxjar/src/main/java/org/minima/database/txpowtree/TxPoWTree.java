package org.minima.database.txpowtree;

/**
 * maxjar FACADE for classic TxPoWTree: always has a tip. Classic gates sends
 * and check-connects on tip != null purely because it cannot MINE without a
 * chain; our miner is chain-free, so the gate is always open.
 */
public class TxPoWTree {

	private final TxPoWTreeNode mTip = new TxPoWTreeNode();

	public TxPoWTreeNode getTip() {
		return mTip;
	}
}
