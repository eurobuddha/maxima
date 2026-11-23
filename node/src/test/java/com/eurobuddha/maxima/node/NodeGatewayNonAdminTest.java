package com.eurobuddha.maxima.node;

import static org.junit.Assert.assertEquals;
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
        // "txndelete id:phone1" goes through the gateway's relay-row path (txndeleteOnlyForRelayRows): the node
        // itself refuses txndelete to 0xFF since the 2026-11-23 txn-table gate
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

    /** The file: oracle (as ADMIN, "txnimport file:/etc/hosts" told a token holder whether any path exists) is now
     *  refused by the gateway itself, before the node's own non-admin file rule (NodeGatewayBurnTest has the
     *  spellings). On this line no other allow-listed command differs between 0xFF and ADMIN. */
    @Test public void aTokenHolderCannotNameAFile() throws Exception {
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
            assertTrue(body, c.getResponseCode() == 403 && body.contains("file: is not permitted"));
        } finally {
            gw.stop();
        }
    }

    /** One command through a started gateway: "<http code> <body>" */
    static String post(NodeGateway zGw, String zCommand) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + zGw.port() + "/cmd").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + zGw.token());
        JSONObject body = new JSONObject();
        body.put("command", zCommand);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
            return c.getResponseCode() + " " + new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The node refuses txndelete to 0xFF (the txn table is shared): a phone's failure-path cleanup still removes
     *  ITS relay row - imported through this gateway - and nothing else (2026-11-23). */
    @Test public void txndeleteOnlyForRelayRows() throws Exception {
        org.minima.database.MinimaDB.createDB();
        java.lang.reflect.Field txndb = org.minima.database.MinimaDB.class.getDeclaredField("mTxnDB");
        txndb.setAccessible(true);
        txndb.set(org.minima.database.MinimaDB.getDB(), new org.minima.database.userprefs.txndb.TxnDB());
        org.minima.database.userprefs.txndb.TxnRow row = new org.minima.database.userprefs.txndb.TxnRow(
                "x", new org.minima.objects.Transaction(), new org.minima.objects.Witness());
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        row.writeDataStream(new java.io.DataOutputStream(bo));
        String blob = new org.minima.objects.base.MiniData(bo.toByteArray()).to0xString();
        CommandRunner.getRunner().runCommand("txncreate", "id", "adminrow");

        Path dir = Files.createTempDirectory("gateway-relay");
        NodeGateway gw = NodeGateway.create(dir, freePort());
        gw.start();
        try {
            assertTrue(post(gw, "txnimport id:relayrow data:" + blob).startsWith("200 "));
            assertTrue(post(gw, "txndelete id:adminrow").startsWith("403 "));
            assertTrue(post(gw, "status;txndelete id:relayrow").startsWith("403 "));
            String r = post(gw, "txndelete id:relayrow");
            assertTrue(r, r.startsWith("200 ") && r.contains("\"status\":true"));
            assertEquals(null, org.minima.database.MinimaDB.getDB().getCustomTxnDB().getTransactionRow("relayrow"));
            assertTrue(post(gw, "txndelete id:relayrow").startsWith("403 "));
            assertTrue(org.minima.database.MinimaDB.getDB().getCustomTxnDB().getTransactionRow("adminrow") != null);
        } finally {
            gw.stop();
        }
    }
}
