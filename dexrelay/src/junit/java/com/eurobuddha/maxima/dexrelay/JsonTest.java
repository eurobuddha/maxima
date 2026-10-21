package com.eurobuddha.maxima.dexrelay;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Canonical form must equal the JS reference (spikes/ed25519/sign_envelope.js): sorted keys, no whitespace, sig excluded. */
public class JsonTest {

    /** The exact canonical string the P0-c spike signed (vector.json). */
    static final String VECTOR_CANONICAL =
            "{\"address\":\"0x0CDCD61692F186EB0BBCFA289F438043F586FF7B3F6864193358E29166E8454A\",\"amount\":\"100.5\","
          + "\"coins\":[\"0xF19FE115E66277A5785D69CA53BDA431B0D7CDB5B7DB1C4AE8C3CA01558C045F\"],\"expiry\":1800000000,"
          + "\"market\":\"MINIMA/MxUSD\",\"orderId\":\"0x0102030405060708\",\"price\":\"0.01234567\",\"pubId\":\"0xa0f29dae2697c9a8b619aea90c27868266a6d2a81b4c4e405bbc043daaf49462782e728cb923b3ce148059e2d063fa37756130c78df1478c817b6713f1a6c850\",\"seq\":1,"
          + "\"side\":\"sell\",\"signPk\":\"0x782e728cb923b3ce148059e2d063fa37756130c78df1478c817b6713f1a6c850\","
          + "\"t\":\"order\",\"v\":1}";

    static final String VECTOR_WITH_SIG = VECTOR_CANONICAL.substring(0, VECTOR_CANONICAL.length() - 1)
            + ",\"sig\":\"0xa5ee8230578b0e5c2f6c9c0fdf52b9f0e8f2141278fad1a67d31145e0a14688fa98109f885838ccfe9d7bb4e2885b1364a74863666fd478d11a074cc3a744c0d\"}";

    @Test public void parsesAndCanonicalisesTheVector() {
        Map<String, Object> m = Json.parseObject(VECTOR_WITH_SIG);
        assertEquals("order", m.get("t"));
        assertEquals(1L, m.get("v"));
        assertEquals(1800000000L, m.get("expiry"));
        assertTrue(m.get("coins") instanceof List);
        assertEquals(VECTOR_CANONICAL, Json.canonicalWithout(m, "sig"));
    }

    @Test public void unorderedInputCanonicalisesIdentically() {
        String shuffled = "{ \"v\" : 1 , \"t\":\"order\", \"sig\":\"0xAA\", \"side\":\"sell\",\"seq\":1,\"price\":\"0.01234567\","
                + "\"orderId\":\"0x0102030405060708\",\"market\":\"MINIMA/MxUSD\",\"expiry\":1800000000,\"pubId\":\"0xa0f29dae2697c9a8b619aea90c27868266a6d2a81b4c4e405bbc043daaf49462782e728cb923b3ce148059e2d063fa37756130c78df1478c817b6713f1a6c850\","
                + "\"coins\":[\"0xF19FE115E66277A5785D69CA53BDA431B0D7CDB5B7DB1C4AE8C3CA01558C045F\"],\"amount\":\"100.5\","
                + "\"address\":\"0x0CDCD61692F186EB0BBCFA289F438043F586FF7B3F6864193358E29166E8454A\","
                + "\"signPk\":\"0x782e728cb923b3ce148059e2d063fa37756130c78df1478c817b6713f1a6c850\"}";
        assertEquals(VECTOR_CANONICAL, Json.canonicalWithout(Json.parseObject(shuffled), "sig"));
    }

    @Test public void rejectsFloatsNestedObjectsAndUnsafeStrings() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":{\"b\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":\"quote\\\"inside\"}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":\"back\\\\slash\"}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":\"\\u00e9\"}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":1,\"a\":2}"));
    }

    @Test public void writesPrimitivesLikeJsonStringify() {
        Map<String, Object> m = Json.parseObject("{\"z\":true,\"y\":null,\"x\":[\"a\",2,false],\"w\":-7,\"n\":9007199254740991}");
        assertEquals("{\"n\":9007199254740991,\"w\":-7,\"x\":[\"a\",2,false],\"y\":null,\"z\":true}", Json.canonicalWithout(m, "sig"));
    }
}
