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
    volatile int greeted;
    volatile boolean running = true;
    /** A wallet gateway to advertise, or null. */
    volatile String gateway;
    volatile String gatewayKey;

    FakeRelay(List<String> zPeers) throws Exception {
        server = new ServerSocket(0);
        port = server.getLocalPort();
        peers = zPeers;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> serve(s)).start();
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
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
            Frame.write(out, Frame.body(Frame.MSG_GREETING,
                    Greeting.commsOnly(PeerDiscoveryTest.PROTO, "127.0.0.1", port, peers, 64, true, 3,
                            gateway, gatewayKey)));
            greeted++;
            // hold the socket like a relay would, until the peer goes
            while (running) {
                byte[] f = Frame.readOrSkip(in, 65536);
                if (f == null) {
                    continue;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        server.close();
    }
}

