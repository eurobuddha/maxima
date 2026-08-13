package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import com.eurobuddha.maxima.core.services.Tier1Services;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-box self test.
 *
 * Separates the two questions that get conflated when a relay "doesn't work":
 *
 *   1. Is the BINARY working on this machine?      <- this test answers it
 *   2. Is the PORT reachable from the internet?    <- only an outside host can
 *
 * Almost every "it isn't working" turns out to be (2) - a cloud firewall or
 * security group - but you cannot tell without ruling out (1) first, locally,
 * where no firewall is in the way.
 */
public final class SelfTest {

    public static int run(int zPort, String zProtocol) {
        System.out.println("Maxima relay self test");
        System.out.println("======================");
        System.out.println();

        int pass = 0, fail = 0;

        // ---- 1. what are we actually bound to ----
        System.out.println("[1] network interfaces on this host");
        List<String> publicish = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address) {
                        boolean priv = a.isSiteLocalAddress();
                        System.out.println("      " + ni.getName() + "  " + a.getHostAddress()
                                + (priv ? "  (private - you are behind NAT)" : "  (public)"));
                        if (!priv) {
                            publicish.add(a.getHostAddress());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("      could not enumerate: " + e);
        }
        if (publicish.isEmpty()) {
            System.out.println("      NOTE: no public IPv4 on an interface. On most VPS hosts the");
            System.out.println("      public address is NAT'd onto a private one, which is fine -");
            System.out.println("      but it means only an OUTSIDE host can confirm reachability.");
        }
        System.out.println();

        // ---- 2. start a relay ----
        System.out.println("[2] starting a relay on port " + zPort);
        MaximaIdentity relayId = MaximaIdentity.fromPhrase(Bip39.generate(24));
        RelayServer relay = new RelayServer(relayId, zPort, zProtocol);
        try {
            relay.start();
            pass++;
            System.out.println("      ok bound to 0.0.0.0:" + zPort);
        } catch (Exception e) {
            fail++;
            System.out.println("      XX could not bind: " + e.getMessage());
            System.out.println();
            System.out.println("      The port is taken. Pick another with --port.");
            summary(pass, fail);
            return 1;
        }
        System.out.println();

        try {
            // ---- 3. can anything connect to it locally ----
            System.out.println("[3] local TCP connect to 127.0.0.1:" + zPort);
            try (Socket s = new Socket("127.0.0.1", zPort)) {
                pass++;
                System.out.println("      ok accepted a connection");
            } catch (Exception e) {
                fail++;
                System.out.println("      XX " + e);
            }
            System.out.println();

            // ---- 4. a real client attaches and registers a route ----
            System.out.println("[4] attaching a Maxima client");
            MaximaIdentity clientId = MaximaIdentity.fromPhrase(Bip39.generate(24));
            String hostPort = "127.0.0.1:" + zPort;
            HostConnection conn = new HostConnection("127.0.0.1", zPort,
                    clientId.hostKey(hostPort), zProtocol);
            try {
                conn.attach(15000);
                pass++;
                System.out.println("      ok attached, routing key registered");
                System.out.println("      address " + conn.contactAddress().substring(0, 30)
                        + "...@" + hostPort);
            } catch (Exception e) {
                fail++;
                System.out.println("      XX attach failed: " + e);
                summary(pass, fail);
                relay.stop();
                return 1;
            }
            System.out.println();

            // ---- 5. relay a real message end to end ----
            System.out.println("[5] relaying a signed, encrypted message through it");
            try {
                com.eurobuddha.maxima.core.MaximaSender.Built built =
                        com.eurobuddha.maxima.core.MaximaSender.build(
                                clientId.publicKey(), clientId.keyPair().getPrivate(),
                                conn.routingKey(), "relay_selftest",
                                "selftest".getBytes(StandardCharsets.UTF_8),
                                System.currentTimeMillis());

                com.eurobuddha.maxima.core.MaximaSender.Result res =
                        com.eurobuddha.maxima.core.MaximaSender.send(
                                "127.0.0.1", zPort, built.unit, built.msgid);

                if (!res.isOk()) {
                    fail++;
                    System.out.println("      XX relay replied " + res.statusName);
                } else {
                    HostConnection.Inbound in = conn.receive(15000);
                    if (in != null && in.signatureValid
                            && "selftest".equals(new String(in.message.mData.getBytes(),
                            StandardCharsets.UTF_8))) {
                        pass++;
                        System.out.println("      ok message relayed and signature verified");
                    } else {
                        fail++;
                        System.out.println("      XX nothing came back down the connection");
                    }
                }
            } catch (Exception e) {
                fail++;
                System.out.println("      XX " + e);
            }
            System.out.println();

            // ---- 6. services answer ----
            System.out.println("[6] Tier 1 services");
            try {
                MaximaNode node = new MaximaNode(clientId, zProtocol, 1);
                node.start(Collections.singletonList(hostPort), 15000);
                Thread stopFlag = null;
                final boolean[] running = {true};
                Thread pump = new Thread(() -> {
                    while (running[0]) {
                        try {
                            node.pump(hostPort, 1000);
                        } catch (Exception ignored) {
                            return;
                        }
                    }
                });
                pump.setDaemon(true);
                pump.start();
                Thread.sleep(1000);

                CountDownLatch l = new CountDownLatch(1);
                AtomicReference<String> got = new AtomicReference<>();
                node.rpc().call(node.myAddresses().get(0), Tier1Services.PING, new byte[0],
                        new RpcPeer.ResponseHandler() {
                            public void onResponse(byte[] p) {
                                got.set(new String(p, StandardCharsets.UTF_8));
                                l.countDown();
                            }

                            public void onError(String m) {
                                got.set("ERR:" + m);
                                l.countDown();
                            }
                        });
                boolean came = l.await(25, TimeUnit.SECONDS);
                running[0] = false;
                if (came && "pong".equals(got.get())) {
                    pass++;
                    System.out.println("      ok ping answered via reply-as-new-message");
                } else {
                    fail++;
                    System.out.println("      XX ping did not return (" + got.get() + ")");
                }
                node.stop();
            } catch (Exception e) {
                fail++;
                System.out.println("      XX " + e);
            }

            conn.close();
        } finally {
            relay.stop();
        }

        System.out.println();
        summary(pass, fail);

        System.out.println();
        System.out.println("REACHABILITY - this test CANNOT answer it");
        System.out.println("  Everything above ran over loopback, so no firewall was involved.");
        System.out.println("  From ANOTHER machine, check the port is actually open:");
        System.out.println();
        System.out.println("      nc -vz <your-public-ip> " + zPort);
        System.out.println();
        System.out.println("  If that fails while this test passes, the binary is fine and the");
        System.out.println("  problem is a firewall or cloud security group. Common causes:");
        System.out.println("      - VPS provider firewall / security group not opened");
        System.out.println("      - ufw / firewalld default deny   (sudo ufw allow " + zPort + "/tcp)");
        System.out.println("      - the host is itself behind NAT with no port forward");

        return fail == 0 ? 0 : 1;
    }

    private static void summary(int pass, int fail) {
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail == 0) {
            System.out.println("The relay binary works correctly on this machine.");
        } else {
            System.out.println("The relay itself has a problem - see above.");
        }
    }
}
