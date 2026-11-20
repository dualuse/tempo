package io.github.dualuse.tempo.demo.medium;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thumbnail renderer that a gallery service uses in two places: a nightly job runs it on its own, and
 * the {@code GET /gallery} request runs it for the pictures it is missing. The renderer used to time
 * itself with a stopwatch of its own; now it starts its own trace, and does not care whether it runs
 * alone or inside a request.
 *
 * <p>It shows a nested trace as a self-contained block. Top-level, the renderer's {@code Tempo.trace} is
 * a trace like any other. Inside the request it joins the request's trace as a subtree, and still works
 * for the renderer exactly as if it were top-level: its {@code total(name)} counts its own subtree only,
 * its tasks on the pool included; its listener ({@code on}) hears only that subtree, also the tasks that
 * end after it closed; its {@code records(STEP)} draws the steps of its own subtree while the request
 * records spans only; its {@code Options} (a sub-budget, strict mode) hold inside it and in its tasks; and
 * {@code printOnClose} logs its own runtime line. The request's totals still count everything, and its
 * header shows the block's steps where they ran. The renderer's settings are one shared, immutable
 * {@code Options} constant.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=SelfContainedBlocks                 the story, then the request's headers
 * ./gradlew -q demo -Pdemo=SelfContainedBlocks | tools/tempo   the request's trace as lanes
 * </pre>
 *
 * Look for: the two {@code render} lines report the same counts, one from the nightly job and one from
 * inside the request; the request's {@code decode} total counts the request's own decode of the gallery
 * index besides the renderer's; in the lanes, the {@code render} span under {@code GET /gallery} holds
 * its {@code decode} steps (drawn, as the renderer asked) and its {@code scale} tasks on the {@code
 * scale-*} threads, while the request's own {@code check} steps after it are not drawn. Or open the
 * printed {@code Tempo-Link} in a browser.
 */
public final class SelfContainedBlocks {
    private SelfContainedBlocks() {
    }

    static final Tempo.Name GALLERY = Tempo.name("GET /gallery"), NIGHTLY = Tempo.name("nightly"),
            RENDER = Tempo.name("render"), DECODE = Tempo.name("decode"), SCALE = Tempo.name("scale"),
            STORE = Tempo.name("store"), INDEX = Tempo.name("index"), CHECK = Tempo.name("check"),
            // The renderer's phases: sections of its own span, labelled for its runtime line.
            READ = Tempo.name("read"), RESIZE = Tempo.name("resize"), WRITE = Tempo.name("write");

    /**
     * The renderer's own settings, configured once and shared by every run: Options are immutable, so no
     * caller can change them for the others. At most 16 steps and tasks drawn per run, strict in tests.
     */
    static final Tempo.Options RENDERER = new Tempo.Options().budget(16).strict(true);

    static final int PICTURES = 3;

    public static void main(String[] args) throws Exception {
        ExecutorService scalePool = Demo.pool("scale", 2);
        ExecutorService scale = Tempo.executor(scalePool, SCALE);
        List<String> log = new ArrayList<>();

        // 1. The nightly job: the renderer alone, a top-level trace.
        Rendered alone = Renderer.render(scale, PICTURES, log);
        Demo.check(alone.decodes == PICTURES && alone.scales == PICTURES, "top-level, the renderer counts its own work");

        // 2. The request: it decodes the gallery index itself, then runs the renderer for what is missing.
        Tempo.Trace t = Tempo.trace(GALLERY);
        Rendered inside;
        try (t) {
            try (Tempo.Span s = Tempo.span(INDEX)) {
                try (Tempo.Span d = Tempo.step(DECODE)) { // the request's own decode, not the renderer's
                    d.set("entries", 120);
                    Demo.cpu(200);
                }
                s.set("missing", PICTURES);
            }
            inside = Renderer.render(scale, PICTURES, log);
            for (int i = 0; i < PICTURES; i++) {
                try (Tempo.Span c = Tempo.step(CHECK)) {  // the request records spans only: not drawn
                    c.set("picture", i);
                    Demo.cpu(50);
                }
            }
        }
        Demo.shutdown(scalePool);

        Demo.check(inside.decodes == alone.decodes && inside.scales == alone.scales && inside.heard == alone.heard,
                "inside the request the renderer reports what it reports alone: its own totals and listener");
        Demo.check(inside.records == Tempo.STEP && t.records() == Tempo.SPAN,
                "the renderer's records(STEP) widened its own subtree; the request still records spans only");
        Demo.check(t.total(DECODE).count() == PICTURES + 1, "the request's totals count its own decode and the renderer's");
        Demo.check(t.total(SCALE).count() == PICTURES && t.total(CHECK).count() == PICTURES,
                "the request counts the renderer's tasks and its own undrawn checks");
        Demo.check(Demo.count(t, DECODE) == PICTURES, "the request's tree draws the renderer's decode steps, and not its own");
        Demo.check(Demo.count(t, SCALE) == PICTURES && Demo.count(t, CHECK) == 0,
                "the renderer's tasks are drawn where they ran; the request's steps are not");
        String caller = Thread.currentThread().getName();
        Demo.check(Demo.count(t, v -> v.name() == SCALE && !caller.equals(v.thread())) == PICTURES,
                "the scale tasks ran on the pool");
        Demo.check(log.size() == 2 && log.get(0).startsWith("render: ") && log.get(1).startsWith("render: "),
                "printOnClose logged one runtime line per render, top-level and nested alike");

        Demo.say("The renderer, alone (nightly) and inside GET /gallery, logged:");
        for (String line : log) Demo.say("  " + line);
        Demo.say("alone:  " + alone);
        Demo.say("inside: " + inside);
        Demo.say("GET /gallery counted " + t.total(DECODE).count() + " decodes and drew " + Demo.count(t, DECODE)
                + " of them (the renderer's), and " + t.total(CHECK).count() + " checks, drawing none.");
        Demo.respond(t);
        t.release();
    }

    /** What one run of the renderer reports about itself, read from its own trace. */
    static final class Rendered {
        final long decodes, scales;
        final int heard, records;

        Rendered(long decodes, long scales, int heard, int records) {
            this.decodes = decodes;
            this.scales = scales;
            this.heard = heard;
            this.records = records;
        }

        @Override
        public String toString() {
            return decodes + " decodes, " + scales + " scale tasks, " + heard + " heard by its listener, records "
                    + (records == Tempo.STEP ? "steps" : records == Tempo.TICK ? "ticks" : "spans");
        }
    }

    /**
     * The self-contained component. It knows nothing about requests: it starts its own trace, which is a
     * trace of its own when nothing else runs, or a block of the caller's trace when there is one, and
     * reads its own totals either way. No trace or span is passed in.
     */
    static final class Renderer {
        static Rendered render(ExecutorService scale, int pictures, List<String> log) throws Exception {
            AtomicInteger heard = new AtomicInteger();
            Tempo.Trace in = Tempo.trace(RENDER, RENDERER).records(Tempo.STEP);
            in.on(SCALE, nanos -> heard.incrementAndGet()); // its scale tasks only, on whichever thread ends them
            in.printOnClose(log::add, "render");
            List<Future<?>> scaled = new ArrayList<>();
            try (in) {
                in.phase(READ).label("Read");
                for (int i = 0; i < pictures; i++) {
                    try (Tempo.Span d = Tempo.step(DECODE)) { // drawn: the renderer records steps
                        d.set("picture", i);
                        Demo.cpu(100);
                    }
                }
                in.phase(RESIZE).label("Resize");
                for (int i = 0; i < pictures; i++) scaled.add(scale.submit(() -> Demo.cpu(150)));
                for (Future<?> f : scaled) f.get();
                in.phase(WRITE).label("Write");
                Tempo.run(STORE, () -> Demo.io(1));
            }
            Rendered r = new Rendered(in.total(DECODE).count(), in.total(SCALE).count(), heard.get(), in.records());
            in.release(); // returns the buffers when the renderer ran alone; inside a request it does nothing
            return r;
        }
    }
}
