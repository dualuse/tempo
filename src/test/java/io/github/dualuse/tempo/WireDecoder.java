package io.github.dualuse.tempo;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only decoder of a {@code Tempo-Timing} v1 value into its JSON form, following FORMAT.md §6.1 step
 * by step, with its error codes. Independent of the encoder in {@link Tempo}: nothing is shared.
 */
final class WireDecoder {

    /** A value that must be rejected; {@link #code} is one of the {@code E_*} codes of FORMAT.md. */
    static final class Rejected extends RuntimeException {
        final String code;

        Rejected(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }
    }

    static final Pattern RECORD = Pattern.compile("^([0-9a-z])([0-9a-z]+)\\.([0-9a-z]+)\\.([0-9a-z]+)"
            + "(?:@([0-9a-z]+|_))?(?:~([0-9a-z]+)(?:\\.([0-9a-z]+))?)?(?:\\*([0-9a-z]+))?"
            + "(?:\\+([0-9a-z]+)\\.([0-9a-z]+)\\.([0-9a-z]+)\\.([0-9a-z]+)\\.([0-9a-z]+))?"
            + "(?:\\^([a-z]+))?((?::[0-9a-z]+=-?[0-9]+(?:\\.[0-9]+)?)*)$");
    static final Pattern ATTR = Pattern.compile(":([0-9a-z]+)=(-?[0-9]+(?:\\.[0-9]+)?)");
    static final Pattern B36 = Pattern.compile("[0-9a-z]+"), NAME = Pattern.compile("(?:[A-Za-z0-9._-]|%[0-9A-Fa-f]{2})+");
    static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ./:;=@~*+^_%-",
            FLAGS = "fconwmp";
    static final List<String> FIELDS = Arrays.asList("w", "x", "l", "T", "N", "S");
    static final int MAX_DEPTH = 35;

    private WireDecoder() {}

    static Map<String, Object> decode(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) if ("\t\n\u000b\f\r ".indexOf(c) < 0) sb.append(c);
        String v = sb.toString();
        if (!v.startsWith("v1;")) throw new Rejected("E_VERSION", "does not start with v1;");
        for (char c : v.toCharArray()) if (ALPHABET.indexOf(c) < 0) throw new Rejected("E_CHAR", "character " + c);

        List<String> fields = new ArrayList<>(Arrays.asList(v.substring(3).split(";", -1)));
        String last = fields.get(fields.size() - 1);
        long expected = -1;
        boolean cut = false;
        if (last.startsWith("e=") && B36.matcher(last.substring(2)).matches()) {
            expected = Long.parseLong(last.substring(2), 36);
            fields.remove(fields.size() - 1);
        } else {
            cut = true;
            if (last.equals("") || last.equals("e") || last.equals("e=")) fields.remove(fields.size() - 1);
            if (fields.isEmpty() || !fields.get(fields.size() - 1).startsWith("S=")) throw new Rejected("E_CUT", "cut before S=");
        }

        Map<String, String> seen = new LinkedHashMap<>();
        int pos = -1;
        for (String f : fields) {
            int eq = f.indexOf('=');
            if (eq < 0) throw new Rejected("E_FIELD", "no = in " + f);
            String key = f.substring(0, eq);
            int at = FIELDS.indexOf(key);
            if (at < 0) throw new Rejected("E_FIELD", "unknown field " + key);
            if (at <= pos) throw new Rejected("E_FIELD", "duplicate or out of order " + key);
            pos = at;
            seen.put(key, f.substring(eq + 1));
        }
        for (String k : new String[] {"T", "N", "S"}) if (!seen.containsKey(k)) throw new Rejected("E_MISSING", k + "= missing");

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("v", 1L);
        doc.put("unit", "us");
        if (seen.containsKey("w")) doc.put("wall", b36(seen.get("w"), "E_FIELD"));
        long folded = 0, dropped = 0;
        if (seen.containsKey("x")) {
            String[] p = seen.get("x").split("\\.", -1);
            if (p.length != 2) throw new Rejected("E_FIELD", "x= needs folded.dropped");
            folded = b36(p[0], "E_FIELD");
            dropped = b36(p[1], "E_FIELD");
        }
        doc.put("folded", folded);
        doc.put("dropped", dropped);
        List<String> threads = names(seen.get("T")), names = names(seen.get("N"));
        List<Long> live = new ArrayList<>();
        if (seen.containsKey("l")) {
            for (String p : seen.get("l").split("\\.", -1)) {
                long t = b36(p, "E_FIELD");
                if (t >= threads.size()) throw new Rejected("E_INDEX", "l= thread " + t);
                live.add(t);
            }
        }
        doc.put("threads", threads);
        doc.put("live", live);

        List<String> records = new ArrayList<>(Arrays.asList(seen.get("S").split("/", -1)));
        boolean truncated = cut || expected != records.size();
        if (truncated) records.remove(records.size() - 1);
        doc.put("truncated", truncated);
        doc.put("spans", spans(records, names, threads));
        return doc;
    }

    private static List<Map<String, Object>> spans(List<String> records, List<String> names, List<String> threads) {
        List<Map<String, Object>> spans = new ArrayList<>();
        long[] cur = new long[MAX_DEPTH + 2];
        List<Map<String, Object>> path = new ArrayList<>();
        int prevDepth = -1;
        boolean prevAgg = false, prevPhase = false;
        for (int i = 0; i < records.size(); i++) {
            Matcher m = RECORD.matcher(records.get(i));
            if (!m.matches()) throw new Rejected("E_RECORD", "record " + i + " " + records.get(i));
            int depth = Character.digit(m.group(1).charAt(0), 36);
            boolean agg = m.group(9) != null, phase = m.group(14) != null && m.group(14).indexOf('p') >= 0;
            long delta = b36(m.group(3), "E_RECORD");
            if (i == 0) {
                if (depth != 0) throw new Rejected("E_ROOT", "first record at depth " + depth);
                if (m.group(5) != null || m.group(6) != null || agg) throw new Rejected("E_ROOT", "root carries @, ~ or +");
                if (delta != 0) throw new Rejected("E_ROOT", "root startDelta " + delta);
                if (phase) throw new Rejected("E_ROOT", "root is a phase");
            } else {
                if (depth == 0) throw new Rejected("E_ROOT", "second root at " + i);
                if (depth > prevDepth + 1) throw new Rejected("E_DEPTH", "depth " + prevDepth + " to " + depth);
                if (prevAgg && depth > prevDepth) throw new Rejected("E_AGG", "child of an aggregate at " + i);
                if (prevPhase && depth > prevDepth) throw new Rejected("E_FLAG", "child of a phase at " + i);
            }
            long nameIdx = b36(m.group(2), "E_RECORD");
            if (nameIdx >= names.size()) throw new Rejected("E_INDEX", "name " + nameIdx);
            long start = cur[depth] + delta;
            cur[depth] = cur[depth + 1] = start;
            Map<String, Object> parent = depth > 0 ? path.get(depth - 1) : null;

            long thread;
            String t = m.group(5);
            if (t == null) {
                thread = parent == null ? 0 : (Long) parent.get("thread");
            } else if (t.equals("_")) {
                if (!agg) throw new Rejected("E_AGG", "@_ on a plain record " + i);
                thread = -1;
            } else {
                thread = b36(t, "E_RECORD");
                if (thread >= threads.size()) throw new Rejected("E_INDEX", "thread " + thread);
            }

            Map<String, Object> span = new LinkedHashMap<>();
            span.put("id", (long) i);
            span.put("parent", parent == null ? -1L : parent.get("id"));
            span.put("name", names.get((int) nameIdx));
            span.put("start", start);
            span.put("dur", b36(m.group(4), "E_RECORD"));
            span.put("thread", thread);
            if (m.group(6) != null && !agg) {
                if (m.group(7) != null) throw new Rejected("E_AGG", "~sum.max on a plain record " + i);
                span.put("wait", b36(m.group(6), "E_RECORD"));
            }
            if (m.group(8) != null) span.put("cpu", b36(m.group(8), "E_RECORD"));
            if (m.group(14) != null) {
                StringBuilder f = new StringBuilder();
                for (char c : m.group(14).toCharArray()) if (FLAGS.indexOf(c) < 0) throw new Rejected("E_FLAG", "flag " + c);
                if (phase && (agg || m.group(6) != null || m.group(14).indexOf('n') >= 0)) {
                    throw new Rejected("E_FLAG", "phase with +, ~ or n at " + i);
                }
                for (char c : FLAGS.toCharArray()) if (m.group(14).indexOf(c) >= 0) f.append(c);
                span.put("flags", f.toString());
            }
            if (!m.group(15).isEmpty()) {
                Map<String, Object> attrs = new LinkedHashMap<>();
                Matcher a = ATTR.matcher(m.group(15));
                while (a.find()) {
                    long k = b36(a.group(1), "E_RECORD");
                    if (k >= names.size()) throw new Rejected("E_INDEX", "attribute key " + k);
                    attrs.put(names.get((int) k), number(a.group(2))); // first position, last value
                }
                span.put("attrs", attrs);
            }
            if (agg) {
                Map<String, Object> g = new LinkedHashMap<>();
                String[] keys = {"count", "min", "max", "extent", "maxAt"};
                for (int k = 0; k < keys.length; k++) g.put(keys[k], b36(m.group(9 + k), "E_RECORD"));
                if ((Long) g.get("count") < 1) throw new Rejected("E_AGG", "aggregate count 0 at " + i);
                if (m.group(6) != null) {
                    if (m.group(7) == null) throw new Rejected("E_AGG", "aggregate wait without max at " + i);
                    g.put("waitSum", b36(m.group(6), "E_RECORD"));
                    g.put("waitMax", b36(m.group(7), "E_RECORD"));
                }
                span.put("agg", g);
            }
            while (path.size() > depth) path.remove(path.size() - 1);
            path.add(span);
            prevDepth = depth;
            prevAgg = agg;
            prevPhase = phase;
            spans.add(span);
        }
        return spans;
    }

    private static List<String> names(String field) {
        List<String> out = new ArrayList<>();
        for (String n : field.split(":", -1)) out.add(name(n));
        return out;
    }

    /** A name of {@code T} or {@code N}; the empty one is an unnamed span's. */
    static String name(String text) {
        if (text.isEmpty()) return "";
        if (!NAME.matcher(text).matches()) throw new Rejected("E_NAME", "bad name " + text);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                raw.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                raw.write(c == '_' ? ' ' : c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new Rejected("E_NAME", "not UTF-8: " + text);
        }
    }

    static Object number(String text) {
        if (text.indexOf('.') >= 0) {
            double d = Double.parseDouble(text);
            return d == 0 ? 0.0 : d;
        }
        try {
            long l = Long.parseLong(text);
            return l == 0 ? 0L : l;
        } catch (NumberFormatException e) { // beyond a long: still a number
            return Double.parseDouble(text);
        }
    }

    private static long b36(String text, String code) {
        if (!B36.matcher(text).matches()) throw new Rejected(code, "bad base-36 number " + text);
        try {
            return Long.parseLong(text, 36);
        } catch (NumberFormatException e) {
            throw new Rejected(code, "base-36 number too large " + text);
        }
    }
}
