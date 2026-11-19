# Tempo

Tempo is a small phase-profiling library for Java. It records where the time of one request
or job goes (nested spans, the phases of a span, work handed to thread pools with its queue
wait) cheaply enough to stay switched on in production. It renders the result as response
headers, as JSON, or as a link that opens the trace in a web viewer.

- **One source file**, `Tempo.java`, Java 11 or newer, no runtime dependencies. An optional
  second file, `TempoJackson.java`, serializes traces with Jackson.
- **No allocation while recording.** Spans go into per-thread primitive buffers, and their
  handles are pooled per thread. A recorded span costs about 37 ns (two clock reads), a tick
  the trace does not record about a branch, and a task allocates one object: itself. See
  [BENCHMARKS.md](BENCHMARKS.md).
- **A compact wire format**, `Tempo-Timing`, specified in [FORMAT.md](FORMAT.md): a whole
  request tree with threads, queue waits, CPU time, phases, attributes and flags in one header
  line, kept to a byte limit by folding.
- **Two viewers**: `tools/tempo` in the terminal and **Tempo Timeline** in the browser at
  <https://dualuse.github.io/tempo/>. Neither sends a trace anywhere.

```
$ curl -si https://api.example.org/search?q=lamp | tempo --tree
search  ·  31.600 ms  ·  2026-11-19 05:56:18.513 UTC  ·  5 spans and 2 phases on 3 threads
span                   start   wait     dur    self    cpu  off-cpu  thread  flags  attributes
search                 0.000         31.600  20.600  1.500   30.100  main
├─ :decode             0.000         20.000                                  p
├─ :rerank            20.000         11.600                                  p
├─ load               20.000          3.000   3.000                  main           bytes=48213
├─ match              23.000  0.400   7.600   7.600  7.500    0.100  cpu-1
├─ match              23.000  0.900   5.300   5.300  5.200    0.100  cpu-2
└─ join               23.100          8.000   8.000                  main    w
```

## Status

Pre-release (0.1.0). The recording API, the wire format v1, the JSON form and both viewers
are complete and tested; the API may still change before 1.0. There is no Maven Central
artifact yet: take the jar from a GitHub release, build it (`./gradlew jar`) or copy
`src/main/java/io/github/dualuse/tempo/Tempo.java` into your source tree.

## Quick start

```java
import io.github.dualuse.tempo.Tempo;

static final ExecutorService MATCH = Tempo.executor(pool, "match");   // every submit is a "match" task

Tempo.Trace t = Tempo.trace("search").records(Tempo.STEP);   // draw steps and tasks, not only count them
try (t) {
  t.phase("decode").label("Decode Input");          // a phase: a section of the root's own timeline
  Query q = decode(body);
  t.phase("rerank").label("Rerank");                // ends decode and starts rerank at one clock read
  try (Tempo.Span s = Tempo.span("load")) {         // a child of the thread's innermost open span
    s.set("bytes", load(q));
  }
  List<Future<Score>> scores = new ArrayList<>();
  for (Candidate c : candidates) scores.add(MATCH.submit(() -> match(q, c)));   // queued here, run there
  try (Tempo.Span join = Tempo.span("join").waiting()) {   // a blocking wait
    for (Future<Score> f : scores) f.get();
  }
}                                                    // closing the trace ends rerank and the root
response.setHeader("Runtime", t.toRuntime(3));       // 0.032s = 0.020s (Decode Input) + 0.012s (Rerank)
response.setHeader("Server-Timing", t.toServerTiming());
response.setHeader("Tempo-Timing", t.toHeader());   // the whole tree, see FORMAT.md
t.release();                                         // optional: the buffers go back to the pool
```

No method takes a trace or a span as a parameter. `Tempo.span(name)` opens a span under the
innermost open span of the calling thread, so code deep in a call stack is instrumented the
same way as the code that started the trace. Recording never throws into the caller: misuse
(a span used on the wrong thread, closed twice, left open) is healed and counted, and
`Options.strict(true)` turns it into an `AssertionError` for tests.

### Traces and spans

`Tempo.trace(name)` starts a trace on the calling thread; `Tempo.trace(name, options)` takes
`Options`. The `Trace` is the trace's root span. It starts the moment `trace` is entered, at 0
in every reading of the trace, so Tempo's own setup is part of the root's time (and of the
totals of `Runtime` and `Server-Timing`) as time no child covers, never hidden. Closing the
trace ends the root, and any span still open, at that instant. The trace stays readable until
`release()`.

Try-with-resources is the usual form; `Tempo.run` and `Tempo.call` are the compact one:

```java
try (Tempo.Span s = Tempo.span("parse")) {          // closed on every path out of the block
  s.set("bytes", body.length);
  parse(body);
}
Tempo.run("validate", () -> validate(request));     // a span around a body
Plan p = Tempo.call("plan", () -> plan(request));   // the same, with a result
```

`run` and `call` flag the span failed when the body throws, and rethrow the throwable
unwrapped. Built with `-Xlint`, a try-with-resources whose span is not used inside the block
warns (`try`); `@SuppressWarnings("try")` on the method silences it, or `run` and `call` avoid
it.

To annotate the innermost open span from deep in a call stack, `Tempo.current()` returns it,
as in `Tempo.current().set("hits", n)`. Outside a trace it returns `Tempo.NOOP`, a handle that
records nothing, or, while a span or step timed only for a global listener is open (see below),
that span, which keeps no attributes. That is the only static way to reach a span: there is no
static `set`, `fail` or phase.

**Names.** `Tempo.name("parse")` interns a name. Kept in a `static final` field and passed
instead of the string, it is the fast path, with no lookup per span. Every method that takes a
`Name` also takes a `String`, looked up in the same global table. Names are constants; dynamic
data belongs in attributes. Past 4,096 names made from strings, a new string maps to
`<other>`, counted as misuse. `Tempo.span()` opens an unnamed span, labelled only by its
phases.

**Where a span opens.** `Tempo.span(name)` opens under the innermost open span of the thread;
`s.span(name)` opens under `s`. If other spans are still open above `s`, they are closed first,
flagged healed, and the misuse is counted. Outside a trace, `Tempo.span`, `step` and `tick` cost
one `ThreadLocal` read and return `Tempo.NOOP`, unless the name of a span or step has a global
listener (see [Totals and listeners](#totals-and-listeners)), which then hears its duration. A trace
started inside another trace joins it: its root is a span under the innermost open one, and
the inner `Trace` renders its own subtree. A trace left open on a pool thread therefore takes
in the later traces of that thread, so close every trace.

### Spans, steps and ticks

Three words open a span, and the word says what is measured:

| Word | Timed | Drawn |
|---|---|---|
| `span` | always | always |
| `step` | always | when the trace records steps |
| `tick` | only when the trace records ticks | when the trace records ticks |

A span is always drawn, a step is always counted, a tick exists only on request. Work whose
total is wanted in production, such as one item of a batch, is a `step`; inner-loop work is a
`tick`. A trace records spans; `t.records(Tempo.STEP)` or `t.records(Tempo.TICK)` widens it,
once, before the first step or tick opens and before the first task is submitted. Later it is
misuse and changes nothing (strict mode throws); `t.records()` reads the level.

```java
Tempo.Trace t = Tempo.trace("import");
if (verbose) t.records(Tempo.TICK);
try (t) {
  for (Row row : rows) {
    try (Tempo.Span s = Tempo.step("row")) {         // always in t.total("row"); drawn only when verbose
      s.set("cells", row.size());
      for (Cell c : row) {
        try (Tempo.Span k = Tempo.tick("cell")) {    // without TICK: Tempo.NOOP, about a branch
          convert(c);
        }
      }
    }
  }
}
```

A step the trace does not draw costs its two clock reads and an update of its totals, and
writes no row; a tick the trace does not record costs about a branch. `recording()` is false
on `Tempo.NOOP`, so `if (k.recording())` guards work done only for a tick that records.

**Budget and folding.** A trace draws up to `Options.budget(n)` steps, ticks and tasks
individually (512 by default). Past the budget they fold: occurrences of the same name under
the same parent go into one aggregate with count, sum, min, max, extent, queue wait sum and
max, CPU time, the thread (or that there were several) and the flags all of them share. A hot
loop of thousands of iterations costs one aggregate, not thousands of rows, and the header says
how many were folded. Folding changes what is drawn, never what `total` returns.

**CPU time.** Thread CPU time is measured for each lane root (the trace's root and every task
on a pool thread) when the trace records steps, or as `Options.cpu(true|false)` says;
`span.cpu()` asks for it on one more span. The first CPU read in a JVM sets the clock up, which
takes milliseconds: a trace pays it on the thread that starts it or widens it with `records`,
in the root's time, never in a task's queue wait. A task reads its start as soon as it wins
its begin, before it sets up its thread's lane or reads its CPU time, so its queue wait is time
in the queue only.

### Phases

A phase is a labelled section of its span's own timeline. It is not the parent of anything:
spans opened during a phase stay children of the span, and may cross phase boundaries.

```java
try (Tempo.Span s = Tempo.span("startup")) {
  s.phase("init");                                   // starts now
  init();
  s.phase("loading");                                // ends init and starts loading at one clock read
  try (Tempo.Span f = Tempo.span("fetch")) {         // a child of startup, not of loading
    fetch();
    s.phase("warm-up");                              // fetch runs on across the boundary
    warmUp();
  }
}                                                    // closing the span ends warm-up
```

Run in a trace named `boot`, it is drawn like this (`tools/tempo --ascii --width 72`): the
phases on their span's bar, with the span's name in front of it. Here the span starts with the
trace, so its name stands in a name column left of the time axis.

```
                     ms 0                 10                20
                        +-----------------+-----------------+-----------
main                    |-------------------- boot --------------------|
                startup |init-|------ loading ------|---- warm-up ----|
                                     |------- fetch --------|
```

- `s.phase(name)` starts a phase at its call; the time before the first phase, and after
  `done()`, is unphased time of the span (dotted in the terminal, a lighter fill in Tempo
  Timeline). A phase right after a phase shares one clock read.
- `s.done()` (or its synonym `s.lap()`) ends the open phase; no phase opens in its place.
- `s.done(name)` ends the open phase, which must be `name`. With another phase open, that one
  ends anyway under its own name, flagged, and the misuse is counted (strict mode throws). With
  no phase open, it names the stretch since the last boundary, as a stopwatch does:

  ```java
  Tempo.Span s = Tempo.current();
  read();
  s.done("read");                                    // the time since the span's start
  transform();
  s.done("transform");                               // the time since the last boundary
  ```

- `phase(name)` returns the `Phase`. `set`, `fail` and `label` apply to the phase; `phase`,
  `lap` and `done` on it continue its span's sequence, and `done()` returns the span.
- `label("Decode Input")` is the text the `Runtime` and `Server-Timing` headers print. They list
  the labelled phases of the trace's root, as in the quick start.

Phases count towards the totals and listeners of their name, also on a step the trace does not
draw. The viewers report a phase's own start and duration only: no self time, no children.

### Timing by hand

Time measured elsewhere goes into a trace in two ways. `s.add(name, nanos)` adds one
occurrence of `name` lasting `nanos` to the trace's totals and calls the name's listeners; no
span is drawn. Any thread may call it, also after `s` closed.

A span can also be timed from clock readings taken elsewhere (`System.nanoTime()`, or the
clock of `Options.clock`), with `start(t)`, `lap(t)` and `stop(t)` on the span or its phase:

```java
Tempo.span("upload").phase("connect").start(t0).lap(t1).phase("send").stop(t2).close();
```

Each time must lie in its window: from the parent's start to now, laps in order, the stop not
before the last boundary nor before the end of a child. A time outside is clamped into it, the
span is flagged, and the misuse is counted (strict mode throws). A span timed by hand stays the
thread's innermost open span until `close()`. A child still open at the stop, or opened after
it, is misuse too, and the span then ends with that child, so a span always holds its
children. The only lanes are threads: there are no named or pseudo-lanes.

### Tasks and thread pools

Work submitted on one thread to run on another is a task. It records its queue wait (from the
submit to its begin) and its run, as a span on the thread that ran it. Like a step, a task is
always counted: its run time and queue wait go to the totals and listeners of its name. It is
drawn when the trace records steps, against the budget; otherwise spans opened inside it are
drawn under the span it was submitted from, on the thread that ran it.

The simplest way to make tasks is an executor:

```java
static final ExecutorService MATCH = Tempo.executor(pool, "match");

Future<Score> f = MATCH.submit(() -> match(q, c));   // a "match" task under the innermost open span
```

Every submit becomes a task of the executor's name, handed to `pool.execute`. The task is
itself the `Future` that `submit` returns, so a submit allocates one object; `invokeAll` and
`invokeAny` make one task per element in the same way. A submit the pool refuses is recorded
as rejected, and the `RejectedExecutionException` is thrown on.

Without such an executor, `Tempo.runnable`, `Tempo.callable` and `Tempo.supplier` make the
task where the work is submitted. Each is the `Runnable`, `Callable` or `Supplier` itself, and
a runnable or callable task is also its own `Future`:

```java
Tempo.RunnableTask index = Tempo.runnable("index", () -> index(doc));
pool.execute(index);                                 // index is also the Future
CompletableFuture<Page> page = CompletableFuture.supplyAsync(Tempo.supplier("render", () -> render(doc)), pool);
```

A task's parent is the innermost open span of the thread that made it. One that runs on the
submitting thread (caller runs) becomes a plain child. `set` and `fail` on a task apply to its
record at the submit. Its `startNanos()` is its submit and `waitNanos()` its queue wait, which
is also its `elapsedNanos()`: the task object times its wait, its span times its run. A task
submitted to `Tempo.executor` goes in as it is, under its own name, and is the future
returned. A task's body runs at most once through `run()`, as a `FutureTask`'s; a second
`call()` or `get()` of a callable or supplier task is misuse and calls the body again,
unrecorded. A null body is misuse and does nothing.

**Cancelling and early exits.** Begin and cancel race for a task, and exactly one of them
wins, inside a trace or not, with no lock and no help from the executor. `task.cancel()`
returns `true` when the body will not run; the task is then recorded as cancelled before it
began (flag `c`, no run time). `Future.cancel` on a task cancels the task first; once the body
runs, it acts as `FutureTask.cancel` does, and the task's span is marked cancelled. So an early
exit is:

```java
for (Future<Score> f : scores) f.cancel(false);     // the tasks not begun never run
```

A task neither begun nor cancelled when the trace renders shows as never started (`n`).
Cancel pending work before releasing the trace.

**Hand-written workers.** `Tempo.task(name)` is a task without a body, for a worker that takes
work from a queue of its own. The worker begins it with `tryBegin()`, which returns `null` when
the task was cancelled or rejected first:

```java
Tempo.Task task = Tempo.task("parse");               // where the work is queued
queue.add(new Job(doc, task));

Tempo.Span s = job.task.tryBegin();                  // on the worker
if (s == null) return;                               // cancelled before it began: recorded so
try (s) {
  parse(job.doc);
}
```

`task.reject()` records that a queue refused the task; it returns like `cancel()`.

### Attributes and flags

```java
Tempo.Span q = Tempo.span("query");
try (q) {
  Rows rows = query();
  q.set("rows", rows.size()).set("ratio", rows.hitRatio());   // long and double attributes
} catch (TimeoutException e) {
  q.fail(e);                                         // after close: only the flag is recorded
  throw e;
}
```

`set`, `fail()` and `cancel()` also work after the span closed, as in the catch block above,
until the trace is released. A step folded past the budget or not drawn has no row: it keeps no
attributes, and flags only while it is open. `cancel()` on a span marks its work abandoned.
`waiting()` marks a blocking wait (a join, a `take`, a `get`), which the viewers draw apart
from run time and follow across threads on the critical path.

### Totals and listeners

A trace keeps, per name, the count, sum, minimum and maximum of everything timed under that
name: spans, steps, recorded ticks, phases, tasks and `s.add`, and the queue waits of its
tasks. The numbers are the same whether the trace drew the steps, folded them past the budget,
or recorded spans only.

```java
Tempo.Total m = t.total("match");    // count(), sum(), mean(), min(), max(), waitSum(), waitMax()
log.info("match: {} tasks, {} ns mean, {} ns longest queue wait", m.count(), m.mean(), m.waitMax());
```

A listener hears every timed occurrence of its name, in nanoseconds, on the thread that ends
it, without a lock, so it must be thread-safe. `Tempo.on` adds a global one, set once at
start-up; `t.on` adds one for a single trace:

```java
Tempo.on("store", nanos -> storeLatency.record(nanos));   // every trace, and outside traces
t.on("match", nanos -> matchTimes.add(nanos));            // this trace only
```

Outside any trace, a span or step whose name has a global listener is still timed for it,
with two clock reads and no allocation. So `Tempo.run("store", () -> store(x))` serves as a
stopwatch for a metric, inside a trace or not.

`t.printOnClose(log::info, "import")` passes `import: ` and the `Runtime` text to the logger
when the trace closes, as a drop-in for a stopwatch log line.

### Handles, misuse and strict mode

A `Span` or `Phase` handle belongs to the thread that opened it. Each recorded span has a
handle of its own for the whole trace, so `set`, `fail` and `cancel` after close reach that
span. Once the trace is released, the thread pools the handle and later issues it to a span of
another trace. A call through it while it sits in the pool is ignored and counted as misuse; a
call after it was issued again reaches the new span, undetected. So keep a handle inside the
block that opened it. Steps folded past the budget or not drawn share one handle per depth.

`Span`, `Phase` and `Task` share a base type, `Timing`: each has a `name()`, a `startNanos()`
(from the trace's start) and an `elapsedNanos()`, and takes `set` and `fail`.

Misuse, such as a span used on another thread, after close or after release, a span opened
under one with others open above it, a `done(name)` that names another phase, a clamped time,
or `records` called too late, is healed and counted, and the record is flagged `m` where there
is one. Outside strict mode Tempo never throws; a task cancelled before it began still throws
`CancellationException` from `call()` or `get()`, as its `Future` does, since it has no result.
`Options.strict(true)` throws an `AssertionError` at the offending call instead, and never pools
or shares a handle, so every stale use throws: use it in tests.

### Options

| Option | Default | Sets |
|---|---|---|
| `budget(n)` | 512 | Steps, ticks and tasks drawn individually before they fold. |
| `cpu(on)` | when the trace records steps | Whether lane roots measure thread CPU time. |
| `headerBytes(n)` | 3,000 | Byte limit of `toHeader()` and of the value `toLink()` carries. |
| `linkBase(url)` | the build's base | The viewer `toLink()` points at. |
| `clock(supplier)` | `System::nanoTime` | The nanosecond clock of every timestamp; for tests. |
| `lanes(n)` | 256 | Lanes (one per thread) of one trace. |
| `strict(on)` | off | `AssertionError` on misuse, no pooled handles. |

### Reading a trace

`forEachSpan(visitor)` walks the stitched tree with a reused read-only `SpanView` (start,
duration, thread, queue wait, CPU, flags, phase and label, attributes, aggregate statistics);
`forEachTop(visitor)` visits the labelled phases of the root. `snapshot()` takes an immutable
copy that stays readable after `release()`, for rendering later or on another thread.

## Demos

`src/demo` holds eleven small programs that use Tempo the way an application does, graded
simple, medium and complex: a first trace, phases, failures, steps and ticks on request,
thread pools with queue wait and CPU time, totals and listeners, headers under a byte limit,
and services with several pools, pipelines, fan-out, deadlines and early exits. They need
nothing but the library and start no servers. Each one checks what it claims about its own
trace. See [src/demo/README.md](src/demo/README.md) for the reading order.

```
./gradlew demo                                      # the list
./gradlew -q demo -Pdemo=FirstTrace                 # one demo: its story, then its response headers
./gradlew -q demo -Pdemo=WorkerPool | tools/tempo   # the trace drawn as lanes in the terminal
```

## Headers

| Header | Method | Content |
|---|---|---|
| `Runtime` | `toRuntime(digits)` | `0.032s = 0.020s (Decode Input) + 0.012s (Rerank)`: the labelled phases of the root, in seconds |
| `Server-Timing` | `toServerTiming()` | `decode;dur=20.000, rerank;dur=11.600, total;dur=31.600`: the same phases by name, W3C, in milliseconds, shown by browser dev tools |
| `Tempo-Timing` | `toHeader()`, `toHeader(maxBytes)` | the whole tree, see below |
| `Tempo-Link` | `toLink()`, `toLink(base, maxBytes)` | a URL that opens the trace in Tempo Timeline |

The `Tempo-Timing` value of the quick-start request:

```
v1;w=mwn50afl;T=main:cpu-1:cpu-2;N=search:decode:rerank:load:match:join:bytes;S=00.0.ods*15o/11.0.ffk^p/12.ffk.8y8^p/13.0.2bc:6=48213/14.2bc.5v4@1~b4*5sc/14.0.438@2~p0*40g/15.2s.668^w;e=7
```

One record per span or phase, in pre-order: depth, name, start and duration (base 36, in
microseconds), then thread (`@`), queue wait (`~`), CPU time (`*`), attributes (`:`) and
flags (`^`; `p` marks a phase). The format, its error codes and its test vectors are
specified in [FORMAT.md](FORMAT.md).

**Size limits.** `toHeader()` keeps to `Options.headerBytes` (3,000 bytes by default),
`toHeader(maxBytes)` to a limit per call. A value that does not fit goes down a ladder
([FORMAT.md §7.1](FORMAT.md)) until it fits: repeated siblings fold into aggregates (failed
ones and the slowest kept), then the deepest levels go, then the attributes, then everything
below the root's children. What is left out is counted in the value (`x=`). When the timing
headers share one limit, render the others first and pass on what is left, in bytes:

```java
String runtime = t.toRuntime(3), timing = t.toServerTiming();
int left = 3000 - runtime.getBytes(UTF_8).length - timing.getBytes(UTF_8).length;
response.setHeader("Tempo-Timing", t.toHeader(left));    // or t.toLink(null, left) for a Tempo-Link
```

**Tempo-Link.** `toLink()` gives
`https://dualuse.github.io/tempo/#t=z.<base64url(deflate(value))>`, about 0.7 times the length
of the value. The trace travels in the URL fragment, which the browser never sends to the
server: one click in a terminal opens it, and nothing is uploaded ([FORMAT.md §9](FORMAT.md)).

**Link base, and forks.** The jar links to the viewer of the repository it was built from.
The build bakes the base into the jar (the resource `io/github/dualuse/tempo/link-base.txt`
and the manifest attribute `Tempo-Link-Base`), from the first of:

1. `-PlinkBase=URL`,
2. `GITHUB_REPOSITORY` (`owner/repo`, set by GitHub Actions): `https://<owner>.github.io/<repo>/`,
3. the git remote `origin` on github.com, for local builds of a clone,
4. `https://dualuse.github.io/tempo/`, also used by a copy of `Tempo.java` built elsewhere.

The `Release` workflow (`.github/workflows/release.yml`) builds the jar on GitHub Actions and
delivers it: a pushed tag `v<version>` attaches it to a GitHub release, and every run keeps it
as a workflow artifact. It names no owner, so a fork's release links to the fork's own viewer
without any change. The viewer is there once the fork turns on Pages (Settings → Pages,
Source: GitHub Actions) and pushes to `master` or runs the `Pages` workflow by hand. At run time,
`-Dtempo.linkBase=URL` sets the base for a JVM, `Options.linkBase(url)` for one trace and
`toLink(base)` for one link, for instance a self-hosted copy of `site/index.html`.

## JSON in a response body

`writeJson(appendable)` writes the JSON form of [FORMAT.md §8](FORMAT.md#8-json-form): what
the whole `Tempo-Timing` value decodes to, with no size limit, as one line. A response
object holds a `Snapshot`, taken while the response is built, never the `Trace`, which may be
released before the body is serialized.

```java
StringBuilder body = new StringBuilder();
t.snapshot().writeJson(body);       // {"v":1,"unit":"us",...,"threads":["main","cpu-1"],"spans":[...]}
```

With Jackson (2.x, `compileOnly` here, so bring your own), add `TempoJackson.java` and
register its module, or annotate the field. It writes the same structure through
`JsonGenerator`, so CBOR, Smile and MessagePack work too:

```java
class SearchResponse { public List<Hit> hits; public Tempo.Snapshot tempo; }

response.tempo = t.snapshot();
new ObjectMapper().registerModule(TempoJackson.module()).writeValueAsString(response);
// or: @JsonSerialize(using = TempoJackson.SnapshotSerializer.class) public Tempo.Snapshot tempo;
```

Both viewers read a tempo object anywhere in a JSON document, such as a whole response body.

## Command-line viewer: `tools/tempo`

`tools/tempo` reads a trace and draws it in the terminal. It is one Python file that needs
Python 3.8 or newer and nothing outside the standard library. Install it by copying it onto
your `PATH`:

```
cp tools/tempo ~/.local/bin/ && chmod +x ~/.local/bin/tempo
```

```
curl -si "$URL" | tempo                  # the Tempo-Timing line of curl -i output
pbpaste | tempo --tree                   # a value copied from a log or a terminal
tempo response.json                      # JSON with a tempo object anywhere (e.g. under "info")
tempo before.header --diff after.header  # what changed between two requests
tempo --stats traces/*.header            # p50/p95/max per path over many requests
tempo 'https://dualuse.github.io/tempo/#t=z.…'  # a Tempo-Link, as an argument
tempo --open --critical trace.header     # the same trace in the web viewer
```

**Input.** Files, stdin or link arguments, in any of these forms:

- `curl -i` output. The `Tempo-Timing:` or `Tempo-Link:` line is found case-insensitively,
  and a value that a proxy folded or a terminal wrapped over several lines is joined again.
  When one response has both headers, `Tempo-Timing` wins; a warning on stderr says when the
  link carries a different trace (and the link is used when `Tempo-Timing` is rejected).
- A bare header value starting with `v1;`, also embedded in a log line. Whitespace and line
  breaks inside the value are ignored.
- A Tempo-Link URL (anything with `#t=`, or `?t=z.…` in the query), also embedded in a log
  line or wrapped over several lines, or given directly as an argument instead of a file
  name. Several values or links in one input are several traces, as for `--stats`. A link may
  carry a second trace (`&c=`); `--diff` without `OTHER` compares with it.
- A JSON document holding a tempo object (`"v": 1` and `"spans"`) at any depth. When a
  response has both the header and such a body, the body wins, because it is not
  size-limited.

Quote a value or link you type on the command line, or pipe it: `;`, `*`, `~`, `^` and `&`
are shell syntax.

**Default view: lanes.** One row group per thread, grouped into pools by stripping a trailing
`-<digits>` from the thread name (`cpu-1`, `cpu-2` form the pool `cpu`; `Thread-N` threads
stay ungrouped). Nested spans stack into rows below their parent. A span is a bar,
`├── name ──┤`, its phases drawn inside it with the span's name in front; when a bar starts
too near the left edge for its name, the chart gets a name column. A task that queued while
its thread was still busy with earlier work of the request moves to a free row of that lane,
so its queue wait stays visible.

```
search  ·  31.600 ms  ·  2026-11-19 05:56:18.513 UTC
5 spans and 2 phases on 3 threads
                   ms 0                   10                   20                   30
                      ┬───────────────────┬────────────────────┬────────────────────┬───
main           search ├──────────────── decode ─────────────────┼─────── rerank ───────┤
                                                                ├load┤
                                                                      ├╌╌╌╌ join ╌╌╌╌╌┤
cpu
  cpu-1                                                               ░├─── match ────┤
  cpu-2                                                               ░░├─ match ─┤
  queued ≤2                                                           █▅
```

| Glyph | Meaning |
|---|---|
| `├─ name ─┤` | A span's run time, its name centred (`\|- name -\|` with `--ascii`). |
| `┼` | A phase boundary inside a span's bar; each phase's name is centred in its section. |
| `·` | Unphased time of a span that has phases. |
| `*1` | A phase too narrow for its name, named in a footnote line under the lanes. One too narrow even for that has its `*1` after its span's name; where many such follow one another, a range such as `*4–*13` stands for them. |
| `░` | Queue wait of a task, drawn dim in front of its run. |
| `├╌╌┤` | Blocking wait (join, future get), flag `w`. |
| `▚` with `×N` and `┃` | Aggregate of N folded spans over its extent; `┃` marks where the slowest one started. |
| `✗` `⊘` `▶` `∅` `!` | Failed (also red), cancelled, still open at render, never started, misuse. |
| `●` | Lane still live at render (`l=`); its records are best effort. |
| `(mixed)`, `(queued)` | Aggregates on several threads of a pool, and tasks that never ran. |
| `queued ≤N` | Per pool: how many of this request's tasks waited in the queue over time. |

The time axis is in milliseconds. The legend follows the chart, then notices for folded or
dropped spans (`x=`), live lanes (`l=`) and cut values.

**Options.**

| Option | Shows |
|---|---|
| `--tree` | Indented tree with start, queue wait, duration, self time, CPU, off-CPU and attributes; a phase is listed as `:name` under its span. |
| `--agg` | Per path: count, sum, self, min, max, wait sum, wait max, CPU; a phase is a row `span:name` with count and durations only. |
| `--pools` | Per pool, over time: this request's running and queued tasks, and the `queued`/`active` gauges recorded at submit (the pool-wide signal). A table gives tasks, threads seen, run, wait, CPU, off-CPU and peaks. |
| `--findings` | Stragglers (ending after their parent), never-started and open tasks, failures, cancellations, the longest queue waits, and off-CPU time on compute pools. |
| `--critical` | The chain that determined the end time. It walks back from the root's end, always into the child that finished last, and follows tasks to other threads instead of the blocking wait on them. On an early exit, where the wait ends while the work still runs (an aggregate, or a straggler with nothing else ending within 1 ms of the wake-up), it follows the work running at the wake-up. Each hop shows its queue wait and run time; totals per pool follow. |
| `--diff [OTHER]` | Per path, B (OTHER, a file or link) minus A (the input): Δcount, Δsum, Δself, Δwait, ΔCPU, sorted by the largest absolute change. Without OTHER, B is the `&c=` trace of the input link. |
| `--stats FILES...` | Over many traces (also several per file, such as a log): per path, the number of traces containing it, the median count, and p50/p95/max of sum, self and wait (nearest rank). |
| `--to json` | The JSON form of FORMAT.md. |
| `--to chrome` | Chrome trace events for [Perfetto](https://ui.perfetto.dev) or `chrome://tracing`: one track per thread, queue waits and phases as async slices, tasks as flow arrows. |
| `--to folded` | Collapsed stacks (`a;b;c <µs>`) for flame-graph tools. Values are self times; queue waits are left out. |
| `--to link` | The Tempo-Link URL of the trace (`z.` form; an input link's `&c=` is kept). JSON input is encoded into a header value first. |
| `--check` | Validate only. Exit status 1 on a malformed or cut value, with the error code of FORMAT.md. |
| `--threads REGEX` | Keep spans on threads whose name matches (`cpu-*` stands for a mixed aggregate). |
| `--focus PATH` | Keep the subtree at a path such as `load` or `startup/fetch`; the lanes zoom to it. |
| `--depth N` | Keep at most N levels below the root or focus. |
| `--min-ms X` | Keep spans of at least X ms, with their subtrees. |
| `--open` | Write the trace's Tempo-Link to stdout and open it in the local browser. |
| `--base URL` | The viewer for `--to link` and `--open` (also `$TEMPO_LINK_BASE`). |
| `--width N` | Output width (default: the terminal, or `$COLUMNS` when piped; at least 40). |
| `--no-color`, `--color`, `--ascii` | No colours, colours into a pipe, no Unicode glyphs. |

The filters apply to every view except the converters and `--critical`. Self time is the
duration minus the children that ran nested on the same thread, blocking waits included;
children on other threads are never subtracted. Off-CPU time is duration minus CPU time,
where CPU was measured. Colours are off when output is not a terminal, when `NO_COLOR` is
set, or when `TERM=dumb`.

**Web viewer from the terminal.** `--open` uses Python's `webbrowser` module. With `--diff`
(or an input link with `&c=`) the link holds both traces; with `--tree`, `--agg`, `--pools`,
`--critical` or `--diff` it opens on that tab. Without `--base` or `$TEMPO_LINK_BASE`, a
`tools/tempo` run from a checkout of Tempo takes the GitHub Pages site of the checkout's
remote `origin`, as the library build does, so a fork's clone opens the fork's viewer;
elsewhere the default is `https://dualuse.github.io/tempo/`. The trace stays in the URL
fragment: the CLI sends nothing anywhere, though the link ends up in the browser history.

**Layout.** Every view fits its lines to the width. The lanes and charts need at least 40
columns. Tables never cut a number: they shorten span names and paths (keeping the end of a
path), wrap attributes, and split a table that is still too wide into blocks stacked one under
the other, each repeating the path column. Below about 80 columns for `--tree`, 70 for
`--critical` and 60 for the others, their lines run over.

**Exit status.** 0 on success, 1 on malformed input (or a cut value with `--check`), 2 on a
usage error.

## Web viewer: Tempo Timeline

<https://dualuse.github.io/tempo/> is the browser viewer: one self-contained HTML file
(`site/index.html`), no build step, no network access (its content security policy is
`default-src 'none'`).

- **Paste** a `curl -i` response, a `Tempo-Timing` value, a Tempo-Link or a JSON body, or
  open a file. Add a second trace to compare.
- **Tabs:** Lanes (zoom with the wheel, pan by dragging, click a span for its details; phases
  are drawn on their span's bar, and a phase too narrow for its name shows it on hover), Tree,
  Aggregates, Pools, Findings, Critical path, Diff, and Stats over a batch.
- **Share link:** the menu copies a link holding the whole trace (and the comparison trace)
  in its fragment. Links are made from the page's own address, so a self-hosted or forked
  copy shares links to itself.
- **Export** the decoded JSON, Chrome trace events, folded stacks or the header value.
- **Demo traces** from `samples/` are built in.

To host it yourself, serve `site/index.html` anywhere and point the library at it with
`-PlinkBase`, `-Dtempo.linkBase` or `Options.linkBase`. The `Pages` workflow
(`.github/workflows/pages.yml`) deploys `site/` to the repository's GitHub Pages on every push
to `master`; it names no owner or domain, so a fork deploys its own copy unchanged.

## Samples and the trace generator

`samples/` holds synthetic traces with invented names, as `Tempo-Timing` values and JSON:
a small search request, the same request with healthy and with saturated pools, a video
pipeline, a before/after pair for `--diff`, and a batch of 30 for `--stats`. See
[samples/README.md](samples/README.md).

```
tools/tempo samples/find-saturated.header
tools/tempo --critical samples/find-saturated.header
tools/tempo samples/diff/before.header --diff samples/diff/after.header
tools/tempo --stats samples/batch/*.header
```

They are produced by `tools/gen_traces.py`, a small discrete-event simulation of thread
pools (Python 3.8+, standard library only, deterministic for given arguments):

```
python3 tools/gen_traces.py --seed 3 --candidates 64 --cpu 4 --saturation 1.1   # a header value
python3 tools/gen_traces.py --mode pipeline --frames 300 --format json
python3 tools/gen_traces.py --seed 3 --link                                     # a Tempo-Link
python3 tools/gen_traces.py --samples samples                                   # rebuild samples/
```

## Benchmarks

JMH, 300-span traces where it matters; Apple M3 Max, JDK 24, 2026-11-19. Full tables and method
in [BENCHMARKS.md](BENCHMARKS.md).

| Operation | Time | Allocation |
|---|---|---|
| one `System.nanoTime()`, for scale | 10 ns | 0 B |
| `Tempo.span(name).close()`, recorded | 37 ns | 0 B |
| a step the trace does not draw | 28 ns | 0 B |
| a tick the trace does not record, `s.tick(name)` | 1.4 ns | 0 B |
| a task submitted, begun and closed | 68 ns | 56 B: the task, which is also its `Future` |
| `Tempo-Timing` value, 300 spans | 15 µs | 5.3 KB, mostly the output string |

CI holds every recording operation to its allocation, and the four cost promises to their
gates, on JDK 11, 17 and 21 (`./gradlew jmhGate`): 0 B per recorded span, one object per task
plus a one-time lane setup of at most 256 B for each trace and worker thread (248 B measured,
gated like `traceLifecycle`), an unrecorded tick under half a clock read, and a recorded static
span at most 6.4 clock reads.

## Building and testing

```
./gradlew test                                   # unit tests
./gradlew test -PtestJdk=11                      # the tests on another installed JDK
./gradlew check                                  # tests, the link-base rule and the demos
./gradlew demos                                  # every demo, with the checks of what each one shows
./gradlew jmhGate                                # the cost gate CI runs: the four cost promises
./gradlew jmh                                    # benchmarks, with -prof gc
./gradlew jcstress                               # concurrency tests (jcstress, quick mode)
./gradlew jar                                    # the jar; its Tempo-Link base follows the git remote origin
python3 tests/run_golden.py                      # format vectors, ladder and samples
python3 -m unittest tests.test_cli               # the command-line viewer
python3 -m unittest tests.test_check_private     # the private-term check
node tests/test_parser.mjs                       # the web viewer's parser
node tests/check_site_headless.mjs               # the web viewer in headless Chrome
```

Gradle needs JDK 17 or newer; the library is compiled with `--release 11`. See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
