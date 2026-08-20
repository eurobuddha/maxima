package org.minima.system.network;

import org.minima.system.network.p2p.P2PManager;

/**
 * maxjar FACADE for classic NetworkManager: Maxima only reaches through it for
 * the P2P manager.
 */
public class NetworkManager {

	private final P2PManager mP2P = new P2PManager();

	public P2PManager getP2PManager() {
		return mP2P;
	}
}
