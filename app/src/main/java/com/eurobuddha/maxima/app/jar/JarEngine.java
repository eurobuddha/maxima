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
		this(zCtx, zName, zHosts, null);
	}

	/** @param zPostDbInit runs after the databases mount and BEFORE the
	 *  MaximaManager first initialises - the migration's identity-seed window
	 *  (classic only creates keys when none exist). */
	public JarEngine(Context zCtx, String zName, List<String> zHosts,
			Runnable zPostDbInit) throws Exception {
		mCtx = zCtx.getApplicationContext();

		SharedPreferences sp = mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		mCapable.addAll(sp.getStringSet(PREF_CAPS, new java.util.HashSet<>()));

		File data = new File(mCtx.getFilesDir(), "maxjar");
		if (!data.exists() && !data.mkdirs()) {
			throw new IllegalStateException("cannot create " + data);
		}
		MinimaDB.init(data);
		MinimaDB.getDB().getUserDB().setMaximaName(zName == null ? "noname" : zName);

		if (zPostDbInit != null) {
			zPostDbInit.run();
		}

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

	/** Connect to an additional host LIVE - no service restart. Classic adopts
	 *  it via the mined check-connect like any other. */
	public void connectHost(String zHostPort) {
		mTransport.addHost(zHostPort);
	}

	// ---------------------------------------------------------------
	// static MLS + permanent MAX# addresses (classic-native)
	// ---------------------------------------------------------------

	/** Pin (or clear, with "") a static MLS - classic's own feature, so the
	 *  20-min loop publishes our location there from now on. */
	public void setStaticMls(String zIdentity) {
		boolean on = zIdentity != null && !zIdentity.trim().isEmpty();
		mManager.setStaticMLS(on, on ? zIdentity.trim() : "");
		EventLog.add(on ? "static MLS pinned (classic)" : "static MLS cleared (classic)");
	}

	public boolean isStaticMls() {
		return mManager.isStaticMLS();
	}

	/** The MLS host our location publishes to right now (static or rotating). */
	public String mlsHost() {
		String h = mManager.getMLSHost();
		return h == null ? "" : h;
	}

	/** Our permanent shareable address - only meaningful with a static MLS. */
	public String permanentAddress() {
		if (!mManager.isStaticMLS()) {
			return "";
		}
		return "MAX#" + mManager.getPublicKey().to0xString() + "#" + mlsHost();
	}

	/** Resolve a MAX#publickey#mlshost permanent address to the peer's CURRENT
	 *  address via a classic MLS GET straight to the named host. Blocking -
	 *  call off the main thread. Returns null when the host has no (visible)
	 *  record. Runs its own socket so the vendored response path (which expects
	 *  the key to already be a contact) is never involved. */
	public String resolveMax(String zMaxAddress) {
		try {
			String clean = zMaxAddress.trim();
			if (!clean.startsWith("MAX#")) {
				return null;
			}
			int split = clean.indexOf('#', 4);
			if (split < 0) {
				return null;
			}
			String pubkey = clean.substring(4, split);
			String mlshost = clean.substring(split + 1);

			org.minima.system.network.maxima.mls.MLSPacketGETReq req =
					new org.minima.system.network.maxima.mls.MLSPacketGETReq(
							pubkey, MaximaManager.MLS_RANDOM_UID);
			Message send = org.minima.system.commands.maxima.maxima.createSendMessage(
					mlshost, MaximaManager.MAXIMA_MLS_GETAPP,
					MiniData.getMiniDataVersion(req));
			MiniData wire = MaxMsgHandler.constructMaximaData(send);
			if (wire == null) {
				return null;
			}
			String host = send.getString("tohost");
			int port = send.getInteger("toport");

			try (java.net.Socket sock = new java.net.Socket()) {
				sock.connect(new java.net.InetSocketAddress(host, port), 20000);
				sock.setSoTimeout(20000);
				java.io.DataOutputStream dos =
						new java.io.DataOutputStream(sock.getOutputStream());
				java.io.DataInputStream dis =
						new java.io.DataInputStream(sock.getInputStream());
				wire.writeDataStream(dos);
				dos.flush();
				long deadline = System.currentTimeMillis() + 20000;
				while (System.currentTimeMillis() < deadline) {
					MiniData resp = MiniData.ReadFromStream(dis);
					try {
						java.io.DataInputStream rdis = new java.io.DataInputStream(
								new java.io.ByteArrayInputStream(resp.getBytes()));
						org.minima.objects.base.MiniByte.ReadFromStream(rdis);
						MiniData data = MiniData.ReadFromStream(rdis);
						org.minima.system.network.maxima.mls.MLSPacketGETResp mls =
								org.minima.system.network.maxima.mls.MLSPacketGETResp
										.convertMiniDataVersion(data);
						if (!mls.getRandomUID().equals(MaximaManager.MLS_RANDOM_UID)) {
							return null;   // not our request - security check
						}
						return mls.getAddress();
					} catch (Exception notMls) {
						// a bare status byte (OK/UNKNOWN...) - keep reading briefly
						if (resp.getLength() <= 8) {
							continue;
						}
						return null;
					}
				}
			}
			return null;
		} catch (Exception e) {
			EventLog.add("MAX# resolve failed: " + e);
			return null;
		}
	}

	/** Register our key with a staticMLS pool server (the eurobuddha API) so
	 *  STRANGERS can resolve our MAX# there, then pin it. Blocking. */
	public boolean registerWithPool(String zApiBase, String zServerId, String zIdentity) {
		try {
			java.net.HttpURLConnection c = (java.net.HttpURLConnection)
					new java.net.URL(zApiBase + "?action=register&publickey="
							+ publicKeyHex() + "&server_id=" + zServerId).openConnection();
			c.setConnectTimeout(15000);
			c.setReadTimeout(25000);
			java.io.InputStream in = c.getInputStream();
			byte[] buf = new byte[4096];
			StringBuilder sb = new StringBuilder();
			int n;
			while ((n = in.read(buf)) > 0) {
				sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
			}
			in.close();
			boolean ok = sb.toString().contains("\"status\":true");
			if (ok) {
				setStaticMls(zIdentity);
			}
			EventLog.add("pool register " + zServerId + ": " + (ok ? "OK" : sb));
			return ok;
		} catch (Exception e) {
			EventLog.add("pool register failed: " + e);
			return false;
		}
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
				// EVERY field, not just the ones the happy path reads - the
				// maxsolo handler stamps arrival time from mTimeMilli, and a
				// null here silently ate every inbound message.
				long timemilli;
				try {
					timemilli = Long.parseLong(String.valueOf(zJson.get("timemilli")));
				} catch (Exception nfe) {
					timemilli = System.currentTimeMillis();
				}
				m.mTimeMilli = new com.eurobuddha.maxima.core.codec.MiniNumber(timemilli);
				String rand = String.valueOf(zJson.get("random"));
				m.mRandom = new com.eurobuddha.maxima.core.codec.MiniData(
						rand != null && rand.startsWith("0x") ? rand : "0x00");
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

	/** Migration hook: a peer the old engine had proven Parlons-capable. */
	public void noteCapable(String zKey) {
		markCapable(zKey);
	}

	/** Post-migration heal, both directions, without waiting for the loops:
	 *  push OUR new address to every contact (MAXIMA_REFRESH) and force-pull
	 *  THEIR current addresses from their MLS (MAXIMA_CHECK_MLS force:true).
	 *  The pull matters when BOTH ends migrated in quick succession - each
	 *  side's stored address for the other went stale simultaneously, so the
	 *  push alone is aimed at a dead slot and only the MLS lookup can heal it
	 *  (classic's backstop, normally 30 minutes away). Delayed so hosts have
	 *  adopted first; repeated once in case the first pass raced the publish. */
	public void announceContactsSoon() {
		Thread t = new Thread(() -> {
			try {
				for (int pass = 0; pass < 2; pass++) {
					Thread.sleep(pass == 0 ? 30_000 : 90_000);
					mManager.PostMessage(MaximaManager.MAXIMA_REFRESH);
					org.minima.utils.messages.Message chk =
							new org.minima.utils.messages.Message(
									MaximaManager.MAXIMA_CHECK_MLS);
					chk.addBoolean("force", true);
					mManager.PostMessage(chk);
					EventLog.add("migration: refresh pushed + forced MLS re-lookup (pass "
							+ (pass + 1) + ")");
				}
			} catch (Exception ignored) {
			}
		}, "jar-migrate-announce");
		t.setDaemon(true);
		t.start();
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
		// Presence: classic bumps lastseen on every inbound from them - without
		// this mapping every contact reads as permanently offline.
		c.lastSeen = zMc.getLastSeen();
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
		String address = zPeerAddress;
		// A MAX# permanent address resolves to the peer's CURRENT address via
		// their static MLS first - that is the whole point of it.
		if (address != null && address.trim().startsWith("MAX#")) {
			address = resolveMax(address);
			if (address == null) {
				throw new IllegalStateException(
						"MAX# did not resolve - is the peer registered at that MLS?");
			}
			EventLog.add("MAX# resolved to " + address);
		}
		// classic maxcontacts action:add, verbatim flow
		JSONObject info = mManager.getContactsManager().getMaximaContactInfo(zIntro, false);
		MiniData mdata = new MiniData(new MiniString(info.toString()).getData());
		mManager.PostMessage(org.minima.system.commands.maxima.maxima.createSendMessage(
				address, MaximaContactManager.CONTACT_APPLICATION, mdata));
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
