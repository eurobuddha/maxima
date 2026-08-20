package com.eurobuddha.maxima.app.jar;

import android.content.Context;
import android.content.SharedPreferences;

import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.rpc.Capabilities;

import org.minima.database.MinimaDB;
import org.minima.database.maxima.MaximaContact;
import org.minima.database.maxima.MaximaDB;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniString;
import org.minima.system.Main;
import org.minima.system.network.maxima.MaxMsgHandler;
import org.minima.system.network.maxima.MaximaContactManager;
import org.minima.system.network.maxima.MaximaManager;
import org.minima.utils.json.JSONObject;
import org.minima.utils.messages.Message;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parlons riding maxima.jar: classic Maxima, extracted whole and chain-free,
 * as the routing engine under the existing chat brain and UI.
 *
 * ChatEngine speaks {@link ChatPort}; this adapter answers it from the
 * vendored classic managers - contact records from classic's own H2 MaximaDB,
 * sends through classic's own construct-sign-encrypt-mine-socket pipeline
 * (called synchronously so the two-tick receipt ladder keeps its SENT/FAILED
 * semantics), rotation and MLS healing handled by classic's MAXIMA_LOOP
 * exactly as a stock node does it.
 *
 * CAPABILITIES: classic's contact handshake drops keys it does not know, so
 * mxcaps cannot ride the verbatim contact-ctrl. Instead capability is LEARNED:
 * any inbound maxima_chat_v1 traffic from a key marks it Parlons-capable
 * (persisted), and we probe new contacts with a silent chat_v1 wallet-address
 * record - a Parlons peer answers in kind, a classic peer ignores an unknown
 * application. Until a peer proves capable it is treated as classic: maxsolo
 * wire, single tick, no resend spam - the safe default either way.
 */
public final class JarEngine implements ChatPort {

	/** The one application string Parlons chat owns (ChatMessage.APPLICATION). */
	private static final String CHAT_APP = "maxima_chat_v1";

	private static final String PREFS = "jar_engine";
	private static final String PREF_CAPS = "capable_keys";

	public interface Inbound {
		void onMessage(com.eurobuddha.maxima.core.msg.MaximaMessage zMsg, String zMsgid);
	}

	private final Context mCtx;
	private final MaximaManager mManager;
	private final org.minima.maxjar.SocketTransport mTransport;
	private final java.util.Set<String> mCapable =
			java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
	/** Last probe time per key - a probe an hour keeps the handshake alive
	 *  without spamming classic peers with unknown-application messages. */
	private final Map<String, Long> mProbed = new ConcurrentHashMap<>();

	private volatile Inbound mInbound;
	private volatile Runnable mContactsChanged;

	public JarEngine(Context zCtx, String zName, List<String> zHosts) throws Exception {
		mCtx = zCtx.getApplicationContext();

		SharedPreferences sp = mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		mCapable.addAll(sp.getStringSet(PREF_CAPS, new java.util.HashSet<>()));

		File data = new File(mCtx.getFilesDir(), "maxjar");
		if (!data.exists() && !data.mkdirs()) {
			throw new IllegalStateException("cannot create " + data);
		}
		MinimaDB.init(data);
		MinimaDB.getDB().getUserDB().setMaximaName(zName == null ? "noname" : zName);

		mTransport = new org.minima.maxjar.SocketTransport("1.0-parlons", zHosts);
		Main.init(mTransport, this::onNotifyEvent);

		mManager = new MaximaManager();
		Main.getInstance().setMaxima(mManager);
		long deadline = System.currentTimeMillis() + 30_000;
		while (!mManager.isInited() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		mTransport.start();
		EventLog.add("JAR ENGINE up - classic Maxima, " + zHosts.size() + " host(s)");
	}

	public void setInbound(Inbound zInbound) {
		mInbound = zInbound;
	}

	public void setContactsChanged(Runnable zRun) {
		mContactsChanged = zRun;
	}

	public MaximaManager manager() {
		return mManager;
	}

	public List<String> connectedHosts() {
		return mTransport.connectedHosts();
	}

	/** Our Mx wallet address into classic's contact handshake (minimaaddress). */
	public void setWalletAddress(String zMxAddress) {
		try {
			if (zMxAddress != null && zMxAddress.startsWith("Mx")) {
				MinimaDB.getDB().getWallet().setDefaultAddressHex(
						org.minima.objects.Address.convertMinimaAddress(zMxAddress)
								.to0xString());
			}
		} catch (Exception e) {
			EventLog.add("jar wallet address: " + e);
		}
	}

	public void shutdown() {
		try {
			mManager.shutdown();
		} catch (Exception ignored) {
		}
		mTransport.stop();
		MinimaDB.clear();
		Main.clear();
	}

	// ---------------------------------------------------------------
	// classic events -> app
	// ---------------------------------------------------------------

	private void onNotifyEvent(String zEvent, JSONObject zJson) {
		if ("MAXIMA".equals(zEvent)) {
			String app = String.valueOf(zJson.get("application"));
			String from = String.valueOf(zJson.get("from"));
			if (CHAT_APP.equals(app)) {
				markCapable(from);
			}
			Inbound in = mInbound;
			if (in == null) {
				return;
			}
			try {
				com.eurobuddha.maxima.core.msg.MaximaMessage m =
						new com.eurobuddha.maxima.core.msg.MaximaMessage();
				m.mFrom = new com.eurobuddha.maxima.core.codec.MiniData(from);
				m.mTo = new com.eurobuddha.maxima.core.codec.MiniData(
						String.valueOf(zJson.get("to")));
				m.mApplication = new com.eurobuddha.maxima.core.codec.MiniString(app);
				m.mData = new com.eurobuddha.maxima.core.codec.MiniData(
						String.valueOf(zJson.get("data")));
				in.onMessage(m, String.valueOf(zJson.get("msgid")));
			} catch (Exception e) {
				EventLog.add("jar inbound map: " + e);
			}
		} else if ("MAXIMACONTACTS".equals(zEvent)) {
			Runnable r = mContactsChanged;
			if (r != null) {
				try {
					r.run();
				} catch (Exception ignored) {
				}
			}
		} else if ("MAXIMAHOSTS".equals(zEvent)) {
			EventLog.add("jar host " + zJson.get("host") + " connected:"
					+ zJson.get("connected"));
		}
	}

	private void markCapable(String zKey) {
		String k = norm(zKey);
		if (mCapable.add(k)) {
			SharedPreferences sp = mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
			sp.edit().putStringSet(PREF_CAPS, new java.util.HashSet<>(mCapable)).apply();
			EventLog.add("peer proven Parlons-capable: " + k.substring(0, Math.min(20, k.length())) + "...");
		}
	}

	/** Probe contacts we have not proven capable: a silent chat_v1 wallet
	 *  record. Parlons peers answer in kind; classic peers ignore it. */
	public void probeContacts(com.eurobuddha.maxima.core.chat.ChatEngine zChat, String zWalletMx) {
		if (zChat == null || zWalletMx == null || zWalletMx.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Contact c : contacts()) {
			if (c.isClassic()) {   // not yet proven capable
				Long last = mProbed.get(norm(c.publicKey));
				if (last != null && now - last < 60 * 60 * 1000L) {
					continue;
				}
				mProbed.put(norm(c.publicKey), now);
				try {
					zChat.shareWalletAddress(c, zWalletMx);
				} catch (Exception ignored) {
				}
			}
		}
	}

	// ---------------------------------------------------------------
	// ChatPort
	// ---------------------------------------------------------------

	@Override
	public String publicKeyHex() {
		return norm(mManager.getPublicKey().to0xString());
	}

	@Override
	public String name() {
		return MinimaDB.getDB().getUserDB().getMaximaName();
	}

	@Override
	public Contact contact(String zPublicKey) {
		MaximaContact mc = MinimaDB.getDB().getMaximaDB()
				.loadContactFromPublicKey(norm(zPublicKey));
		return mc == null ? null : map(mc);
	}

	@Override
	public List<Contact> contacts() {
		List<Contact> out = new ArrayList<>();
		for (MaximaContact mc : MinimaDB.getDB().getMaximaDB().getAllContacts()) {
			out.add(map(mc));
		}
		return out;
	}

	private Contact map(MaximaContact zMc) {
		Contact c = new Contact(norm(zMc.getPublicKey()));
		c.name = zMc.getName();
		c.icon = zMc.getIcon();
		c.minimaAddress = zMc.getMinimaAddress();
		c.mls = zMc.getMLS();
		List<String> addrs = new ArrayList<>();
		if (zMc.getCurrentAddress() != null && !zMc.getCurrentAddress().isEmpty()) {
			addrs.add(zMc.getCurrentAddress());
		}
		c.setAddresses(addrs);
		// Capability is LEARNED (see class comment) - default classic.
		c.capabilities = mCapable.contains(norm(zMc.getPublicKey()))
				? Capabilities.phoneDefaults() : Capabilities.none();
		return c;
	}

	@Override
	public MaximaSender.Result sendToContact(Contact zContact, String zApplication,
			byte[] zData) throws Exception {
		MaximaContact mc = MinimaDB.getDB().getMaximaDB()
				.loadContactFromPublicKey(norm(zContact.publicKey));
		if (mc == null || mc.getCurrentAddress() == null
				|| mc.getCurrentAddress().isEmpty()) {
			throw new IllegalStateException("no classic address for " + zContact.name);
		}

		// Classic's OWN pipeline - createSendMessage builds and signs, then
		// construct (encrypt + MINE) and the raw-socket delivery, called
		// synchronously so the receipt ladder sees a real SENT/FAILED.
		Message send = org.minima.system.commands.maxima.maxima.createSendMessage(
				mc.getCurrentAddress(), zApplication, new MiniData(zData));
		MiniData wire = MaxMsgHandler.constructMaximaData(send);
		if (wire == null) {
			throw new IllegalStateException("could not mine message in time");
		}
		String host = send.getString("tohost");
		int port = send.getInteger("toport");
		MiniData resp = MaxMsgHandler.sendMaxPacket(host, port, wire);

		int status;
		if (resp.isEqual(MaximaManager.MAXIMA_RESPONSE_OK)) {
			status = 1;
		} else if (resp.isEqual(MaximaManager.MAXIMA_RESPONSE_UNKNOWN)) {
			status = 2;
		} else if (resp.isEqual(MaximaManager.MAXIMA_RESPONSE_TOOBIG)) {
			status = 3;
		} else if (resp.isEqual(MaximaManager.MAXIMA_RESPONSE_WRONGHASH)) {
			status = 4;
		} else {
			status = 0;
		}
		return MaximaSender.Result.of(status);
	}

	@Override
	public void introduce(String zPeerAddress, boolean zIntro) throws Exception {
		// classic maxcontacts action:add, verbatim flow
		JSONObject info = mManager.getContactsManager().getMaximaContactInfo(zIntro, false);
		MiniData mdata = new MiniData(new MiniString(info.toString()).getData());
		mManager.PostMessage(org.minima.system.commands.maxima.maxima.createSendMessage(
				zPeerAddress, MaximaContactManager.CONTACT_APPLICATION, mdata));
	}

	@Override
	public boolean removeContact(String zPublicKey) {
		MaximaDB db = MinimaDB.getDB().getMaximaDB();
		MaximaContact mc = db.loadContactFromPublicKey(norm(zPublicKey));
		if (mc == null) {
			return false;
		}
		Message del = new Message(MaximaContactManager.MAXCONTACTS_DELETECONTACT);
		del.addInteger("id", mc.getUID());
		mManager.getContactsManager().PostMessage(del);
		return true;
	}

	@Override
	public List<String> myAddresses() {
		List<String> out = new ArrayList<>();
		String a = mManager.getRandomMaximaAddress();
		if (a != null && !a.isEmpty()) {
			out.add(a);
		}
		return out;
	}

	@Override
	public void log(String zLine) {
		EventLog.add(zLine);
	}

	private static String norm(String zKey) {
		return zKey == null ? "" : zKey.toUpperCase().replace("0X", "0x");
	}
}
