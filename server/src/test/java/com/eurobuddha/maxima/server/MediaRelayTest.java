package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.media.MediaManifest;
import com.eurobuddha.maxima.core.media.MediaService;
import com.eurobuddha.maxima.core.media.MediaWire;
import com.eurobuddha.maxima.core.store.BlobStore;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * The self-hosted media path, in-process: a phone publishes media (kept locally
 * + replicated to a relay's blob shelf), then a SECOND phone fetches and
 * reassembles it through the relay while the publisher is offline — the phone-
 * -as-server story, minus the phone.
 */
public final class MediaRelayTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static final String PROTO = "1.0.48";

    static MaximaIdentity idFrom(int s) {
        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i * s + s);
        return MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static Thread pump(MaximaNode n, String hp, CountDownLatch stop) {
        Thread t = new Thread(() -> {
            while (stop.getCount() > 0) {
                try {
                    n.pump(hp, 800);
                } catch (Exception e) {
                    if (stop.getCount() > 0) return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static boolean waitFor(java.util.function.BooleanSupplier c, int s) throws Exception {
        long until = System.currentTimeMillis() + s * 1000L;
        while (System.currentTimeMillis() < until) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return c.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== SELF-HOSTED MEDIA (loopback) ===\n");

        int port = freePort();
        String hostPort = "127.0.0.1:" + port;
        MaximaIdentity relayId = MaximaIdentity.fromPhrase(Bip39.generate(24));
        RelayServer relay = new RelayServer(relayId, port, PROTO);
        relay.setPublicHost("127.0.0.1");
        File shelf = Files.createTempDirectory("relayblobs").toFile();
        relay.setBlobStore(new BlobStore(shelf, 64L * 1024 * 1024));
        relay.start();

        CountDownLatch stop = new CountDownLatch(1);
        MaximaNode alice = null, bob = null;
        try {
            alice = new MaximaNode(idFrom(21), PROTO, 1);
            bob = new MaximaNode(idFrom(22), PROTO, 1);
            alice.start(Collections.singletonList(hostPort), 15000);
            bob.start(Collections.singletonList(hostPort), 15000);
            Thread alicePump = pump(alice, hostPort, stop);
            pump(bob, hostPort, stop);
            final MaximaNode fa = alice, fb = bob;
            if (!waitFor(() -> !fa.pool().activeHosts().isEmpty()
                    && !fb.pool().activeHosts().isEmpty(), 15)) {
                bad("nodes did not attach");
                System.exit(1);
            }

            // ---- Alice publishes a 400KB "image" ----
            byte[] media = new byte[400 * 1024];
            for (int i = 0; i < media.length; i++) media[i] = (byte) (i * 17 + 3);

            File aliceBlobs = Files.createTempDirectory("aliceblobs").toFile();
            MediaService aliceMedia = new MediaService(alice, new BlobStore(aliceBlobs));
            long t0 = System.currentTimeMillis();
            MediaManifest mf = aliceMedia.publish(media, "image/jpeg");
            long publishMs = System.currentTimeMillis() - t0;

            if (mf.chunkIds.size() == 3 && !mf.sources.isEmpty()) {
                ok("published: 3 chunks, manifest lists " + mf.sources.size() + " source(s)");
            } else {
                bad("publish shape: chunks=" + mf.chunkIds.size() + " src=" + mf.sources);
            }

            // The regression this whole fix targets: only ONE relay is attached
            // but REPLICAS is 2. The old serial path would grind chunk-by-chunk;
            // the new bounded path must return as soon as the single worker
            // finishes - NOT sit on the 55s budget waiting for a 2nd relay that
            // can never appear. Comfortably under the client's 90s latch.
            if (publishMs < 20_000) {
                ok("publish returned in " + publishMs + "ms (no budget stall on a single relay)");
            } else {
                bad("publish took " + publishMs + "ms - replication is stalling the client");
            }

            // the relay actually holds every chunk now
            boolean allOnRelay = true;
            String relayAddr = alice.pool().connection(hostPort).getTheirMlsAddress();
            for (String id : mf.chunkIds) {
                allOnRelay &= MediaWire.has(alice, relayAddr, id);
            }
            if (allOnRelay) {
                ok("every chunk was replicated to the relay's blob shelf");
            } else {
                bad("chunks not all on the relay");
            }

            // ---- the mesh per-file cap rejects oversize UP FRONT ----
            byte[] tooBig = new byte[(int) MediaService.MAX_MESH_FILE_BYTES + 1];
            long r0 = System.currentTimeMillis();
            String capErr = null;
            try {
                aliceMedia.publish(tooBig, "video/mp4");
            } catch (Exception ex) {
                capErr = ex.getMessage();
            }
            long capMs = System.currentTimeMillis() - r0;
            if (capErr != null && capErr.contains("16 MB") && capMs < 1000) {
                ok("oversize rejected in " + capMs + "ms with guidance: \"" + capErr + "\"");
            } else {
                bad("oversize not rejected cleanly (err=" + capErr + " in " + capMs + "ms)");
            }

            // ---- Alice goes offline; Bob fetches purely from the relay ----
            alice.stop();
            alicePump.interrupt();
            Thread.sleep(1500);

            File bobBlobs = Files.createTempDirectory("bobblobs").toFile();
            MediaService bobMedia = new MediaService(bob, new BlobStore(bobBlobs));
            byte[] got = bobMedia.fetch(mf);

            if (java.util.Arrays.equals(got, media)) {
                ok("BOB REASSEMBLED THE MEDIA from the relay, publisher OFFLINE");
            } else {
                bad("fetch mismatch (got " + (got == null ? "null" : got.length + "B") + ")");
            }

            // a stranger key cannot PUT (fill our disk) - anti-flood gate
            // (bob is attached, so this proves the put path works for attached
            // users; the never-attached rejection is covered by the mailbox test
            // that shares the exact gate. Here we assert bob CAN put.)
            byte[] extra = new byte[1024];
            for (int i = 0; i < extra.length; i++) extra[i] = (byte) i;
            String bobRelay = bob.pool().connection(hostPort).getTheirMlsAddress();
            if (MediaWire.put(bob, bobRelay, extra)
                    && MediaWire.has(bob, bobRelay, BlobStore.idOf(extra))) {
                ok("an attached user can put a chunk and read it back");
            } else {
                bad("attached put/has failed");
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
        System.out.println("Self-hosted media holds.");
    }
}
