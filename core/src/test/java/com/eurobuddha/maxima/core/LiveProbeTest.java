package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.DirectEndpoint;
import com.eurobuddha.maxima.core.net.Probe;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * probe.dial end to end against a REAL relay.
 *
 * Must run on a box with a PUBLIC IP and the listen port open, because the
 * relay probes the SOURCE IP of the request - so the requester and the listener
 * have to be the same public address. On a fleet box (no NAT) a DirectEndpoint
 * on 0.0.0.0:port is directly reachable once ufw allows it.
 *
 *   java -cp probe.jar LiveProbeTest <relay-host:port> <listen-port>
 *
 * Expected: reachable=true. A run with the port CLOSED in ufw must report
 * reachable=false - that is the honest negative that proves the probe is really
 * dialling and not rubber-stamping.
 */
public class LiveProbeTest {

    static MaximaIdentity idFrom(int zSalt) {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * zSalt + zSalt);
        }
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
    }

    public static void main(String[] args) throws Exception {
        String relay = args[0];
        int listenPort = Integer.parseInt(args[1]);

        System.out.println("=== probe.dial LIVE via " + relay
                + ", listening on " + listenPort + " ===");

        MaximaIdentity me = idFrom(91);
        DirectEndpoint ep = new DirectEndpoint(me, "1.0.48", inb -> { });
        int bound = ep.start(listenPort);
        System.out.println("[1] DirectEndpoint listening on " + bound);

        MaximaNode node = new MaximaNode(me, "1.0.48", 1);
        int attached = node.start(Collections.singletonList(relay), 30000);
        System.out.println("[2] attached to relay: " + attached);

        // Pump so the CTRL/MLS frame (the relay's identity address) is read.
        CountDownLatch stop = new CountDownLatch(1);
        Thread pump = new Thread(() -> {
            while (stop.getCount() > 0) {
                try {
                    node.pump(relay, 1000);
                } catch (Exception e) {
                    if (stop.getCount() > 0) return;
                }
            }
        });
        pump.setDaemon(true);
        pump.start();
        Thread.sleep(2000);

        String relayAddr = node.pool().connection(relay).getTheirMlsAddress();
        System.out.println("[3] relay address: "
                + (relayAddr == null ? "(unknown)" : relayAddr.substring(0, 24) + "...@"
                + relayAddr.substring(relayAddr.indexOf('@') + 1)));
        if (relayAddr == null) {
            System.out.println("RESULT: could not learn relay address");
            System.exit(1);
        }

        System.out.println("[4] asking the relay to probe us back at " + bound);
        MaximaSender.Result r = node.sendRaw(relayAddr, Probe.APPLICATION,
                Probe.request(bound));
        boolean reachable = r.isOk();
        System.out.println("RESULT: reachable=" + reachable + " (ack " + r.statusName + ")");

        stop.countDown();
        node.stop();
        ep.stop();
        System.exit(reachable ? 0 : 2);
    }
}
