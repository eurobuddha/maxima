package org.minima.system.network.minima;

/**
 * maxjar FACADE for classic NIOClient: the connection-state holder the Maxima
 * managers read and annotate. Classic's 511-line original is mostly selector
 * plumbing; Maxima only ever touches the fields below (verified by grep across
 * the vendored managers). The transport driver creates one per live peer.
 */
public class NIOClient {

	private final String mUID;
	private final String mHost;
	private final int mPort;
	private final boolean mIncoming;

	/** The Maxima ident (routing pubkey) this peer registered via MAXIMACTRL_TYPE_ID. */
	private String mMaximaIdent = "";

	/** The MLS address this peer offered via MAXIMACTRL_TYPE_MLS. */
	private String mMaximaMLS = "";

	private boolean mMaximaDisconnected = false;

	public NIOClient(String zUID, String zHost, int zPort, boolean zIncoming) {
		mUID = zUID;
		mHost = zHost;
		mPort = zPort;
		mIncoming = zIncoming;
	}

	public String getUID() {
		return mUID;
	}

	public String getHost() {
		return mHost;
	}

	public int getPort() {
		return mPort;
	}

	public String getFullAddress() {
		return mHost + ":" + mPort;
	}

	public boolean isIncoming() {
		return mIncoming;
	}

	public boolean isOutgoing() {
		return !mIncoming;
	}

	public void setMaximaIdent(String zIdent) {
		mMaximaIdent = zIdent;
	}

	public String getMaximaIdent() {
		return mMaximaIdent;
	}

	public boolean isMaximaMLS() {
		return !mMaximaMLS.equals("");
	}

	public void setMaximaMLS(String zMLS) {
		mMaximaMLS = zMLS;
	}

	public String getMaximaMLS() {
		return mMaximaMLS;
	}

	public void setMaximaDisconnected() {
		mMaximaDisconnected = true;
	}

	/** Classic's exact (typo'd) method name - kept verbatim for the vendored callers. */
	public boolean hasMaximaDiscxonnected() {
		return mMaximaDisconnected;
	}

	@Override
	public String toString() {
		return mUID + " " + getFullAddress() + (mIncoming ? " in" : " out");
	}
}
