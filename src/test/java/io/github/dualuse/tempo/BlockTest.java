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
                "  match 10+5 @worker-1",
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

    // ---- immutable options ---------------------------------------------------------------------------

    @Test
    void optionsAreImmutableAndEverySetterReturnsACopy() {
        Options base = new Options();
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
