package com.eurobuddha.maxima.node;

import org.junit.Test;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Only empty commands are used: no node instance, identity or funds. Raw HTTP preserves
 * Host and Origin exactly (HttpURLConnection suppresses those restricted headers). */
public class AdminRpcTest {
    private int call(AdminRpc rpc, String method, String host, String extra) throws Exception {
        try (Socket s = new Socket("127.0.0.1", rpc.port())) {
            s.setSoTimeout(2000);
            String request = method + " / HTTP/1.1\r\nHost: " + host + "\r\n"
                    + extra + "Content-Length: 0\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }

    @Test public void browserRequestsAreRejectedBeforeCommandDispatch() throws Exception {
        AdminRpc rpc = AdminRpc.start(0);
        try {
            String host = "127.0.0.1:" + rpc.port();
            assertEquals(403, call(rpc, "GET", "attacker.example:" + rpc.port(), ""));
            assertEquals(403, call(rpc, "POST", host, "Origin: https://attacker.example\r\n"));
            assertEquals(403, call(rpc, "POST", host, "Origin: null\r\n"));
            assertEquals(403, call(rpc, "GET", host, "Sec-Fetch-Site: cross-site\r\n"));
            assertEquals(403, call(rpc, "GET", host, "Sec-Fetch-Site: same-site\r\n"));
            assertEquals(405, call(rpc, "OPTIONS", host, ""));
            // Accepted transport reaches the empty-command validator, never CommandRunner.
            assertEquals(400, call(rpc, "GET", host, ""));
            assertEquals(400, call(rpc, "POST", host, ""));
            assertEquals(400, call(rpc, "POST", host, "Origin: http://" + host + "\r\nSec-Fetch-Site: same-origin\r\n"));
        } finally { rpc.stop(); }
    }

    private String send(AdminRpc rpc, String method, String path, String extra, String body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", rpc.port())) {
            s.setSoTimeout(5000);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            String request = method + " /" + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + rpc.port() + "\r\n"
                    + extra + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().write(b);
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A request a page could send without a preflight (a GET or simple POST with no X-Minima-Rpc / Authorization)
     *  runs as a non-admin caller; the desktops' rpcCall (GET + Basic) and X-Minima-Rpc run as ADMIN (2026-11-23). */
    @Test public void simpleRequestsRunAsNonAdmin() throws Exception {
        org.minima.database.MinimaDB.createDB();
        java.lang.reflect.Field txndb = org.minima.database.MinimaDB.class.getDeclaredField("mTxnDB");
        txndb.setAccessible(true);
        txndb.set(org.minima.database.MinimaDB.getDB(), new org.minima.database.userprefs.txndb.TxnDB());
        org.minima.database.userprefs.txndb.TxnDB db = org.minima.database.MinimaDB.getDB().getCustomTxnDB();
        AdminRpc rpc = AdminRpc.start(0);
        try {
            // seedrandom: a WRITE command on every node jar (txncreate became one only with this series' txn gate)
            String r = send(rpc, "GET", "seedrandom%20modifier:x", "", "");
            assertTrue(r, r.contains("You need ADMIN privileges") && r.contains("Add the request header  X-Minima-Rpc: 1  (curl: -H 'X-Minima-Rpc: 1')"));
            r = send(rpc, "POST", "", "Content-Type: text/plain\r\n", "seedrandom modifier:x");
            assertTrue(r, r.contains("You need ADMIN privileges"));
            assertTrue(send(rpc, "GET", "random", "", "").contains("\"status\":true"));

            send(rpc, "POST", "", "X-Minima-Rpc: 1\r\n", "txncreate id:admheader");
            assertTrue(db.getTransactionRow("admheader") != null);
            // the desktops' rpcCall: GET with a Basic header
            send(rpc, "GET", "txncreate%20id:admbasic", "Authorization: Basic bWluaW1hOng=\r\n", "");
            assertTrue(db.getTransactionRow("admbasic") != null);
        } finally { rpc.stop(); }
    }
    /** Two requests that are still being served (their bodies have not arrived) must not hold up a third: with two
     *  worker threads two slow commands starved every other command of the host app (24 Nov 2026). */
    @Test public void twoSlowRequestsDoNotHoldUpAThird() throws Exception {
        AdminRpc rpc = AdminRpc.start(0);
        java.util.List<Socket> held = new java.util.ArrayList<>();
        try {
            String host = "127.0.0.1:" + rpc.port();
            for (int i = 0; i < 4; i++) {   // four handlers wait for a body that does not come
                Socket s = new Socket("127.0.0.1", rpc.port());
                s.getOutputStream().write(("POST / HTTP/1.1\r\nHost: " + host + "\r\nContent-Length: 10\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                held.add(s);
            }
            Thread.sleep(300);
            assertTrue(AdminRpc.WORKERS >= 8);
            assertEquals("a fifth request is answered while four are held", 400, call(rpc, "GET", host, ""));
        } finally { for (Socket s : held) try { s.close(); } catch (Exception ignored) { } rpc.stop(); }
    }
}
