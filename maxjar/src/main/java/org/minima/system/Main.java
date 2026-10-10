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

	// Failed application writes stay pending until a mailbox barrier can retry them.
	// The relay retains its encrypted copy while any notification is not durable.
	private final java.util.Map<String, JSONObject> mPending = new java.util.LinkedHashMap<>();
	private boolean mPendingOverflow;
	public synchronized void PostNotifyEvent(String event, JSONObject data) {
		if (mListener == null) return;
		try { mListener.onNotifyEvent(event, data); }
		catch (RuntimeException failed) {
			if (!"MAXIMA".equals(event)) return;
			String id = String.valueOf(data.get("msgid"));
			if (mPending.size() >= 500 && !mPending.containsKey(id)) mPendingOverflow = true;
			else mPending.put(id, data);
		}
	}

	/** Called on the classic manager lane, after all messages preceding a drain challenge. */
	public synchronized boolean flushNotifications() {
		for (String id : new java.util.ArrayList<>(mPending.keySet())) {
			try { mListener.onNotifyEvent("MAXIMA", mPending.get(id)); mPending.remove(id); }
			catch (RuntimeException failed) { return false; }
		}
		return !mPendingOverflow;
	}
}
