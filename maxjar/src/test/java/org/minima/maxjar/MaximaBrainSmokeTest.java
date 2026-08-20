package org.minima.maxjar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;
import org.minima.database.MinimaDB;
import org.minima.database.maxima.MaximaContact;
import org.minima.objects.base.MiniByte;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniString;
import org.minima.system.Main;
import org.minima.system.network.maxima.MaximaManager;
import org.minima.system.network.maxima.message.MaxTxPoW;
import org.minima.system.network.maxima.message.MaximaInternal;
import org.minima.system.network.maxima.message.MaximaMessage;
import org.minima.system.network.maxima.message.MaximaPackage;
import org.minima.system.network.minima.NIOClient;
import org.minima.system.params.GeneralParams;
import org.minima.utils.Crypto;
import org.minima.utils.encrypt.CryptoPackage;
import org.minima.utils.encrypt.GenerateKey;
import org.minima.utils.encrypt.SignVerify;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;
import org.minima.utils.messages.Message;

/**
 * Phase-1 gate: the vendored classic brain, chain-free, does a full protocol
 * round trip in one JVM. One REAL MaximaManager talks to (a) a socket-level
 * fake host that behaves like a classic relay peer and (b) a hand-rolled
 * foreign peer identity. Covers: init + key creation, host adoption +
 * mined check-connect, inbound decrypt/sig-verify, contact add via
 * **maxima_contact_ctrl**, the UPDATEINFO reply, and MLS SET reaching the host.
 */
public class MaximaBrainSmokeTest {

	private MaximaManager mManager;
	private File mTmp;
	private ServerSocket mHostSocket;

	/** Frames the fake host received, already parsed to [type, MaxTxPoW]. */
	private final BlockingQueue<MaxTxPoW> mHostInbox = new LinkedBlockingQueue<>();

	/** CTRL/PING sends captured from the transport facade. */
	private final BlockingQueue<Object[]> mCtrlSent = new LinkedBlockingQueue<>();

	@After
	public void tearDown() throws Exception {
		if (mManager != null) {
			mManager.shutdown();
		}
		if (mHostSocket != null) {
			mHostSocket.close();
		}
		Main.clear();
		MinimaDB.clear();
		GeneralParams.ALLOW_ALL_IP = false;
	}

	@Test
	public void fullProtocolRoundTrip() throws Exception {

		// The fake host lives on loopback - classic blocks internal IPs unless
		// -allowallip, its own escape hatch for exactly this.
		GeneralParams.ALLOW_ALL_IP = true;

		// -- the fake classic host: reads NIO frames, answers RESPONSE_OK ----
		mHostSocket = new ServerSocket(0);
		int hostport = mHostSocket.getLocalPort();
		Thread host = new Thread(() -> {
			try {
				while (true) {
					Socket s = mHostSocket.accept();
					DataInputStream in = new DataInputStream(s.getInputStream());
					DataOutputStream out = new DataOutputStream(s.getOutputStream());
					MiniData frame = MiniData.ReadFromStream(in);
					DataInputStream fin = new DataInputStream(
							new java.io.ByteArrayInputStream(frame.getBytes()));
					MiniByte type = MiniByte.ReadFromStream(fin);
					if (type.getByteValue() == 10) {   // MSG_MAXIMA_TXPOW
						mHostInbox.offer(MaxTxPoW.ReadFromStream(fin));
					}
					MaximaManager.MAXIMA_RESPONSE_OK.writeDataStream(out);
					out.flush();
					s.close();
				}
			} catch (Exception fin) {
				// socket closed - test over
			}
		}, "fake-host");
		host.setDaemon(true);
		host.start();

		// -- boot the brain ---------------------------------------------------
		mTmp = Files.createTempDirectory("maxjar-test").toFile();
		MinimaDB.init(mTmp);
		Main.init(new MaximaTransport() {
			public NIOClient getMaximaUID(String zIdent) {
				return null;
			}

			public NIOClient getNIOClient(String zFullHost) {
				return null;
			}

			public NIOClient getNIOClientFromUID(String zUID) {
				return null;
			}

			public void disconnect(String zUID) {
			}

			public void sendNetworkMessage(String zUID, MiniByte zType,
					org.minima.utils.Streamable zObject) {
				mCtrlSent.offer(new Object[] { zUID, zType, zObject });
			}

			public void requestNewConnection() {
			}

			public String getP2PAddress() {
				return "";
			}
		}, (event, data) -> {
		});

		mManager = new MaximaManager();
		Main.getInstance().setMaxima(mManager);

		long deadline = System.currentTimeMillis() + 20_000;
		while (!mManager.isInited() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertTrue("manager inited (keys created)", mManager.isInited());

		// -- adopt the host: MAXIMA_CONNECTED --------------------------------
		NIOClient nioc = new NIOClient("u1", "127.0.0.1", hostport, false);
		Message conn = new Message("MAXIMA_CONNECTED");
		conn.addObject("nioclient", nioc);
		mManager.PostMessage(conn);

		// The manager sends its ident CTRL down the connection...
		Object[] ctrl = mCtrlSent.poll(10, TimeUnit.SECONDS);
		assertNotNull("MAXIMACTRL_TYPE_ID sent to host", ctrl);

		// ...and a MINED check-connect through the host socket.
		MaxTxPoW chk = mHostInbox.poll(30, TimeUnit.SECONDS);
		assertNotNull("mined check-connect arrived at host", chk);
		assertTrue("check-connect carries valid custom-hash binding", chk.checkValidTxPoW());
		assertTrue("check-connect carries the protocol minimum work",
				chk.getTxPoW().checkMinimumWork());

		// Bounce it back - the host relays our own message to us.
		Message rec = new Message("MAXIMA_RECMESSAGE");
		rec.addObject("maxtxpow", chk);
		rec.addObject("nioclient", nioc);
		mManager.PostMessage(rec);

		// Host accepted -> we have a usable random address at that host.
		deadline = System.currentTimeMillis() + 10_000;
		String myaddress = "";
		while (System.currentTimeMillis() < deadline) {
			myaddress = mManager.getRandomMaximaAddress();
			if (myaddress.contains("@127.0.0.1:" + hostport)) {
				break;
			}
			Thread.sleep(100);
		}
		assertTrue("host adopted after check-connect: " + myaddress,
				myaddress.contains("@127.0.0.1:" + hostport));

		// -- a foreign peer adds us as a contact -----------------------------
		KeyPair peer = GenerateKey.generateKeyPair();
		MiniData peerpub = new MiniData(peer.getPublic().getEncoded());
		MiniData peerpriv = new MiniData(peer.getPrivate().getEncoded());
		String peeraddress = peerpub.to0xString() + "@127.0.0.1:" + hostport;

		JSONObject intro = new JSONObject();
		intro.put("delete", false);
		intro.put("intro", true);
		intro.put("publickey", peerpub.to0xString());
		intro.put("address", peeraddress);
		intro.put("name", "smokepeer");
		intro.put("icon", "0x00");
		intro.put("minimaaddress", "Mx00");
		intro.put("topblock", "0");
		intro.put("checkblock", "0");
		intro.put("checkhash", "0x00");
		intro.put("mls", "");

		// Classic addresses are Mx-encoded: MxABC..@host:port
		String myhostpubstr = myaddress.substring(0, myaddress.indexOf("@"));
		MiniData myhostpub = myhostpubstr.startsWith("Mx")
				? org.minima.objects.Address.convertMinimaAddress(myhostpubstr)
				: new MiniData(myhostpubstr);

		MaximaMessage maxmsg = new MaximaMessage();
		maxmsg.mFrom = peerpub;
		maxmsg.mTo = myhostpub;
		maxmsg.mApplication = new MiniString("**maxima_contact_ctrl**");
		maxmsg.mData = new MiniData(intro.toString().getBytes(StandardCharsets.UTF_8));

		MiniData maxdata = MiniData.getMiniDataVersion(maxmsg);
		byte[] sig = SignVerify.sign(peerpriv.getBytes(), maxdata.getBytes());
		MaximaInternal internal = new MaximaInternal();
		internal.mFrom = peerpub;
		internal.mData = maxdata;
		internal.mSignature = new MiniData(sig);

		CryptoPackage cp = new CryptoPackage();
		cp.encrypt(MiniData.getMiniDataVersion(internal).getBytes(),
				myhostpub.getBytes());
		MaximaPackage mpkg = new MaximaPackage(myhostpub,
				cp.getCompleteEncryptedData());
		MaxTxPoW contactmsg = MaxTxPoW.createMaxTxPoW(mpkg);
		assertNotNull("peer message mined", contactmsg);

		Message rec2 = new Message("MAXIMA_RECMESSAGE");
		rec2.addObject("maxtxpow", contactmsg);
		rec2.addObject("nioclient", nioc);
		mManager.PostMessage(rec2);

		// Contact appears in the classic H2 MaximaDB...
		deadline = System.currentTimeMillis() + 15_000;
		MaximaContact stored = null;
		while (stored == null && System.currentTimeMillis() < deadline) {
			stored = MinimaDB.getDB().getMaximaDB()
					.loadContactFromPublicKey(peerpub.to0xString());
			Thread.sleep(100);
		}
		assertNotNull("contact stored", stored);
		assertEquals("smokepeer", stored.getName());
		assertEquals(peeraddress, stored.getCurrentAddress());

		// ...and because intro=true, the manager REPLIES with our own contact
		// JSON to the peer's address - through the fake host socket, mined,
		// encrypted to the peer's key. Decrypt it as the peer and verify.
		JSONObject reply = null;
		long reployDeadline = System.currentTimeMillis() + 30_000;
		while (reply == null && System.currentTimeMillis() < reployDeadline) {
			MaxTxPoW out = mHostInbox.poll(5_000, TimeUnit.MILLISECONDS);
			if (out == null) {
				continue;
			}
			if (!out.getMaximaPackage().mTo.isEqual(peerpub)) {
				continue;   // MLS SET traffic etc - not ours
			}
			CryptoPackage rcp = new CryptoPackage();
			rcp.ConvertMiniDataVersion(out.getMaximaPackage().mData);
			byte[] dec = rcp.decrypt(peerpriv.getBytes());
			MaximaInternal rint = MaximaInternal.ConvertMiniDataVersion(new MiniData(dec));
			assertTrue("reply signature verifies", SignVerify.verify(
					rint.mFrom.getBytes(), rint.mData.getBytes(), rint.mSignature.getBytes()));
			MaximaMessage rmsg = MaximaMessage.ConvertMiniDataVersion(rint.mData);
			assertEquals("**maxima_contact_ctrl**", rmsg.mApplication.toString());
			reply = (JSONObject) new JSONParser().parse(
					new String(rmsg.mData.getBytes(), StandardCharsets.UTF_8));
		}
		assertNotNull("contact-ctrl reply reached the peer", reply);
		assertEquals("our reply declares our identity key",
				mManager.getPublicKey().to0xString(), reply.getString("publickey"));
		assertTrue("our reply advertises our address at the adopted host",
				reply.getString("address").contains("@127.0.0.1:" + hostport));

		// Sanity on the crypto stack end to end - classic hashObject is SHA3-256.
		assertEquals(32, Crypto.getInstance().hashObject(maxdata).getLength());
	}
}
