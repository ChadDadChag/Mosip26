package io.mosip.registration.liveness.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON for trace files (one flat object per line): strings, numbers, booleans, null and arrays of those.
 * No dependency on a JSON library so the tools stay as dependency-free as the engine.
 */
final class Json {
    private Json() { }

    static String write(Map<String, ?> obj) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : obj.entrySet()) {
            if (!first) b.append(',');
            first = false;
            str(b, e.getKey());
            b.append(':');
            value(b, e.getValue());
        }
        return b.append('}').toString();
    }

    private static void value(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            str(b, (String) v);
        } else if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else b.append(String.format(Locale.ROOT, "%.4f", d));
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                value(b, o);
            }
            b.append(']');
        } else {
            str(b, v.toString());
        }
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }

    static Map<String, Object> parse(String line) {
        Parser p = new Parser(line);
        p.ws();
        Object o = p.value();
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object: " + line);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
        return m;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    if (e == 'n') b.append('\n');
                    else if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    else b.append(e);
                } else {
                    b.append(c);
                }
            }
            i++;
            return b.toString();
        }

        Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw error("unexpected character");
            return Double.valueOf(s.substring(start, i));
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw error("expected '" + c + "'");
            i++;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at " + i + ": " + s);
        }
    }
}
