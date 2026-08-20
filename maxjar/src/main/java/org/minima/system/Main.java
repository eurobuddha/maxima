package org.minima.system;

import org.minima.maxjar.MaximaTransport;
import org.minima.system.network.NetworkManager;
import org.minima.system.network.maxima.MaximaManager;
import org.minima.system.network.minima.NIOManager;
import org.minima.utils.json.JSONObject;

/**
 * maxjar FACADE for classic Main: the singleton locator the vendored managers
 * reach back into. Holds the Maxima manager, the transport-backed NIO facade,
 * and the embedder's event listener (classic PostNotifyEvent - MAXIMA /
 * MAXIMAHOSTS / MAXIMACONTACTS land here, exactly the events MiniDapps get).
 */
public class Main {

	/** The embedder's event sink. */
	public interface NotifyListener {
		void onNotifyEvent(String zEvent, JSONObject zData);
	}

	private static Main mMain;

	public static Main getInstance() {
		return mMain;
	}

	/** Create the singleton - the embedder calls this once at startup. */
	public static Main init(MaximaTransport zTransport, NotifyListener zListener) {
		// Classic Main starts the global timer thread that PostTimerMessage
		// rides on - without it the 20-min MAXIMA_LOOP and the 30s
		// check-connect verification never fire.
		if (org.minima.utils.messages.TimerProcessor.getTimerProcessor() == null) {
			org.minima.utils.messages.TimerProcessor.createTimerProcessor();
		}
		mMain = new Main(zTransport, zListener);
		return mMain;
	}

	/** Tear down (tests). */
	public static void clear() {
		mMain = null;
		try {
			org.minima.utils.messages.TimerProcessor.stopTimerProcessor();
		} catch (Exception ignored) {
		}
	}

	private final MaximaTransport mTransport;
	private final NotifyListener mListener;
	private final NIOManager mNIOManager = new NIOManager();
	private final NetworkManager mNetworkManager = new NetworkManager();
	private MaximaManager mMaxima;

	private Main(MaximaTransport zTransport, NotifyListener zListener) {
		mTransport = zTransport;
		mListener = zListener;
	}

	public void setMaxima(MaximaManager zMaxima) {
		mMaxima = zMaxima;
	}

	public MaximaManager getMaxima() {
		return mMaxima;
	}

	public NIOManager getNIOManager() {
		return mNIOManager;
	}

	public NetworkManager getNetworkManager() {
		return mNetworkManager;
	}

	public MaximaTransport getTransport() {
		return mTransport;
	}

	public void PostNotifyEvent(String zEvent, JSONObject zData) {
		if (mListener != null) {
			try {
				mListener.onNotifyEvent(zEvent, zData);
			} catch (Exception ignored) {
			}
		}
	}
}
