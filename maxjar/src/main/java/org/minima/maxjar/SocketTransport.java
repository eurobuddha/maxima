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
		for (Peer p : mByUid.values()) {
			p.close();
		}
	}

	public List<String> connectedHosts() {
		return new java.util.ArrayList<>(mByHost.keySet());
	}

	/** Stop maintaining a host (it stays dropped once disconnected). */
	public void removeHost(String zHostPort) {
		mHosts.remove(zHostPort);
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
		for (String host : mHosts) {
			Peer p = mByHost.get(host);
			if (p == null) {
				connect(host);
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
			nioc = new NIOClient(zUid, zHost, zPort, false);
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
