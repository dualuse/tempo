package io.github.dualuse.tempo.demo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Lists and runs the demos.
 *
 * <pre>
 * ./gradlew demo                                       the list below
 * ./gradlew -q demo -Pdemo=FirstTrace                  one demo: its story, then its response
 * ./gradlew -q demo -Pdemo=FirstTrace | tools/tempo    the trace drawn as lanes in the terminal
 * ./gradlew demos                                      every demo, quietly, with its checks
 * </pre>
 *
 * Read them in the order listed: each grade builds on the one before (src/demo/README.md).
 */
public final class Demos {
    private Demos() {
    }

    static final class Entry {
        final String grade, name, about;

        Entry(String grade, String name, String about) {
            this.grade = grade;
            this.name = name;
            this.about = about;
        }

        String className() {
            return Demos.class.getPackage().getName() + "." + grade + "." + name;
        }
    }

    static final List<Entry> ALL = Arrays.asList(
            new Entry("simple", "FirstTrace", "a trace, spans opened with static calls, and the four response headers"),
            new Entry("simple", "StopwatchSteps", "phases, a span timed by hand, an unnamed span, Runtime/Server-Timing"),
            new Entry("simple", "AttributesAndFailures", "numbers on spans, failed and cancelled work, Tempo.current() in a helper"),

            new Entry("medium", "LevelsAndOptIn", "spans always, steps and ticks on request, a hot loop folded, totals"),
            new Entry("medium", "WorkerPool", "Tempo.executor: queue wait, CPU time, caller-runs, rejection, Future.cancel"),
            new Entry("medium", "MetricsAndListeners", "listeners and totals: metrics from spans, queue wait of undrawn tasks"),
            new Entry("medium", "ResponseHeaders", "headers under a byte limit, Tempo-Link, JSON in the body, reading a trace"),

            new Entry("complex", "SearchRequest", "a search request fanned out to an I/O pool and a compute pool, with early exit"),
            new Entry("complex", "IngestPipeline", "an upload through hand-written stage workers, bounded queues, backpressure"),
            new Entry("complex", "BatchUpload", "a batch of items in parallel: nested fan-out, a failure, rejection, a deadline"),
            new Entry("complex", "PoolDiagnosis", "one pool or two? the same load under both layouts, compared from the traces"));

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            list();
            return;
        }
        if (args[0].equals("--all")) {
            Demo.quiet(true);
            for (Entry e : ALL) {
                long t0 = System.nanoTime();
                run(e, new String[0]);
                System.out.printf(Locale.ROOT, "ok   %-8s %-22s %5d ms%n", e.grade, e.name, (System.nanoTime() - t0) / 1_000_000);
            }
            return;
        }
        for (Entry e : ALL) {
            if (e.name.equalsIgnoreCase(args[0])) {
                run(e, Arrays.copyOfRange(args, 1, args.length));
                return;
            }
        }
        System.err.println("no demo named " + args[0]);
        list();
        System.exit(2);
    }

    private static void list() {
        String grade = "";
        for (Entry e : ALL) {
            if (!e.grade.equals(grade)) {
                grade = e.grade;
                System.out.println();
                System.out.println(grade);
            }
            System.out.printf(Locale.ROOT, "  %-22s %s%n", e.name, e.about);
        }
        System.out.println();
        System.out.println("./gradlew -q demo -Pdemo=FirstTrace | tools/tempo");
    }

    private static void run(Entry e, String[] args) throws Exception {
        Method main = Class.forName(e.className()).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException x) {
            Throwable cause = x.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw x;
        }
    }
}
