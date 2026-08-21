package org.minima.maxjar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.minima.objects.base.MiniByte;
import org.minima.objects.base.MiniData;
import org.minima.system.Main;
import org.minima.system.network.maxima.MaximaCTRLMessage;
import org.minima.system.network.maxima.MaximaManager;
import org.minima.system.network.maxima.message.MaxTxPoW;
import org.minima.system.network.minima.NIOClient;
import org.minima.utils.MinimaLogger;
import org.minima.utils.Streamable;
import org.minima.utils.messages.Message;

/**
 * The Phase-2 transport driver: MaximaTransport over plain blocking sockets
 * speaking the Minima P2P wire - ported from the proven :core implementation
 * that already attaches Parlons phones to stock 9001 nodes live.
 *
 * Wire shape (classic NIOClient/NIOManager):
 *   frame       = int32 BE length | body
 *   body        = uint8 type | payload
 *   greeting    = type 0, REQUIRED on connect - classic only raises
 *                 MAXIMA_CONNECTED from its greeting handler
 *   maxima ctrl = type 9, maxima txpow = type 10, ack/status = type 8 (PING)
 *   keep-alive  = type 11 SINGLE_PING -> answered with type 12 SINGLE_PONG
 *
 * The driver maintains OUTGOING connections to a configured host list, posts
 * classic's four events (MAXIMA_CONNECTED / MAXIMA_DISCONNECTED /
 * MAXIMA_CTRLMESSAGE / MAXIMA_RECMESSAGE) into the MaximaManager, and writes
 * whatever the brain sends back down the right socket. Relayed SENDS do not
 * pass through here - classic's MaxMsgHandler opens its own fresh socket per
 * send, and the vendored code does exactly that already.
 */
public class SocketTransport implements MaximaTransport {

	// classic NIO message types
	private static final int MSG_GREETING = 0;
	private static final int MSG_PING = 8;
	private static final int MSG_MAXIMA_CTRL = 9;
	private static final int MSG_MAXIMA_TXPOW = 10;
	private static final int MSG_SINGLE_PING = 11;
	private static final int MSG_SINGLE_PONG = 12;

	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final long RECONNECT_SWEEP_MS = 30_000;
	private static final long KEEPALIVE_INTERVAL_MS = 120_000;
	private static final long SILENCE_DROP_MS = 480_000;
	/** Frames larger than this are chain traffic - consumed and dropped. */
	private static final int MAX_KEEP_FRAME = 4 * 1024 * 1024;
	/** Classic's MaximaPackage cap (NIOMessage): a unit above this is TOOBIG. */
	private static final int MAX_MAXIMA_PACKAGE = 262144;
	/** LAN listener is unauthenticated (anyone on the WiFi) - bound the blast
	 *  radius: at most this many concurrent LAN connections, each a daemon
	 *  thread + brain-queue producer. Excess accepts are closed immediately. */
	private static final int MAX_LAN_CONNS = 24;
	private final java.util.concurrent.atomic.AtomicInteger mLanConns =
			new java.util.concurrent.atomic.AtomicInteger(0);

	private final String mVersion;
	private final List<String> mHosts = new CopyOnWriteArrayList<>();
	private final Map<String, Peer> mByUid = new ConcurrentHashMap<>();
	private final Map<String, Peer> mByHost = new ConcurrentHashMap<>();
	private final AtomicLong mUidGen = new AtomicLong(1);
	private volatile boolean mRunning = false;
	private volatile String mP2PAddress = "";

	public SocketTransport(String zVersion, List<String> zHosts) {
		mVersion = zVersion;
		mHosts.addAll(zHosts);
	}

	/** Our own externally-reachable host:port, if the embedder knows it. */
	public void setP2PAddress(String zHostPort) {
		mP2PAddress = zHostPort == null ? "" : zHostPort;
	}

	public void start() {
		mRunning = true;
		Thread t = new Thread(this::maintainLoop, "maxjar-transport-maintain");
		t.setDaemon(true);
		t.start();
	}

	public void stop() {
		mRunning = false;
		java.net.ServerSocket lan = mLanServer;
		if (lan != null) {
			try {
				lan.close();
			} catch (Exception ignored) {
			}
		}
		for (Peer p : mByUid.values()) {
			p.close();
		}
	}

	// ---------------------------------------------------------------
	// LAN direct endpoint - same-WiFi instant delivery
	//
	// A same-LAN peer dials us DIRECTLY with the classic one-shot send
	// (sendMaxPacket: one mined frame sealed to our IDENTITY key, await the
	// ack, close). The vendored brain decrypts identity-sealed units natively
	// (mpkg.mTo == mPublic), so this is just a better address for the same
	// wire units - classic-invisible by construction.
	// ---------------------------------------------------------------

	private volatile java.net.ServerSocket mLanServer;
	private volatile int mLanPort = 0;

	public int lanPort() {
		return mLanPort;
	}

	public void startLanListener() {
		if (mLanServer != null) {
			return;
		}
		try {
			mLanServer = new java.net.ServerSocket(0);
			mLanPort = mLanServer.getLocalPort();
			Thread t = new Thread(() -> {
				while (mRunning && !mLanServer.isClosed()) {
					try {
						java.net.Socket s = mLanServer.accept();
						if (mLanConns.get() >= MAX_LAN_CONNS) {
							// Over the cap: drop rather than spawn an unbounded
							// thread/queue producer for a LAN flooder.
							try { s.close(); } catch (Exception ignored) { }
							continue;
						}
						Thread h = new Thread(() -> handleLanConn(s), "maxjar-lan-conn");
						h.setDaemon(true);
						h.start();
					} catch (Exception e) {
						if (mRunning && !mLanServer.isClosed()) {
							MinimaLogger.log("LAN accept: " + e);
						}
					}
				}
			}, "maxjar-lan-listen");
			t.setDaemon(true);
			t.start();
			MinimaLogger.log("LAN direct endpoint listening on port " + mLanPort);
		} catch (Exception e) {
			MinimaLogger.log("LAN listener failed: " + e);
		}
	}

	private void handleLanConn(java.net.Socket zSock) {
		String uid = "lan" + mUidGen.getAndIncrement();
		mLanConns.incrementAndGet();
		try {
			zSock.setSoTimeout(30_000);
			Peer peer = new Peer(uid, zSock.getInetAddress().getHostAddress(),
					zSock.getPort(), zSock, true);
			mByUid.put(uid, peer);
			DataInputStream in = new DataInputStream(zSock.getInputStream());
			while (!zSock.isClosed()) {
				byte[] frame = readOrSkip(in, MAX_KEEP_FRAME);
				if (frame == null || frame.length == 0) {
					continue;
				}
				int type = frame[0] & 0xFF;
				DataInputStream din = new DataInputStream(
						new ByteArrayInputStream(frame, 1, frame.length - 1));
				if (type == MSG_MAXIMA_TXPOW) {
					MaxTxPoW mx = MaxTxPoW.ReadFromStream(din);
					if (!mx.checkValidTxPoW()) {
						peer.write(body(MSG_PING, MaximaManager.MAXIMA_WRONGHASH));
						continue;
					}
					if (mx.getMaximaPackage() != null
							&& mx.getMaximaPackage().mData.getLength() > MAX_MAXIMA_PACKAGE) {
						peer.write(body(MSG_PING, MaximaManager.MAXIMA_TOOBIG));
						continue;
					}
					MinimaLogger.log("LAN direct: inbound unit from "
							+ zSock.getInetAddress().getHostAddress());
					Message msg = new Message(MaximaManager.MAXIMA_RECMESSAGE);
					msg.addObject("maxtxpow", mx);
					msg.addObject("nioclient", peer.nioc);
					Main.getInstance().getMaxima().PostMessage(msg);
					// the brain acks via sendNetworkMessage(uid, MSG_PING, status)
				}
			}
		} catch (Exception done) {
			// one-shot classic socket: EOF/timeout is the normal end
		} finally {
			mLanConns.decrementAndGet();
			mByUid.remove(uid);
			try {
				zSock.close();
			} catch (Exception ignored) {
			}
		}
	}

	public List<String> connectedHosts() {
		return new java.util.ArrayList<>(mByHost.keySet());
	}

	/** Stop maintaining a host (it stays dropped once disconnected). */
	public void removeHost(String zHostPort) {
		mHosts.remove(zHostPort);
	}

	/** Start maintaining a host - connects on the spot, then the sweep owns it. */
	public void addHost(String zHostPort) {
		if (zHostPort == null || zHostPort.isEmpty() || mHosts.contains(zHostPort)) {
			return;
		}
		mHosts.add(zHostPort);
		requestNewConnection();
	}

	// ---------------------------------------------------------------
	// maintain: keep an outgoing connection to every configured host
	// ---------------------------------------------------------------

	private void maintainLoop() {
		while (mRunning) {
			sweep();
			try {
				Thread.sleep(RECONNECT_SWEEP_MS);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private synchronized void sweep() {
		long now = System.currentTimeMillis();
		java.util.List<String> toConnect = new java.util.ArrayList<>();
		for (String host : mHosts) {
			Peer p = mByHost.get(host);
			if (p == null) {
				// Blocking connect() must NOT run under this monitor - one dead
				// host would stall keepalives to healthy peers for the connect
				// timeout. Collect now, dial after releasing the lock.
				toConnect.add(host);
			} else if (now - p.lastRead > SILENCE_DROP_MS) {
				MinimaLogger.log("TRANSPORT silence-reap " + host);
				p.close();   // reader thread posts DISCONNECTED
			} else if (now - p.lastWrite > KEEPALIVE_INTERVAL_MS) {
				try {
					p.write(body(MSG_SINGLE_PING, new MiniData("0x00")));
				} catch (IOException e) {
					p.close();
				}
			}
		}
		// Off-lock: each connect() blocks up to CONNECT_TIMEOUT_MS.
		for (String host : toConnect) {
			connect(host);
		}
	}

	private void connect(String zHostPort) {
		int idx = zHostPort.lastIndexOf(':');
		if (idx < 0) {
			return;
		}
		String host = zHostPort.substring(0, idx);
		int port;
		try {
			port = Integer.parseInt(zHostPort.substring(idx + 1));
		} catch (NumberFormatException e) {
			return;
		}
		try {
			Socket sock = new Socket();
			sock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			String uid = "mxj" + mUidGen.getAndIncrement();
			Peer peer = new Peer(uid, host, port, sock);
			mByUid.put(uid, peer);
			mByHost.put(zHostPort, peer);

			// Greeting first - classic only wakes Maxima from its greeting
			// handler, and answers ours with its own.
			peer.write(body(MSG_GREETING, Greeting.commsOnly(mVersion, null, port)));

			Thread r = new Thread(() -> readLoop(peer), "maxjar-read-" + zHostPort);
			r.setDaemon(true);
			r.start();
		} catch (Exception e) {
			MinimaLogger.log("TRANSPORT connect failed " + zHostPort + " : " + e);
		}
	}

	// ---------------------------------------------------------------
	// read loop: classic NIOMessage dispatch, Maxima slice only
	// ---------------------------------------------------------------

	private void readLoop(Peer zPeer) {
		try {
			DataInputStream in = new DataInputStream(zPeer.socket.getInputStream());
			while (mRunning && !zPeer.socket.isClosed()) {
				byte[] frame = readOrSkip(in, MAX_KEEP_FRAME);
				zPeer.lastRead = System.currentTimeMillis();
				if (frame == null || frame.length == 0) {
					continue;   // oversized chain traffic, consumed and dropped
				}
				int type = frame[0] & 0xFF;
				DataInputStream din = new DataInputStream(
						new ByteArrayInputStream(frame, 1, frame.length - 1));

				if (type == MSG_GREETING) {
					Greeting g = Greeting.ReadFromStream(din);
					MinimaLogger.log("TRANSPORT greeting from " + zPeer.nioc.getFullAddress()
							+ " version " + g.getVersion());
					// Classic NIOMessage.java:368 - the greeting IS the connect event.
					Message conn = new Message(MaximaManager.MAXIMA_CONNECTED);
					conn.addObject("nioclient", zPeer.nioc);
					Main.getInstance().getMaxima().PostMessage(conn);

				} else if (type == MSG_MAXIMA_CTRL) {
					MaximaCTRLMessage ctrl = MaximaCTRLMessage.ReadFromStream(din);
					// OUR mailbox handshake (relay extension, never sent by
					// classic nodes) is handled HERE so the vendored brain
					// stays byte-verbatim: the relay held mail for us while we
					// were offline, just delivered it, and asks us to prove we
					// hold the routing key so it can DELETE the copies.
					if (ctrl.getType().getValue() == CTRL_MAILBOX_INFO) {
						answerMailboxChallenge(zPeer, ctrl);
						continue;
					}
					Message msg = new Message(MaximaManager.MAXIMA_CTRLMESSAGE);
					msg.addObject("maximactrl", ctrl);
					msg.addObject("nioclient", zPeer.nioc);
					Main.getInstance().getMaxima().PostMessage(msg);

				} else if (type == MSG_MAXIMA_TXPOW) {
					MaxTxPoW mx = MaxTxPoW.ReadFromStream(din);
					// Classic NIOMessage.java:997-1040: bad hash-binding is
					// answered WRONGHASH and dropped.
					if (!mx.checkValidTxPoW()) {
						zPeer.write(body(MSG_PING, MaximaManager.MAXIMA_WRONGHASH));
						continue;
					}
					// Classic's 256K MaximaPackage cap - a hostile host must not
					// hand the brain a ~4MB package to RSA-decrypt and process.
					if (mx.getMaximaPackage() != null
							&& mx.getMaximaPackage().mData.getLength() > MAX_MAXIMA_PACKAGE) {
						zPeer.write(body(MSG_PING, MaximaManager.MAXIMA_TOOBIG));
						continue;
					}
					Message msg = new Message(MaximaManager.MAXIMA_RECMESSAGE);
					msg.addObject("maxtxpow", mx);
					msg.addObject("nioclient", zPeer.nioc);
					Main.getInstance().getMaxima().PostMessage(msg);

				} else if (type == MSG_SINGLE_PING) {
					zPeer.write(body(MSG_SINGLE_PONG,
							Greeting.commsOnly(mVersion, null, zPeer.nioc.getPort())));

				} else {
					// MSG_PING acks, pongs, chain smalltalk - not ours; classic
					// never disconnects on an unhandled type and nor do we.
				}
			}
		} catch (Exception e) {
			// fall through to disconnect
		}
		dropPeer(zPeer);
	}

	private void dropPeer(Peer zPeer) {
		zPeer.close();
		mByUid.remove(zPeer.nioc.getUID());
		mByHost.remove(zPeer.nioc.getFullAddress());
		MinimaLogger.log("TRANSPORT disconnected " + zPeer.nioc.getFullAddress());
		// Classic NIOManager.java:524 - reconnect true: the maintain loop will.
		Message disc = new Message(MaximaManager.MAXIMA_DISCONNECTED);
		disc.addObject("nioclient", zPeer.nioc);
		disc.addBoolean("reconnect", true);
		MaximaManager max = Main.getInstance() == null ? null
				: Main.getInstance().getMaxima();
		if (max != null) {
			max.PostMessage(disc);
		}
	}

	// ---------------------------------------------------------------
	// MaximaTransport
	// ---------------------------------------------------------------

	@Override
	public NIOClient getMaximaUID(String zMaximaIdent) {
		for (Peer p : mByUid.values()) {
			if (p.nioc.getMaximaIdent().equalsIgnoreCase(zMaximaIdent)) {
				return p.nioc;
			}
		}
		return null;
	}

	@Override
	public NIOClient getNIOClient(String zFullHost) {
		Peer p = mByHost.get(zFullHost);
		return p == null ? null : p.nioc;
	}

	@Override
	public NIOClient getNIOClientFromUID(String zUID) {
		Peer p = mByUid.get(zUID);
		return p == null ? null : p.nioc;
	}

	@Override
	public void disconnect(String zUID) {
		Peer p = mByUid.get(zUID);
		if (p != null) {
			p.close();
		}
	}

	@Override
	public void sendNetworkMessage(String zUID, MiniByte zType, Streamable zObject)
			throws IOException {
		Peer p = mByUid.get(zUID);
		if (p == null) {
			throw new IOException("No such connection " + zUID);
		}
		p.write(body(zType.getValue(), zObject));
	}

	@Override
	public void requestNewConnection() {
		Thread t = new Thread(this::sweep, "maxjar-reconnect");
		t.setDaemon(true);
		t.start();
	}

	@Override
	public String getP2PAddress() {
		return mP2PAddress;
	}

	// ---------------------------------------------------------------
	// mailbox handshake (relay extension - classic-invisible)
	// ---------------------------------------------------------------

	/** Relay->client after a drain: [key][maxSeq]. Client->relay reply:
	 *  [key][seq][signature by the routing PRIVATE key]. Same values as the
	 *  relay's RelayServer.CTRL_MAILBOX_* - keep in lockstep. */
	private static final int CTRL_MAILBOX_INFO = 40;
	private static final int CTRL_MAILBOX_ACK = 41;

	private void answerMailboxChallenge(Peer zPeer, MaximaCTRLMessage zCtrl) {
		try {
			java.io.DataInputStream d = new java.io.DataInputStream(
					new java.io.ByteArrayInputStream(zCtrl.getData().getBytes()));
			MiniData key = MiniData.ReadFromStream(d);
			long seq = org.minima.objects.base.MiniNumber.ReadFromStream(d).getAsLong();

			// Only ack a key we actually hold - the host keypair we created for
			// this relay. Anything else is not ours to destroy.
			org.minima.database.maxima.MaximaHost host =
					org.minima.database.MinimaDB.getDB().getMaximaDB()
							.loadHostFromPublicKey(key.to0xString());
			if (host == null) {
				return;
			}

			// canonical: "maxack" + key DER + 8-byte big-endian seq (relay mirrors)
			java.io.ByteArrayOutputStream cb = new java.io.ByteArrayOutputStream();
			java.io.DataOutputStream cd = new java.io.DataOutputStream(cb);
			cd.write("maxack".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			cd.write(key.getBytes());
			cd.writeLong(seq);
			cd.flush();
			byte[] sig = org.minima.utils.encrypt.SignVerify.sign(
					host.getPrivateKey().getBytes(), cb.toByteArray());

			java.io.ByteArrayOutputStream ab = new java.io.ByteArrayOutputStream();
			java.io.DataOutputStream ad = new java.io.DataOutputStream(ab);
			key.writeDataStream(ad);
			new org.minima.objects.base.MiniNumber(seq).writeDataStream(ad);
			new MiniData(sig).writeDataStream(ad);
			ad.flush();
			MaximaCTRLMessage ack = new MaximaCTRLMessage(
					new org.minima.objects.base.MiniByte(CTRL_MAILBOX_ACK));
			ack.setData(new MiniData(ab.toByteArray()));
			zPeer.write(body(MSG_MAXIMA_CTRL, ack));
			MinimaLogger.log("mailbox: held mail collected - signed ack sent to "
					+ zPeer.nioc.getFullAddress());
		} catch (Exception e) {
			MinimaLogger.log("mailbox ack failed: " + e);
		}
	}

	// ---------------------------------------------------------------
	// framing
	// ---------------------------------------------------------------

	private static byte[] body(int zType, Streamable zObj) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(bos);
		new MiniByte(zType).writeDataStream(dos);
		zObj.writeDataStream(dos);
		dos.flush();
		return bos.toByteArray();
	}

	/** Read one frame body; consume-and-drop anything over zMaxKeep. */
	private static byte[] readOrSkip(DataInputStream zIn, int zMaxKeep) throws IOException {
		int len = zIn.readInt();
		if (len < 0 || len > 256 * 1024 * 1024) {
			throw new IOException("Bad frame length " + len);
		}
		if (len <= zMaxKeep) {
			byte[] b = new byte[len];
			zIn.readFully(b);
			return b;
		}
		byte[] chunk = new byte[8192];
		int remaining = len;
		while (remaining > 0) {
			int want = Math.min(chunk.length, remaining);
			zIn.readFully(chunk, 0, want);
			remaining -= want;
		}
		return null;
	}

	private static final class Peer {
		final NIOClient nioc;
		final Socket socket;
		final DataOutputStream out;
		volatile long lastRead = System.currentTimeMillis();
		volatile long lastWrite = System.currentTimeMillis();

		Peer(String zUid, String zHost, int zPort, Socket zSock) throws IOException {
			this(zUid, zHost, zPort, zSock, false);
		}

		Peer(String zUid, String zHost, int zPort, Socket zSock, boolean zIncoming)
				throws IOException {
			nioc = new NIOClient(zUid, zHost, zPort, zIncoming);
			socket = zSock;
			out = new DataOutputStream(zSock.getOutputStream());
		}

		synchronized void write(byte[] zBody) throws IOException {
			out.writeInt(zBody.length);
			out.write(zBody);
			out.flush();
			lastWrite = System.currentTimeMillis();
		}

		void close() {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}
}
