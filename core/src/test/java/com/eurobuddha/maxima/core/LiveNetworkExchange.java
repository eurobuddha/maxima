package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.contacts.ContactCtrl;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.Capabilities;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A REAL TWO-WAY EXCHANGE ACROSS THE LIVE NETWORK.
 *
 * We attach to our own relay, then introduce ourselves to a stock Minima node
 * whose contact address routes through a THIRD-PARTY public host. The node
 * reciprocates, and its reply comes back to us through our own relay.
 *
 * Full path, both directions, all real infrastructure:
 *
 *   us -> (their public host) -> stock Minima node
 *   stock Minima node -> (our relay) -> us
 *
 * Nothing here is loopback and nothing is simulated. If the reciprocation
 * arrives, we are a first-class participant on the live Maxima network.
 */
public class LiveNetworkExchange {

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"(.*?)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static void main(String[] args) throws Exception {
        String myRelay = args.length > 0 ? args[0] : "31.125.188.214:8001";
        String infoPath = args.length > 1 ? args[1] : "/tmp/maxinfo.json";

        String json = new String(Files.readAllBytes(Paths.get(infoPath)), StandardCharsets.UTF_8);
        String nodeName = field(json, "name");
        String nodeContact = field(json, "contact");
        String nodePubkey = field(json, "publickey");

        System.out.println("=== LIVE NETWORK EXCHANGE ===");
        System.out.println("our relay   : " + myRelay);
        System.out.println("their node  : " + nodeName + " via " + nodeContact.split("@")[1]);
        System.out.println();

        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * 17 + 41);
        }
        MaximaIdentity me = MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));

        MaximaNode node = new MaximaNode(me, "1.0.48", 1);
        node.setName("maxima-core");
        node.setCapabilities(Capabilities.phoneDefaults());

        System.out.println("[1] attaching to our own relay");
        int n = node.start(Collections.singletonList(myRelay), 25000);
        if (n < 1) {
            System.out.println("    XX could not attach");
            System.exit(1);
        }
        String myAddr = node.myAddresses().get(0);
        System.out.println("    attached. our address:");
        System.out.println("      " + myAddr.substring(0, 30) + "...@" + myRelay);
        System.out.println();

        final boolean[] running = {true};
        Thread pump = new Thread(() -> {
            while (running[0]) {
                try {
                    node.pump(myRelay, 1500);
                } catch (Exception e) {
                    return;
                }
            }
        }, "pump");
        pump.setDaemon(true);
        pump.start();
        Thread.sleep(1200);

        System.out.println("[2] introducing ourselves to the stock node");
        System.out.println("    (sent to " + nodeContact.split("@")[1]
                + ", a third-party public host)");
        node.introduce(nodeContact, true);
        System.out.println("    sent **maxima_contact_ctrl** with intro=true");
        System.out.println();

        System.out.println("[3] waiting for the node to reciprocate THROUGH OUR RELAY...");
        Contact them = null;
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            them = node.contact(nodePubkey);
            if (them != null) {
                break;
            }
            Thread.sleep(1000);
        }

        running[0] = false;
        System.out.println();
        System.out.println("=====================================");
        if (them == null) {
            System.out.println("  NO RECIPROCATION WITHIN 90s");
            System.out.println("  Our introduction may still have landed - check the node with");
            System.out.println("    maxcontacts action:list");
            System.out.println("=====================================");
            node.stop();
            System.exit(1);
        }

        System.out.println("  TWO-WAY EXCHANGE COMPLETE");
        System.out.println("    the node introduced itself back:");
        System.out.println("      name      : " + them.name);
        System.out.println("      publickey : " + them.publicKey.substring(0, 34) + "...");
        System.out.println("      address   : " + (them.primaryAddress() == null ? "(none)"
                : them.primaryAddress().substring(0, 24) + "...@"
                + them.primaryAddress().split("@")[1]));
        System.out.println("      caps      : " + them.capabilities
                + (them.isClassic() ? "  <- a classic peer, as expected" : ""));
        System.out.println();
        System.out.println("    us   -> third-party host -> stock Minima node");
        System.out.println("    node -> OUR relay        -> us");
        System.out.println("=====================================");
        node.stop();
    }
}
