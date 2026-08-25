package com.eurobuddha.maxima.core.directory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniNumber;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.msg.MLSPacketSET;
import com.eurobuddha.maxima.core.msg.MaximaMessage;

import org.junit.Test;

import java.util.Collections;

/**
 * Phase-B B1: a directory entry retains the publisher's signed proof so ANY relay can
 * verify it after it is forwarded, and a forwarding relay can never forge or redirect an
 * answer — only withhold it. These tests exercise the proof recipe end to end.
 */
public class MlsProofTest {

    /** A deterministic identity from raw seed bytes (avoids BIP39 phrase validation). */
    private static MaximaIdentity idFrom(byte b) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, b);
        return MaximaIdentity.fromSeed(new MiniData(seed));
    }

    /** Build the verified envelope triplet exactly as MaximaSender.build / handleForUs do:
     *  from = publisher DER, payload = signed MaximaMessage bytes, sig = signature. */
    private static byte[][] signedSet(MaximaIdentity id, String maximaAddress) {
        MLSPacketSET set = new MLSPacketSET(maximaAddress);
        set.addValidPublicKey(id.publicKeyHex());

        MaximaMessage mm = new MaximaMessage();
        mm.mRandom = new MiniData(MaximaCrypto.randomBytes(32));
        mm.mFrom = new MiniData(id.publicKey());
        mm.mTo = new MiniData(id.publicKey());
        mm.mTimeMilli = new MiniNumber(1_700_000_000_000L);
        mm.mApplication = new MiniString(MlsService.APP_SET);
        mm.mData = new MiniData(Codec.serialise(set));

        byte[] payload = Codec.serialise(mm);
        byte[] sig = MaximaCrypto.sign(id.keyPair().getPrivate(), payload);
        return new byte[][]{id.publicKey(), payload, sig};
    }

    @Test
    public void verifiedAddress_roundTrips() {
        MaximaIdentity id = idFrom((byte) 0x11);
        String addr = "MxG08000000000000000000000000000000000000000000000@1.2.3.4:9501";
        byte[][] p = signedSet(id, addr);

        String got = MlsService.verifiedAddress(id.publicKeyHex(), p[0], p[1], p[2]);
        assertEquals("a valid proof resolves to the published address", addr, got);
    }

    @Test
    public void verifiedAddress_rejectsTamperedPayload() {
        MaximaIdentity id = idFrom((byte) 0x11);
        byte[][] p = signedSet(id, "MxG08000000000000000000000000000000000000000000000@1.2.3.4:9501");
        p[1] = p[1].clone();
        p[1][p[1].length - 1] ^= 0x01;   // flip a byte of the signed payload
        assertNull("a tampered payload must not verify",
                MlsService.verifiedAddress(id.publicKeyHex(), p[0], p[1], p[2]));
    }

    @Test
    public void verifiedAddress_rejectsWrongExpectedKey() {
        MaximaIdentity id = idFrom((byte) 0x11);
        MaximaIdentity other = idFrom((byte) 0x22);
        byte[][] p = signedSet(id, "MxG08000000000000000000000000000000000000000000000@1.2.3.4:9501");
        assertNull("a proof signed by A must not resolve when we asked for B's key",
                MlsService.verifiedAddress(other.publicKeyHex(), p[0], p[1], p[2]));
    }

    @Test
    public void verifiedAddress_rejectsMissingProof() {
        assertNull(MlsService.verifiedAddress("0xABCD", null, null, null));
    }

    @Test
    public void store_retainsProof_andExpiresPerEntry() {
        MaximaIdentity id = idFrom((byte) 0x11);
        String addr = "MxG08000000000000000000000000000000000000000000000@1.2.3.4:9501";
        byte[][] p = signedSet(id, addr);

        MlsStore store = new MlsStore();
        store.setOpenResolve(true);
        store.put(id.publicKeyHex(), Collections.singletonList(addr),
                Collections.singletonList(id.publicKeyHex()),
                p[0], p[1], p[2], MlsStore.DEFAULT_TTL_MS);

        MlsStore.Entry e = store.peek(id.publicKeyHex());
        assertTrue("stored entry carries a proof", e.hasProof());
        assertEquals(addr, MlsService.verifiedAddress(id.publicKeyHex(),
                e.proofFrom, e.proofPayload, e.proofSig));

        // A short-TTL (forwarded/cached) entry expires on its OWN clock, independent of
        // the store's default 24h — the mechanism the mesh uses to cache briefly.
        MlsStore shortStore = new MlsStore();
        shortStore.setOpenResolve(true);
        shortStore.put(id.publicKeyHex(), Collections.singletonList(addr),
                Collections.singletonList(id.publicKeyHex()), p[0], p[1], p[2], 20L);
        assertEquals("present before its short TTL elapses", addr,
                shortStore.get(id.publicKeyHex(), "0xANYONE").primary());
        try {
            Thread.sleep(40);
        } catch (InterruptedException ignored) {
        }
        assertNull("gone once its short TTL elapses",
                shortStore.get(id.publicKeyHex(), "0xANYONE"));

        // An unsigned local publish is usable but carries no forwardable proof.
        MlsStore plain = new MlsStore();
        plain.setOpenResolve(true);
        plain.put(id.publicKeyHex(), Collections.singletonList(addr),
                Collections.singletonList(id.publicKeyHex()));
        assertFalse("an unsigned publish has no proof", plain.peek(id.publicKeyHex()).hasProof());
    }
}
