package org.minima.system.network.p2p;

import org.minima.system.Main;

/**
 * maxjar FACADE for classic P2PManager: Maxima only asks it for our own
 * externally-reachable address and, when a host dies, to find a replacement
 * connection (P2P_RANDOM_CONNECT). Both delegate to the transport driver.
 */
public class P2PManager {

	public static final String P2P_RANDOM_CONNECT = "P2P_RANDOM_CONNECT";

	public String getP2PAddress() {
		return Main.getInstance().getTransport().getP2PAddress();
	}

	public void PostMessage(String zMessage) {
		if (P2P_RANDOM_CONNECT.equals(zMessage)) {
			Main.getInstance().getTransport().requestNewConnection();
		}
	}
}
