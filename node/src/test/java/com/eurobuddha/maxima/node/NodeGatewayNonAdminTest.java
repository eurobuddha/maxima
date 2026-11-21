package com.eurobuddha.maxima.node;

import static org.junit.Assert.assertFalse;
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
import org.minima.system.commands.CommandRunner;
import org.minima.utils.json.JSONObject;

/**
 * The gateway runs its allow-listed commands as a NON-ADMIN caller (0xFF). Every command the phone clients
 * send through it (walletshared GatewayNode / WalletPublisher / PaymentSender, the Parlons app's WalletPage,
 * app TxnFactory, the iOS ParlonsKit) must still pass the node's non-admin gate on this (keets) line. No node
 * runs here: the commands fail later, but never on the gate (no ADMIN refusal, no pending).
 */
public class NodeGatewayNonAdminTest {

    static final String ADDR = "0x2868AE4C92C22C5CDAF223574203F2906E8AA92A17050CD99D851EC5DBB61B66";

    /** Exactly what the clients send (walletshared/.../GatewayNode.java, WalletPublisher.java, WalletPage.java,
     *  app/.../TxnFactory.java; iOS: status) */
    static final String[] CLIENT_CALLS = {
        "status",
        "balance megammr:true address:" + ADDR,
        "coins megammr:true address:" + ADDR,
        "newscript trackall:true script:\"RETURN SIGNEDBY(" + ADDR + ")\"",
        "coinexport coinid:" + ADDR,
        "coinimport data:0x00 track:true",
        "txnimport id:phone1 data:0x00",
        "txnbasics id:phone1",
        "txnpost id:phone1 mine:true",
        "txndelete id:phone1",
    };

    @Test public void everyClientCallPassesTheNonAdminGate() {
        for (String call : CLIENT_CALLS) {
            JSONObject res = NodeGateway.firstResult(CommandRunner.getRunner().runMultiCommand("0xFF", call));
            String error = String.valueOf(res.get("error"));
            assertFalse(call + " was refused as non-admin: " + res, error.contains("ADMIN"));
            assertFalse(call + " went pending: " + res, Boolean.TRUE.equals(res.get("pending")));
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @Test public void aTokenHolderGetsTheNonAdminFileRule() throws Exception {
        Path dir = Files.createTempDirectory("gateway-nonadmin");
        NodeGateway gw = NodeGateway.create(dir, freePort());
        gw.start();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + gw.port() + "/cmd").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + gw.token());
            try (OutputStream o = c.getOutputStream()) {
                o.write("{\"command\":\"txnimport id:oracle file:/etc/hosts\"}".getBytes(StandardCharsets.UTF_8));
            }
            String body;
            try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // as ADMIN it read /etc/hosts (an existence oracle for any path); as 0xFF the path is confined
            assertTrue(body, body.contains("only use files in the node's base folder"));
        } finally {
            gw.stop();
        }
    }
}
