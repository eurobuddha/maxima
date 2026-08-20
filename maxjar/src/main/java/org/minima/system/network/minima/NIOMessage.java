package org.minima.system.network.minima;

import org.minima.objects.base.MiniByte;

/**
 * maxjar FACADE for classic NIOMessage: only the message-type bytes the Maxima
 * path uses, with classic's exact values (NIOMessage.java:76-79). The inbound
 * dispatch classic does here lives in the transport driver instead.
 */
public class NIOMessage {

	public static final MiniByte MSG_PING 			= new MiniByte(8);
	public static final MiniByte MSG_MAXIMA_CTRL	= new MiniByte(9);
	public static final MiniByte MSG_MAXIMA_TXPOW 	= new MiniByte(10);
}
