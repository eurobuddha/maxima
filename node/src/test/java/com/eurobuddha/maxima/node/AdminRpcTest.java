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
}
