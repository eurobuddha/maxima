package org.minima.database.wallet;

/**
 * maxjar FACADE for classic Wallet: Maxima publishes your default wallet
 * address (as minimaaddress) in every contact intro. The embedder injects the
 * real hex address from its own wallet; unset, "0x00" produces classic's own
 * Mx00 none-placeholder.
 */
public class Wallet {

	private String mDefaultAddressHex = "0x00";

	public void setDefaultAddressHex(String zAddressHex) {
		if (zAddressHex != null && !zAddressHex.isEmpty()) {
			mDefaultAddressHex = zAddressHex;
		}
	}

	public ScriptRow getDefaultAddress() {
		return new ScriptRow(mDefaultAddressHex);
	}

	/** Classic returns a ScriptRow; only getAddress() is read on this path. */
	public static class ScriptRow {
		private final String mAddress;

		ScriptRow(String zAddress) {
			mAddress = zAddress;
		}

		public String getAddress() {
			return mAddress;
		}
	}
}
