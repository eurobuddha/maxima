package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.DirectEndpoint;
import com.eurobuddha.maxima.core.net.Probe;
import com.eurobuddha.maxima.core.portmap.PortMapper;

/**
 * The reachability-probe mechanism and its guards, in-process.
 *
 * The live end-to-end (a fleet relay dialling a phone's mapped port) needs real
 * infrastructure and is done by hand; here we prove the two things that matter:
 * the dial correctly distinguishes an answering endpoint from a dead port, and
 * the source-IP / port / private-address guards that stop the service being a
 * scanner all hold.
 */
public class ProbeTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static MaximaIdentity idFrom(int zSalt) {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * zSalt + zSalt);
        }
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== REACHABILITY PROBE ===\n");

        // A real endpoint answers the greeting -> reachable.
        MaximaIdentity id = idFrom(81);
        DirectEndpoint ep = new DirectEndpoint(id, "1.0.48", inb -> { });
        int port = ep.start(0);
        if (Probe.dial("127.0.0.1", port, 3000, "1.0.48")) {
            ok("dial to a live DirectEndpoint reports reachable");
        } else {
            bad("dial to a live endpoint reported unreachable");
        }
        ep.stop();

        // Nothing listening now -> not reachable (connection refused).
        if (!Probe.dial("127.0.0.1", port, 1500, "1.0.48")) {
            ok("dial to a closed port reports NOT reachable");
        } else {
            bad("dial to a closed port claimed reachable");
        }

        // An open port that is NOT a Maxima endpoint (never greets) -> not
        // reachable: reachability means an ENDPOINT answered, not just a port.
        java.net.ServerSocket silent = new java.net.ServerSocket(0);
        Thread t = new Thread(() -> {
            try {
                java.net.Socket s = silent.accept();
                Thread.sleep(2000);   // accept, then say nothing
                s.close();
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        if (!Probe.dial("127.0.0.1", silent.getLocalPort(), 1500, "1.0.48")) {
            ok("an open port that does not greet is NOT counted reachable");
        } else {
            bad("a silent open port was counted reachable");
        }
        silent.close();

        // ---- the guards that make it not a scanner ----
        // The relay would reject these BEFORE dialling; assert the predicates
        // the relay uses.
        if (Probe.portOf(Probe.request(19501)) == 19501) {
            ok("request/parse round-trips the port");
        } else {
            bad("port round trip broken");
        }
        if (Probe.portOf("80".getBytes()) < Probe.MIN_PORT
                && Probe.MIN_PORT == 1024) {
            ok("low ports are below the floor the relay enforces");
        } else {
            bad("port floor wrong");
        }
        // Target must be a public IP - the relay refuses to probe its own LAN.
        boolean guards = !PortMapper.isPublic("127.0.0.1")
                && !PortMapper.isPublic("192.168.1.10")
                && !PortMapper.isPublic("10.0.0.1")
                && PortMapper.isPublic("81.2.3.4");
        if (guards) {
            ok("source-IP guard: loopback and RFC1918 refused, public allowed");
        } else {
            bad("source-IP guard wrong");
        }
        if (Probe.portOf("garbage".getBytes()) == -1
                && Probe.portOf("70000".getBytes()) == -1) {
            ok("malformed and out-of-range ports are rejected");
        } else {
            bad("port validation weak");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Probe mechanism and guards hold.");
    }
}
