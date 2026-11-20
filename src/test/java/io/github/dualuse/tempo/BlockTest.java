package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Context;
import io.github.dualuse.tempo.Tempo.Lane;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.RunnableTask;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Task;
import io.github.dualuse.tempo.Tempo.ThreadSlot;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.dualuse.tempo.Tempo.SPAN;
import static io.github.dualuse.tempo.Tempo.STEP;
import static io.github.dualuse.tempo.Tempo.TICK;
import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.TempoTest.allocatedBy;
import static io.github.dualuse.tempo.TempoTest.lines;
import static io.github.dualuse.tempo.TempoTest.onThread;
import static io.github.dualuse.tempo.TempoTest.tree;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A trace started inside another one: a self-contained block, which joins the outer trace as a subtree and
 * works for itself as if it were top-level, with its own totals, listeners, level, budget, strictness, CPU
 * setting and output.
 */
class BlockTest {

    static final Name SEARCH = TempoTest.SEARCH, LOAD = TempoTest.LOAD, MATCH = TempoTest.MATCH,
            RERANK = TempoTest.RERANK, DECODE = TempoTest.DECODE, INDEX = Tempo.name("index"),
            SHARD = Tempo.name("shard");

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options opts() {
        return new Options().clock(clock);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    private void span(Name n, long nanos) {
        try (Span s = Tempo.span(n)) {
            clock.add(nanos);
        }
    }

    private void step(Name n, long nanos) {
        try (Span s = Tempo.step(n)) {
            clock.add(nanos);
        }
    }

    // ---- totals: the block's subtree, computed on read ------------------------------------------------

    @Test
    void aBlockCountsItsOwnSubtreeAndTheRequestCountsEverything() {
        Trace req = Tempo.trace(SEARCH, opts());
        span(LOAD, 100);                 // the request's, before the block
        Trace in = Tempo.trace(RERANK);
        span(LOAD, 30);
        step(LOAD, 5);                   // undrawn: the request records spans only
        in.add(LOAD, 2);                 // measured elsewhere, under the block
        in.close();
        span(LOAD, 7);                   // the request's, after the block
        req.add(LOAD, 1);
        req.close();
        assertEquals("count=3 sum=37 mean=12 min=2 max=30 waitSum=0 waitMax=0", in.total(LOAD).toString());
        assertEquals("count=6 sum=145 mean=24 min=1 max=100 waitSum=0 waitMax=0", req.total(LOAD).toString());
        assertEquals(in.total(LOAD).toString(), in.snapshot().total(LOAD).toString()); // a snapshot reads the same
        assertEquals(req.total(LOAD).toString(), req.snapshot().total(LOAD).toString());
        assertEquals(1, in.total(RERANK).count()); // the block's own root is in its subtree
        assertEquals(0, in.total(SEARCH).count());
        assertEquals(0, req.misuses());
        req.release();
        assertEquals(0, in.total(LOAD).count());
    }

    @Test
    void sequentialBlocksEachCountTheirOwn() {
        Trace req = Tempo.trace(SEARCH, opts().budget(16));
        List<String> totals = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Trace in = Tempo.trace(RERANK);
            in.phase(DECODE).label("Decode");
            span(LOAD, i + 1);
            in.done();
            in.close();
            totals.add(in.total(LOAD).count() + "/" + in.total(LOAD).sum() + "/" + in.total(DECODE).count());
        }
        req.close();
        for (int i = 0; i < 40; i++) assertEquals("1/" + (i + 1) + "/1", totals.get(i));
        assertEquals("count=40 sum=820 mean=20 min=1 max=40 waitSum=0 waitMax=0", req.total(LOAD).toString());
        req.release();
    }

    @Test
    void undrawnStepsAreKeptPerBlock() {
        Trace req = Tempo.trace(SEARCH, opts());
        step(MATCH, 10);
        Trace in = Tempo.trace(RERANK);
        step(MATCH, 3);
        try (Span s = Tempo.step(MATCH)) {    // a phase of an undrawn step counts in its scope too
            s.phase(DECODE);
            clock.add(4);
        }
        in.close();
        step(MATCH, 20);
        req.close();
        assertEquals("count=2 sum=7 mean=3 min=3 max=4 waitSum=0 waitMax=0", in.total(MATCH).toString());
        assertEquals("count=4 sum=37 mean=9 min=3 max=20 waitSum=0 waitMax=0", req.total(MATCH).toString());
        assertEquals(1, in.total(DECODE).count());
        assertEquals(1, req.total(DECODE).count());
        assertEquals(lines("rerank 0+7"), tree(in));
        req.release();
    }

    /**
     * A block's total reads the block, not the request: on its lane only from its open to its end, so the
     * same block reads the same whether the request recorded a hundred spans before it or ten thousand.
     */
    @Test
    void aBlocksTotalReadsTheBlockNotTheRequest() {
        Trace req = Tempo.trace(SEARCH, opts());
        Trace[] blocks = new Trace[2];
        long[] bytes = new long[2];
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < (k == 0 ? 100 : 10_000); i++) span(MATCH, 1);
            Trace in = blocks[k] = Tempo.trace(RERANK);
            step(LOAD, 2);                             // undrawn: an entry of the block
            for (int i = 0; i < 3; i++) span(LOAD, 1);
            in.close();
            in.total(LOAD);
            bytes[k] = allocatedBy(() -> in.total(LOAD));
        }
        req.close();
        assertEquals(bytes[0], bytes[1], "bytes of a block's total read after 100 and after 10,000 spans");
        for (Trace in : blocks) {
            assertEquals("count=4 sum=5 mean=1 min=1 max=2 waitSum=0 waitMax=0", in.total(LOAD).toString());
            assertEquals(in.total(LOAD).toString(), in.snapshot().total(LOAD).toString());
        }
        req.release();
    }

    /**
     * The totals of every block, read in place from the block's own lanes, match its snapshot, which
     * decides its subtree over every lane: blocks nested at random, with tasks drawn, folded and undrawn,
     * run on other threads, inline in other blocks, after their block ended and from inside other tasks,
     * cancelled, and blocks opened in them.
     */
    @Test
    void aBlocksTotalsReadInPlaceMatchItsSnapshot() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            for (int seed = 0; seed < 24; seed++) {
                java.util.Random random = new java.util.Random(seed);
                Trace req = Tempo.trace(SEARCH, opts().cpu(false).budget(seed % 3 == 0 ? 0 : 6 + seed)).records(seed % 4 == 1 ? SPAN : STEP);
                List<Trace> blocks = Collections.synchronizedList(new ArrayList<>());
                List<RunnableTask> later = new ArrayList<>();
                List<java.util.concurrent.Future<?>> running = new ArrayList<>();
                java.util.ArrayDeque<Trace> open = new java.util.ArrayDeque<>();
                for (int op = 0; op < 120; op++) {
                    switch (random.nextInt(9)) {
                        case 0: case 1:
                            if (open.size() < 3) {
                                Trace in = Tempo.trace(open.size() % 2 == 0 ? RERANK : INDEX, random.nextBoolean() ? null : new Options().budget(3));
                                blocks.add(in);
                                open.push(in);
                            }
                            break;
                        case 2:
                            if (!open.isEmpty()) open.pop().close();
                            break;
                        case 3:
                            span(LOAD, 1);
                            break;
                        case 4:
                            step(LOAD, 2);
                            break;
                        case 5: {
                            boolean nested = random.nextBoolean();
                            RunnableTask t = Tempo.runnable(MATCH, () -> {
                                step(LOAD, 1);
                                if (nested) {
                                    Trace in = Tempo.trace(SHARD);
                                    blocks.add(in);
                                    span(DECODE, 1);
                                    Tempo.runnable(MATCH, () -> span(DECODE, 1)).run();
                                    in.close();
                                }
                            });
                            if (random.nextBoolean()) running.add(pool.submit(t));
                            else later.add(t);
                            break;
                        }
                        case 6:
                            if (!later.isEmpty()) later.remove(random.nextInt(later.size())).run(); // inline, anywhere
                            break;
                        case 7:
                            if (!later.isEmpty()) later.remove(random.nextInt(later.size())).cancel();
                            break;
                        default:
                            Tempo.current().add(DECODE, 3);
                    }
                }
                for (java.util.concurrent.Future<?> f : running) f.get();
                for (RunnableTask t : later) running.add(pool.submit(t));
                for (java.util.concurrent.Future<?> f : running) f.get();
                while (!open.isEmpty()) open.pop().close();
                req.close();
                for (Trace in : blocks) {
                    Tempo.Snapshot snap = in.snapshot();
                    for (Name n : new Name[]{LOAD, MATCH, DECODE}) assertEquals(snap.total(n).toString(), in.total(n).toString(), "seed " + seed + " " + n);
                }
                assertEquals(0, req.misuses(), "seed " + seed);
                req.release();
            }
        } finally {
            pool.shutdown();
        }
    }

    // ---- listeners: the block's subtree --------------------------------------------------------------

    @Test
    void aBlockListenerHearsOnlyTheBlock() {
        Trace req = Tempo.trace(SEARCH, opts());
        List<Long> block = new ArrayList<>(), all = new ArrayList<>();
        req.on(LOAD, all::add);
        span(LOAD, 100);
        Trace in = Tempo.trace(RERANK).on(LOAD, block::add);
        span(LOAD, 30);
        step(LOAD, 5);
        in.add(LOAD, 2);
        in.close();
        span(LOAD, 7);                   // after the block closed: not the block's
        req.close();
        assertEquals(List.of(30L, 5L, 2L), block);
        assertEquals(List.of(100L, 30L, 5L, 2L, 7L), all);
        req.release();
    }

    @Test
    void blocksNestAndEachCountsAndHearsItsOwnSubtree() {
        Trace req = Tempo.trace(SEARCH, opts());
        List<Long> heardA = new ArrayList<>(), heardB = new ArrayList<>();
        Trace a = Tempo.trace(INDEX).on(LOAD, heardA::add);
        span(LOAD, 1);
        Trace b = Tempo.trace(SHARD).on(LOAD, heardB::add);
        span(LOAD, 2);
        b.close();
        span(LOAD, 4);
        a.close();
        span(LOAD, 8);
        req.close();
        assertEquals("count=3 sum=7 mean=2 min=1 max=4 waitSum=0 waitMax=0", a.total(LOAD).toString());
        assertEquals("count=1 sum=2 mean=2 min=2 max=2 waitSum=0 waitMax=0", b.total(LOAD).toString());
        assertEquals("count=4 sum=15 mean=3 min=1 max=8 waitSum=0 waitMax=0", req.total(LOAD).toString());
        assertEquals(List.of(1L, 2L, 4L), heardA);
        assertEquals(List.of(2L), heardB);
        assertEquals(lines(
                "index 0+7",
                "  load 0+1",
                "  shard 1+2",
                "    load 1+2",
                "  load 3+4"), tree(a));
        assertEquals(lines(
                "shard 0+2",
                "  load 0+2"), tree(b));
        assertEquals(lines(
                "search 0+15",
                "  index 0+7",
                "    load 0+1",
                "    shard 1+2",
                "      load 1+2",
                "    load 3+4",
                "  load 7+8"), tree(req));
        req.release();
    }

    // ---- blocks and tasks on other threads -----------------------------------------------------------

    /** A task submitted in a block carries it to the thread where it runs, also after the block closed. */
    @Test
    void aBlocksTasksCountAndAreHeardAfterTheBlockClosed() throws Exception {
        for (int level = SPAN; level <= STEP; level++) { // undrawn tasks, then drawn ones
            Trace req = Tempo.trace(SEARCH, opts().cpu(false)).records(level);
            List<Long> heard = Collections.synchronizedList(new ArrayList<>());
            Trace in = Tempo.trace(RERANK).on(LOAD, heard::add).on(MATCH, heard::add);
            clock.add(10);
            RunnableTask r = Tempo.runnable(MATCH, () -> span(LOAD, 5));
            clock.add(20);
            in.close();                                // before its task even began
            RunnableTask outside = Tempo.runnable(MATCH, () -> span(LOAD, 1));
            assertNull(onThread("worker-1", r::run));
            assertNull(onThread("worker-1", outside::run));
            req.close();
            assertEquals(List.of(5L, 5L), heard, "level " + level);  // the task's load, then the task
            assertEquals("count=1 sum=5 mean=5 min=5 max=5 waitSum=0 waitMax=0", in.total(LOAD).toString());
            assertEquals("count=1 sum=5 mean=5 min=5 max=5 waitSum=20 waitMax=20", in.total(MATCH).toString());
            assertEquals("count=2 sum=6 mean=3 min=1 max=5 waitSum=25 waitMax=20", req.total(MATCH).toString());
            assertEquals(in.total(MATCH).toString(), in.snapshot().total(MATCH).toString());
            assertEquals(0, req.misuses() + req.dropped());
            req.release();
        }
    }

    /**
     * A task belongs, with everything in it, to the scope it was submitted from, wherever it runs: one
     * submitted in a block that closed and run inline in a sibling block counts and is heard in the first
     * only, drawn, folded or undrawn, and its spans are drawn where it was submitted.
     */
    @Test
    void aTaskSubmittedInOneBlockAndRunInAnotherBelongsToTheFirst() {
        String[] trees = {
                lines("search 0+5",            // undrawn: its span hangs from the span it was submitted from
                        "  index 0+0",
                        "    load 2+3",
                        "  shard 0+5"),
                lines("search 0+5",            // drawn: at its record in the first block
                        "  index 0+0",
                        "    match 0+3 handoff~2",
                        "      load 2+3",
                        "  shard 0+5"),
                lines("search 0+5",            // folded: its span hangs from the span it was submitted from
                        "  index 0+0",
                        "    match 0+3 agg*1 min=3 max=3 maxAt=0 extent=5 handoff~2/2",
                        "    load 2+3",
                        "  shard 0+5")};
        for (int kind = 0; kind < 3; kind++) {
            Trace req = Tempo.trace(SEARCH, opts().cpu(false).budget(kind == 2 ? 0 : 512)).records(kind == 0 ? SPAN : STEP);
            List<Long> first = new ArrayList<>(), second = new ArrayList<>();
            Trace b1 = Tempo.trace(INDEX).on(MATCH, first::add).on(LOAD, first::add);
            RunnableTask t = Tempo.runnable(MATCH, () -> span(LOAD, 3));
            b1.close();
            Trace b2 = Tempo.trace(SHARD).on(MATCH, second::add).on(LOAD, second::add);
            clock.add(2);
            t.run();
            b2.close();
            req.close();
            String what = "kind " + kind;
            assertEquals("1/1", b1.total(MATCH).count() + "/" + b1.total(LOAD).count(), what);
            assertEquals("0/0", b2.total(MATCH).count() + "/" + b2.total(LOAD).count(), what);
            assertEquals("1/1", req.total(MATCH).count() + "/" + req.total(LOAD).count(), what);
            assertEquals(b1.total(LOAD).toString(), b1.snapshot().total(LOAD).toString(), what);
            assertEquals(b2.total(LOAD).toString(), b2.snapshot().total(LOAD).toString(), what);
            assertEquals(b1.total(MATCH).toString(), b1.snapshot().total(MATCH).toString(), what);
            assertEquals(List.of(3L, 3L), first, what);
            assertEquals(List.of(), second, what);
            assertEquals(trees[kind], tree(req), what);
            assertEquals(0, req.misuses() + req.dropped(), what);
            req.release();
        }
    }

    /** A task of the request run inline inside a block is the request's, not the block's. */
    @Test
    void aRequestsTaskRunInsideABlockIsNotTheBlocks() {
        for (int level = SPAN; level <= STEP; level++) {
            Trace req = Tempo.trace(SEARCH, opts().cpu(false)).records(level);
            RunnableTask t = Tempo.runnable(MATCH, () -> {
                span(LOAD, 3);
                step(DECODE, 4);
            });
            List<Long> heard = new ArrayList<>();
            Trace in = Tempo.trace(RERANK).on(MATCH, heard::add).on(LOAD, heard::add).on(DECODE, heard::add);
            t.run();
            in.close();
            req.close();
            assertEquals("0/0/0", in.total(MATCH).count() + "/" + in.total(LOAD).count() + "/" + in.total(DECODE).count(), "level " + level);
            assertEquals("1/1/1", req.total(MATCH).count() + "/" + req.total(LOAD).count() + "/" + req.total(DECODE).count(), "level " + level);
            assertEquals(List.of(), heard);
            assertEquals(lines("rerank 0+7"), tree(in), "level " + level);
            req.release();
        }
    }

    @Test
    void aFoldedTaskInABlockCountsInTheBlock() {
        Trace req = Tempo.trace(SEARCH, opts().cpu(false).budget(0)).records(STEP);
        Trace in = Tempo.trace(RERANK);
        RunnableTask r = Tempo.runnable(MATCH, () -> span(LOAD, 5));
        clock.add(20);
        RunnableTask cancelled = Tempo.runnable(MATCH, () -> fail("cancelled"));
        clock.add(3);
        assertTrue(cancelled.cancel());
        r.run();                                       // inline, folded
        in.close();
        req.close();
        assertEquals("count=1 sum=5 mean=5 min=5 max=5 waitSum=26 waitMax=23", in.total(MATCH).toString()); // the cancelled one waited 3
        assertEquals("count=1 sum=5 mean=5 min=5 max=5 waitSum=0 waitMax=0", in.total(LOAD).toString());
        req.release();
    }

    /** A block opened in a folded task unfolds the task, so the block's report is complete. */
    @Test
    void aBlockInAFoldedTaskUnfoldsTheTask() throws Exception {
        Trace req = Tempo.trace(SEARCH, opts().cpu(false).budget(0)).records(STEP);
        AtomicReference<Trace> block = new AtomicReference<>();
        RunnableTask r = Tempo.runnable(MATCH, () -> {
            clock.add(1);
            Trace in = Tempo.trace(RERANK);
            in.phase(DECODE).label("Decode");
            span(LOAD, 3);
            in.done();
            in.close();
            block.set(in);
            clock.add(1);
        });
        RunnableTask folded = Tempo.runnable(MATCH, () -> clock.add(2)); // stays folded
        clock.add(10);
        assertNull(onThread("worker-1", r::run));
        assertNull(onThread("worker-1", folded::run));
        req.close();
        Trace in = block.get();
        assertEquals(lines(
                "rerank 0+3",
                "  decode 0+3 phase 'Decode'",
                "  load 0+3"), tree(in));
        assertEquals("Decode", in.toRuntime(-1).replaceAll(".*\\((.*)\\)", "$1"));
        assertEquals(lines(
                "search 0+17",
                "  match 0+2 agg*1 min=2 max=2 maxAt=0 extent=17 handoff~15/15 @worker-1",
                "  match 0+5 handoff~10 @worker-1",
                "    rerank 11+3 @worker-1",
                "      decode 11+3 phase 'Decode' @worker-1",
                "      load 11+3 @worker-1"), tree(req));
        assertEquals("count=2 sum=7 mean=3 min=2 max=5 waitSum=25 waitMax=15", req.total(MATCH).toString());
        assertEquals(0, req.misuses() + req.dropped());
        req.release();
    }

    /**
     * A folded task begun as its trace is released may read its submitter's TASKS table after the retired
     * lane gave it to its thread's next lane, of another trace (emulated by pointing the submitter at that
     * table): the task never takes that trace's scope, so it neither counts nor throws there.
     */
    @Test
    void aLateFoldedTaskNeverTakesTheScopeOfAnotherTrace() throws Exception {
        Context first = new Context(opts().budget(0).cpu(false));
        first.level(STEP);
        Lane sub = first.register(new Lane(first, new ThreadSlot()));
        sub.openRoot(SEARCH, 0, -1);
        Trace block = new Trace(sub, first, sub.depth, sub.scope, new Options());
        sub.nest(block, RERANK, 0);
        Task late = new Task(sub, MATCH, null, 0);     // folded: TASKS entry 0, in the block
        assertSame(block, late.scope());

        Context second = new Context(opts().budget(0).cpu(false));
        second.level(STEP);
        Lane next = second.register(new Lane(second, new ThreadSlot()));
        next.openRoot(SEARCH, 0, -1);
        Trace strict = new Trace(next, second, next.depth, next.scope, new Options().strict(true));
        next.nest(strict, RERANK, 0);
        new Task(next, MATCH, null, 0);                // TASKS entry 0 of the second trace, in its strict block
        java.lang.reflect.Field aggs = Lane.class.getDeclaredField("aggs");
        aggs.setAccessible(true);
        aggs.set(sub, aggs.get(next));                 // the recycled table, as a late reader holds it

        assertSame(first.root, late.scope());
        Lane worker = first.register(new Lane(first, new ThreadSlot()));
        Span s = worker.task(late, true, 5);
        assertNotSame(NOOP, s);
        worker.openSpan((Name) null, 0).close();       // misuse: in the first trace, lenient; never the strict block's
        s.close();
        assertEquals(1, first.misuses.get());
        assertEquals(0, second.misuses.get());
    }

    /**
     * An unfolded task is drawn as the handoff record of its submit (FORMAT.md 6.5): it starts at the submit
     * and carries its queue wait and thread, as a drawn task does.
     */
    @Test
    void anUnfoldedTaskIsDrawnAsTheHandoffOfItsSubmit() throws Exception {
        Trace req = Tempo.trace(SEARCH, opts().cpu(false).budget(0)).records(STEP);
        RunnableTask r = Tempo.runnable(MATCH, () -> {
            clock.add(1_000);
            Trace in = Tempo.trace(RERANK);
            span(LOAD, 3_000);
            in.close();
            clock.add(1_000);
        });
        clock.add(10_000);
        assertNull(onThread("worker-1", r::run));
        req.close();
        assertEquals(lines(
                "search 0+15000",
                "  match 0+5000 handoff~10000 @worker-1",
                "    rerank 11000+3000 @worker-1",
                "      load 11000+3000 @worker-1"), tree(req));
        assertTrue(req.toHeader().contains(";S=00.0.f/11.0.5@1~a/22.b.3/33.0.3;"), req.toHeader());
        assertEquals("count=1 sum=5000 mean=5000 min=5000 max=5000 waitSum=10000 waitMax=10000", req.total(MATCH).toString());
        req.release();
    }

    /**
     * A trace unfolds at most max(8, budget / 8) folded tasks for blocks. Past that a folded task that opens
     * a block stays folded, and the block's row hangs from the span the task was submitted from: its own
     * report is still complete, and the tree only leaves out the task between them.
     */
    @Test
    void aTraceUnfoldsABoundedNumberOfTasksForBlocks() throws Exception {
        Trace req = Tempo.trace(SEARCH, opts().cpu(false).budget(0)).records(STEP);
        List<Trace> blocks = Collections.synchronizedList(new ArrayList<>());
        List<RunnableTask> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(Tempo.runnable(MATCH, () -> {
                Trace in = Tempo.trace(RERANK);
                span(LOAD, 1);
                in.close();
                blocks.add(in);
            }));
        }
        clock.add(5);
        assertNull(onThread("worker-1", () -> tasks.forEach(RunnableTask::run)));
        req.close();
        for (Trace in : blocks) assertEquals(lines("rerank 0+1", "  load 0+1"), tree(in));
        String t = tree(req);
        assertEquals(8, t.lines().filter(l -> l.startsWith("  match ") && !l.contains("agg")).count(), t);
        assertEquals(2, t.lines().filter(l -> l.startsWith("  rerank ")).count(), t); // past the cap: under the root
        assertTrue(t.contains("  match 0+2 agg*2 min=1 max=1 maxAt="), t);           // the two tasks still folded
        assertEquals("count=10 sum=10 mean=1 min=1 max=1 waitSum=0 waitMax=0", req.total(LOAD).toString());
        assertEquals(10, req.total(MATCH).count());
        assertEquals(req.total(MATCH).toString(), req.snapshot().total(MATCH).toString());
        assertEquals(0, req.misuses() + req.dropped());
        req.release();
    }

    // ---- granularity ---------------------------------------------------------------------------------

    @Test
    void aBlockWidensItsOwnSubtreeAfterARequestStep() {
        Trace req = Tempo.trace(SEARCH, opts());
        step(MATCH, 10);                 // fixes the request's level: spans
        Trace in = Tempo.trace(RERANK).records(STEP);
        assertEquals(STEP, in.records());
        assertEquals(SPAN, req.records());
        step(MATCH, 20);                 // drawn: in the block
        in.close();
        step(MATCH, 5);                  // undrawn again
        req.close();
        assertEquals(lines(
                "search 0+35",
                "  rerank 10+20",
                "    match 10+20 step"), tree(req));
        assertEquals(lines(
                "rerank 0+20",
                "  match 0+20 step"), tree(in));
        assertEquals("count=1 sum=20 mean=20 min=20 max=20 waitSum=0 waitMax=0", in.total(MATCH).toString());
        assertEquals("count=3 sum=35 mean=11 min=5 max=20 waitSum=0 waitMax=0", req.total(MATCH).toString());
        assertEquals(0, req.misuses());
        req.records(STEP);               // the request saw steps long ago: misuse, changes nothing
        assertEquals(1, req.misuses());
        assertEquals(SPAN, req.records());
        req.release();
    }

    @Test
    void aStepInABlockFixesTheBlockAndTheRequest() {
        Trace req = Tempo.trace(SEARCH, opts());
        Trace in = Tempo.trace(RERANK, new Options().strict(true));
        step(MATCH, 1);
        assertThrows(AssertionError.class, () -> in.records(STEP)); // late within the block
        assertEquals(SPAN, in.records());
        in.close();
        req.records(STEP);               // the block's step was the request's too
        assertEquals(SPAN, req.records());
        req.close();
        assertEquals(2, req.misuses());
        req.release();
    }

    @Test
    void aBlockInheritsAWiderRequestLevelAndTicksOnlyWhereAsked() {
        Trace req = Tempo.trace(SEARCH, opts()).records(STEP);
        Trace in = Tempo.trace(RERANK);
        assertEquals(STEP, in.records());
        step(MATCH, 1);                  // drawn: the request's level holds inside
        Trace ticks = Tempo.trace(INDEX).records(TICK);
        try (Span t = Tempo.tick(LOAD)) {
            clock.add(2);
            try (Span u = t.tick(DECODE)) {
                clock.add(1);
            }
        }
        ticks.close();
        assertSame(NOOP, Tempo.tick(LOAD)); // not recorded outside the block that asked
        assertSame(NOOP, in.tick(LOAD));
        in.close();
        req.close();
        assertEquals(lines(
                "search 0+4",
                "  rerank 0+4",
                "    match 0+1 step",
                "    index 1+3",
                "      load 1+3 tick",
                "        decode 3+1 tick"), tree(req));
        assertEquals(TICK, ticks.records());
        assertEquals(STEP, req.records());
        assertEquals(0, req.misuses());
        req.release();
    }

    // ---- the block's options -------------------------------------------------------------------------

    @Test
    void aBlockDrawsAtMostItsSubBudget() {
        Trace req = Tempo.trace(SEARCH, opts().budget(10)).records(STEP);
        Trace in = Tempo.trace(RERANK, new Options().budget(2));
        for (int i = 0; i < 4; i++) step(LOAD, 1);
        in.close();
        assertEquals(8, req.ctx.unspent()); // the block's two came from the request's budget
        for (int i = 0; i < 3; i++) step(MATCH, 1);
        req.close();
        assertEquals(lines(
                "search 0+7",
                "  rerank 0+4",
                "    load 0+1 step",
                "    load 1+1 step",
                "    load 2+2 agg*2 min=1 max=1 maxAt=0 extent=2",
                "  match 4+1 step",
                "  match 5+1 step",
                "  match 6+1 step"), tree(req));
        assertEquals(4, in.total(LOAD).count());
        assertEquals(2, req.folded());
        assertEquals(5, req.ctx.unspent());
        req.release();
    }

    @Test
    void aSubBudgetNeverTakesMoreThanTheRequestHasLeft() {
        Trace req = Tempo.trace(SEARCH, opts().budget(3)).records(STEP);
        step(MATCH, 1);
        step(MATCH, 1);
        Trace in = Tempo.trace(RERANK); // no sub-budget: what the request has left, 1
        for (int i = 0; i < 3; i++) step(LOAD, 1);
        in.close();
        req.close();
        assertEquals(lines(
                "search 0+5",
                "  match 0+1 step",
                "  match 1+1 step",
                "  rerank 2+3",
                "    load 2+1 step",
                "    load 3+2 agg*2 min=1 max=1 maxAt=0 extent=2"), tree(req));
        assertEquals(0, req.ctx.unspent());
        req.release();
    }

    /** What a block's Options leave unset comes from the request; what they set applies, a null link base too. */
    @Test
    void aBlockInheritsWhatItsOptionsLeaveUnset() {
        TempoTest.Clock cpu = new TempoTest.Clock();
        Trace req = Tempo.trace(SEARCH, opts().cpuClock(cpu).cpu(true).budget(600).headerBytes(256)
                .linkBase("https://viewer.example.org/request/")).records(STEP);
        Trace in = Tempo.trace(RERANK, new Options());
        for (int i = 0; i < 550; i++) step(LOAD, 1); // no sub-budget: the request's 600, not a default 512
        in.close();
        assertEquals(0, req.folded());
        assertEquals(50, req.ctx.unspent());
        assertTrue(in.measuresCpu());
        assertTrue(in.toHeader().length() <= 256 && in.toHeader().contains("x="), in.toHeader());
        assertTrue(in.toLink().startsWith("https://viewer.example.org/request/#"), in.toLink());

        Trace own = Tempo.trace(INDEX, new Options().budget(2).headerBytes(3000).linkBase(null).cpu(false));
        for (int i = 0; i < 4; i++) step(MATCH, 1);
        for (int i = 0; i < 40; i++) span(LOAD, 1);
        own.close();
        req.close();
        assertEquals(2, req.folded());
        assertEquals(48, req.ctx.unspent());
        assertFalse(own.measuresCpu());
        assertTrue(own.toHeader().length() > 256 && own.toHeader().contains(";x=2.0;"), own.toHeader()); // its own limit: no ladder
        assertFalse(own.toLink().startsWith("https://viewer.example.org/"), own.toLink());
        req.release();

        Trace strict = Tempo.trace(SEARCH, opts().strict(true));
        Trace lenient = Tempo.trace(RERANK, new Options());
        assertThrows(AssertionError.class, () -> Tempo.span((Name) null));
        lenient.close();
        strict.close();
        strict.release();
    }

    @Test
    void aStrictBlockThrowsInsideAndALenientOneNeverLoosensAStrictRequest() {
        Trace req = Tempo.trace(SEARCH, opts());
        Trace in = Tempo.trace(RERANK, new Options().strict(true));
        assertThrows(AssertionError.class, () -> Tempo.span((Name) null));
        Span kept = Tempo.span(LOAD);
        kept.close();
        in.close();
        Tempo.span((Name) null).close(); // the request is lenient: healed as ?, counted
        req.close();
        assertEquals(2, req.misuses());
        req.release();
        assertThrows(AssertionError.class, () -> kept.set(DECODE, 1)); // a strict block never pools its handles

        Trace strict = Tempo.trace(SEARCH, opts().strict(true));
        Trace lenient = Tempo.trace(RERANK, new Options().strict(false));
        assertThrows(AssertionError.class, () -> Tempo.span((Name) null));
        lenient.close();
        strict.close();
        strict.release();
    }

    @Test
    void strictAndCpuCarryIntoTheBlocksTasks() throws Exception {
        TempoTest.Clock cpu = new TempoTest.Clock();
        Trace req = Tempo.trace(SEARCH, opts().cpuClock(cpu).cpu(false)).records(STEP);
        Trace in = Tempo.trace(RERANK, new Options().strict(true).cpu(true));
        RunnableTask misused = Tempo.runnable(MATCH, () -> Tempo.span((Name) null).close());
        RunnableTask measured = Tempo.runnable(MATCH, () -> cpu.add(7));
        in.close();
        RunnableTask outside = Tempo.runnable(MATCH, () -> {
            cpu.add(3);
            Tempo.span((Name) null).close(); // lenient here: counted only
        });
        assertTrue(onThread("worker-1", misused::run) instanceof AssertionError);
        assertNull(onThread("worker-1", measured::run));
        assertNull(onThread("worker-1", outside::run));
        req.close();
        StringBuilder b = new StringBuilder();
        req.forEachSpan(v -> b.append(v.name()).append('=').append(v.cpuNanos()).append(' '));
        assertEquals("search=-1 rerank=0 match=0 match=7 match=-1 ?=-1 ", b.toString());
        assertEquals(2, req.misuses());
        req.release();
    }

    @Test
    void aBlockWritesItsOutputWithItsOwnLimitAndViewer() {
        Trace req = Tempo.trace(SEARCH, opts().wallClock(() -> 1794567890123L));
        Trace in = Tempo.trace(RERANK, new Options().headerBytes(256).linkBase("https://viewer.example.org/blocks/"));
        for (int i = 0; i < 40; i++) span(LOAD, 1);
        in.close();
        req.close();
        assertTrue(in.toHeader().length() <= 256, in.toHeader());
        assertTrue(in.toHeader().contains("x="), in.toHeader());           // past its limit: the ladder
        assertTrue(req.toHeader().length() > 256 && !req.toHeader().contains("x="), req.toHeader());
        assertTrue(in.toLink().startsWith("https://viewer.example.org/blocks/#"), in.toLink());
        assertFalse(req.toLink().startsWith("https://viewer.example.org/"), req.toLink());
        assertEquals(in.toHeader(), in.snapshot().toHeader());
        req.release();
    }

    @Test
    void aBlockPrintsItsOwnRuntimeWhenItCloses() {
        Trace req = Tempo.trace(SEARCH, opts());
        List<String> out = new ArrayList<>();
        Trace in = Tempo.trace(RERANK).printOnClose(out::add, "rerank");
        in.phase(LOAD).label("Load");
        clock.add(2_000_000);
        in.done();
        in.close();
        req.close();
        assertEquals(List.of("rerank: 0.002s = 0.002s (Load)"), out);
        req.release();
    }

    // ---- top-level and nested, the same code ---------------------------------------------------------

    /** The same block, top-level and inside a request, reports the same for itself. */
    @Test
    void theSameBlockReportsTheSameTopLevelAndNested() {
        String top = block(null);
        Trace req = Tempo.trace(SEARCH, opts());
        span(LOAD, 50);
        String nested = block(req);
        req.close();
        assertEquals(top, nested);
        req.release();
    }

    private String block(Trace outer) {
        List<Long> heard = new ArrayList<>();
        Trace in = Tempo.trace(INDEX, outer == null ? opts().budget(2) : new Options().budget(2)).records(STEP);
        in.on(LOAD, heard::add);
        in.phase(DECODE).label("Decode");
        for (int i = 1; i <= 4; i++) step(LOAD, i);
        in.done();
        in.close();
        String r = tree(in) + in.total(LOAD) + " heard=" + heard + " runtime=" + in.toRuntime(9) + " records=" + in.records();
        if (outer == null) in.release();
        return r;
    }

    // ---- cost ----------------------------------------------------------------------------------------

    @Test
    void aNestedTraceIsOneObjectAndSpansInsideItAllocateNothing() {
        int n = 1_000;
        Trace[] blocks = new Trace[n];
        boolean warm = false;
        for (int round = 0; round < 8 && !warm; round++) {
            Trace req = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE));
            Trace first = Tempo.trace(RERANK); // the lane's first block sets up its scope arrays, once per lane
            span(LOAD, 1);
            first.close();
            long nested = allocatedBy(() -> {
                for (int i = 0; i < n; i++) {
                    blocks[i] = Tempo.trace(RERANK);
                    blocks[i].close();
                }
            });
            Trace in = Tempo.trace(INDEX);
            long spans = allocatedBy(() -> {
                for (int i = 0; i < n; i++) {
                    Tempo.span(LOAD).close();
                    Tempo.step(MATCH).close(); // undrawn, in the block's scope
                }
            });
            in.close();
            req.close();
            req.release();
            warm = round >= 3 && nested > 0 && nested % n == 0 && nested / n <= 160 && spans == 0;
            if (round == 7) {
                assertTrue(nested > 0 && nested % n == 0 && nested / n <= 160, "a nested trace allocated " + nested + " B for " + n);
                assertEquals(0, spans, "bytes allocated by spans and steps in a block");
            }
        }
    }

    /**
     * A block called thousands of times in one request, each call with an undrawn task: the request's totals
     * stay exact. Each call's entry is the block's own until blocks fill their share of the lane's
     * aggregates; past it a call's task counts in the request's entry, unattributed, and that block's total
     * undercounts.
     */
    @Test
    void manyBlockCallsKeepTheRequestsTotalsExact() {
        Trace req = Tempo.trace(SEARCH, opts().cpu(false));
        Trace first = null, last = null;
        for (int i = 0; i < 6_000; i++) {
            Trace in = Tempo.trace(RERANK);
            Tempo.runnable(MATCH, () -> clock.add(1)).run();
            in.close();
            if (i == 0) first = in;
            last = in;
        }
        req.close();
        assertEquals("count=6000 sum=6000 mean=1 min=1 max=1 waitSum=0 waitMax=0", req.total(MATCH).toString());
        assertEquals(0, req.dropped());
        assertEquals(6_000 - Tempo.Aggs.SCOPED, req.unattributed());
        assertEquals(1, first.total(MATCH).count());
        assertEquals(0, last.total(MATCH).count());
        req.release();
    }

    /** The entries of one name in many blocks do not share a probe chain: a find costs the same for each. */
    @Test
    void aBlocksEntriesSpreadOverTheIndex() {
        int n = 2_000;
        Trace req = Tempo.trace(SEARCH, opts());
        Trace[] blocks = new Trace[n];
        for (int i = 0; i < n; i++) {
            blocks[i] = Tempo.trace(RERANK);
            step(MATCH, 1);                            // undrawn: an entry of the block's own
            blocks[i].close();
        }
        req.close();
        Tempo.Aggs a = req.lane.table();
        long probes = 0;
        for (Trace b : blocks) probes += a.probes(-1, 0, -1, MATCH.id, Tempo.Aggs.HIDDEN, b);
        assertTrue(probes <= 2L * n, probes + " probes to find " + n + " entries");
        req.release();
    }

    /**
     * A request that calls a block hundreds of times, each with an undrawn task, allocates per call the
     * block's Trace and the task only, once its thread's aggregates have grown: the table is kept for the
     * next trace.
     */
    @Test
    void manyBlockCallsWithATaskAllocateTheirTraceAndTaskOnly() {
        int n = 400;
        Runnable nothing = () -> { };
        for (int round = 0; round < 10; round++) {
            Trace req = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE).cpu(false));
            long tasks = allocatedBy(() -> {
                for (int i = 0; i < n; i++) Tempo.runnable(MATCH, nothing).run();
            });
            long blocks = allocatedBy(() -> {
                for (int i = 0; i < n; i++) Tempo.trace(RERANK).close();
            });
            long both = allocatedBy(() -> {
                for (int i = 0; i < n; i++) {
                    Trace in = Tempo.trace(RERANK);
                    Tempo.runnable(MATCH, nothing).run();
                    in.close();
                }
            });
            req.close();
            req.release();
            if (round >= 3 && both == tasks + blocks && blocks % n == 0 && tasks % n == 0) return;
            if (round == 9) assertEquals(tasks + blocks, both, "bytes of " + n + " block calls with a task each");
        }
    }

    /** Add counts in its block and the scopes enclosing it, from inside the block, its task and after it. */
    @Test
    void addCountsInTheBlockFromInsideItsTasksAndAfter() throws Exception {
        Trace req = Tempo.trace(SEARCH, opts().cpu(false));
        Trace outer = Tempo.trace(INDEX);
        Trace in = Tempo.trace(RERANK);
        in.add(LOAD, 1);                               // in the block: its lane's entry
        Tempo.current().add(LOAD, 2);
        RunnableTask task = Tempo.runnable(MATCH, () -> Tempo.current().add(LOAD, 4));
        in.close();
        outer.close();
        in.add(LOAD, 8);                               // after the block closed: lane-less
        assertNull(onThread("worker-1", task::run));  // in the block's task, on its worker's lane
        assertNull(onThread("worker-2", () -> in.add(LOAD, 16))); // a thread outside the trace
        req.add(LOAD, 32);
        req.close();
        assertEquals("count=5 sum=31 mean=6 min=1 max=16 waitSum=0 waitMax=0", in.total(LOAD).toString());
        assertEquals("count=5 sum=31 mean=6 min=1 max=16 waitSum=0 waitMax=0", outer.total(LOAD).toString());
        assertEquals("count=6 sum=63 mean=10 min=1 max=32 waitSum=0 waitMax=0", req.total(LOAD).toString());
        assertEquals(in.total(LOAD).toString(), in.snapshot().total(LOAD).toString());
        assertEquals(req.total(LOAD).toString(), req.snapshot().total(LOAD).toString());
        req.release();
    }

    /** Add in a block allocates nothing past the block's Trace, also in a block called hundreds of times. */
    @Test
    void addInABlockAllocatesNothing() {
        int n = 400;
        for (int round = 0; round < 10; round++) {
            Trace req = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE));
            long blocks = allocatedBy(() -> {
                for (int i = 0; i < n; i++) Tempo.trace(RERANK).close();
            });
            long adds = allocatedBy(() -> {
                for (int i = 0; i < n; i++) {
                    Trace in = Tempo.trace(RERANK);
                    in.add(LOAD, 5);
                    in.close();
                }
            });
            req.close();
            req.release();
            if (round >= 3 && adds == blocks && blocks % n == 0) return;
            if (round == 9) assertEquals(blocks, adds, "bytes of " + n + " block calls with an add each");
        }
    }

    // ---- immutable options ---------------------------------------------------------------------------

    @Test
    void optionsAreImmutableAndEverySetterReturnsACopy() {
        Options base = Options.DEFAULTS;
        Options[] derived = {
                base.budget(64), base.cpu(true), base.cpuClock(() -> 1), base.wallClock(() -> 1), base.headerBytes(1),
                base.linkBase("https://viewer.example.org/"), base.clock(() -> 1), base.lanes(1), base.strict(true)};
        for (Options o : derived) assertNotSame(base, o);
        assertEquals(512, base.budget);
        assertEquals(Tempo.CPU_AUTO, base.cpu);
        assertNull(base.cpuClock);
        assertEquals(3000, base.headerBytes);
        assertNull(base.linkBase);
        assertSame(Tempo.SYSTEM_CLOCK, base.clock);
        assertEquals(256, base.lanes);
        assertFalse(base.strict);
        Options block = base.budget(64).strict(true), debug = block.cpu(true);
        assertEquals(64, debug.budget);
        assertTrue(debug.strict);
        assertEquals(Tempo.CPU_ON, debug.cpu);
        assertEquals(Tempo.CPU_AUTO, block.cpu); // a derived setting never leaks back
    }

    @Test
    void aBlockKeepsTheRequestsClockAndLanes() throws Exception {
        Trace req = Tempo.trace(SEARCH, opts().lanes(1));
        Trace in = Tempo.trace(RERANK, new Options().clock(() -> 0).lanes(64));
        clock.add(5);
        in.close();
        Task t = Tempo.task(MATCH);
        assertNull(onThread("worker-1", () -> assertSame(NOOP, t.tryBegin()))); // the request's one lane: none left
        req.close();
        assertEquals(lines(
                "search 0+5",
                "  rerank 0+5"), tree(req));
        assertEquals(1, req.dropped());
        req.release();
    }
}
