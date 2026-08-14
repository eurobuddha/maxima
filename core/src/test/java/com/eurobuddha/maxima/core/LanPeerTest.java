package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.DirectEndpoint;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The LAN-discovery integration (phase E), minus the Android mDNS plumbing:
 * once a peer is noted on the local network, a message to that contact takes
 * the LAN path to their direct endpoint - no relay - and falls back cleanly
 * when the LAN address goes stale.
 *
 * The NsdManager discovery itself is Android-only and cannot run here; what is
 * tested is the :core contract it drives: noteLanPeer / forgetLanPeer and the
 * send ordering that makes the whole feature worthwhile.
 */
public class LanPeerTest {

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
        System.out.println("=== LAN PEER (phase E integration) ===\n");

        MaximaIdentity meId = idFrom(101);
        MaximaIdentity peerId = idFrom(103);

        // The peer runs a direct endpoint on loopback - standing in for "same LAN".
        final AtomicInteger got = new AtomicInteger();
        final AtomicReference<String> body = new AtomicReference<>();
        DirectEndpoint peerEndpoint = new DirectEndpoint(peerId, "1.0.48", inb -> {
            got.incrementAndGet();
            body.set(new String(inb.message.mData.getBytes()));
        });
        int lanPort = peerEndpoint.start(0);

        MaximaNode me = new MaximaNode(meId, "1.0.48", 1);

        // We know the peer as a contact, but with a DEAD relay address (nothing
        // listening there), so a successful send can only have gone via the LAN.
        Contact peer = new Contact(peerId.publicKeyHex());
        peer.name = "peer";
        peer.setAddresses(java.util.Collections.singletonList(
                peerId.mxIdentity() + "@127.0.0.1:1"));   // port 1: refused
        me.storeContact(peer);

        // Before discovery: the only address is the dead one, so a send fails.
        boolean failedFirst;
        try {
            me.sendToContact(peer, "lan_test_v1", "hi".getBytes());
            failedFirst = false;
        } catch (Exception e) {
            failedFirst = true;
        }
        if (failedFirst && got.get() == 0) {
            ok("with only a dead relay address, the send fails (nothing on the LAN yet)");
        } else {
            bad("unexpected delivery before LAN discovery");
        }

        // mDNS discovers the peer on the LAN -> note it.
        me.noteLanPeer(peerId.publicKeyHex(), "127.0.0.1:" + lanPort);
        if (me.lanAddressFor(peerId.publicKeyHex()) != null) {
            ok("noteLanPeer recorded a LAN address for the contact");
        } else {
            bad("LAN address not recorded");
        }

        // Now a send takes the LAN path FIRST and reaches the peer directly.
        me.sendToContact(peer, "lan_test_v1", "hello over the LAN".getBytes());
        Thread.sleep(400);
        if (got.get() == 1 && "hello over the LAN".equals(body.get())) {
            ok("the message reached the peer over the LAN, no relay involved");
        } else {
            bad("LAN delivery failed: " + got.get() + " " + body.get());
        }

        // They leave the LAN -> forget it -> we are back to the dead address.
        me.forgetLanPeer(peerId.publicKeyHex());
        boolean failedAfter;
        try {
            me.sendToContact(peer, "lan_test_v1", "gone".getBytes());
            failedAfter = false;
        } catch (Exception e) {
            failedAfter = true;
        }
        if (failedAfter && got.get() == 1) {
            ok("after they leave the LAN, the send falls back and fails on the dead relay - "
                    + "a stale LAN address costs one attempt, never a lost message");
        } else {
            bad("stale LAN address not handled: failed=" + failedAfter + " got=" + got.get());
        }

        // SELF-HEAL: a LAN address that fails a send must be forgotten on the
        // spot, so a peer who left the network cannot tax every future send with
        // a connect timeout even if the mDNS "lost" event was missed.
        me.noteLanPeer(peerId.publicKeyHex(), "127.0.0.1:1");   // dead LAN port
        try {
            me.sendToContact(peer, "lan_test_v1", "will fail".getBytes());
        } catch (Exception ignored) {
        }
        if (me.lanAddressFor(peerId.publicKeyHex()) == null) {
            ok("a failed LAN send evicts the stale address immediately (self-heal)");
        } else {
            bad("stale LAN address survived a failed send");
        }

        peerEndpoint.stop();
        me.stop();

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("LAN peer integration holds.");
    }
}
