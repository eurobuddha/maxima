package org.minima.maxjar;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.minima.database.MinimaDB;
import org.minima.database.maxima.MaximaContact;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniString;
import org.minima.system.Main;
import org.minima.system.commands.maxima.maxima;
import org.minima.system.network.maxima.MaximaContactManager;
import org.minima.system.network.maxima.MaximaManager;
import org.minima.system.params.GeneralParams;
import org.minima.utils.MinimaLogger;
import org.minima.utils.json.JSONObject;

/**
 * The standalone maxima.jar node: classic Maxima, chain-free, on real sockets.
 *
 * Usage:
 *   java -cp maxima.jar org.minima.maxjar.MaxJarNode \
 *        -data /path/to/data -hosts host1:9001,host2:9001 [-name Alice] [-logs]
 *
 * REPL commands (the phase-3 parity-gate harness):
 *   address            my current random Maxima address (rotates per call)
 *   identity           my permanent identity (MAX#... style pubkey)
 *   hosts              connected hosts
 *   contacts           stored contacts
 *   add <Mx..@h:p>     add a contact - classic maxcontacts action:add
 *   send <name> <txt>  send a chat message (MaxSolo application) to a contact
 *   mls                my current/old MLS hosts
 *   quit
 */
public class MaxJarNode {

	public static void main(String[] zArgs) throws Exception {

		File data = new File("maxjar-data");
		List<String> hosts = new ArrayList<>();
		String name = "maxjar";

		for (int i = 0; i < zArgs.length; i++) {
			switch (zArgs[i]) {
			case "-data":
				data = new File(zArgs[++i]);
				break;
			case "-hosts":
				hosts.addAll(Arrays.asList(zArgs[++i].split(",")));
				break;
			case "-name":
				name = zArgs[++i];
				break;
			case "-logs":
				GeneralParams.MAXIMA_LOGS = true;
				break;
			default:
				break;
			}
		}
		if (!data.exists() && !data.mkdirs()) {
			throw new IllegalStateException("Cannot create data dir " + data);
		}

		MinimaDB.init(data);
		MinimaDB.getDB().getUserDB().setMaximaName(name);

		SocketTransport transport = new SocketTransport("1.0-maxjar", hosts);
		Main.init(transport, (event, json) -> {
			if ("MAXIMA".equals(event)) {
				String app = String.valueOf(json.get("application"));
				String from = String.valueOf(json.get("from"));
				String dataHex = String.valueOf(json.get("data"));
				String text = dataHex;
				try {
					text = new String(new MiniData(dataHex).getBytes(), StandardCharsets.UTF_8);
				} catch (Exception ignored) {
				}
				System.out.println("\n[MAXIMA " + app + "] from " + from + " : " + text);
			} else {
				System.out.println("\n[" + event + "] " + json);
			}
		});

		MaximaManager manager = new MaximaManager();
		Main.getInstance().setMaxima(manager);
		while (!manager.isInited()) {
			Thread.sleep(50);
		}
		transport.start();

		System.out.println("maxjar node up");
		System.out.println("identity : " + manager.getMaximaIdentity());
		System.out.println("data     : " + data.getAbsolutePath());
		System.out.println("hosts    : " + hosts);

		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		String line;
		System.out.print("> ");
		while ((line = in.readLine()) != null) {
			try {
				handle(manager, transport, line.trim());
			} catch (Exception e) {
				System.out.println("error: " + e);
			}
			System.out.print("> ");
		}
	}

	private static void handle(MaximaManager zManager, SocketTransport zTransport,
			String zLine) throws Exception {
		if (zLine.isEmpty()) {
			return;
		}
		String[] parts = zLine.split("\\s+", 3);
		switch (parts[0]) {

		case "address":
			System.out.println(zManager.getRandomMaximaAddress());
			break;

		case "identity":
			System.out.println(zManager.getMaximaIdentity());
			break;

		case "hosts":
			System.out.println(zTransport.connectedHosts());
			break;

		case "mls":
			System.out.println("mls     : " + zManager.getMLSHost());
			System.out.println("old mls : " + zManager.getOldMLSHost());
			break;

		case "contacts":
			for (MaximaContact c : MinimaDB.getDB().getMaximaDB().getAllContacts()) {
				System.out.println(c.getName()
						+ "\n  publickey : " + c.getPublicKey()
						+ "\n  current   : " + c.getCurrentAddress()
						+ "\n  myaddress : " + c.getMyAddress()
						+ "\n  mls       : " + c.getMLS());
			}
			break;

		case "add": {
			// classic maxcontacts action:add, verbatim flow
			String address = parts[1];
			JSONObject contactinfo = zManager.getContactsManager()
					.getMaximaContactInfo(true, false);
			MiniString datastr = new MiniString(contactinfo.toString());
			MiniData mdata = new MiniData(datastr.getData());
			zManager.PostMessage(maxima.createSendMessage(address,
					MaximaContactManager.CONTACT_APPLICATION, mdata));
			System.out.println("intro sent to " + address);
			break;
		}

		case "send": {
			// MaxSolo-compatible chat message to a stored contact by name
			String who = parts[1];
			String text = parts.length > 2 ? parts[2] : "";
			MaximaContact target = null;
			for (MaximaContact c : MinimaDB.getDB().getMaximaDB().getAllContacts()) {
				if (c.getName().equals(who)) {
					target = c;
				}
			}
			if (target == null) {
				System.out.println("no contact named " + who);
				return;
			}
			String json = "{\"username\":\""
					+ MinimaDB.getDB().getUserDB().getMaximaName()
					+ "\",\"type\":\"text\",\"message\":\"" + text + "\"}";
			MiniData mdata = new MiniData(json.getBytes(StandardCharsets.UTF_8));
			zManager.PostMessage(maxima.createSendMessage(target.getCurrentAddress(),
					"maxsolo", mdata));
			System.out.println("sent to " + who + " @ " + target.getCurrentAddress());
			break;
		}

		case "drop": {
			// Kill the live connection to a host - the rotation-heal gate.
			// The maintain sweep will reconnect after ~30s, exactly like a
			// flapping host; MAXIMA_DISCONNECTED reassignment fires first.
			org.minima.system.network.minima.NIOClient nioc =
					zTransport.getNIOClient(parts[1]);
			if (nioc == null) {
				System.out.println("not connected to " + parts[1]);
			} else {
				zTransport.disconnect(nioc.getUID());
				System.out.println("dropped " + parts[1]);
			}
			break;
		}

		case "unhost": {
			// Remove a host from the maintain list AND drop it - a host that
			// is gone for good, not flapping.
			zTransport.removeHost(parts[1]);
			org.minima.system.network.minima.NIOClient nioc =
					zTransport.getNIOClient(parts[1]);
			if (nioc != null) {
				zTransport.disconnect(nioc.getUID());
			}
			System.out.println("unhosted " + parts[1]);
			break;
		}

		case "quit":
		case "exit":
			zManager.shutdown();
			zTransport.stop();
			MinimaDB.clear();
			System.exit(0);
			break;

		default:
			System.out.println("commands: address identity hosts mls contacts add send quit");
		}
	}

	static {
		// Keep the console readable - classic logs go to stdout anyway.
		MinimaLogger.log("maxjar starting");
	}
}
