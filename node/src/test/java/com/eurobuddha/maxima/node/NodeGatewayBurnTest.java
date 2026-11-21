package com.eurobuddha.maxima.node;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * txnpost burn: through the gateway would make the node pay a burn from its own coins (the gateway runs
 * commands as ADMIN and its bearer token is shared with every phone). Refused before the node is asked;
 * no node runs in this test.
 */
public class NodeGatewayBurnTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static int post(int zPort, String zToken, String zCommand, StringBuilder zBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + zPort + "/cmd").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + zToken);
        try (OutputStream o = c.getOutputStream()) {
            o.write(("{\"command\":\"" + zCommand + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in != null) zBody.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return code;
    }

    @Test public void txnpostBurnIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("gateway-burn");
        NodeGateway gw = NodeGateway.create(dir, freePort());
        gw.start();
        try {
            for (String cmd : new String[] {"txnpost id:x burn:1", "txnpost id:x burn :1", "txnpost id:x mine:true burn:0.5"}) {
                StringBuilder body = new StringBuilder();
                assertEquals(cmd, 403, post(gw.port(), gw.token(), cmd, body));
                assertTrue(body.toString(), body.toString().contains("burn"));
            }
        } finally {
            gw.stop();
        }
    }

    /** Round-5 review: no file: of any command through the gateway (it names a file on the node's machine) */
    @Test public void fileParamIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("gateway-file");
        NodeGateway gw = NodeGateway.create(dir, freePort());
        gw.start();
        try {
            for (String cmd : new String[] {"txnimport id:x file:pay.txn", "txnimport id:x file :pay.txn",
                    "coinexport coinid:0x01 file:x.txn", "status ; txnimport id:y file:../../wallet.txn"}) {
                StringBuilder body = new StringBuilder();
                assertEquals(cmd, 403, post(gw.port(), gw.token(), cmd, body));
                assertTrue(body.toString(), body.toString().contains("file:"));
            }
        } finally {
            gw.stop();
        }
    }
}
