package org.minima.system.params;

/**
 * maxjar CUT-DOWN of classic GlobalParams: the one protocol constant the
 * vendored TxHeader reads. Chain speed/cascade tuning stays behind.
 */
public class GlobalParams {

	/** Number of cascade super-parent levels in a TxPoW header. */
	public static int MINIMA_CASCADE_LEVELS = 32;
}
