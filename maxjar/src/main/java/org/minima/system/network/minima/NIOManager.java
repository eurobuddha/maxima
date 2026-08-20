package org.minima.system.network.minima;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.minima.maxjar.MaximaTransport;
import org.minima.objects.base.MiniByte;
import org.minima.objects.base.MiniData;
import org.minima.system.Main;
import org.minima.utils.Streamable;

/**
 * maxjar FACADE for classic NIOManager: the surface the Maxima managers call,
 * delegating to the injected {@link MaximaTransport}. createNIOMessage keeps
 * classic's exact framing ([type byte][object]) - it is on the wire.
 */
public class NIOManager {

	public NIOClient getMaximaUID(String zMaximaIdent) {
		return transport().getMaximaUID(zMaximaIdent);
	}

	public NIOClient getNIOClient(String zFullHost) {
		return transport().getNIOClient(zFullHost);
	}

	public NIOClient getNIOClientFromUID(String zUID) {
		return transport().getNIOClientFromUID(zUID);
	}

	public void disconnect(String zUID) {
		transport().disconnect(zUID);
	}

	public TrafficListener getTrafficListener() {
		return mTraffic;
	}

	public static void sendNetworkMessage(String zUID, MiniByte zType, Streamable zObject)
			throws IOException {
		transport().sendNetworkMessage(zUID, zType, zObject);
	}

	/** Classic framing, verbatim: type byte then the serialised object. */
	public static MiniData createNIOMessage(MiniByte zType, Streamable zObject) throws IOException {
		ByteArrayOutputStream baos 	= new ByteArrayOutputStream();
		DataOutputStream dos 		= new DataOutputStream(baos);
		zType.writeDataStream(dos);
		zObject.writeDataStream(dos);
		dos.flush();
		byte[] bb = baos.toByteArray();
		dos.close();
		baos.close();
		return new MiniData(bb);
	}

	private static MaximaTransport transport() {
		return Main.getInstance().getTransport();
	}

	/** Classic traffic counters - byte accounting only, safe as a no-op sink. */
	private final TrafficListener mTraffic = new TrafficListener();

	public static class TrafficListener {
		public void addWriteBytes(String zTag, long zBytes) {
		}

		public void addReadBytes(String zTag, long zBytes) {
		}
	}
}
