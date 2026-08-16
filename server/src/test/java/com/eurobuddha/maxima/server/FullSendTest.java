package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import com.eurobuddha.maxima.core.services.Tier1Services;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The whole thing, wired together in one process, over loopback - no live
 * network, no device.
 *
 * SelfTest proves the relay binary works; the LiveXxx tests prove we interop
 * with the real network. This sits between them: a real RelayServer and two
 * real MaximaNodes on 127.0.0.1, exercising EVERY send path end to end so a
 * regression anywhere in the stack fails here, fast and offline:
 *
 *   - 1:1 signed+encrypted send, delivered and signature-verified
 *   - the msgid the sender computed is the msgid the receiver saw
 *   - reply-as-new-message RPC (ping) - the phone-contribution spine
 *   - Tier 1 services: blob put/get, witness receipt
 *   - reliable send via the outbox
 *   - store-and-forward: send to an offline peer, drain on reconnect
 *   - dedup: the same message twice is delivered once
 *   - oversize rejection (TOOBIG) and the relay's own counters
 */
public final class FullSendTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static final String PROTO = "1.0.48";

    static MaximaIdentity idFrom(int salt) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * salt + salt);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }

    static Thread pump(MaximaNode node, String hostPort, CountDownLatch stop) {
        Thread t = new Thread(() -> {
            while (stop.getCount() > 0) {
                try {
                    node.pump(hostPort, 1000);
                } catch (Exception e) {
                    if (stop.getCount() > 0) return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return c.getAsBoolean();
    }

    /** Find a free loopback port. */
    static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== FULL SEND, in-process over loopback ===\n");

        // Loopback: 127.0.0.1 is an internal IP, which the advertised-address
        // filter rejects (mirroring classic, which never advertises an internal
        // host). Classic tests on loopback with -allowallip; do the same here so
        // contact addresses are published over 127.0.0.1.
        com.eurobuddha.maxima.core.MaximaNode.ALLOW_ALL_IP = true;

        int port = freePort();
        String hostPort = "127.0.0.1:" + port;
        MaximaIdentity relayId = MaximaIdentity.fromPhrase(Bip39.generate(24));
        RelayServer relay = new RelayServer(relayId, port, PROTO);
        relay.setPublicHost("127.0.0.1");
        relay.start();
        System.out.println("[*] relay up on " + hostPort + "\n");

        CountDownLatch stop = new CountDownLatch(1);
        final MaximaIdentity aliceId = idFrom(11);
        final MaximaIdentity bobId = idFrom(13);
        final MaximaNode alice = new MaximaNode(aliceId, PROTO, 1);
        MaximaNode bob = new MaximaNode(bobId, PROTO, 1);
        try {
            alice.setName("alice");
            bob.setName("bob");

            final AtomicInteger bobGot = new AtomicInteger();
            final List<String> bobBodies =
                    Collections.synchronizedList(new java.util.ArrayList<>());
            final AtomicReference<String> bobLastMsgid = new AtomicReference<>();
            bob.setMessageListener((msg, msgid) -> {
                bobGot.incrementAndGet();
                bobBodies.add(new String(msg.mData.getBytes(), StandardCharsets.UTF_8));
                bobLastMsgid.set(msgid.to0xString());
            });

            alice.start(Collections.singletonList(hostPort), 15000);
            bob.start(Collections.singletonList(hostPort), 15000);
            pump(alice, hostPort, stop);
            Thread bobPump = pump(bob, hostPort, stop);
            Thread.sleep(800);

            // ---- 0. both attached and registered a route ----
            if (waitFor(() -> relay.routeCount() >= 2, 15)) {
                ok("both nodes attached and registered a route on the relay");
            } else {
                bad("nodes did not register routes (routeCount=" + relay.routeCount() + ")");
                System.exit(1);
            }

            // ---- 1. contact exchange (intro + reciprocation) ----
            bob.introduce(alice.myAddresses().get(0), true);
            boolean known = waitFor(() -> alice.contact(bobId.publicKeyHex()) != null
                    && bob.contact(aliceId.publicKeyHex()) != null, 15);
            if (known) {
                ok("contact intro + reciprocation: each side has the other as a contact");
            } else {
                bad("contact exchange failed");
                System.exit(1);
            }
            Contact bobAtAlice = alice.contact(bobId.publicKeyHex());
            String bobAddr = bobAtAlice.primaryAddress();

            // ---- 2. 1:1 signed+encrypted send, delivered ----
            int before = bobGot.get();
            MaximaSender.Result r = alice.sendToContact(bobAtAlice, "chat_v1",
                    "hello bob".getBytes(StandardCharsets.UTF_8));
            if (r.isOk()) {
                ok("1:1 send accepted by the relay (OK)");
            } else {
                bad("1:1 send not OK: " + r.statusName);
            }
            if (waitFor(() -> bobGot.get() > before, 15)
                    && bobBodies.contains("hello bob")) {
                ok("bob received the 1:1 message, decrypted to the exact plaintext");
            } else {
                bad("bob did not receive the 1:1 message");
            }

            // ---- 3. msgid agreement (sender's id == receiver's id) ----
            long now = System.currentTimeMillis();
            MaximaSender.Built built = MaximaSender.build(
                    aliceId.publicKey(), aliceId.keyPair().getPrivate(),
                    com.eurobuddha.maxima.core.identity.MxAddress.convert(
                            bobAddr.substring(0, bobAddr.indexOf('@'))).getBytes(),
                    "chat_v1", "id-check".getBytes(StandardCharsets.UTF_8), now);
            int beforeId = bobGot.get();
            MaximaSender.send("127.0.0.1", port, built.unit, built.msgid);
            if (waitFor(() -> bobGot.get() > beforeId, 15)
                    && built.msgid.to0xString().equalsIgnoreCase(bobLastMsgid.get())) {
                ok("msgid the sender computed == msgid the receiver saw (dedup keys agree)");
            } else {
                bad("msgid mismatch: sent " + built.msgid.to0xString()
                        + " saw " + bobLastMsgid.get());
            }

            // ---- 4. dedup: the identical frame twice is delivered once ----
            int beforeDup = bobGot.get();
            MaximaSender.send("127.0.0.1", port, built.unit, built.msgid);
            Thread.sleep(2000);
            if (bobGot.get() == beforeDup) {
                ok("a replayed identical message is de-duplicated (delivered once)");
            } else {
                bad("dedup failed: replay was delivered again");
            }

            // ---- 5. reply-as-new-message RPC (ping) ----
            CountDownLatch pingLatch = new CountDownLatch(1);
            AtomicReference<String> pong = new AtomicReference<>();
            alice.rpc().call(bobAddr, Tier1Services.PING, new byte[0],
                    new RpcPeer.ResponseHandler() {
                        public void onResponse(byte[] p) {
                            pong.set(new String(p, StandardCharsets.UTF_8));
                            pingLatch.countDown();
                        }
                        public void onError(String m) {
                            pong.set("ERR:" + m);
                            pingLatch.countDown();
                        }
                    });
            if (pingLatch.await(20, TimeUnit.SECONDS) && "pong".equals(pong.get())) {
                ok("reply-as-new-message RPC: alice pinged bob, bob answered by dialling out");
            } else {
                bad("RPC ping failed (" + pong.get() + ")");
            }

            // ---- 6. Tier 1: blob put then get by content id ----
            byte[] blob = "content-addressed blob".getBytes(StandardCharsets.UTF_8);
            CountDownLatch putLatch = new CountDownLatch(1);
            AtomicReference<String> blobId = new AtomicReference<>();
            alice.rpc().call(bobAddr, Tier1Services.BLOB_PUT, blob,
                    new RpcPeer.ResponseHandler() {
                        public void onResponse(byte[] p) {
                            blobId.set(new String(p, StandardCharsets.UTF_8));
                            putLatch.countDown();
                        }
                        public void onError(String m) { putLatch.countDown(); }
                    });
            putLatch.await(20, TimeUnit.SECONDS);
            boolean gotBack = false;
            if (blobId.get() != null && !blobId.get().isEmpty()) {
                CountDownLatch getLatch = new CountDownLatch(1);
                AtomicReference<byte[]> fetched = new AtomicReference<>();
                alice.rpc().call(bobAddr, Tier1Services.BLOB_GET,
                        blobId.get().getBytes(StandardCharsets.UTF_8),
                        new RpcPeer.ResponseHandler() {
                            public void onResponse(byte[] p) {
                                fetched.set(p);
                                getLatch.countDown();
                            }
                            public void onError(String m) { getLatch.countDown(); }
                        });
                getLatch.await(20, TimeUnit.SECONDS);
                gotBack = fetched.get() != null
                        && new String(fetched.get(), StandardCharsets.UTF_8)
                                .equals("content-addressed blob");
            }
            if (gotBack) {
                ok("Tier 1 blob: put returned a content id, get by that id returned the bytes");
            } else {
                bad("Tier 1 blob put/get failed");
            }

            // ---- 7. Tier 1: witness receipt ----
            CountDownLatch wLatch = new CountDownLatch(1);
            AtomicReference<String> receipt = new AtomicReference<>();
            String someMsgid = built.msgid.to0xString();
            alice.rpc().call(bobAddr, Tier1Services.WITNESS_SIGN,
                    someMsgid.getBytes(StandardCharsets.UTF_8),
                    new RpcPeer.ResponseHandler() {
                        public void onResponse(byte[] p) {
                            receipt.set(new String(p, StandardCharsets.UTF_8));
                            wLatch.countDown();
                        }
                        public void onError(String m) { wLatch.countDown(); }
                    });
            wLatch.await(20, TimeUnit.SECONDS);
            if (receipt.get() != null && receipt.get().startsWith(someMsgid + "|")
                    && receipt.get().contains("0x")) {
                ok("Tier 1 witness: bob countersigned 'saw msgid X at T' (delivery evidence)");
            } else {
                bad("witness receipt malformed: " + receipt.get());
            }

            // ---- 8. reliable send via the outbox ----
            int beforeRel = bobGot.get();
            String rmid = alice.sendReliable(bobAtAlice, "reliable_v1",
                    "guaranteed".getBytes(StandardCharsets.UTF_8));
            // drive the outbox
            boolean relDelivered = waitFor(() -> {
                try { alice.flushOutbox(); } catch (Throwable ignored) { }
                return bobGot.get() > beforeRel && bobBodies.contains("guaranteed");
            }, 20);
            if (relDelivered) {
                ok("reliable send (outbox " + rmid.substring(0, 8) + "...) was delivered");
            } else {
                bad("reliable send not delivered");
            }

            // ---- 9. oversize rejection ----
            byte[] huge = new byte[300 * 1024]; // > 262144 ceiling
            MaximaSender.Result big;
            try {
                big = alice.sendToContact(bobAtAlice, "chat_v1", huge);
                if (!big.isOk()) {
                    ok("an oversize message is rejected (" + big.statusName + "), not relayed");
                } else {
                    bad("oversize message was accepted");
                }
            } catch (Exception e) {
                ok("an oversize message is refused before send (" + e.getClass().getSimpleName() + ")");
            }

            // ---- 10. store-and-forward: offline peer, drain on reconnect ----
            System.out.println("[*] bob goes offline");
            bob.stop();
            bobPump.interrupt();
            Thread.sleep(1500);
            alice.sendRaw(bobAddr, "held", "while you were out".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(500);

            MaximaNode bob2 = new MaximaNode(bobId, PROTO, 1);
            final AtomicInteger bob2Got = new AtomicInteger();
            final List<String> bob2Bodies =
                    Collections.synchronizedList(new java.util.ArrayList<>());
            bob2.setMessageListener((msg, msgid) -> {
                bob2Got.incrementAndGet();
                bob2Bodies.add(new String(msg.mData.getBytes(), StandardCharsets.UTF_8));
            });
            bob2.start(Collections.singletonList(hostPort), 15000);
            CountDownLatch stop2 = new CountDownLatch(1);
            pump(bob2, hostPort, stop2);
            boolean drained = waitFor(() ->
                    bob2Bodies.contains("while you were out"), 25);
            if (drained) {
                ok("store-and-forward: message held for offline bob drained on reconnect");
            } else {
                bad("mailbox not drained (got " + bob2Bodies + ")");
            }
            stop2.countDown();
            bob2.stop();

            // ---- 11. relay counters are sane ----
            if (relay.relayedCount() > 0) {
                ok("relay relayed-count is non-zero (" + relay.relayedCount() + " frames forwarded)");
            } else {
                bad("relay relayed-count is zero");
            }

        } finally {
            stop.countDown();
            if (alice != null) try { alice.stop(); } catch (Exception ignored) { }
            if (bob != null) try { bob.stop(); } catch (Exception ignored) { }
            relay.stop();
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Full send path holds, end to end, offline.");
    }
}
