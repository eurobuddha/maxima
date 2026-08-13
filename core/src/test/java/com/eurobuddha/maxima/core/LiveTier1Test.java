package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.reliability.DedupCache;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import com.eurobuddha.maxima.core.services.Tier1Services;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TIER 1 END TO END, LIVE.
 *
 * Two full MaximaNodes on different relays, both behind NAT, exercising every
 * service a phone is meant to contribute: mailbox store-and-forward, directory
 * lookup, address gossip, content-addressed blobs and witness receipts - plus
 * the contact protocol and replay protection.
 *
 * None of these are reachable in classic Maxima from behind NAT, because a
 * service reply there replaces the socket ack and acks die at a relay.
 */
public class LiveTier1Test {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("    ok  " + m); }
    static void bad(String m) { fail++; System.out.println("    XX  " + m); }

    static MaximaIdentity idFrom(int zSalt) {
        byte[] ent = new byte[32];
        for (int i = 0; i < 32; i++) {
            ent[i] = (byte) (i * zSalt + zSalt);
        }
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(ent));
    }

    static Thread pump(String zLabel, MaximaNode zNode, String zHostPort, CountDownLatch zStop) {
        Thread t = new Thread(() -> {
            while (zStop.getCount() > 0) {
                try {
                    zNode.pump(zHostPort, 2000);
                } catch (Exception e) {
                    if (zStop.getCount() > 0) {
                        return;
                    }
                }
            }
        }, "pump-" + zLabel);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Blocking RPC helper - the tests read better as request/response. */
    static String call(RpcPeer zFrom, String zTo, String zMethod, String zPayload, int zSeconds)
            throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        zFrom.call(zTo, zMethod, zPayload.getBytes(StandardCharsets.UTF_8),
                new RpcPeer.ResponseHandler() {
                    public void onResponse(byte[] p) {
                        out.set(new String(p, StandardCharsets.UTF_8));
                        l.countDown();
                    }

                    public void onError(String m) {
                        err.set(m);
                        l.countDown();
                    }
                });
        if (!l.await(zSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timeout calling " + zMethod);
        }
        if (err.get() != null) {
            throw new IllegalStateException("rpc error: " + err.get());
        }
        return out.get();
    }

    public static void main(String[] args) throws Exception {
        String relayA = args.length > 0 ? args[0] : "eurobuddha.com:9001";
        String relayB = args.length > 1 ? args[1] : "eurobuddha.com:8001";

        System.out.println("=== TIER 1 SERVICES, LIVE ===");
        System.out.println("alice relay " + relayA + " | bob relay " + relayB + "\n");

        MaximaNode alice = new MaximaNode(idFrom(7), "1.0.48", 1);
        MaximaNode bob = new MaximaNode(idFrom(13), "1.0.48", 1);
        alice.setName("alice");
        bob.setName("bob");

        System.out.println("[1] starting both nodes");
        int na = alice.start(Collections.singletonList(relayA), 30000);
        int nb = bob.start(Collections.singletonList(relayB), 30000);
        System.out.println("    alice relays=" + na + " addr=" + shorten(alice.myAddresses()));
        System.out.println("    bob   relays=" + nb + " addr=" + shorten(bob.myAddresses()));
        if (na >= 1 && nb >= 1) {
            ok("both nodes attached");
        } else {
            bad("attach failed");
            System.exit(1);
        }
        System.out.println("    alice offers " + alice.services().methods().size() + " services");

        CountDownLatch stop = new CountDownLatch(1);
        pump("alice", alice, relayA, stop);
        pump("bob", bob, relayB, stop);
        Thread.sleep(1500);

        String aliceAddr = alice.myAddresses().get(0);
        String bobAddr = bob.myAddresses().get(0);

        // ---------------- contact protocol ----------------
        System.out.println("\n[2] contact introduction (bob -> alice, with reciprocation)");
        bob.introduce(aliceAddr, true);
        Thread.sleep(6000);
        Contact bobAtAlice = alice.contact(bob.identity().publicKeyHex());
        Contact aliceAtBob = bob.contact(alice.identity().publicKeyHex());
        if (bobAtAlice != null) {
            ok("alice stored bob: " + bobAtAlice);
        } else {
            bad("alice did not store bob");
        }
        if (aliceAtBob != null) {
            ok("alice reciprocated, bob stored alice: " + aliceAtBob);
        } else {
            bad("no reciprocation");
        }
        if (bobAtAlice != null && !bobAtAlice.isClassic()) {
            ok("capabilities discovered: " + bobAtAlice.capabilities);
        } else {
            bad("capabilities not advertised");
        }

        // ---------------- ping ----------------
        System.out.println("\n[3] ping");
        if ("pong".equals(call(bob.rpc(), aliceAddr, Tier1Services.PING, "", 40))) {
            ok("ping/pong");
        } else {
            bad("ping failed");
        }

        // ---------------- mailbox ----------------
        System.out.println("\n[4] mailbox store-and-forward (the biggest classic gap)");
        String carolKey = "0x" + "AB".repeat(20);
        String cipher = new MiniData("hello-offline-peer".getBytes(StandardCharsets.UTF_8)).to0xString();
        String r = call(bob.rpc(), aliceAddr, Tier1Services.MAILBOX_STORE,
                carolKey + "|" + cipher, 40);
        if ("STORED".equals(r)) {
            ok("alice accepted mail for an offline third party");
        } else {
            bad("store returned " + r);
        }
        String again = call(bob.rpc(), aliceAddr, Tier1Services.MAILBOX_STORE,
                carolKey + "|" + cipher, 40);
        if ("DUPLICATE".equals(again)) {
            ok("re-storing the same message is idempotent (content-addressed)");
        } else {
            bad("expected DUPLICATE, got " + again);
        }
        String count = call(bob.rpc(), aliceAddr, Tier1Services.MAILBOX_COUNT, carolKey, 40);
        if ("1".equals(count)) {
            ok("mailbox holds exactly 1 item for carol");
        } else {
            bad("count=" + count);
        }

        // bob fetches HIS OWN mail - the box key is the requester, never the payload
        alice.mailbox().store(bob.identity().publicKeyHex(),
                "for-bob".getBytes(StandardCharsets.UTF_8));
        String fetched = call(bob.rpc(), aliceAddr, Tier1Services.MAILBOX_FETCH, "0", 40);
        if (fetched.contains("|")
                && new String(new MiniData(fetched.split("\\|")[1]).getBytes(),
                StandardCharsets.UTF_8).equals("for-bob")) {
            ok("bob fetched his own mail");
        } else {
            bad("fetch returned: " + fetched);
        }
        String acked = call(bob.rpc(), aliceAddr, Tier1Services.MAILBOX_ACK, "1", 40);
        if (!"0".equals(acked)) {
            ok("ack removed " + acked + " item(s) - delete only after confirmation");
        } else {
            bad("ack removed nothing");
        }

        // ---------------- directory ----------------
        System.out.println("\n[5] directory replica on a NAT'd node");
        call(bob.rpc(), aliceAddr, "directory.publish", bobAddr, 40);
        String looked = call(bob.rpc(), aliceAddr, "directory.lookup",
                bob.identity().publicKeyHex(), 40);
        if (bobAddr.equals(looked)) {
            ok("published then resolved through a phone-hosted directory");
        } else {
            bad("lookup returned: " + looked);
        }

        // ---------------- gossip ----------------
        System.out.println("\n[6] address gossip (fixes the orphaning race)");
        call(bob.rpc(), aliceAddr, Tier1Services.GOSSIP_TELL, carolKey + "|" + bobAddr, 40);
        String asked = call(bob.rpc(), aliceAddr, Tier1Services.GOSSIP_ASK, carolKey, 40);
        if (bobAddr.equals(asked)) {
            ok("a third party's address was learned and served back");
        } else {
            bad("gossip returned: " + asked);
        }

        // ---------------- blobs ----------------
        System.out.println("\n[7] content-addressed storage");
        String blobId = call(bob.rpc(), aliceAddr, Tier1Services.BLOB_PUT, "the-content", 40);
        String expect = Tier1Services.idOf("the-content".getBytes(StandardCharsets.UTF_8));
        if (expect.equals(blobId)) {
            ok("blob id is sha3(content) - replication is verifiable");
        } else {
            bad("id=" + blobId + " expected " + expect);
        }
        String got = call(bob.rpc(), aliceAddr, Tier1Services.BLOB_GET, blobId, 40);
        if ("the-content".equals(got)) {
            ok("blob retrieved by content id");
        } else {
            bad("blob get returned: " + got);
        }

        // ---------------- witness ----------------
        System.out.println("\n[8] witness receipts (classic has no delivery evidence)");
        String fakeMsgid = "0x" + "CD".repeat(32);
        String receipt = call(bob.rpc(), aliceAddr, Tier1Services.WITNESS_SIGN, fakeMsgid, 40);
        if (Tier1Services.verifyWitness(alice.identity().publicKey(), receipt)) {
            ok("receipt verifies against alice's identity key");
        } else {
            bad("receipt did not verify: " + receipt);
        }
        String tampered = receipt.replace(fakeMsgid, "0x" + "EF".repeat(32));
        if (!Tier1Services.verifyWitness(alice.identity().publicKey(), tampered)) {
            ok("a tampered receipt is rejected");
        } else {
            bad("tampered receipt ACCEPTED");
        }

        // ---------------- replay protection ----------------
        System.out.println("\n[9] replay + dedup (classic has neither)");
        DedupCache d = new DedupCache();
        String mid = "0xFEED";
        long now = System.currentTimeMillis();
        boolean first = d.check(mid, now) == DedupCache.Verdict.ACCEPT;
        boolean dupe = d.check(mid, now) == DedupCache.Verdict.DUPLICATE;
        boolean stale = d.check("0xBEEF", now - (60L * 60 * 1000)) == DedupCache.Verdict.STALE;
        if (first && dupe) {
            ok("second delivery of the same msgid is dropped");
        } else {
            bad("dedup broken");
        }
        if (stale) {
            ok("an hour-old replay is rejected on freshness");
        } else {
            bad("stale message accepted");
        }

        // ---------------- mailbox quotas ----------------
        System.out.println("\n[10] mailbox quotas (admission control must be real)");
        Mailbox small = new Mailbox(60000, 2, 1024);
        small.store("0xAA", "one".getBytes(StandardCharsets.UTF_8));
        small.store("0xAA", "two".getBytes(StandardCharsets.UTF_8));
        Mailbox.Result third = small.store("0xAA", "three".getBytes(StandardCharsets.UTF_8));
        if (third == Mailbox.Result.QUOTA_COUNT) {
            ok("per-peer count quota enforced");
        } else {
            bad("quota not enforced: " + third);
        }

        stop.countDown();
        alice.stop();
        bob.stop();

        System.out.println("\n=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.out.println("TIER 1 FAILED");
            System.exit(1);
        }
        System.out.println("  ALL TIER 1 SERVICES WORK FROM BEHIND NAT");
    }

    static String shorten(List<String> zAddrs) {
        if (zAddrs.isEmpty()) {
            return "(none)";
        }
        String a = zAddrs.get(0);
        return a.substring(0, 18) + "..." + a.substring(a.indexOf('@'));
    }
}
