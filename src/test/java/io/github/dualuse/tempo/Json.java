package io.github.dualuse.tempo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the test vectors: objects become {@link LinkedHashMap}s, arrays {@link List}s,
 * integers that fit {@link Long}s and other numbers {@link Double}s. {@link #same} compares two values the way
 * FORMAT.md §8 asks: structurally, key order ignored, numbers by value.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.space();
        if (j.i != text.length()) throw j.error("trailing text");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        return (Map<String, Object>) parse(text);
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Long && b instanceof Long) return a.equals(b);
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a, y = (Map<?, ?>) b;
            if (!x.keySet().equals(y.keySet())) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) if (!same(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int k = 0; k < x.size(); k++) if (!same(x.get(k), y.get(k))) return false;
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }

    /** Compact JSON text, for failure messages; one span per line. */
    static String show(Object v) {
        StringBuilder b = new StringBuilder();
        show(b, v);
        return b.toString();
    }

    private static void show(StringBuilder b, Object v) {
        if (v instanceof Map) {
            b.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                b.append('"').append(e.getKey()).append("\":");
                if ("spans".equals(e.getKey())) b.append('\n');
                show(b, e.getValue());
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            for (int k = 0; k < ((List<?>) v).size(); k++) {
                if (k > 0) b.append(((List<?>) v).get(k) instanceof Map ? ",\n" : ",");
                show(b, ((List<?>) v).get(k));
            }
            b.append(']');
        } else if (v instanceof String) {
            b.append('"').append(v).append('"');
        } else {
            b.append(v);
        }
    }

    private Object value() {
        space();
        if (i == s.length()) throw error("end of input");
        char c = s.charAt(i);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            space();
            if (peek('}')) return m;
            do {
                space();
                String k = string();
                space();
                expect(':');
                m.put(k, value());
                space();
            } while (peek(','));
            expect('}');
            return m;
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            i++;
            space();
            if (peek(']')) return l;
            do {
                l.add(value());
                space();
            } while (peek(','));
            expect(']');
            return l;
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) return word("true", Boolean.TRUE);
        if (s.startsWith("false", i)) return word("false", Boolean.FALSE);
        if (s.startsWith("null", i)) return word("null", null);
        int from = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(from, i);
        if (n.isEmpty()) throw error("unexpected " + c);
        if (n.matches("-?\\d+")) {
            try {
                return Long.parseLong(n);
            } catch (NumberFormatException past) {
                return Double.parseDouble(n);
            }
        }
        return Double.parseDouble(n);
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    private Object word(String w, Object v) {
        i += w.length();
        return v;
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected " + c);
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + i);
    }
}
