package org.minima.maxjar;

import java.io.IOException;

import org.minima.objects.base.MiniByte;
import org.minima.system.network.minima.NIOClient;
import org.minima.utils.Streamable;

/**
 * The port boundary between the vendored classic Maxima brain and the outside
 * world - exactly the four-message surface classic's NIO layer feeds
 * MaximaManager with, plus the outbound calls the managers make back into it.
 *
 * The driver (phase 2) implements this over plain sockets speaking the Minima
 * greeting; the loopback test implements it in-process. Inbound events are
 * delivered by the driver posting MAXIMA_CONNECTED / MAXIMA_DISCONNECTED /
 * MAXIMA_CTRLMESSAGE / MAXIMA_RECMESSAGE to the MaximaManager, mirroring
 * classic NIOMessage.java:368,972,997 and NIOManager.java:524.
 */
public interface MaximaTransport {

	/** The connected peer that registered this Maxima ident (MAXIMACTRL_TYPE_ID). */
	NIOClient getMaximaUID(String zMaximaIdent);

	/** The connected peer at this full host:port, or null. */
	NIOClient getNIOClient(String zFullHost);

	/** The connected peer with this connection UID, or null. */
	NIOClient getNIOClientFromUID(String zUID);

	/** Drop a connection - classic disconnects hosts that fail check-connect. */
	void disconnect(String zUID);

	/** Frame and send [type byte][object] down an existing connection. */
	void sendNetworkMessage(String zUID, MiniByte zType, Streamable zObject) throws IOException;

	/** Classic P2P_RANDOM_CONNECT: please find me a fresh outgoing host. */
	void requestNewConnection();

	/** Our own externally-reachable host:port, or "" when NATed. */
	String getP2PAddress();
}
