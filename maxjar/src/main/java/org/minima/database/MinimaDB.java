package org.minima.database;

import java.io.File;

import org.minima.database.maxima.MaximaDB;
import org.minima.database.txpowtree.TxPoWTree;
import org.minima.database.userprefs.UserDB;
import org.minima.database.wallet.Wallet;
import org.minima.utils.MinimaLogger;

/**
 * maxjar FACADE for classic MinimaDB: the singleton locator the vendored
 * managers read. It mounts the real MaximaDB (H2, classic's own two tables and
 * SQL) plus the cut-down UserDB (JSON file), and serves the constant chain-tip
 * and wallet facades. The 807-line original mounts a dozen chain databases
 * that stay behind.
 */
public class MinimaDB {

	private static MinimaDB mDB;

	public static MinimaDB getDB() {
		return mDB;
	}

	/**
	 * Mount the databases under the given data folder - the embedder calls this
	 * once BEFORE creating the MaximaManager. Layout mirrors classic:
	 * maximasql/maxima (H2) and userprefs.json.
	 */
	public static MinimaDB init(File zDataFolder) throws Exception {
		mDB = new MinimaDB(zDataFolder);
		return mDB;
	}

	public static void clear() {
		if (mDB != null) {
			try {
				mDB.mMaximaDB.saveDB(false);
				mDB.mMaximaDB.hardCloseDB();
			} catch (Exception ignored) {
			}
			mDB = null;
		}
	}

	private final File mDataFolder;
	private final MaximaDB mMaximaDB;
	private final UserDB mUserDB;
	private final TxPoWTree mTxPoWTree = new TxPoWTree();
	private final Wallet mWallet = new Wallet();

	private MinimaDB(File zDataFolder) throws Exception {
		mDataFolder = zDataFolder;

		// Classic layout: <base>/maximasql/maxima (MinimaDB.java:360-388)
		File sqlfolder = new File(zDataFolder, "maximasql");
		if (!sqlfolder.exists() && !sqlfolder.mkdirs()) {
			throw new IllegalStateException("Cannot create " + sqlfolder);
		}
		mMaximaDB = new MaximaDB();
		mMaximaDB.loadDB(new File(sqlfolder, "maxima"));

		mUserDB = new UserDB();
		File prefs = userPrefsFile();
		if (prefs.exists()) {
			mUserDB.loadDB(prefs);
		}
	}

	private File userPrefsFile() {
		return new File(mDataFolder, "userprefs.json");
	}

	public MaximaDB getMaximaDB() {
		return mMaximaDB;
	}

	public UserDB getUserDB() {
		return mUserDB;
	}

	public void saveUserDB() {
		try {
			mUserDB.saveDB(userPrefsFile());
		} catch (Exception e) {
			MinimaLogger.log("saveUserDB failed : " + e);
		}
	}

	public TxPoWTree getTxPoWTree() {
		return mTxPoWTree;
	}

	public Wallet getWallet() {
		return mWallet;
	}
}
