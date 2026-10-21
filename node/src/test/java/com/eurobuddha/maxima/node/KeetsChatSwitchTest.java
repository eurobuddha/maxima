package com.eurobuddha.maxima.node;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** -Dparlons.node.keetschat: default on; "off" hands the keets inbox to the host app. */
public class KeetsChatSwitchTest {

    @After
    public void clear() {
        System.clearProperty("parlons.node.keetschat");
    }

    @Test
    public void defaultIsOn() {
        System.clearProperty("parlons.node.keetschat");
        assertTrue(ParlonsNodeMain.keetsChatBridgeEnabled());
        System.setProperty("parlons.node.keetschat", "on");
        assertTrue(ParlonsNodeMain.keetsChatBridgeEnabled());
        System.setProperty("parlons.node.keetschat", "weird");
        assertTrue(ParlonsNodeMain.keetsChatBridgeEnabled());
    }

    @Test
    public void offVariantsDisable() {
        for (String v : new String[]{"off", "OFF", " false ", "0", "no"}) {
            System.setProperty("parlons.node.keetschat", v);
            assertFalse(v, ParlonsNodeMain.keetsChatBridgeEnabled());
        }
    }
}
