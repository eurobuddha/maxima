package com.eurobuddha.maxima.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

/** The flat-JSON codec every ChatMessage rides through. The injection and
 *  malformed-rejection tests are the load-bearing security assertions. */
public class JsonTest {

    @Test
    public void roundTripWithControlChars() {
        String encoded = new Json.Writer()
                .put("a", "one")
                .put("b", "line1\nline2\ttab\"quote\\slash")
                .done();
        Map<String, String> m = Json.parse(encoded);
        assertEquals("one", m.get("a"));
        assertEquals("line1\nline2\ttab\"quote\\slash", m.get("b"));
    }

    @Test
    public void injectionCannotForgeSiblingFields() {
        // A value containing what looks like JSON structure must round-trip as
        // ONE value, never create a new key. This is the parser's whole job.
        String evil = "x\",\"admin\":\"true";
        String encoded = new Json.Writer().put("name", evil).done();
        Map<String, String> m = Json.parse(encoded);
        assertEquals(evil, m.get("name"));
        if (m.containsKey("admin")) {
            fail("injection forged a sibling 'admin' field: " + encoded);
        }
    }

    @Test
    public void duplicateKeyLastWins() {
        Map<String, String> m = Json.parse("{\"a\":\"1\",\"a\":\"2\"}");
        assertEquals("2", m.get("a"));
    }

    @Test
    public void malformedInputThrows() {
        String[] bad = {
                "not an object",
                "{\"a\":{\"nested\":\"1\"}}",
                "{\"a\":[\"1\"]}",
                "{\"a\" \"1\"}",          // missing colon
                "{\"a\":\"unterminated",
        };
        for (String b : bad) {
            try {
                Json.parse(b);
                fail("expected rejection for: " + b);
            } catch (RuntimeException expected) {
                // good
            }
        }
    }
}
