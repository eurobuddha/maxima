package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.directory.MlsClient;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.session.SeedRelays;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real signed directory replication and encrypted delivery without compiled-in relays. */
public class IndependentRelaysTest {
    private static final String PROTO = MeshReplicateTest.PROTO;

    private static MaximaIdentity identity() throws Exception {
        return MaximaIdentity.fromPhrase(Bip39.generate(24));
    }

    // RelaySharedModeTest's host-owned listener: keep the allocated port bound from the
    // outset, avoiding the freePort()/close/rebind race of older mesh fixtures.
    private static final class Relay implements AutoCloseable {
        final MaximaIdentity identity = identity();
        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final String host = "127.0.0.1:" + listener.getLocalPort();
        final String mls = identity.mxIdentity() + "@" + host;
        final RelayServer server = new RelayServer(identity, listener.getLocalPort(), PROTO, true);
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final Thread accept;

        Relay() throws Exception {
            server.setShared(true);
            server.setPublicHost("127.0.0.1");
            server.start();
            accept = new Thread(() -> {
                try {
                    while (!listener.isClosed()) server.admit(listener.accept(), null, null);
                } catch (Exception e) {
                    if (!listener.isClosed()) failure.set(e);
                }
            }, "independent-relay-test");
            accept.setDaemon(true);
            accept.start();
        }

        @Override public void close() throws Exception {
            listener.close();
            accept.join(5000);
            server.stop();
            assertFalse("listener terminated", accept.isAlive());
            assertNull("listener failed", failure.get());
        }
    }

    @Test public void originalPermanentAddressSurvivesAnchorLossWithoutBuiltIns() throws Exception {
        MaximaIdentity publisher = identity();
        MaximaIdentity resolver = identity();
        MaximaNode node = new MaximaNode(resolver, PROTO, 2);
        try (Relay anchor = new Relay(); Relay b = new Relay(); Relay c = new Relay();
             HostConnection recipient = new HostConnection("127.0.0.1", b.listener.getLocalPort(),
                     publisher.keyPair(), PROTO)) {
            anchor.server.setPeers(Arrays.asList(b.host, c.host));
            anchor.server.setReplicas(3);
            recipient.attach(5000);
            AttachedSendTest.Collector received = new AttachedSendTest.Collector();
            recipient.startReader(received);
            String published = publisher.mxIdentity() + "@" + b.host;
            String permanent = "MAX#" + publisher.publicKeyHex() + "#" + anchor.mls;
            assertTrue(new MlsClient(publisher).publish(anchor.mls,
                    Collections.singletonList(published), Collections.singletonList(resolver.publicKeyHex())));
            assertTrue(MeshReplicateTest.waitFor(() -> b.server.directory().peek(publisher.publicKeyHex()) != null, 10));
            assertTrue(MeshReplicateTest.waitFor(() -> c.server.directory().peek(publisher.publicKeyHex()) != null, 10));
            assertTrue(b.server.directory().peek(publisher.publicKeyHex()).hasProof());
            assertTrue(c.server.directory().peek(publisher.publicKeyHex()).hasProof());
            assertEquals("anchor works before outage", published, node.resolvePermanent(permanent));

            List<String> seeds = SeedRelays.compose(Collections.singletonList(b.host), null, false, null);
            assertEquals(Collections.singletonList(b.host), seeds);
            assertEquals(1, node.start(seeds, 5000));
            assertTrue(MeshReplicateTest.waitFor(() -> node.poolMlsAddresses().size() == 1, 5));
            anchor.close();
            try {
                node.resolvePermanent(permanent);
                fail("one pool answer must not become an authority");
            } catch (IllegalStateException expected) {
                // No quorum yet, even though B has the signed replica. Keep the anchor
                // transport cause so callers can still diagnose the original outage.
                assertTrue(expected.getCause() instanceof java.net.ConnectException);
            }
            assertTrue(node.pool().attachOne(c.host, 5000));
            assertTrue(MeshReplicateTest.waitFor(() -> node.poolMlsAddresses().size() == 2, 5));
            String resolved = node.resolvePermanent(permanent);
            assertEquals(published, resolved);
            byte[] message = "independent relay recovery".getBytes(StandardCharsets.UTF_8);
            assertTrue(node.sendRaw(resolved, "independent-relay-test", message, 3000, 5000).isOk());
            AttachedSendTest.waitFor(() -> !received.got.isEmpty(), 5000);
            assertArrayEquals(message, received.got.get(0));
        } finally {
            node.stop();
        }
    }

    @Test public void interruptedLookupDoesNotStartFallbackWork() throws Exception {
        MaximaNode node = new MaximaNode(identity(), PROTO, 2);
        try (Relay anchor = new Relay()) {
            String permanent = "MAX#" + identity().publicKeyHex() + "#" + anchor.mls;
            long before = MaximaSender.FRESH_SOCKETS.get();
            try {
                Thread.currentThread().interrupt();
                node.resolvePermanent(permanent);
                fail("interruption must propagate");
            } catch (InterruptedException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(before, MaximaSender.FRESH_SOCKETS.get());
            } finally {
                Thread.interrupted();
            }
        } finally {
            node.stop();
        }
    }
}
