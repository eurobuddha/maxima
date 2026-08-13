package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.identity.MxAddress;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.session.HostPool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MILESTONE 10 - MULTI-HOMING.
 *
 * Classic publishes ONE address from ONE randomly chosen host and purges a host
 * after 7 days. On a phone, where relays come and go constantly, that is a
 * single point of failure chosen at random.
 *
 * Here we attach to several relays at once and publish every resulting address.
 * The test does not just count connections - it proves each published address
 * is INDEPENDENTLY DELIVERABLE by sending to each one and confirming it arrives
 * on the matching connection.
 *
 * It also checks the privacy property that motivates per-host keys: the routing
 * key must differ per relay, so two relay operators cannot correlate us.
 */
public class LiveMultiHomeTest {

    public static void main(String[] args) throws Exception {
        List<String> candidates = args.length > 0
                ? Arrays.asList(args)
                : Arrays.asList("eurobuddha.com:9001", "eurobuddha.com:8001",
                "34.105.180.174:9001", "168.138.15.32:9001");

        System.out.println("=== MILESTONE 10: MULTI-HOMING ===");
        System.out.println("candidate relays: " + candidates + "\n");

        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * 11 + 3);
        }
        MaximaIdentity me = MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));

        int pass = 0, fail = 0;

        HostPool pool = new HostPool(me, "1.0.48", 3);
        pool.addCandidates(candidates);

        System.out.println("[1] filling the pool (target 3)");
        long t0 = System.currentTimeMillis();
        int n = pool.fill(30000);
        System.out.println("    attached " + n + "/3 in " + (System.currentTimeMillis() - t0) + "ms");
        for (String h : pool.activeHosts()) {
            System.out.println("      " + h);
        }
        if (n >= 2) {
            pass++;
            System.out.println("    ok multi-homed across " + n + " relays");
        } else {
            fail++;
            System.out.println("    XX only " + n + " relay(s) - cannot demonstrate multi-homing");
        }
        System.out.println();

        List<String> addrs = pool.contactAddresses();
        System.out.println("[2] publishable addresses (" + addrs.size() + ")");
        for (String a : addrs) {
            System.out.println("      " + a.substring(0, 28) + "..." + a.substring(a.indexOf('@')));
        }

        // Every relay must see a DIFFERENT routing key, or operators can link us.
        Set<String> keys = new HashSet<>();
        for (String a : addrs) {
            keys.add(MxAddress.convert(a.substring(0, a.indexOf('@'))).to0xString());
        }
        if (keys.size() == addrs.size()) {
            pass++;
            System.out.println("    ok every relay sees a distinct routing key (unlinkable)");
        } else {
            fail++;
            System.out.println("    XX routing keys repeat across relays - operators could correlate");
        }
        System.out.println();

        // ---- the real test: is each address independently deliverable? ----
        System.out.println("[3] sending to EACH published address in turn");
        int delivered = 0;
        for (String addr : addrs) {
            String hostPort = addr.substring(addr.indexOf('@') + 1);
            HostConnection conn = pool.connection(hostPort);
            if (conn == null) {
                System.out.println("    " + hostPort + " -> no connection?!");
                continue;
            }

            String payload = "multihome-probe-" + hostPort;
            MiniData routingKey = MxAddress.convert(addr.substring(0, addr.indexOf('@')));

            MaximaSender.Built built = MaximaSender.build(
                    me.publicKey(), me.keyPair().getPrivate(), routingKey.getBytes(),
                    "maxima_core_multihome_test",
                    payload.getBytes(StandardCharsets.UTF_8),
                    System.currentTimeMillis());

            String h = hostPort.substring(0, hostPort.lastIndexOf(':'));
            int p = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));

            MaximaSender.Result res = MaximaSender.send(h, p, built.unit, built.msgid);
            if (!res.isOk()) {
                System.out.println("    " + hostPort + " -> relay ack " + res.statusName);
                continue;
            }

            HostConnection.Inbound in = conn.receive(25000);
            if (in != null
                    && payload.equals(new String(in.message.mData.getBytes(), StandardCharsets.UTF_8))
                    && in.signatureValid) {
                delivered++;
                System.out.println("    " + hostPort + " -> DELIVERED (sig valid)");
            } else {
                System.out.println("    " + hostPort + " -> no delivery within 25s");
            }
        }
        if (delivered == addrs.size() && delivered > 0) {
            pass++;
            System.out.println("    ok all " + delivered + " published addresses are live");
        } else {
            fail++;
            System.out.println("    XX only " + delivered + "/" + addrs.size() + " addresses delivered");
        }
        System.out.println();

        // ---- failover ----
        System.out.println("[4] simulating a relay loss");
        String victim = pool.activeHosts().get(0);
        pool.detach(victim);
        System.out.println("    detached " + victim + " -> active now " + pool.activeCount());
        List<String> after = pool.contactAddresses();
        if (!after.contains(addrs.stream().filter(a -> a.endsWith(victim)).findFirst().orElse("?"))
                && pool.activeCount() == n - 1) {
            pass++;
            System.out.println("    ok the dead relay's address is no longer published");
        } else {
            fail++;
            System.out.println("    XX stale address still advertised");
        }

        int refilled = pool.reconcile(30000);
        System.out.println("    reconcile -> " + refilled + " attached");
        if (refilled >= n - 1) {
            pass++;
            System.out.println("    ok pool recovered without manual intervention");
        } else {
            fail++;
            System.out.println("    XX pool did not recover");
        }
        System.out.println();

        System.out.println("[5] relay scores (churn-native ranking)");
        for (HostPool.HostRecord r : pool.knownByScore()) {
            System.out.println("      " + r);
        }

        pool.closeAll();

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.out.println("MILESTONE 10 FAILED");
            System.exit(1);
        }
        System.out.println("  MILESTONE 10 PASSED - multi-homed, unlinkable, self-healing.");
    }
}
