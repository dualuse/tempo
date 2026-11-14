package io.github.dualuse.tempo.demo.simple;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An upload that goes partly wrong: {@code PUT /upload} reads the body, decodes it, finds which chunks
 * are stored already, tries to render a preview (the format is not supported), writes to the primary
 * store (it times out, the replica takes the write) and renders thumbnails until its time is up.
 *
 * <p>It shows numbers on spans (long and double attributes), the three ways a span learns about a
 * failure ({@code run}/{@code call} on a throw, {@code fail(e)} in a catch, and {@code cancel()} for
 * work given up), a flag set after the span closed, a helper that finds its parent with
 * {@code Tempo.current()}, and what {@code Options.strict} does with a span left open.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=AttributesAndFailures                      the story and the response
 * ./gradlew -q demo -Pdemo=AttributesAndFailures | tools/tempo          the lanes: ✗ failed, ⊘ cancelled
 * ./gradlew -q demo -Pdemo=AttributesAndFailures | tools/tempo --tree   the tree with attributes and flags
 * </pre>
 *
 * Look for: {@code bytes=} on {@code read}, {@code hit-ratio=0.75} on {@code dedupe}, {@code parse}
 * one level below {@code decode}, flag {@code f} on {@code preview} and on the first {@code write},
 * flag {@code c} and {@code made=} on {@code thumbnails}; in the narration, flag {@code m} on the
 * {@code lookup} of {@code GET /status}. Or open the printed {@code Tempo-Link} in a browser.
 */
public final class AttributesAndFailures {
    private AttributesAndFailures() {
    }

    static final Tempo.Name UPLOAD = Tempo.name("PUT /upload"), READ = Tempo.name("read"),
            DECODE = Tempo.name("decode"), PARSE = Tempo.name("parse"), DEDUPE = Tempo.name("dedupe"),
            PREVIEW = Tempo.name("preview"), STORE = Tempo.name("store"), WRITE = Tempo.name("write"),
            THUMBNAILS = Tempo.name("thumbnails"), STATUS = Tempo.name("GET /status"),
            LOOKUP = Tempo.name("lookup"), RENDER = Tempo.name("render");
    // Attribute keys are names too.
    static final Tempo.Name BYTES = Tempo.name("bytes"), FIELDS = Tempo.name("fields"),
            CHUNKS = Tempo.name("chunks"), HIT_RATIO = Tempo.name("hit-ratio"),
            REPLICA = Tempo.name("replica"), MADE = Tempo.name("made");

    static final int[] THUMBNAIL_SIZES = {64, 256, 1024};

    public static void main(String[] args) throws Exception {
        // The parser is shared code: here it runs at startup, outside any trace. Tempo.current() is
        // then Tempo.NOOP, a handle on which every call returns at once and records nothing.
        Demo.check(Tempo.current() == Tempo.NOOP, "outside a trace Tempo.current() is Tempo.NOOP");
        Demo.check(Parser.parse("format=png;width=640;height=480") == 3, "the parser works without a trace");
        Demo.check(!Tempo.current().recording() && Tempo.current().span(PARSE) == Tempo.NOOP,
                "outside a trace a new span is NOOP too: nothing is recorded");

        // A mistake first, and what Tempo does with it with and without strict mode.
        strictAndHealed();

        // strict(true) is for tests: misuse (opening a child on a span that already closed, a call on a
        // foreign thread, closing a span while a child is still open) throws AssertionError where Tempo
        // detects it, instead of being healed and counted. set, fail and cancel after close are allowed
        // until the next span opens at the same depth (see thumbnails below), and are not misuse. In
        // production leave it off.
        Tempo.Trace t = Tempo.start(UPLOAD, new Tempo.Options().strict(true));
        try (t) {
            upload(t.root());
        }

        Demo.check(Tempo.current() == Tempo.NOOP, "once the trace closed, Tempo.current() is NOOP again");
        Demo.check(Demo.count(t, PARSE) == 1, "the trace holds exactly one parse, the one decode called");
        int decode = Demo.id(t, DECODE);
        Demo.check(Demo.count(t, v -> v.name() == PARSE && v.parent() == decode) == 1,
                "the helper's parse span nests under decode, the caller's span");
        Demo.check(Demo.attribute(t, READ, BYTES) == 48_213, "read carries bytes=48213, a long attribute");
        Demo.check(Demo.attribute(t, PARSE, FIELDS) == 3, "parse carries fields=3, set by the helper");
        Demo.check(doubleAttribute(t, DEDUPE, HIT_RATIO) == 0.75, "dedupe carries hit-ratio=0.75, a double attribute");
        Demo.check(Demo.count(t, v -> v.name() == PREVIEW && v.failed()) == 1, "preview is flagged failed by run()");
        Demo.check(Demo.count(t, v -> v.name() == WRITE) == 2, "store made two write attempts");
        Demo.check(Demo.count(t, v -> v.name() == WRITE && v.failed() && Demo.attribute(v, REPLICA) == 0) == 1,
                "the write to replica 0 (the primary) is flagged failed by fail(e)");
        Demo.check(Demo.count(t, v -> v.name() == WRITE && !v.failed() && Demo.attribute(v, REPLICA) == 1) == 1,
                "the write to replica 1 succeeded");
        Demo.check(Demo.count(t, v -> v.name() == STORE && !v.failed()) == 1, "store itself did not fail");
        Demo.check(Demo.count(t, v -> v.name() == THUMBNAILS && v.cancelled() && !v.failed()) == 1,
                "thumbnails is flagged cancelled, not failed, although cancel() came after close");
        long made = Demo.attribute(t, THUMBNAILS, MADE);
        Demo.check(made >= 0 && made < THUMBNAIL_SIZES.length, "thumbnails carries made=, fewer than all sizes");
        Demo.check(Demo.count(t, v -> v.failed()) == 2, "exactly two spans failed: preview and one write");
        Demo.check(Demo.count(t, v -> v.healed() || v.misuse() || v.open()) == 0,
                "the strict trace has no healed, misused or open span");

        Demo.say("PUT /upload: preview failed and the upload went on; the primary write timed out, the replica took it;");
        Demo.say("thumbnails ran out of time after " + made + " of " + THUMBNAIL_SIZES.length + " sizes and were cancelled.");
        Demo.respond(t);
    }

    /** The request handler. Every step is a child of the root, so the tree reads like the method. */
    static void upload(Tempo.Span root) {
        byte[] body;
        try (Tempo.Span s = root.span(READ)) {
            Demo.io(2);
            body = new byte[48_213];
            s.set(BYTES, body.length);                      // a long attribute: the size of what was read
        }

        // call() returns the body's result; inside it, Tempo.current() is the decode span.
        int fields = root.call(DECODE, () -> {
            Demo.cpu(500);
            return Parser.parse("format=tiff;width=" + body.length / 64 + ";height=48");
        });

        try (Tempo.Span s = root.span(DEDUPE)) {
            Demo.io(1);
            int chunks = 4, stored = 3;
            s.set(CHUNKS, chunks).set(HIT_RATIO, (double) stored / chunks);   // a double attribute
        }

        // run() flags the span failed when the body throws, and rethrows the exception as it was:
        // no wrapper to unpack, so the handler catches its own type and carries on without a preview.
        try {
            root.run(PREVIEW, () -> renderPreview(fields));
        } catch (UnsupportedFormat e) {
            Demo.say("preview: " + e.getMessage() + " (caught as UnsupportedFormat, not wrapped)");
        }

        try (Tempo.Span store = root.span(STORE)) {
            for (int replica = 0; replica < 2; replica++) {
                Tempo.Span w = store.span(WRITE).set(REPLICA, replica);
                try {
                    write(replica);
                    break;
                } catch (TimeoutException e) {
                    w.fail(e);                             // only the flag is recorded, not the exception
                } finally {
                    w.close();
                }
            }
        }

        // Optional work under a time budget. try-with-resources closes the span before the catch block
        // runs; set, fail and cancel still apply to it there (the handle stays this span's until the
        // next span opens at the same depth). cancel(), not fail(): nothing broke, the work was given up.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4);
        int made = 0;
        Tempo.Span thumbs = root.span(THUMBNAILS);
        try (thumbs) {
            for (int size : THUMBNAIL_SIZES) {
                if (System.nanoTime() > deadline) throw new TimeoutException("no time left for size " + size);
                Demo.io(3);
                made++;
            }
        } catch (TimeoutException e) {
            thumbs.set(MADE, made).cancel();
        }
    }

    /**
     * Shared code that knows nothing about the request: it receives no span, but asks Tempo for the
     * innermost open span of its thread and adds its own child there. Outside a trace that is NOOP.
     */
    static final class Parser {
        static int parse(String header) {
            try (Tempo.Span s = Tempo.current().span(PARSE)) {
                Demo.cpu(100);
                int fields = header.split(";").length;
                s.set(FIELDS, fields);
                return fields;
            }
        }
    }

    static void renderPreview(int fields) throws UnsupportedFormat {
        Demo.cpu(300);
        throw new UnsupportedFormat("no preview for a tiff with " + fields + " header fields");
    }

    /** Replica 0, the primary, does not answer in time; replica 1 does. */
    static void write(int replica) throws TimeoutException {
        Demo.io(replica == 0 ? 4 : 2);
        if (replica == 0) throw new TimeoutException("primary store did not answer");
    }

    /**
     * The same mistake in two traces: a cache lookup that returns early on a hit and forgets to close
     * its span. Closing the parent heals it; strict mode reports it instead. Neither is the response.
     */
    static void strictAndHealed() {
        // Without strict, as in production: the request is unharmed. The open span is ended at its
        // parent's close, flagged healed and counted as misuse: flag m in Tempo-Timing.
        Tempo.Trace t = Tempo.start(STATUS);
        try (t) {
            t.root().run(RENDER, () -> {
                leakyLookup("logo");
                Demo.cpu(200);
            });
        }
        Demo.check(Demo.count(t, v -> v.name() == LOOKUP && v.healed()) == 1,
                "the lookup left open is healed when render closes");
        Demo.check(Demo.count(t, v -> v.name() == RENDER && !v.healed()) == 1, "render itself closed normally");
        Demo.check(t.toHeader().contains("^m"), "Tempo-Timing shows the healed span with flag m");
        Demo.say("GET /status Tempo-Timing: " + t.toHeader());

        // With strict, as in a test: the same close throws, naming the mistake.
        String error = null;
        try (Tempo.Trace s = Tempo.start(STATUS, new Tempo.Options().strict(true))) {
            s.root().run(RENDER, () -> leakyLookup("logo"));
        } catch (AssertionError e) {
            error = e.getMessage();
        }
        Demo.check(error != null && error.startsWith("Tempo misuse"), "strict mode turns the misuse into an AssertionError");
        Demo.check(Tempo.current() == Tempo.NOOP, "after the AssertionError the thread is outside any trace");

        Demo.say("GET /status: lookup was left open; render's close ended it and flagged it healed (m in Tempo-Timing).");
        Demo.say("Under Options.strict(true) the same close threw: " + error);
    }

    /** The bug: on a cache hit it returns before closing its span. */
    static String leakyLookup(String key) {
        Tempo.Span s = Tempo.current().span(LOOKUP);
        Demo.io(1);
        if (key.equals("logo")) return "cached";
        s.close();
        return "loaded";
    }

    static final class UnsupportedFormat extends Exception {
        private static final long serialVersionUID = 1L;

        UnsupportedFormat(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ reading the trace back

    /** The double attribute {@code key} of the (first) span called {@code name}, or NaN (Demo.attribute reads longs). */
    static double doubleAttribute(Tempo.Trace t, Tempo.Name name, Tempo.Name key) {
        double[] value = {Double.NaN};
        boolean[] seen = {false};
        t.forEachSpan(v -> {
            if (v.name() != name || seen[0]) return;
            seen[0] = true;
            for (int i = 0; i < v.attributes(); i++)
                if (v.attributeKey(i) == key && v.attributeIsDouble(i)) value[0] = v.doubleAttribute(i);
        });
        return value[0];
    }
}
