package com.eurobuddha.maxima.core.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal FLAT JSON object reader/writer.
 *
 * The contact-control message is a flat object of string and boolean values,
 * which is the only JSON on the Maxima wire. Rather than add a parser
 * dependency to a module that deliberately has none, this handles exactly that
 * shape - and refuses anything more complex instead of silently mis-parsing it.
 *
 * Not a general JSON library. Do not grow it into one.
 */
public final class Json {

    private Json() {
    }

    public static String escape(String zValue) {
        StringBuilder sb = new StringBuilder(zValue.length() + 8);
        for (int i = 0; i < zValue.length(); i++) {
            char c = zValue.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Ordered writer. Values are emitted as strings unless flagged raw. */
    public static final class Writer {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private void sep() {
            if (!first) {
                sb.append(',');
            }
            first = false;
        }

        public Writer put(String zKey, String zValue) {
            sep();
            sb.append('"').append(escape(zKey)).append("\":\"")
                    .append(escape(zValue == null ? "" : zValue)).append('"');
            return this;
        }

        /** Booleans and numbers - emitted unquoted, matching the reference. */
        public Writer putRaw(String zKey, String zValue) {
            sep();
            sb.append('"').append(escape(zKey)).append("\":").append(zValue);
            return this;
        }

        public Writer put(String zKey, boolean zValue) {
            return putRaw(zKey, Boolean.toString(zValue));
        }

        public String done() {
            return sb.append('}').toString();
        }
    }

    /**
     * Parse a flat object. Nested objects/arrays are rejected rather than
     * guessed at.
     */
    public static Map<String, String> parse(String zJson) {
        Map<String, String> out = new LinkedHashMap<>();
        String s = zJson.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        int i = 1;
        int end = s.length() - 1;
        while (i < end) {
            i = skipWs(s, i);
            if (i >= end) {
                break;
            }
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (s.charAt(i) != '"') {
                throw new IllegalArgumentException("Expected key at " + i);
            }
            StringBuilder key = new StringBuilder();
            i = readString(s, i, key);
            i = skipWs(s, i);
            if (i >= end || s.charAt(i) != ':') {
                throw new IllegalArgumentException("Expected ':' at " + i);
            }
            i = skipWs(s, i + 1);

            char c = s.charAt(i);
            if (c == '"') {
                StringBuilder val = new StringBuilder();
                i = readString(s, i, val);
                out.put(key.toString(), val.toString());
            } else if (c == '{' || c == '[') {
                throw new IllegalArgumentException("Nested JSON not supported for key " + key);
            } else {
                int st = i;
                while (i < end && s.charAt(i) != ',' && !Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                out.put(key.toString(), s.substring(st, i).trim());
            }
        }
        return out;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Reads a quoted string starting at the opening quote; returns the index after the close. */
    private static int readString(String s, int i, StringBuilder out) {
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i);
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                        break;
                    default: out.append(e);
                }
                i++;
            } else if (c == '"') {
                return i + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }
}
