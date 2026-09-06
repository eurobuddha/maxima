package com.eurobuddha.maxima.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** A device's APNs wake record lives with its pairing and dies with it. */
public class DevicePairingApnsTest {
    @Test
    public void wakeRecordPersistsWithThePairingAndRevokeDropsIt() throws Exception {
        Path dir = Files.createTempDirectory("parlons-pairing");
        DevicePairing p = new DevicePairing(dir);
        MaximaIdentity dev = MaximaIdentity.fromPhrase(Bip39.generate(24));
        String code = p.newBootstrapCode();
        assertEquals(DevicePairing.Result.AUTHORIZED, p.requestPair(dev.publicKey(), "ios:test", code));
        String key = new com.eurobuddha.maxima.core.codec.MiniData(dev.publicKey()).to0xString();
        assertFalse(p.device(key).canWake());
        assertTrue(p.setApns(key, "abcd1234", "prod", "https://wake.example/v1/wake"));
        assertTrue(p.device(key).canWake());

        DevicePairing reloaded = new DevicePairing(dir);
        DevicePairing.Device d = reloaded.device(key);
        assertEquals("abcd1234", d.apnsToken);
        assertEquals("prod", d.apnsEnv);
        assertEquals("https://wake.example/v1/wake", d.wakeProxy);
        assertTrue(d.canWake());

        assertTrue(reloaded.setApns(key, "abcd1234", "prod", "off"));
        assertFalse("off means never wake", reloaded.device(key).canWake());
        assertTrue(reloaded.setApns(key, "", "", ""));
        assertFalse(reloaded.device(key).canWake());

        assertFalse("an unknown device has no record", reloaded.setApns("0x00", "t", "prod", ""));
        assertTrue(reloaded.revoke(dev.publicKey(), key));
        assertNull(reloaded.device(key));
    }
}
