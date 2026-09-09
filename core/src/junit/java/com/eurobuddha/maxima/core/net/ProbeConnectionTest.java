package com.eurobuddha.maxima.core.net;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeConnectionTest {
    private static final String PROTOCOL = "1.0.48";

    @Test public void aSocketCannotProveItsOwnReachability() throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = socket.getLocalPort();
            assertNull("our own echoed greeting is not a peer",
                    Probe.dialGreeting(socket, "127.0.0.1", port, 1000, 1000,
                            PROTOCOL, null, 0));
            assertTrue("the rejected socket is closed", socket.isClosed());
            // Linux permits simultaneous open to the same local/remote endpoint.
            // macOS refuses that connect itself; it still must return null and close.
            if (System.getProperty("os.name").equals("Linux")) {
                assertTrue("exercise a real self-connected socket on Linux", socket.isConnected());
            }
        }
    }

    @Test public void ordinaryLoopbackStillReachesASeparateEndpoint() throws Exception {
        DirectEndpoint endpoint = endpoint();
        try {
            int port = endpoint.start(0);
            assertTrue(port > 0);
            try (Socket socket = new Socket()) {
                assertNotNull(Probe.dialGreeting(socket, "127.0.0.1", port, 3000, 3000,
                        PROTOCOL, null, 0));
                assertTrue("successful probes also close their socket", socket.isClosed());
            }
            assertTrue(Probe.dial("127.0.0.1", port, 3000, 3000, PROTOCOL));
        } finally { endpoint.stop(); }
    }

    @Test public void aLogicalAliasStillReachesItsSeparateLocalEndpoint() throws Exception {
        DirectEndpoint endpoint = endpoint();
        String logical = "probe-alias.invalid:9501";
        try {
            int port = endpoint.start(0);
            assertTrue(port > 0);
            DialAlias.set(logical, "127.0.0.1:" + port);
            assertNotNull(Probe.dialGreeting("probe-alias.invalid", 9501, 3000, 3000,
                    PROTOCOL, "192.0.2.1", 9501));
        } finally { DialAlias.clear(logical); endpoint.stop(); }
    }

    private static DirectEndpoint endpoint() {
        return new DirectEndpoint(MaximaIdentity.fromSeed(new MiniData(new byte[32])),
                PROTOCOL, inbound -> { });
    }
}
