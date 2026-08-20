package org.minima.maxjar;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.base.MiniString;
import org.minima.utils.Streamable;

/**
 * The Minima P2P handshake message (NIO type 0), exactly as classic streams it:
 * version, extraData JSON, topBlock, then a (count, hash...) chain list.
 *
 * Ported from the proven :core implementation (live-verified against stock
 * 1.0.4x nodes). Two hard-won rules carried over:
 *  - A comms-only peer sends topBlock -1 and an empty chain - accepted.
 *  - NEVER advertise a bind address: extraData.host OVERRIDES the address the
 *    peer dialled (classic NIOMessage.java:342-344), and "0.0.0.0" gets the
 *    whole host rejected as internal. Silence is correct when NATed.
 */
public final class Greeting implements Streamable {

	private MiniString mVersion;
	private MiniString mExtraData;
	private MiniNumber mTopBlock;
	private final List<MiniData> mChain = new ArrayList<>();

	public Greeting() {
		mVersion = new MiniString("");
		mExtraData = new MiniString("{}");
		mTopBlock = new MiniNumber(0);
	}

	public Greeting(String zVersion, String zExtraDataJson, long zTopBlock) {
		mVersion = new MiniString(zVersion);
		mExtraData = new MiniString(zExtraDataJson);
		mTopBlock = new MiniNumber(zTopBlock);
	}

	public String getVersion() {
		return mVersion.toString();
	}

	public String getExtraData() {
		return mExtraData.toString();
	}

	/**
	 * A greeting for a comms-only (chainless) peer.
	 *
	 * @param zHost our public host, or null/empty/0.0.0.0 to stay silent
	 */
	public static Greeting commsOnly(String zVersion, String zHost, int zPort) {
		boolean known = zHost != null && !zHost.isEmpty()
				&& !zHost.equals("0.0.0.0") && !zHost.equals("::");
		String json = "{\"welcome\":\"Maxima\""
				+ (known ? ",\"host\":\"" + zHost + "\"" : "")
				+ ",\"port\":\"" + zPort + "\",\"peers\":[]}";
		return new Greeting(zVersion, json, -1);
	}

	@Override
	public void writeDataStream(DataOutputStream zOut) throws IOException {
		mVersion.writeDataStream(zOut);
		mExtraData.writeDataStream(zOut);
		mTopBlock.writeDataStream(zOut);
		new MiniNumber(mChain.size()).writeDataStream(zOut);
		for (MiniData id : mChain) {
			id.writeDataStream(zOut);
		}
	}

	@Override
	public void readDataStream(DataInputStream zIn) throws IOException {
		mVersion = MiniString.ReadFromStream(zIn);
		mExtraData = MiniString.ReadFromStream(zIn);
		mTopBlock = MiniNumber.ReadFromStream(zIn);
		mChain.clear();
		int len = MiniNumber.ReadFromStream(zIn).getAsInt();
		for (int i = 0; i < len; i++) {
			mChain.add(MiniData.ReadFromStream(zIn));
		}
	}

	public static Greeting ReadFromStream(DataInputStream zIn) throws IOException {
		Greeting g = new Greeting();
		g.readDataStream(zIn);
		return g;
	}
}
