package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.rpc.Capabilities;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MILESTONE 9 - REPLY-AS-NEW-MESSAGE, PROVEN LIVE.
 *
 * Two peers, BOTH behind NAT, neither ever accepting an inbound connection.
 * Peer B calls a service hosted by peer A; A answers by dialling out.
 *
 * In classic Maxima this is impossible: a service reply is sent in place of the
 * socket ack, and acks die at the relay, so only a directly-reachable node can
 * host a service. That single constraint is why MLS lives on public nodes and
 * why phones are consumers only.
 *
 * If this passes, a phone can host the directory, the mailbox, gossip, storage
 * and witness services - everything in Tier 1.
 */
public class LiveRpcTest {

    static HostConnection attach(String zLabel, MaximaIdentity zId,
                                 String zHost, int zPort, String zVersion) throws Exception {
        HostConnection c = new HostConnection(zHost, zPort,
                zId.hostKey(zHost + ":" + zPort), zVersion);
        long t0 = System.currentTimeMillis();
        c.attach(30000);
        System.out.println("  " + zLabel + " attached in "
                + (System.currentTimeMillis() - t0) + "ms -> "
                + c.contactAddress().substring(0, 34) + "...@" + zHost + ":" + zPort);
        return c;
    }

    /** Pump one connection's inbound messages into its RpcPeer. */
    static Thread pump(String zLabel, HostConnection zConn, RpcPeer zPeer, CountDownLatch zStop) {
        Thread t = new Thread(() -> {
            while (zStop.getCount() > 0) {
                try {
                    HostConnection.Inbound in = zConn.receive(3000);
                    if (in != null) {
                        System.out.println("  [" + zLabel + "] inbound app="
                                + in.message.mApplication + " sig="
                                + (in.signatureValid ? "ok" : "BAD"));
                        zPeer.onInbound(in.message);
                    }
                } catch (Exception e) {
                    if (zStop.getCount() > 0) {
                        System.out.println("  [" + zLabel + "] receive error: " + e);
                    }
                    return;
                }
            }
        }, "pump-" + zLabel);
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static void main(String[] args) throws Exception {
        // Two DIFFERENT relays - the realistic case, and it also sidesteps a
        // relay refusing two connections from one IP. Each sender dials the
        // RECIPIENT's relay, so cross-relay delivery is the normal path.
        String aSpec = args.length > 0 ? args[0] : "eurobuddha.com:9001";
        String bSpec = args.length > 1 ? args[1] : "eurobuddha.com:8001";
        String version = "1.0.48";

        String aHost = aSpec.substring(0, aSpec.lastIndexOf(':'));
        int aPort = Integer.parseInt(aSpec.substring(aSpec.lastIndexOf(':') + 1));
        String bHost = bSpec.substring(0, bSpec.lastIndexOf(':'));
        int bPort = Integer.parseInt(bSpec.substring(bSpec.lastIndexOf(':') + 1));

        System.out.println("=== MILESTONE 9: REPLY-AS-NEW-MESSAGE ===");
        System.out.println("alice relay: " + aSpec);
        System.out.println("bob   relay: " + bSpec);
        System.out.println("both peers are behind NAT and never accept an inbound socket\n");

        // Deterministic, genuinely valid checksummed phrases - built from fixed
        // entropy so the test is reproducible and the identities are stable.
        byte[] entA = new byte[32];
        byte[] entB = new byte[32];
        for (int i = 0; i < 32; i++) {
            entA[i] = (byte) (i * 7 + 1);
            entB[i] = (byte) (i * 13 + 5);
        }
        java.util.List<String> phraseA = com.eurobuddha.maxima.core.identity.Bip39.fromEntropy(entA);
        java.util.List<String> phraseB = com.eurobuddha.maxima.core.identity.Bip39.fromEntropy(entB);

        MaximaIdentity alice = MaximaIdentity.fromPhrase(phraseA);
        MaximaIdentity bob = MaximaIdentity.fromPhrase(phraseB);
        System.out.println("  alice phrase: " + String.join(" ", phraseA.subList(0, 4)) + " ...");
        System.out.println("  bob   phrase: " + String.join(" ", phraseB.subList(0, 4)) + " ...");

        System.out.println("[1] attaching both peers to the relay");
        HostConnection aConn = attach("alice", alice, aHost, aPort, version);
        HostConnection bConn = attach("bob  ", bob, bHost, bPort, version);
        System.out.println();

        // ---- Alice hosts services. This is the part classic cannot do. ----
        ServiceRegistry aliceServices = new ServiceRegistry();
        aliceServices.register("echo", req ->
                ("echo:" + req.payloadAsString()).getBytes(StandardCharsets.UTF_8));
        aliceServices.register("directory.lookup", req ->
                ("address-for:" + req.payloadAsString()).getBytes(StandardCharsets.UTF_8));
        aliceServices.register("boom", req -> {
            throw new IllegalStateException("deliberate handler failure");
        });

        RpcPeer alicePeer = new RpcPeer(alice, aliceServices);
        alicePeer.setMyAddresses(Collections.singletonList(aConn.contactAddress()));

        RpcPeer bobPeer = new RpcPeer(bob, new ServiceRegistry());
        bobPeer.setMyAddresses(Collections.singletonList(bConn.contactAddress()));

        System.out.println("[2] alice offers: " + aliceServices.methods());
        System.out.println("    alice caps  : " + Capabilities.phoneDefaults());
        System.out.println();

        CountDownLatch stop = new CountDownLatch(1);
        pump("alice", aConn, alicePeer, stop);
        pump("bob", bConn, bobPeer, stop);

        Thread.sleep(1500);

        int pass = 0, fail = 0;

        // ---- call 1: echo ----
        System.out.println("[3] bob calls alice.echo(\"hello\")");
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        bobPeer.call(aConn.contactAddress(), "echo",
                "hello".getBytes(StandardCharsets.UTF_8),
                new RpcPeer.ResponseHandler() {
                    public void onResponse(byte[] p) {
                        result.set(new String(p, StandardCharsets.UTF_8));
                        got.countDown();
                    }

                    public void onError(String m) {
                        error.set(m);
                        got.countDown();
                    }
                });

        boolean came = got.await(45, TimeUnit.SECONDS);
        if (came && "echo:hello".equals(result.get())) {
            pass++;
            System.out.println("    RESPONSE: \"" + result.get() + "\"  <-- dialled out by alice");
        } else {
            fail++;
            System.out.println("    FAILED: came=" + came + " result=" + result.get()
                    + " error=" + error.get());
        }
        System.out.println();

        // ---- call 2: a different method ----
        System.out.println("[4] bob calls alice.directory.lookup(\"0xABCD\")");
        CountDownLatch got2 = new CountDownLatch(1);
        AtomicReference<String> r2 = new AtomicReference<>();
        bobPeer.call(aConn.contactAddress(), "directory.lookup",
                "0xABCD".getBytes(StandardCharsets.UTF_8),
                new RpcPeer.ResponseHandler() {
                    public void onResponse(byte[] p) {
                        r2.set(new String(p, StandardCharsets.UTF_8));
                        got2.countDown();
                    }

                    public void onError(String m) {
                        r2.set("ERR:" + m);
                        got2.countDown();
                    }
                });
        boolean came2 = got2.await(45, TimeUnit.SECONDS);
        if (came2 && "address-for:0xABCD".equals(r2.get())) {
            pass++;
            System.out.println("    RESPONSE: \"" + r2.get() + "\"");
        } else {
            fail++;
            System.out.println("    FAILED: came=" + came2 + " r=" + r2.get());
        }
        System.out.println();

        // ---- call 3: handler throws -> must surface as an ERROR, not a hang ----
        System.out.println("[5] bob calls alice.boom() - handler throws on purpose");
        CountDownLatch got3 = new CountDownLatch(1);
        AtomicReference<String> e3 = new AtomicReference<>();
        bobPeer.call(aConn.contactAddress(), "boom", new byte[0],
                new RpcPeer.ResponseHandler() {
                    public void onResponse(byte[] p) {
                        e3.set("UNEXPECTED RESPONSE");
                        got3.countDown();
                    }

                    public void onError(String m) {
                        e3.set(m);
                        got3.countDown();
                    }
                });
        boolean came3 = got3.await(45, TimeUnit.SECONDS);
        if (came3 && e3.get() != null && e3.get().contains("deliberate")) {
            pass++;
            System.out.println("    ERROR propagated: \"" + e3.get() + "\"  (caller not left hanging)");
        } else {
            fail++;
            System.out.println("    FAILED: came=" + came3 + " e=" + e3.get());
        }
        System.out.println();

        // ---- call 4: unknown method ----
        System.out.println("[6] bob calls alice.nosuch()");
        CountDownLatch got4 = new CountDownLatch(1);
        AtomicReference<String> e4 = new AtomicReference<>();
        bobPeer.call(aConn.contactAddress(), "nosuch", new byte[0],
                new RpcPeer.ResponseHandler() {
                    public void onResponse(byte[] p) {
                        e4.set("UNEXPECTED RESPONSE");
                        got4.countDown();
                    }

                    public void onError(String m) {
                        e4.set(m);
                        got4.countDown();
                    }
                });
        boolean came4 = got4.await(45, TimeUnit.SECONDS);
        if (came4 && e4.get() != null && e4.get().contains("no such method")) {
            pass++;
            System.out.println("    ERROR propagated: \"" + e4.get() + "\"");
        } else {
            fail++;
            System.out.println("    FAILED: came=" + came4 + " e=" + e4.get());
        }

        stop.countDown();
        aConn.close();
        bConn.close();

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.out.println("MILESTONE 9 FAILED");
            System.exit(1);
        }
        System.out.println("  MILESTONE 9 PASSED");
        System.out.println("  A NAT'd peer hosted services and answered by dialling out.");
        System.out.println("  Phones can now be providers, not just consumers.");
    }
}
