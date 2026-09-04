package com.eurobuddha.maxima.node;

import org.minima.system.commands.CommandRunner;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * The Parlons Node wallet — the merged binary's wallet, served by the EMBEDDED Minima node via the
 * in-process command API ({@link CommandRunner}), never by the grafted {@code com.eurobuddha.wallet}
 * stack and never by a hosted gateway. The node owns the seed, the Winternitz key-use counter, and
 * the chain, so it builds + SIGNS + broadcasts every send itself — one seed, one signer, no reuse,
 * no {@code sendable=0} gateway friction.
 *
 * <p>This is the M2 replacement for {@code CloudWallet}/{@code CloudPaymentSender}/{@code CloudKeyUses}/
 * {@code WatchWallet}: those exist because the phone/cloud front-end had no local chain and rented
 * one from {@code privateprivate.org}. The Parlons Node IS the chain, so the account address is the
 * node's own wallet address and a send is a plain in-process {@code send} command.
 *
 * <p>Zero vendored org.minima: every type here (JSONObject/JSONArray) is the NODE's own class from
 * the bundled node jar — the single copy on this classpath.
 */
public final class NodeWallet {

    /** Minima's native token id. */
    public static final String TOKEN_MINIMA = "0x00";

    private NodeWallet() {}

    // ── read ────────────────────────────────────────────────────────────────────────────────────

    /**
     * A default receive address for this account. The node hands out a fresh unused address; for a
     * stable "account address" we take {@code getaddress} (the node's default script address).
     *
     * @return the full {@code 0x…} hex address (never truncated — it is meant to be copied)
     */
    public static String address() throws Exception {
        JSONObject resp = response(run("getaddress"));
        return str(resp, "address");
    }

    /** The user-facing {@code Mx…} form of {@link #address()} (full, copyable). */
    public static String miniAddress() throws Exception {
        JSONObject resp = response(run("getaddress"));
        return str(resp, "miniaddress");
    }

    /** Confirmed spendable Minima balance as a decimal string (e.g. {@code "0"}, {@code "12.5"}). */
    public static Balance balance() throws Exception {
        return balance(TOKEN_MINIMA);
    }

    /** Balance for a specific token id. */
    public static Balance balance(String zTokenId) throws Exception {
        Object r = run("balance");
        // `balance` returns response: [ {tokenid, confirmed, unconfirmed, sendable, ...}, ... ]
        Object respObj = (r instanceof JSONObject) ? ((JSONObject) r).get("response") : null;
        if (respObj instanceof JSONArray) {
            for (Object o : (JSONArray) respObj) {
                if (o instanceof JSONObject) {
                    JSONObject row = (JSONObject) o;
                    if (zTokenId.equals(String.valueOf(row.get("tokenid")))) {
                        return new Balance(
                                String.valueOf(row.get("confirmed")),
                                String.valueOf(row.get("unconfirmed")),
                                String.valueOf(row.get("sendable")));
                    }
                }
            }
        }
        return new Balance("0", "0", "0");
    }

    // ── write (build + sign + broadcast, all in-process, node-signed) ─────────────────────────────

    /**
     * Send Minima to an address. The node builds, SIGNS (advancing its own Winternitz key-use
     * counter with reserve-before-sign) and posts to its mempool — broadcast to peers from OUR node,
     * no gateway. Returns the full txpow id of the posted transaction.
     *
     * @param zToAddress destination — {@code 0x…} or {@code Mx…}
     * @param zAmount    decimal Minima string, e.g. {@code "1.5"}
     * @return the send result: the full txpow id + whether it is already a confirmed transaction
     * @throws WalletException if the node rejects the send (insufficient funds, bad address, …)
     */
    public static SendResult send(String zToAddress, String zAmount) throws Exception {
        // Interpolating into a command string: reject anything that isn't a bare address / decimal so
        // a caller can never smuggle a second ';'-separated command (the node splits on ';' and runs
        // each) — same command-injection class the gateway guards against.
        if (zToAddress == null || !zToAddress.matches("(0x[0-9A-Fa-f]+|Mx[0-9A-Za-z]+)")) {
            throw new WalletException("refusing to send to a malformed address: " + zToAddress);
        }
        if (zAmount == null || !zAmount.matches("[0-9]+(\\.[0-9]+)?")) {
            throw new WalletException("refusing to send a malformed amount: " + zAmount);
        }
        String cmd = "send address:" + zToAddress + " amount:" + zAmount;
        Object r = run(cmd);
        JSONObject top = (r instanceof JSONObject) ? (JSONObject) r : new JSONObject();
        boolean status = Boolean.TRUE.equals(top.get("status"));
        if (!status) {
            throw new WalletException(String.valueOf(top.get("error")));
        }
        Object respObj = top.get("response");
        JSONObject resp = (respObj instanceof JSONObject) ? (JSONObject) respObj : new JSONObject();
        // `send` returns the built TxPoW; the id lives at response.txpowid (or response.txpow.txpowid).
        String txid = str(resp, "txpowid");
        if (txid.isEmpty()) {
            Object txpow = resp.get("txpow");
            if (txpow instanceof JSONObject) txid = str((JSONObject) txpow, "txpowid");
        }
        // istransaction:false is async mining, NOT a failure (build-family rule).
        boolean isTxn = !Boolean.FALSE.equals(resp.get("istransaction"));
        return new SendResult(txid, isTxn);
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────

    private static Object run(String zCommand) {
        return CommandRunner.getRunner().runSingleCommand(zCommand);
    }

    private static JSONObject response(Object zResult) {
        if (zResult instanceof JSONObject) {
            Object resp = ((JSONObject) zResult).get("response");
            if (resp instanceof JSONObject) return (JSONObject) resp;
        }
        return new JSONObject();
    }

    private static String str(JSONObject zObj, String zKey) {
        Object v = zObj.get(zKey);
        return v == null ? "" : String.valueOf(v);
    }

    /** A token balance triple, all decimal strings. */
    public static final class Balance {
        public final String confirmed;
        public final String unconfirmed;
        public final String sendable;
        Balance(String c, String u, String s) { confirmed = c; unconfirmed = u; sendable = s; }
        @Override public String toString() {
            return "confirmed=" + confirmed + " sendable=" + sendable + " unconfirmed=" + unconfirmed;
        }
    }

    /** Result of a node-signed send. {@code txid} is the FULL txpow id (copyable, never truncated). */
    public static final class SendResult {
        public final String txid;
        public final boolean isTransaction;
        SendResult(String txid, boolean isTransaction) { this.txid = txid; this.isTransaction = isTransaction; }
    }

    /** The node refused the wallet operation (insufficient funds, bad address, locked wallet, …). */
    public static final class WalletException extends Exception {
        public WalletException(String zMessage) { super(zMessage); }
    }
}
