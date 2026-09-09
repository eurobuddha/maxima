package com.eurobuddha.maxima.core.session;

import com.eurobuddha.maxima.core.msg.Greeting;
import com.eurobuddha.maxima.core.net.Frame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/** A loopback relay: answers every connection with our greeting (peers included). */
final class FakeRelay implements AutoCloseable {
    final ServerSocket server;
    final List<String> peers;
    final int port;
    final Thread acceptor;
    volatile int greeted;
    volatile boolean running = true;
    /** A wallet gateway to advertise, or null. */
    volatile String gateway;
    volatile String gatewayKey;
    volatile Runnable beforeGreeting = () -> { };
    interface MessageHook { void received(DataOutputStream out) throws Exception; }
    volatile MessageHook onMessage = out -> { };

    FakeRelay(List<String> zPeers) throws Exception {
        server = new ServerSocket(0);
        port = server.getLocalPort();
        peers = zPeers;
        acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    if (!running) { s.close(); return; }
                    new Thread(() -> serve(s)).start();
                } catch (Exception e) {
                    return;
                }
            }
        }, "fake-relay-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String hostPort() {
        return "127.0.0.1:" + port;
    }

    void serve(Socket s) {
        try {
            s.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Frame.readOrSkip(in, 65536);   // their greeting
            beforeGreeting.run();
            synchronized (this) {
                if (!running) return;
                Frame.write(out, Frame.body(Frame.MSG_GREETING,
                        Greeting.commsOnly(PeerDiscoveryTest.PROTO, "127.0.0.1", port, peers, 64, true, 3,
                                gateway, gatewayKey)));
                greeted++;
            }
            // hold the socket like a relay would, until the peer goes
            while (running) {
                byte[] f = Frame.readOrSkip(in, 65536);
                if (f == null) {
                    continue;
                }
                if (Frame.typeOf(f) == Frame.MSG_MAXIMA_TXPOW) onMessage.received(out);
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            running = false;
            server.close();
        }
        // Java 21/Linux can complete a blocked accept after ServerSocket.close returns.
        // Wait for the accept loop to leave before a test treats this port as stopped.
        acceptor.join(5000);
        if (acceptor.isAlive()) throw new AssertionError("fake relay acceptor did not stop");
    }
}
