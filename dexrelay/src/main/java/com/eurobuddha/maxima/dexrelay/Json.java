package com.eurobuddha.maxima.dexrelay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The envelope subset of JSON (SPEC §3) — deliberately small so Java and the JS reference canonicalise
 * byte-identically without a JSON library on either side:
 * one top-level object; values are safe-ASCII strings (0x20..0x7E, no quote, no backslash), integers within the
 * JS safe range, booleans, null, or flat arrays of those. No floats, no nested objects, no escapes, no duplicates.
 * Canonical form = keys sorted (String natural order == JS default sort for ASCII), no whitespace.
 */
final class Json {
    static final long MAX_SAFE = 9007199254740991L;

    private Json() {}

    static Map<String, Object> parseObject(String s) {
        Parser p = new Parser(s);
        p.ws();
        Map<String, Object> m = p.object();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing content");
        return m;
    }

    /** Sorted-key, whitespace-free serialisation; {@code without} (may be null) is omitted. */
    static String canonicalWithout(Map<String, Object> m, String without) {
        TreeMap<String, Object> sorted = new TreeMap<>(m);
        if (without != null) sorted.remove(without);
        StringBuilder b = new StringBuilder(256);
        b.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) b.append(',');
            first = false;
            string(b, e.getKey()); b.append(':'); value(b, e.getValue());
        }
        return b.append('}').toString();
    }

    private static void value(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof String) string(b, (String) v);
        else if (v instanceof Long || v instanceof Integer) b.append(((Number) v).longValue());
        else if (v instanceof Boolean) b.append(((Boolean) v) ? "true" : "false");
        else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                if (o instanceof List || o instanceof Map) throw new IllegalArgumentException("nested container");
                value(b, o);
            }
            b.append(']');
        } else throw new IllegalArgumentException("unsupported value " + v.getClass().getSimpleName());
    }

    private static void string(StringBuilder b, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '"' || c == '\\') throw new IllegalArgumentException("unsafe string char");
        }
        b.append('"').append(s).append('"');
    }

    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++; }
        char peek() { if (i >= s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }
        void expect(char c) { if (peek() != c) throw new IllegalArgumentException("expected '" + c + "' at " + i); i++; }

        Map<String, Object> object() {
            expect('{'); ws();
            Map<String, Object> m = new LinkedHashMap<>();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws(); String k = str(); ws(); expect(':'); ws();
                Object v = value(false);
                if (m.containsKey(k)) throw new IllegalArgumentException("duplicate key " + k);
                m.put(k, v); ws();
                char c = peek(); i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at " + (i - 1));
            }
        }

        Object value(boolean inArray) {
            char c = peek();
            if (c == '"') return str();
            if (c == '[') { if (inArray) throw new IllegalArgumentException("nested array"); return array(); }
            if (c == '{') throw new IllegalArgumentException("nested object");
            if (c == 't') { lit("true"); return Boolean.TRUE; }
            if (c == 'f') { lit("false"); return Boolean.FALSE; }
            if (c == 'n') { lit("null"); return null; }
            return number();
        }

        List<Object> array() {
            expect('['); ws();
            List<Object> l = new ArrayList<>();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws(); l.add(value(true)); ws();
                char c = peek(); i++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at " + (i - 1));
            }
        }

        void lit(String w) { if (!s.startsWith(w, i)) throw new IllegalArgumentException("bad literal at " + i); i += w.length(); }

        String str() {
            expect('"');
            int start = i;
            while (true) {
                char c = peek();
                if (c == '"') break;
                if (c < 0x20 || c > 0x7E || c == '\\') throw new IllegalArgumentException("unsafe string char at " + i);
                i++;
            }
            String out = s.substring(start, i); i++;
            return out;
        }

        Long number() {
            int start = i;
            if (peek() == '-') i++;
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw new IllegalArgumentException("bad number at " + start);
            if (s.charAt(i) == '0' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) throw new IllegalArgumentException("leading zero");
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) throw new IllegalArgumentException("non-integer number");
            String n = s.substring(start, i);
            if ("-0".equals(n)) throw new IllegalArgumentException("negative zero");
            long v;
            try { v = Long.parseLong(n); } catch (NumberFormatException e) { throw new IllegalArgumentException("number out of range"); }
            if (v > MAX_SAFE || v < -MAX_SAFE) throw new IllegalArgumentException("number beyond JS safe range");
            return v;
        }
    }
}
