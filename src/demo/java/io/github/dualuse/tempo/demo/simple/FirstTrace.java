package io.github.dualuse.tempo.demo.simple;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

/**
 * The first contact: one request, {@code GET /hello?name=Ada}, recorded as a trace. The handler parses
 * the query, looks the greeting up (which loads it), renders the body and stores the visit count, each
 * a span under the request.
 *
 * <p>It shows the three ways to open a span (try-with-resources, {@code Tempo.run} and {@code
 * Tempo.call}), all static: a span opens under the innermost open span of its thread, so no method
 * takes a span or a trace as a parameter. It also shows a span nested in another, a number on a span,
 * and the four headers a service sends with the response.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=FirstTrace               the story and the response
 * ./gradlew -q demo -Pdemo=FirstTrace | tools/tempo the trace drawn as lanes (--tree for the tree)
 * </pre>
 *
 * Look for: one lane, {@code main}, with {@code parse}, {@code lookup}, {@code render} and
 * {@code store} side by side under {@code GET /hello}, and {@code load} one row below {@code lookup}.
 * Or open the printed {@code Tempo-Link} in a browser.
 */
public final class FirstTrace {
    private FirstTrace() {
    }

    // The checks below compare names. Tempo.name("x") interns "x" once; the String overloads used in the
    // handler look "x" up in the same table, so both give the same Name.
    static final Tempo.Name HELLO = Tempo.name("GET /hello"), PARSE = Tempo.name("parse"),
            LOOKUP = Tempo.name("lookup"), LOAD = Tempo.name("load"),
            RENDER = Tempo.name("render"), STORE = Tempo.name("store"), BYTES = Tempo.name("bytes");

    public static void main(String[] args) throws Exception {
        // The trace's root span opens here, on this thread; strict turns any misuse into an AssertionError.
        // It is declared before the try so that it can still be read after it closed.
        Tempo.Trace t = Tempo.trace("GET /hello", new Tempo.Options().strict(true));
        try (t) {
            handle("name=Ada");
        }
        // Closed, but still readable: everything below reads the finished trace.

        String thread = Thread.currentThread().getName();
        int lookup = Demo.id(t, LOOKUP);
        for (Tempo.Name n : new Tempo.Name[] {HELLO, PARSE, LOOKUP, LOAD, RENDER, STORE})
            Demo.check(Demo.count(t, n) == 1, n + " is recorded once");
        Demo.check(Demo.count(t, v -> v.depth() == 1) == 4, "parse, lookup, render and store are children of the root");
        Demo.check(Demo.count(t, v -> v.name() == LOAD && v.depth() == 2 && v.parent() == lookup) == 1,
                "load is a child of lookup, one level deeper");
        Demo.check(Demo.attribute(t, LOAD, BYTES) == 5, "load carries bytes=5");
        Demo.check(Demo.count(t, v -> !thread.equals(v.thread())) == 0, "every span ran on " + thread);
        Demo.check(Demo.count(t, v -> v.failed() || v.open()) == 0, "no span failed or was left open");
        Demo.check(Tempo.current() == Tempo.NOOP, "outside the trace a span opens nothing: Tempo.current() is NOOP");

        Demo.say("Runtime: the total in seconds, plus the labelled phases (none here; see StopwatchSteps).");
        Demo.say("Server-Timing: the same in milliseconds, W3C format, shown by the browser's dev tools.");
        Demo.say("Tempo-Timing: the whole tree in one line (FORMAT.md); pipe this output into tools/tempo.");
        Demo.say("Tempo-Link: the same tree as a link that opens Tempo Timeline in the browser;");
        Demo.say("  the trace rides in the URL fragment, which the browser never sends: nothing is uploaded.");
        Demo.say("After responding, t.release() (optional) hands the trace's buffers back to a pool;");
        Demo.say("  every read of the trace is empty afterwards.");
        Demo.respond(t);

        // Optional: hands the buffers back to a pool for the next request. Every read is empty afterwards,
        // so render the headers (or take t.snapshot()) first.
        t.release();
        Demo.check(t.toHeader().isEmpty(), "a released trace reads empty");
    }

    /** The request handler. It receives no span: each span it opens nests under the innermost open one. */
    static String handle(String query) {
        // Tempo.call and Tempo.run wrap a lambda in a span; a throw from the body flags the span failed.
        String name = Tempo.call("parse", () -> {
            Demo.cpu(1500);
            return query.substring(query.indexOf('=') + 1);
        });

        // try-with-resources for a block. The handle belongs to this thread and to this span until the
        // trace is released, so use it inside the block (set and fail also work right after it closes).
        String greeting;
        try (Tempo.Span lookup = Tempo.span("lookup")) {
            Demo.cpu(100);
            greeting = load();      // load opens its span inside lookup: a child of lookup
            lookup.set("hit", 1);
        }

        String body = Tempo.call("render", () -> {
            Demo.cpu(300);
            return greeting + ", " + name + "!";
        });
        Tempo.run("store", () -> Demo.io(2));   // store the visit count
        return body;
    }

    /** Any code can open a span; it lands under whatever span its caller has open. */
    static String load() {
        try (Tempo.Span load = Tempo.span("load")) {
            Demo.io(3);
            String value = "Hello";
            load.set("bytes", value.length());   // a number on the span (see AttributesAndFailures)
            return value;
        }
    }
}
