package io.mosip.registration.liveness.analyzer.integrity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, minimal JSON reader (RFC 8259) for the signed model manifest and the test fixtures. No third-party
 * JSON library, so it cannot clash with the versions the Registration Client already ships.
 *
 * <p>Values map to {@link LinkedHashMap} (objects, key order kept), {@link ArrayList} (arrays), {@link String},
 * {@link Double}, {@link Boolean} and {@code null}. Rejected: trailing content, duplicate keys, NaN/Infinity
 * (including numbers that overflow a double), leading zeros, unescaped control characters, invalid escapes and
 * nesting deeper than {@link #MAX_DEPTH}.
 */
public final class MiniJson {

    /** Maximum nesting depth of objects and arrays. */
    public static final int MAX_DEPTH = 32;

    /** Malformed JSON, or a value of the wrong type in a typed getter. */
    public static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private final String s;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
    }

    /** Parses one JSON value that must span the whole input (surrounding whitespace allowed). */
    public static Object parse(String json) throws JsonException {
        if (json == null) throw new JsonException("null input");
        MiniJson p = new MiniJson(json);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (p.pos != json.length()) throw p.error("trailing content");
        return value;
    }

    /** Parses input whose top-level value must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) throws JsonException {
        Object value = parse(json);
        if (!(value instanceof Map)) throw new JsonException("top-level value is not an object");
        return (Map<String, Object>) value;
    }

    // ---- typed getters ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObject(Map<String, Object> obj, String key) throws JsonException {
        Object v = require(obj, key);
        if (!(v instanceof Map)) throw new JsonException("\"" + key + "\" is not an object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getArray(Map<String, Object> obj, String key) throws JsonException {
        Object v = require(obj, key);
        if (!(v instanceof List)) throw new JsonException("\"" + key + "\" is not an array");
        return (List<Object>) v;
    }

    public static String getString(Map<String, Object> obj, String key) throws JsonException {
        Object v = require(obj, key);
        if (!(v instanceof String)) throw new JsonException("\"" + key + "\" is not a string");
        return (String) v;
    }

    public static double getDouble(Map<String, Object> obj, String key) throws JsonException {
        Object v = require(obj, key);
        if (!(v instanceof Double)) throw new JsonException("\"" + key + "\" is not a number");
        return (Double) v;
    }

    /** A number that must be an exact integer within the int range. */
    public static int getInt(Map<String, Object> obj, String key) throws JsonException {
        return toInt(getDouble(obj, key), key);
    }

    /** A number that must be an exact integer within the long range that a double represents exactly. */
    public static long getLong(Map<String, Object> obj, String key) throws JsonException {
        double d = getDouble(obj, key);
        if (d != Math.rint(d) || Math.abs(d) > 9007199254740992.0) {
            throw new JsonException("\"" + key + "\" is not an exact integer");
        }
        return (long) d;
    }

    public static boolean getBoolean(Map<String, Object> obj, String key) throws JsonException {
        Object v = require(obj, key);
        if (!(v instanceof Boolean)) throw new JsonException("\"" + key + "\" is not a boolean");
        return (Boolean) v;
    }

    /** True when the key is present with a JSON {@code null}. */
    public static boolean isNull(Map<String, Object> obj, String key) throws JsonException {
        return require(obj, key) == null;
    }

    /** An array of numbers as doubles. */
    public static double[] toDoubles(List<Object> array) throws JsonException {
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = array.get(i);
            if (!(v instanceof Double)) throw new JsonException("array element " + i + " is not a number");
            out[i] = (Double) v;
        }
        return out;
    }

    public static int toInt(double d, String what) throws JsonException {
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new JsonException("\"" + what + "\" is not an exact int");
        }
        return (int) d;
    }

    private static Object require(Map<String, Object> obj, String key) throws JsonException {
        if (obj == null || !obj.containsKey(key)) throw new JsonException("missing key \"" + key + "\"");
        return obj.get(key);
    }

    // ---- parser ----

    private Object readValue(int depth) throws JsonException {
        if (pos >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(pos);
        switch (c) {
            case '{': return readObject(depth + 1);
            case '[': return readArray(depth + 1);
            case '"': return readString();
            case 't': expectWord("true"); return Boolean.TRUE;
            case 'f': expectWord("false"); return Boolean.FALSE;
            case 'n': expectWord("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw error("nesting deeper than " + MAX_DEPTH);
        pos++; // '{'
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw error("expected a string key");
            String key = readString();
            if (out.containsKey(key)) throw error("duplicate key \"" + key + "\"");
            skipWhitespace();
            expect(':');
            skipWhitespace();
            out.put(key, readValue(depth));
            skipWhitespace();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> readArray(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw error("nesting deeper than " + MAX_DEPTH);
        pos++; // '['
        List<Object> out = new ArrayList<Object>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(readValue(depth));
            skipWhitespace();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String readString() throws JsonException {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw error("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("unescaped control character in string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) throw error("unterminated escape");
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u': sb.append(readHex4()); break;
                default: throw error("invalid escape '\\" + e + "'");
            }
        }
    }

    private char readHex4() throws JsonException {
        if (pos + 4 > s.length()) throw error("truncated \\u escape");
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = Character.digit(s.charAt(pos + i), 16);
            if (d < 0) throw error("invalid \\u escape");
            v = (v << 4) | d;
        }
        pos += 4;
        return (char) v;
    }

    private Double readNumber() throws JsonException {
        int start = pos;
        if (peek() == '-') pos++;
        if (peek() == '0') {
            pos++;
            if (isDigit(peek())) throw error("leading zero in number");
        } else if (isDigit(peek())) {
            while (isDigit(peek())) pos++;
        } else {
            throw error("invalid number");
        }
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) throw error("digit expected after '.'");
            while (isDigit(peek())) pos++;
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') pos++;
            if (!isDigit(peek())) throw error("digit expected in exponent");
            while (isDigit(peek())) pos++;
        }
        double d = Double.parseDouble(s.substring(start, pos));
        if (Double.isInfinite(d) || Double.isNaN(d)) throw error("number out of range");
        return d;
    }

    private void expectWord(String word) throws JsonException {
        if (!s.startsWith(word, pos)) throw error("invalid literal");
        pos += word.length();
    }

    private void expect(char c) throws JsonException {
        if (next() != c) throw error("expected '" + c + "'");
    }

    private char next() throws JsonException {
        if (pos >= s.length()) throw error("unexpected end of input");
        return s.charAt(pos++);
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    private JsonException error(String message) {
        return new JsonException(message + " at offset " + pos);
    }
}
