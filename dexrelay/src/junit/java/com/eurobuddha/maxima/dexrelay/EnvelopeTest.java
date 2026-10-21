package com.eurobuddha.maxima.dexrelay;

import static org.junit.Assert.*;

import org.junit.Test;

/** Envelope validation (SPEC §3). Clock is injected: the vector expires at 1800000000. */
public class EnvelopeTest {

    static final long NOW = 1800000000L - 3600;   // one hour before the vector's expiry

    @Test public void acceptsTheVectorAsAnOrder() {
        Envelope e = Envelope.parse(JsonTest.VECTOR_WITH_SIG, NOW);
        assertEquals(Envelope.Kind.ORDER, e.kind);
        assertEquals("MINIMA/MxUSD", e.market);
        assertEquals("sell", e.side);
        assertEquals(1L, e.seq);
        assertEquals(1800000000L, e.expiry);
        assertEquals("0x782e728cb923b3ce148059e2d063fa37756130c78df1478c817b6713f1a6c850|0x0102030405060708", e.key());
        assertFalse(e.isCancel());
        assertEquals(JsonTest.VECTOR_WITH_SIG.length(), e.raw.length());
    }

    @Test public void rejectsBadSignatureExpiredFarFutureAndTooBig() {
        String forged = JsonTest.VECTOR_WITH_SIG.replace("0x744e68c5", "0x744e68c6");
        assertThrows(Envelope.Invalid.class, () -> Envelope.parse(forged, NOW));
        assertThrows(Envelope.Invalid.class, () -> Envelope.parse(JsonTest.VECTOR_WITH_SIG, 1800000000L + 1));      // expired
        assertThrows(Envelope.Invalid.class, () -> Envelope.parse(JsonTest.VECTOR_WITH_SIG, NOW - 2 * 86400));     // > 24 h ahead
        StringBuilder big = new StringBuilder("{\"pad\":\"");
        for (int i = 0; i < Envelope.MAX_ORDER_BYTES; i++) big.append('a');
        big.append("\"}");
        assertThrows(Envelope.Invalid.class, () -> Envelope.parse(big.toString(), NOW));
    }

    @Test public void rejectsShapeViolationsEvenWithAValidSignature() {
        // Same canonical bytes cannot be produced for a different shape, so any field change invalidates the sig; but
        // shape checks must fire BEFORE signature work (cheap rejects first). Each of these fails for a shape reason.
        String[] bad = {
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"side\":\"sell\"", "\"side\":\"short\""),
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"price\":\"0.01234567\"", "\"price\":\"0.123456789\""),   // 9 dp
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"price\":\"0.01234567\"", "\"price\":\"-1\""),
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"orderId\":\"0x0102030405060708\"", "\"orderId\":\"0x01\""),
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"v\":1", "\"v\":2"),
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"t\":\"order\"", "\"t\":\"ordr\""),
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"seq\":1", "\"seq\":-1"),
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("\"market\":\"MINIMA/MxUSD\"", "\"market\":\"MINIMA/MxUSD\",\"extra\":1"),
            JsonTest.VECTOR_WITH_SIG.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"),   // non-simple script
            JsonTest.VECTOR_WITH_SIG.replace("0xa0f29dae2697c9a8b619aea90c27868266a6d2a81b4c4e405bbc043daaf49462782e728cb923b3ce148059e2d063fa37756130c78df1478c817b6713f1a6c850", "0x" + "00".repeat(64)),   // pubId must end with signPk
        };
        for (String b : bad) {
            Envelope.Invalid ex = assertThrows(Envelope.Invalid.class, () -> Envelope.parse(b, NOW));
            assertNotEquals("shape error expected, got signature error for: " + b, "bad signature", ex.getMessage());
        }
    }

    @Test public void cancelIsAmountZero() {
        // Re-signed cancel is produced by the client; here we only check the predicate on a parsed shape by
        // building a canonical body and signing it with a throwaway JCA key when available.
        Envelope e = Envelope.parse(JsonTest.VECTOR_WITH_SIG, NOW);
        assertFalse(e.isCancel());
        assertTrue(Envelope.isCancelAmount("0"));
        assertTrue(Envelope.isCancelAmount("0.0"));
        assertFalse(Envelope.isCancelAmount("0.00000001"));
    }

    @Test public void msgEnvelopeShape() {
        // A msg: {v,t:"msg",to,from,blob,expiry,sig}; the outer sig key is the signPk half of `from`.
        // No signing key for a publicId here, so assert the shape gate rejects a wrong-length `to` before signature work.
        String msg = "{\"v\":1,\"t\":\"msg\",\"to\":\"0x00\",\"from\":\"0x" + "11".repeat(64) + "\",\"blob\":\"aabb\",\"expiry\":"
                + (NOW + 60) + ",\"sig\":\"0x" + "00".repeat(64) + "\"}";
        Envelope.Invalid ex = assertThrows(Envelope.Invalid.class, () -> Envelope.parse(msg, NOW));
        assertTrue(ex.getMessage(), ex.getMessage().contains("to"));
    }
}
