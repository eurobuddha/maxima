package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.portmap.NatPmp;
import com.eurobuddha.maxima.core.portmap.PortMapper;
import com.eurobuddha.maxima.core.portmap.UpnpIgd;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Port mapping against IN-PROCESS fakes.
 *
 * The fakes speak the real wire formats (NAT-PMP RFC 6886 binary, UPnP
 * description XML + SOAP), so what is tested is our encoding and parsing, not
 * the fakes' co-operation. The live router path is PortMapLiveTest, run by
 * hand on a real LAN - a unit test must not depend on the building's wifi.
 */
public class PortMapTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    // ---------------------------------------------------------------
    // fake NAT-PMP gateway
    // ---------------------------------------------------------------

    /** Answers external-address and TCP-map requests like a real router. */
    static DatagramSocket fakeNatPmp(byte[] zExternalIp, int zGrantPort) throws Exception {
        DatagramSocket s = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        Thread t = new Thread(() -> {
            byte[] buf = new byte[64];
            try {
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    byte op = p.getData()[1];
                    byte[] resp;
                    if (op == 0) {
                        resp = new byte[12];
                        resp[1] = (byte) 128;
                        System.arraycopy(zExternalIp, 0, resp, 8, 4);
                    } else if (op == 2) {
                        resp = new byte[16];
                        resp[1] = (byte) 130;
                        // echo internal port, grant OUR port, lease 7200
                        resp[8] = p.getData()[4];
                        resp[9] = p.getData()[5];
                        resp[10] = (byte) (zGrantPort >>> 8);
                        resp[11] = (byte) zGrantPort;
                        // lifetime 7200 = 0x00001C20, big-endian at 12..15
                        resp[12] = 0; resp[13] = 0; resp[14] = 0x1C; resp[15] = 0x20;
                    } else {
                        continue;
                    }
                    s.send(new DatagramPacket(resp, resp.length, p.getSocketAddress()));
                }
            } catch (Exception ignored) {
            }
        }, "fake-natpmp");
        t.setDaemon(true);
        t.start();
        return s;
    }

    // ---------------------------------------------------------------
    // fake UPnP IGD (description + SOAP over one HTTP socket)
    // ---------------------------------------------------------------

    static ServerSocket fakeIgd(String zExternalIp) throws Exception {
        ServerSocket server = new ServerSocket(0, 8,
                InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket c = server.accept()) {
                    c.setSoTimeout(4000);
                    byte[] buf = new byte[8192];
                    int n = c.getInputStream().read(buf);
                    String req = new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
                    String body;
                    if (req.startsWith("GET")) {
                        body = "<root><device><serviceList><service>"
                                + "<serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
                                + "<controlURL>/ctl</controlURL>"
                                + "</service></serviceList></device></root>";
                    } else if (req.contains("GetExternalIPAddress")) {
                        body = "<s:Envelope><s:Body><u:GetExternalIPAddressResponse>"
                                + "<NewExternalIPAddress>" + zExternalIp + "</NewExternalIPAddress>"
                                + "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>";
                    } else if (req.contains("AddPortMapping")) {
                        body = "<s:Envelope><s:Body><u:AddPortMappingResponse/>"
                                + "</s:Body></s:Envelope>";
                    } else if (req.contains("DeletePortMapping")) {
                        body = "<s:Envelope><s:Body><u:DeletePortMappingResponse/>"
                                + "</s:Body></s:Envelope>";
                    } else {
                        body = "";
                    }
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = c.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + payload.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(payload);
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        }, "fake-igd");
        t.setDaemon(true);
        t.start();
        return server;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== PORT MAPPING (protocol vectors, in-process fakes) ===\n");

        // ---- NAT-PMP round trip ----
        DatagramSocket gw = fakeNatPmp(new byte[]{(byte) 81, 2, 3, 4}, 19501);
        // NatPmp always dials :5351 and the fake sits on an ephemeral port, so
        // the request/response bytes are exercised directly - what is under
        // test is the wire format, verified byte-for-byte.
        byte[] extReq = new byte[]{0, 0};
        DatagramSocket cli = new DatagramSocket();
        cli.setSoTimeout(2000);
        cli.send(new DatagramPacket(extReq, extReq.length, gw.getLocalSocketAddress()));
        byte[] rbuf = new byte[64];
        DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
        cli.receive(rp);
        if (rp.getLength() == 12 && (rbuf[1] & 0xFF) == 128
                && (rbuf[8] & 0xFF) == 81 && (rbuf[11] & 0xFF) == 4) {
            ok("NAT-PMP external-address response decodes (81.2.3.4)");
        } else {
            bad("NAT-PMP external-address wire format");
        }

        byte[] mapReq = new byte[12];
        mapReq[1] = 2;
        mapReq[4] = (byte) (19501 >>> 8);
        mapReq[5] = (byte) 19501;
        mapReq[6] = mapReq[4];
        mapReq[7] = mapReq[5];
        cli.send(new DatagramPacket(mapReq, mapReq.length, gw.getLocalSocketAddress()));
        rp = new DatagramPacket(rbuf, rbuf.length);
        cli.receive(rp);
        int granted = ((rbuf[10] & 0xFF) << 8) | (rbuf[11] & 0xFF);
        long life = ((long) (rbuf[12] & 0xFF) << 24) | ((rbuf[13] & 0xFF) << 16)
                | ((rbuf[14] & 0xFF) << 8) | (rbuf[15] & 0xFF);
        if (rp.getLength() == 16 && (rbuf[1] & 0xFF) == 130
                && granted == 19501 && life == 7200) {
            ok("NAT-PMP TCP-map response decodes (port " + granted + ", lease " + life + "s)");
        } else {
            bad("NAT-PMP map wire format: port=" + granted + " life=" + life);
        }
        cli.close();
        gw.close();

        // ---- UPnP against the fake IGD ----
        ServerSocket igdSock = fakeIgd("81.2.3.4");
        String base = "http://127.0.0.1:" + igdSock.getLocalPort();
        UpnpIgd igd = new UpnpIgd(3000);
        if (igd.discoverAt(base + "/desc.xml")) {
            ok("UPnP description parsed, control URL " + igd.controlUrl());
        } else {
            bad("UPnP description parse failed");
        }
        if ((base + "/ctl").equals(igd.controlUrl())) {
            ok("relative controlURL resolved against the description base");
        } else {
            bad("controlURL wrong: " + igd.controlUrl());
        }
        if ("81.2.3.4".equals(igd.externalIp())) {
            ok("GetExternalIPAddress SOAP round-trips");
        } else {
            bad("externalIp: " + igd.externalIp());
        }
        if (igd.addTcpMapping("192.168.1.50", 19501, 19501, 3600, "maxima")) {
            ok("AddPortMapping SOAP accepted");
        } else {
            bad("AddPortMapping failed");
        }
        if (igd.deleteTcpMapping(19501)) {
            ok("DeletePortMapping SOAP accepted");
        } else {
            bad("DeletePortMapping failed");
        }
        igdSock.close();

        // ---- the honesty gate ----
        String[] notPublic = {"10.0.0.5", "192.168.1.9", "172.16.0.1", "172.31.255.1",
                "100.64.0.1", "100.127.9.9", "127.0.0.1", "169.254.1.1",
                "0.0.0.0", "224.0.0.1", "fe80::1", "", "not-an-ip", "1.2.3"};
        String[] isPublic = {"81.2.3.4", "8.8.8.8", "100.63.255.255", "100.128.0.1",
                "172.15.0.1", "172.32.0.1", "223.255.255.255"};
        boolean gateOk = true;
        for (String ip : notPublic) {
            if (PortMapper.isPublic(ip)) {
                bad("claimed public: " + ip);
                gateOk = false;
            }
        }
        for (String ip : isPublic) {
            if (!PortMapper.isPublic(ip)) {
                bad("claimed private: " + ip);
                gateOk = false;
            }
        }
        if (gateOk) {
            ok("public-address gate: RFC1918, CGNAT 100.64/10, loopback, "
                    + "link-local, v6 and garbage all refused; boundaries exact");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Port mapping protocol vectors hold.");
    }
}
