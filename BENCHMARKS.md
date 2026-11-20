# Benchmarks

The JMH benchmarks live in `src/jmh/java/io/github/dualuse/tempo/TempoBenchmark.java`.
CI runs the cost gate, `./gradlew jmhGate` (`CostGate.java`), on JDK 11, 17 and 21. It runs the
gated benchmarks once with `-prof gc` and checks two things:

- **Allocation** per operation (`gc.alloc.rate.norm`), which is deterministic.
- **Time**, only for the cost promises, and only as a ratio to `baselineNanoTime` (one
  `System.nanoTime()`) measured in the same run. Absolute times differ from machine to
  machine; the ratio much less. Each time is the median of the measurement iterations, so one
  slow iteration on a busy machine does not decide the gate.

Other times are a local check before a release; they are recorded here with the machine they
were taken on.

```
./gradlew jmhGate                                         # the cost gate, about four minutes
./gradlew jmh -Pjmh.args="TempoBenchmark -f 1 -prof gc"   # everything, with times
```

## The four cost promises

| Promise | Benchmarks | Measured | Gate |
|---|---|---|---|
| 1. 0 B per recorded span, steady state | `span`, `spanStatic`, `spanNotInlined`, `run`, `stepDrawn`, `phase`, `done`, `setLong` | 0.0000 to 0.0003 B/op | 0.01 B/op |
| 2. One object per task, plus a one-time lane setup of at most 256 B for each trace and worker thread (248 B measured), gated like `traceLifecycle` | `task`, `supplierInline` | 48.0001, 48.0004 B/op | 48.01 B/op: a `Task` or `SupplierTask` |
| | `taskInline`, `callableInline`, `taskFolded`, `taskUndrawn` | 56.0004 to 56.0007 B/op | 56.01 B/op: a `RunnableTask` or `CallableTask`, also its future |
| | `executorSubmit`, `executorCall`, `executorCallableTask` (through `Tempo.executor`) | 56.0005 B/op | 56.01 B/op: the task, which is the future returned |
| | `across` (a `Task` begun on another thread), per op of either side | 24.0014 B/op, setup 630 B per trace | 24.01 B/op, setup 1,024 B |
| | `acrossUndrawn` (a `RunnableTask` run on another thread), per op of either side | 28.0001 B/op, setup 630 B per trace | 28.01 B/op, setup 1,024 B |
| 3. An unrecorded tick costs about a branch | `tickUnrecorded` (`s.tick`) | 1.4 ns = 0.14 x `baselineNanoTime`, 0 B | 0.5 x, 0.01 B/op |
| | `tickStatic` (`Tempo.tick`, in a trace) | 1.8 ns = 0.18 x, 0 B | 1 x, 0.01 B/op |
| | `tickNoTrace` (`Tempo.tick`, no trace) | 1.2 ns = 0.12 x, 0 B | 1 x, 0.01 B/op |
| 3. An undrawn step allocates nothing | `stepUndrawn` | 27.4 ns = 2.74 x, 0.0002 B/op | 0.01 B/op; time reported |
| 4. A recorded static span costs at most 6.4 clock reads | `spanStatic` | 37.4 ns = 3.73 x `baselineNanoTime` (10.0 ns) | 6.4 x |

## Self-contained blocks

A trace started inside another one is a self-contained block (README, "Self-contained blocks").
The four promises hold with a block open, and the gate checks the block paths in a group of
their own (`./gradlew jmhGate`, 2026-11-20, `baselineNanoTime` 10.3 ns):

| Path | Benchmark | Measured | Gate |
|---|---|---|---|
| A recorded span inside an open block, static API | `spanInBlock` | 0.0002 B/op (552 B per trace: the trace and its block); 36.9 ns = 3.59 x | 0.01 B/op; time reported |
| An undrawn step inside an open block | `stepUndrawnInBlock` | 0.0002 B/op; 28.4 ns = 2.76 x | 0.01 B/op; time reported |
| A nested trace, opened and closed | `nestedTrace` | 128.0003 B/op | 128.01 B/op: the one `Trace` |

A block is its `Trace`: the block's span handle and its scope (level, sub-budget, strictness, CPU
setting, listeners, lane-less totals and output settings) in one object of 128 B. The lane
carries the innermost open block and switches it where a block opens or a task of another block
begins; without blocks nothing switches, so a span in a block costs what one outside does
(`spanInBlock` beside `spanStatic`). The first block on a thread sets up two small arrays of its
lane's kit (the scopes from before each switch), which the thread's later lanes reuse. A task
carries its block in a field it already had (the waiting-list link, which only a folded task
uses; a folded task's block is its aggregate's), so tasks stay 48 and 56 B.

Promise 1 holds because a recorded span takes its handle from its thread's pool, which a
released trace refills. A handle allocated per span, in one trace or in every trace, would grow
with the cycle length and show in the per-op figure (see below).

Promise 2: sizes are for a 64-bit JVM with compressed oops. A task carries its lane, name,
submit time, state, queue wait and its scope (or, folded, its link in the waiting list), 48 B; a supplier task adds its body within the same 48 B. A
runnable or callable task also holds its body, its outcome and the thread that runs it, as a
`FutureTask` does, 56 B. Running a task allocates nothing, drawn, folded or undrawn, on the
submitting thread or on another one. The `across` groups run the task on a thread of their own,
as a pool thread would; a group op is one of either side, so the gate is half the task's size.
That thread sets up its lane of each trace once, at its first task of the trace: the lane
object and a weak reference to it for the thread's slot (248 B). Its arrays come from the
thread's lanes of earlier traces, which hand them back when their trace is released. The gate
solves this per-trace setup out of the per-task figure, from two cycle lengths, and holds the
setup of both threads (the submitter's trace and the worker's lane) to 1,024 B per trace.

Promise 3: a tick costs less than half a clock read, so it reads no clock. The static form adds
the `ThreadLocal` read. Each tick benchmark opens and closes its tick, so a tick that opened a
frame by mistake would show its clock reads. A step is always timed, so a step the trace does not draw costs its two
clock reads and an update of its totals, and nothing else.

Promise 4: `span` (through a span handle) is reported beside `spanStatic`; the `ThreadLocal`
read of the static form is within the noise.

## Allocation gate

`gc.alloc.rate.norm` per operation, from one `./gradlew jmhGate` run on the machine below. The
cycling benchmarks start a new trace every `cycle` operations; the gate solves the
per-operation bytes from two cycle lengths and reports the per-trace setup apart. A trace's
setup is its `Trace`, its context and its root lane; the lane's arrays come from the thread's
lanes of earlier traces.

| Benchmark | Measured | Gate |
|---|---|---|
| `span`, `spanStatic`, `spanNotInlined`, `run` | 0.0002 to 0.0003 B/op (400 B per trace) | 0.01 B/op |
| `stepDrawn` | 0.0003 B/op (400 B per trace) | 0.01 B/op |
| `phase` | 0.0002 B/op (400 B per trace) | 0.01 B/op |
| `done` | 0.0001 B/op (400 B per trace) | 0.01 B/op |
| `setLong` | 0.0000 B/op (400 B per trace) | 0.01 B/op |
| `tickUnrecorded`, `tickStatic`, `tickNoTrace` | 0.0000 B/op | 0.01 B/op |
| `stepUndrawn` | 0.0002 B/op | 0.01 B/op |
| `folded` | 0.0003 B/op | 0.01 B/op |
| `add` | 0.0000 B/op | 0.01 B/op |
| `task` | 48.0001 B/op (400 B per trace) | 48.01 B/op: the one `Task` |
| `supplierInline` | 48.0004 B/op (400 B per trace) | 48.01 B/op: the one `SupplierTask` |
| `taskInline` | 56.0004 B/op (400 B per trace) | 56.01 B/op: the one `RunnableTask` |
| `callableInline` | 56.0005 B/op (400 B per trace) | 56.01 B/op: the one `CallableTask` |
| `taskFolded` | 56.0007 B/op | 56.01 B/op |
| `taskUndrawn` | 56.0005 B/op | 56.01 B/op |
| `executorSubmit` | 56.0005 B/op (400 B per trace) | 56.01 B/op |
| `executorCall` | 56.0005 B/op (400 B per trace) | 56.01 B/op |
| `executorCallableTask` | 56.0005 B/op (400 B per trace) | 56.01 B/op: the `CallableTask` itself |
| `across` | 24.0014 B/op (630 B per trace, both threads) | 24.01 B/op; 1,024 B per trace |
| `acrossUndrawn` | 28.0001 B/op (630 B per trace, both threads) | 28.01 B/op; 1,024 B per trace |
| `traceLifecycle` | 424 B per trace (2026-11-20: the root `Trace` holds the trace's scope) | 1,024 B |
| `spanInBlock` | 0.0002 B/op (552 B per trace) | 0.01 B/op |
| `stepUndrawnInBlock` | 0.0002 B/op | 0.01 B/op |
| `nestedTrace` | 128.0003 B/op (424 B per trace) | 128.01 B/op: the one `Trace` |
| `snapshotToHeader` (300 spans) | 5,304 B per render | 6,144 B |
| `toHeader` (300 spans) | 22,712 B per render | 24,576 B |
| `snapshotWriteJson` (300 spans, into a reused builder) | 1,960 B per render | 2,560 B |
| `snapshotToHeaderLadder` (300 spans, 1,000 bytes) | 57,745 B per render | reported |
| `snapshotToLinkLadder` (300 spans, 1,000-byte link) | 64,332 B per render | reported |

The 300-span render writes a value of about 3,440 characters (its times vary by run). The encoder alone
(`snapshotToHeader`) allocates the output `String` (about 3.5 KB) and about 1.8 KB of
tables sized to the trace's own names and threads, plus walk state; its builder is reused
per thread. `toHeader` also takes the snapshot, which copies the trace's rows, events and
aggregates (about 17 KB here), so it is above "one output String". Rendering straight from
the live lanes would avoid that copy, at the cost of a second walker that repeats the
snapshot's consistency handling, including its race with `release()`.

This is an accepted deviation (2026-11-14): a render happens once per request, not per span,
and its ceiling is the 24,576 B gate above. The recording path stays at 0 B per span.

`snapshotWriteJson` writes the JSON form into the caller's builder, so it allocates no
output at all: only the encoder's tables and walk state (the same two walks as the header).
Any other `Appendable` receives the text in pieces from the thread's reused builder.

A value over its limit goes down the ladder of FORMAT.md §7.1, which reads the whole value
back into per-record arrays (about 40 KB for 300 records) and re-encodes after every step
into the same builder; a link limited as a whole also compresses each step with one
`Deflater`, ended after the render. Neither is gated: only a value over its limit pays.

## Times

Apple M3 Max, macOS, Amazon Corretto 24.0.2, JMH 1.37, one fork, 3 × 1 s warm-up,
5 × 1 s measurement, `cycle=1000`, 2026-11-19. The rows `baselineNanoTime`, `span`,
`spanStatic`, the ticks, `phase`, `done`, `setLong`, `executorCall`, `executorCallableTask`,
`across`, `acrossUndrawn` and `traceLifecycle` are from a later run of the same day, after the
review fixes. The ratios in the promise table above come from the `./gradlew jmhGate` run of
that day (`baselineNanoTime` 10.0 ns there).

| Benchmark | ns/op | Note |
|---|---|---|
| `baselineNanoTime` | 9.8 ± 0.8 | one `System.nanoTime()` |
| `span` | 36.4 ± 1.6 | open + close through a span handle, two clock reads |
| `spanStatic` | 37.4 ± 2.6 | `Tempo.span(name).close()`: the same, parent found through the `ThreadLocal` |
| `spanNotInlined` | 36.4 ± 3.3 | `span` with the open call kept out of line |
| `run` | 36.4 ± 5.6 | `Tempo.run(name, body)` around an empty body |
| `stepDrawn` | 36.4 ± 3.5 | a step in a trace that records steps: a row, like a span |
| `stepUndrawn` | 27.8 ± 4.3 | a step in a trace of spans only: two clock reads and its totals, no row |
| `folded` | 28.9 ± 1.3 | a step past the budget: two clock reads and an aggregate update |
| `tickUnrecorded` | 1.40 ± 0.06 | `s.tick(name)` in a trace of spans only, opened and closed: a branch |
| `tickStatic` | 1.87 ± 0.06 | `Tempo.tick(name)` in a trace of spans only, opened and closed: the `ThreadLocal` read and the branch |
| `tickNoTrace` | 1.26 ± 0.02 | `Tempo.tick(name)` without a trace, opened and closed |
| `phase` | 23.1 ± 1.2 | one boundary: the open phase ends and the next starts at one clock read |
| `done` | 17.5 ± 0.3 | a phase named after the fact, from the last boundary to now |
| `setLong` | 3.9 ± 0.3 | a long attribute |
| `add` | 3.7 ± 0.1 | `s.add(name, nanos)`: a duration into the totals |
| `task` | 22.1 ± 1.7 | submit side of a drawn task: its record and the one `Task` |
| `taskInline` | 67.7 ± 19.7 | a drawn `RunnableTask` run on the submitting thread: submit, begin, close |
| `callableInline` | 68.1 ± 3.7 | the same for a `CallableTask` |
| `supplierInline` | 66.2 ± 7.2 | the same for a `SupplierTask` |
| `taskFolded` | 65.5 ± 15.0 | the same past the budget: no row, the task folded |
| `taskUndrawn` | 49.5 ± 11.9 | the same in a trace of spans only: no record at the submit, its totals |
| `executorSubmit` | 68.9 ± 4.7 | `submit(Runnable)` to `Tempo.executor` over a pool that runs it at once |
| `executorCall` | 71.6 ± 3.2 | `submit(Callable)` to the same |
| `executorCallableTask` | 69.9 ± 7.1 | `submit` of a `Tempo.callable` task to the same: the task itself goes to the pool |
| `across` | 156 ± 40 | a drawn task submitted on one thread and begun and closed on another, through a ring; per op of either side |
| `acrossUndrawn` | 152 ± 47 | an undrawn `RunnableTask` submitted on one thread and run on another, through a ring; per op of either side |
| `cpuTime` | 474 ± 47 | one read of the thread's CPU time, as taken at lane roots and by `Span.cpu()` |
| `traceLifecycle` | 99 ± 7 | start, close, release; the root starts as `trace` is entered, so its time includes this setup |
| `snapshotToHeader` | 14,829 ± 1,761 | encoder alone, 300 spans |
| `snapshotToHeaderLadder` | 47,118 ± 894 | the same under 1,000 bytes: the value read back, the largest group (the root's 30 children) folded, re-encoded |
| `snapshotToLinkLadder` | 90,054 ± 7,929 | a link of at most 1,000 bytes: each step also compressed |
| `toHeader` | 16,691 ± 1,055 | snapshot and encoder, 300 spans |
| `snapshotWriteJson` | 50,500 ± 10,109 | the JSON form of the same snapshot: about 24,200 characters, seven times the header value |

The load average of the machine was high during these runs (other builds ran beside them), so
the errors are wider than on an idle machine; the gated ratios use medians for that reason.
