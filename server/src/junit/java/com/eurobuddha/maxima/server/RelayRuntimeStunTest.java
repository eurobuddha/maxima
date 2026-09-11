package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** The node's shared-port relay must offer the same STUN service as the standalone CLI. */
public class RelayRuntimeStunTest {
    @Test
    public void sharedRuntimeAnswersStunAndReleasesUdpOnStop() throws Exception {
        // Reuse the shared-mode fixture: the host owns TCP throughout the test.
        try (ServerSocket host = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = host.getLocalPort();
            RelayRuntime runtime = new RelayRuntime(MaximaIdentity.fromPhrase(Bip39.generate(24)),
                    port, "1.0.48", 100, "", Files.createTempDirectory("runtime-stun-"));
            runtime.setShared(true);
            try {
                runtime.start();
                assertTrue(runtime.server().acceptAlive());
                try (DatagramSocket client = new DatagramSocket()) {
                    client.setSoTimeout(1500);
                    byte[] tx = {1,2,3,4,5,6,7,8,9,10,11,12};
                    byte[] request = MiniStunTest.bindingRequest(tx);
                    client.send(new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));
                    DatagramPacket reply = new DatagramPacket(new byte[128], 128);
                    client.receive(reply);
                    assertEquals(32, reply.getLength());
                    assertArrayEquals(tx, Arrays.copyOfRange(reply.getData(), 8, 20));
                    assertEquals(client.getLocalPort(), (((reply.getData()[26] & 255) << 8)
                            | (reply.getData()[27] & 255)) ^ 0x2112);
                }
            } finally { runtime.stop(); }
            // Closing a runtime must not leave an orphan STUN thread holding the port.
            try (DatagramSocket rebound = new DatagramSocket(port)) {
                assertEquals(port, rebound.getLocalPort());
            }
        }
    }

    @Test
    public void occupiedUdpDoesNotKillSharedRelayOrCloseAnotherOwnersSocket() throws Exception {
        try (DatagramSocket owner = new DatagramSocket(0)) {
            RelayRuntime runtime = new RelayRuntime(MaximaIdentity.fromPhrase(Bip39.generate(24)),
                    owner.getLocalPort(), "1.0.48", 100, "", Files.createTempDirectory("runtime-stun-busy-"));
            runtime.setShared(true);
            try {
                runtime.start();
                assertTrue(runtime.server().acceptAlive());
            } finally { runtime.stop(); }
            assertFalse(owner.isClosed());
        }
    }

    @Test
    public void operatorCanDisableStunWithoutDisablingTheRelay() throws Exception {
        try (ServerSocket host = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            RelayRuntime runtime = new RelayRuntime(MaximaIdentity.fromPhrase(Bip39.generate(24)),
                    host.getLocalPort(), "1.0.48", 100, "", Files.createTempDirectory("runtime-no-stun-"));
            runtime.setShared(true);
            runtime.setStunEnabled(false);
            try {
                runtime.start();
                assertTrue(runtime.server().acceptAlive());
                try (DatagramSocket other = new DatagramSocket(host.getLocalPort())) {
                    assertEquals(host.getLocalPort(), other.getLocalPort());
                }
            } finally { runtime.stop(); }
        }
    }
}
