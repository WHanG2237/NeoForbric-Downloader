package com.deepseek.neoforbric.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public final class JsonLite {
    private final String src;
    private int pos;

    private JsonLite(String src) {
        this.src = src;
    }


    public static Object parse(String text) {
        JsonLite parser = new JsonLite(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        return value;
    }


    public static Object parseQuietly(String text, String what) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            ForbricLog.warn("JSON parse failed (%s): %s", what, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }


    public static String str(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value instanceof String s ? s : fallback;
    }

    public static List<String> strList(Map<String, Object> object, String key) {
        List<String> result = new ArrayList<>();
        for (Object item : asArray(object.get(key))) {
            if (item instanceof String s) {
                result.add(s);
            }
        }
        return result;
    }

    private Object readValue() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return result;
            } else {
                throw new IllegalArgumentException("expected ',' or '}' in object at " + pos);
            }
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return result;
            } else {
                throw new IllegalArgumentException("expected ',' or ']' in array at " + pos);
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("unknown escape \\" + esc);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String raw = src.substring(start, pos);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("not a valid JSON value at " + start);
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number: " + raw);
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("expected " + literal + " at " + pos);
        }
        pos += literal.length();
        return value;
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' at " + pos);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
