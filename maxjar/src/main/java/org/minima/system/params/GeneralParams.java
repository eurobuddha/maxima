package org.minima.system.params;

/**
 * maxjar CUT-DOWN of classic GeneralParams: only the fields the Maxima
 * subsystem (and MiniFile) actually read, with classic's default values.
 * Everything else in the 440-line original is node/chain configuration and
 * stays behind. The embedder sets these at startup.
 */
public class GeneralParams {

	/** Are we running a TxBlock (slave) node - forces static MLS in classic. */
	public static boolean TXBLOCK_NODE = false;

	/** Base folder for file writes (JsonDB persistence). */
	public static String BASE_FILE_FOLDER = "";

	/** Our advertised host, if externally reachable. */
	public static String MINIMA_HOST = "";

	/** Was the host explicitly set. */
	public static boolean IS_HOST_SET = false;

	/** Our listen port. */
	public static int MINIMA_PORT = 9001;

	/** Test params - shorter chains, classic uses this for checkblock depth. */
	public static boolean TEST_PARAMS = false;

	/** Is P2P enabled. */
	public static boolean P2P_ENABLED = true;

	/** Allow internal/private IPs as Maxima hosts (classic -allowallip). */
	public static boolean ALLOW_ALL_IP = false;

	/** Verbose Maxima logging. */
	public static boolean MAXIMA_LOGS = false;
}
