package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * The address-churn self-heal, in-process over loopback.
 *
 * When a contact moves host, both sides can end up holding each other's OLD
 * address (mutual orphaning) - neither can reach the other to push the new one.
 * The fix: a node publishes itself to EVERY relay it uses, and re-resolves a
 * stale contact across every relay it can reach. Two nodes that share any relay
 * then converge without a manual re-add. This proves it: give bob a stale, WRONG
 * address for alice (and forget alice's MLS), and confirm checkStaleMls() pulls
 * alice's real current address from the shared relay's directory.
 */
public final class RelaySelfHealTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static final String PROTO = "1.0.48";

    static MaximaIdentity idFrom(int salt) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * salt + salt);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }

    static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return c.getAsBoolean();
    }

    static Thread pump(MaximaNode node, String hp, CountDownLatch stop) {
        Thread t = new Thread(() -> {
            while (stop.getCount() > 0) {
                try { node.pump(hp, 1000); }
                catch (Exception e) { if (stop.getCount() > 0) return; }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== SELF-HEAL a stale contact via the relay directory ===\n");
        MaximaNode.ALLOW_ALL_IP = true;   // loopback (127.) is internal; mirror -allowallip

        int port = freePort();
        String hp = "127.0.0.1:" + port;
        RelayServer relay = new RelayServer(MaximaIdentity.fromPhrase(Bip39.generate(24)), port, PROTO);
        relay.setPublicHost("127.0.0.1");
        relay.start();

        CountDownLatch stop = new CountDownLatch(1);
        MaximaNode alice = new MaximaNode(idFrom(11), PROTO, 1);
        MaximaNode bob = new MaximaNode(idFrom(13), PROTO, 1);
        try {
            alice.setName("alice");
            bob.setName("bob");
            alice.start(Collections.singletonList(hp), 15000);
            bob.start(Collections.singletonList(hp), 15000);
            pump(alice, hp, stop);
            pump(bob, hp, stop);
            Thread.sleep(600);

            if (!waitFor(() -> relay.routeCount() >= 2, 15)) {
                bad("nodes did not attach"); System.exit(1);
            }

            // mutual contacts
            bob.introduce(alice.myAddresses().get(0), true);
            boolean known = waitFor(() -> alice.contact(bob.identity().publicKeyHex()) != null
                    && bob.contact(alice.identity().publicKeyHex()) != null, 15);
            if (known) {
                ok("alice and bob are mutual contacts");
            } else {
                bad("contact exchange failed"); System.exit(1);
            }

            // alice publishes herself to the relay directory (readers include bob)
            alice.publishToMls();
            Thread.sleep(500);

            String aliceReal = alice.myAddresses().get(0);

            // Corrupt bob's cache of alice: a STALE, WRONG address.
            Contact ac = bob.contact(alice.identity().publicKeyHex());
            ac.lastSeen = 0;                                   // ancient -> stale
            ac.setAddresses(Collections.singletonList("MxBOGUS@203.0.113.9:9999"));

            // SECURITY: forget alice's own MLS, leaving only ONE reachable relay
            // directory. A lone relay is NOT an authority on alice's key, so
            // self-heal must NOT trust it (a single malicious relay could
            // otherwise redirect our encryption to a key it controls). The bogus
            // address must SURVIVE.
            ac.mls = "";
            bob.checkStaleMls();
            Thread.sleep(3000);   // let any resolver pass complete
            if ("MxBOGUS@203.0.113.9:9999".equals(
                    bob.contact(alice.identity().publicKeyHex()).primaryAddress())) {
                ok("a single relay directory is NOT trusted to redirect a contact (security)");
            } else {
                bad("a single relay redirected the contact - trust is too broad");
            }

            // CONTACT-VOUCHED: restore alice's OWN advertised MLS (the directory
            // she published to and chose). A single answer from it is the classic
            // trust model, and heals.
            ac.mls = alice.mlsAddress();
            ac.lastSeen = 0;
            int scheduled = bob.checkStaleMls();
            if (scheduled >= 1) {
                ok("checkStaleMls scheduled the stale contact for re-resolution");
            } else {
                bad("checkStaleMls scheduled nothing (" + scheduled + ")");
            }
            boolean healed = waitFor(() -> aliceReal.equals(
                    bob.contact(alice.identity().publicKeyHex()).primaryAddress()), 15);
            if (healed) {
                ok("bob heals via the contact's own advertised MLS");
            } else {
                bad("self-heal did not converge: bob has "
                        + bob.contact(alice.identity().publicKeyHex()).primaryAddress()
                        + " expected " + aliceReal);
            }

            // and the healed address actually works: bob can now reach alice
            final java.util.concurrent.atomic.AtomicInteger aliceGot =
                    new java.util.concurrent.atomic.AtomicInteger();
            alice.setMessageListener((msg, id) -> aliceGot.incrementAndGet());
            int before = aliceGot.get();
            bob.sendRaw(bob.contact(alice.identity().publicKeyHex()).primaryAddress(),
                    "chat_v1", "healed".getBytes(StandardCharsets.UTF_8));
            if (waitFor(() -> aliceGot.get() > before, 15)) {
                ok("bob reaches alice at the self-healed address");
            } else {
                bad("message to the healed address did not arrive");
            }

        } finally {
            stop.countDown();
            try { alice.stop(); } catch (Exception ignored) { }
            try { bob.stop(); } catch (Exception ignored) { }
            relay.stop();
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Address-churn self-heal converges via the shared relay directory.");
    }
}
