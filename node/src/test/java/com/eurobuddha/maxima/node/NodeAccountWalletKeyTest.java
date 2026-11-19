package com.eurobuddha.maxima.node;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/** The account wallet's key numbers come from the node's own {@code keys} rows, in both key modes. */
public class NodeAccountWalletKeyTest {

    private static JSONObject row(String zPub, int zUses, int zMax) {
        JSONObject k = new JSONObject();
        k.put("publickey", zPub);
        k.put("uses", zUses);
        k.put("maxuses", zMax);
        return k;
    }

    @Test public void followsTheNodesKeyShape() {
        JSONArray classic = new JSONArray();
        classic.add(row("0xAA", 3, 262144));
        classic.add(row("0xBB", 7, 262144));
        assertArrayEquals(new int[]{7, 262144}, NodeAccountWallet.usesAndMax(classic, "RETURN SIGNEDBY(0xBB)"));

        JSONArray block = new JSONArray();
        block.add(row("0xAA", 0, 268435456));
        block.add(row("0xBB", 2417300, 268435456));   // block mode: uses is a block number, far past 262144
        assertArrayEquals(new int[]{2417300, 268435456}, NodeAccountWallet.usesAndMax(block, "RETURN SIGNEDBY(0xBB)"));
        // our key not listed: the capacity is still the wallet's, never the classic constant
        assertArrayEquals(new int[]{0, 268435456}, NodeAccountWallet.usesAndMax(block, "RETURN SIGNEDBY(0xCC)"));
        // a node that reports nothing: 0 = "unknown" (maxUses() then answers the classic default)
        assertArrayEquals(new int[]{0, 0}, NodeAccountWallet.usesAndMax(null, "RETURN SIGNEDBY(0xCC)"));
    }
}
