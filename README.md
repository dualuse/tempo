# Tempo

Tempo is a small phase-profiling library for Java. It records where the time of one request
or job goes (nested spans, phases and laps, work handed to thread pools with its queue wait)
cheaply enough to stay switched on in production. It renders the result as response headers,
as JSON, or as a link that opens the trace in a web viewer.

- **One source file**, `Tempo.java`, Java 11 or newer, no runtime dependencies. An optional
  second file, `TempoJackson.java`, serializes traces with Jackson.
- **No allocation while recording.** Spans go into per-thread primitive buffers; a span costs
  about 30 ns (two clock reads), a disabled detail span a branch. See [BENCHMARKS.md](BENCHMARKS.md).
- **A compact wire format**, `Tempo-Timing`, specified in [FORMAT.md](FORMAT.md): a whole
  request tree with threads, queue waits, CPU time, attributes and flags in one header line,
  kept to a byte limit by folding.
- **Two viewers**: `tools/tempo` in the terminal and **Tempo Timeline** in the browser at
  <https://dualuse.github.io/tempo/>. Neither sends a trace anywhere.

```
$ curl -si https://api.example.org/search?q=lamp | tempo --tree
search  ·  31.600 ms  ·  2026-11-14 05:56:18.513 UTC  ·  6 spans on 2 threads
span                   start   wait     dur    self    cpu  off-cpu  thread  flags  attributes
search                 0.000         31.600   0.000  1.526   30.074  main
├─ decode              0.000         20.000  20.000                  main
└─ rerank             20.000         11.600   0.600                  main
   ├─ load            20.000          3.000   3.000                  main           bytes=48213
   ├─ match           23.000  0.500   8.000   8.000  0.010    7.990  cpu-1          queued=0
   └─ join            23.500          8.000   8.000                  main    w
```

## Status

Pre-release (0.1.0). The recording API, the wire format v1, the JSON form and both viewers
are complete and tested; the API may still change before 1.0. There is no Maven Central
artifact yet: build the jar (`./gradlew jar`) or copy
`src/main/java/io/github/dualuse/tempo/Tempo.java` into your source tree.

## Quick start

```java
import io.github.dualuse.tempo.Tempo;

static final Tempo.Name SEARCH = Tempo.name("search"), DECODE = Tempo.name("decode"),
    RERANK = Tempo.name("rerank"), LOAD = Tempo.name("load"), MATCH = Tempo.name("match"),
    JOIN = Tempo.name("join"), BYTES = Tempo.name("bytes"), QUEUED = Tempo.name("queued");

Tempo.Trace t = Tempo.start(SEARCH, new Tempo.Options().level(Tempo.DETAIL));
try (t) {
  Tempo.Span s = t.root();
  Query q = decode(body);
  s.done(DECODE, "Decode Input");                    // a lap, named after it ran
  s.phase(RERANK).legacy();                          // a phase, named before it runs
  try (Tempo.Span l = s.detail(LOAD)) {              // recorded at DETAIL and above
    l.set(BYTES, load(q));
  }
  List<Future<Score>> scores = new ArrayList<>();
  for (Candidate c : candidates) {
    Tempo.Handoff h = s.handoff(MATCH).set(QUEUED, queue.size());  // submitted here...
    scores.add(pool.submit(h.callable(() -> match(q, c))));        // ...runs there, after its queue wait
  }
  try (Tempo.Span j = s.span(JOIN).waiting()) {      // a blocking wait
    for (Future<Score> f : scores) f.get();
  }
}
response.setHeader("Runtime", t.toRuntime(3));       // 0.032s = 0.020s (Decode Input) + 0.012s (rerank)
response.setHeader("Server-Timing", t.toServerTiming());
response.setHeader("Tempo-Timing", t.toHeader());   // the whole tree, see FORMAT.md
t.release();                                         // optional: buffers go back to the pool
```

Names are interned once and kept in `static final` fields; dynamic data belongs in
attributes, never in names. Recording never throws into the caller: misuse (a span used on
the wrong thread, closed twice, left open) is healed and counted, and `Options.strict(true)`
turns it into an `AssertionError` for tests.

### Traces and spans

`Tempo.start(name)` opens a trace and its root span on the calling thread;
`Tempo.start(name, options)` takes `Options`. Closing the trace ends the root, and any span
still open, at that instant. The trace stays readable until `release()`.

```java
try (Tempo.Span s = t.root().span(PARSE)) { parse(body); }   // always recorded
t.root().run(VALIDATE, () -> validate(request));             // a child span around a body
Plan p = t.root().call(PLAN, () -> plan(request));           // the same, with a result
```

A span nests under the innermost open span of its thread. A `Span` handle belongs to its
thread and is reused for the next span at the same depth, so it is not kept after close.
Deep in a call stack, `Tempo.current()` returns the innermost open span of the thread, or
`Tempo.NOOP` (a handle that records nothing) outside a trace. `run`, `call` and `lap` flag
the span failed when the body throws and rethrow the throwable unwrapped.

### Phases and laps

Phases and laps split one span into consecutive steps.

```java
Tempo.Span s = t.root();
s.phase(FETCH);                    // label first: FETCH runs from here to the next boundary
fetch();                           //   spans opened now nest under FETCH
s.done(FETCH);                     // ends the open phase
transform();
s.done(TRANSFORM, "Transform");    // label after: a lap from the last boundary to now
Result r = s.lap(STORE, () -> store());   // runs the body, then done(STORE)
s.phaseSet(ROWS, r.rows());        // an attribute on the last lap
```

The steps marked legacy (`done(name, label)` or `legacy()`) are the ones the `Runtime` and
`Server-Timing` headers print, under their label.

### Handoffs and queue wait

A handoff carries a span to another thread and records how long the task waited in the
queue. `handoff(name)` is called where the task is submitted, `begin()` where it runs:

```java
Tempo.Handoff h = s.handoff(MATCH).set(QUEUED, executor.getQueue().size());
executor.execute(() -> {
  try (Tempo.Span m = h.begin()) {   // queue wait = now - submit
    match(c);
  }
});
```

`runnable`, `callable` and `supplier` wrap a task in `begin()` and close. A task that runs
inline on the submitting thread (caller-runs) becomes a plain child. One that never runs is
marked with `h.cancel()` (an early exit) or `h.reject()` (the executor refused it), and shows
as never started. The `Handoff` is the one object Tempo allocates while recording, 32 bytes.

On a thread without a trace, `t.submitted()` and `t.waited(name, token)` measure a queue
wait into `t.aggregate(name)` with a single clock read each.

### Attributes and flags

```java
Tempo.Span q = s.detail(QUERY);
try {
  Rows rows = query();
  q.set(ROWS, rows.size()).set(RATIO, rows.hitRatio());   // long and double attributes
} catch (TimeoutException e) {
  q.fail(e);                               // only the flag is recorded, not the throwable
  throw e;
} finally {
  q.close();
}
```

`set`, `fail()` and `cancel()` also work after the span closed, so a catch block can call
them. `waiting()` marks a blocking wait (a join, a `take`, a `get`), which the viewers draw
apart from run time and follow across threads on the critical path.

### Levels

Every trace records at one level, chosen when it starts: `Tempo.COARSE` (default),
`Tempo.DETAIL` or `Tempo.DEEP`. `span` is always recorded; `detail` and `deep` only at their
level and above. Below it they return `Tempo.NOOP` at the cost of a branch, so detail
instrumentation can stay in hot code.

```java
Tempo.Trace t = Tempo.start(SEARCH, new Tempo.Options().level(debug ? Tempo.DETAIL : Tempo.COARSE));
t.raise(Tempo.DEEP);                       // once, before the first detail span
if (s.detailed()) s.set(KEYS, keys.size());   // guard work done only for detail
```

Thread CPU time is measured for each lane root (the trace's root and every task on a pool
thread) from `DETAIL` up, or as `Options.cpu(true|false)` says; `span.cpu()` asks for it on
one more span.

### Budget and folding

At `DETAIL` and `DEEP`, a trace records up to `Options.budget(n)` detail spans and
handoffs individually (512 by default). Past the budget they fold: occurrences of the same
name under the same parent go into one aggregate with count, sum, min, max, extent, queue
wait sum and max, CPU time, the thread (or that there were several) and the flags all of them
share. A hot loop of thousands of iterations costs one aggregate, not thousands of rows, and
the header says how many spans were folded.

### Listeners and aggregates

A name can be measured at every level, without rows:

```java
static final Tempo.Name LOAD = Tempo.name("load").aggregate();          // always-on
static final Tempo.Name STORE = Tempo.name("store")
    .sink(Tempo.seconds(storeHistogram::record));                       // global listener, seconds

t.on(MATCH, Tempo.serialized(matchTimes::add));   // this trace only, before its first child span
...
Tempo.Aggregate a = t.aggregate(LOAD);    // count(), sumNanos(), minNanos(), maxNanos(), waitSumNanos() ...
```

Below the trace's level, `detail(LOAD)` on an always-on name times itself into the aggregate
and calls the listeners, but writes no row. Listeners run on the thread that ends the span,
without a lock; `Tempo.serialized` wraps one that is not thread-safe. `t.add(name, nanos)`
adds a measurement taken elsewhere. Without any trace, `Tempo.time(sink, body)` times a body
with two clock reads.

`t.printOnClose(log::info, "import")` passes `import: ` and the `Runtime` text to the logger
when the trace closes, as a drop-in for a stopwatch log line.

### Reading a trace

`forEachSpan(visitor)` walks the stitched tree with a reused read-only `SpanView`
(start, duration, thread, queue wait, CPU, flags, attributes, aggregate statistics);
`forEachTop(visitor)` visits the legacy steps. `snapshot()` takes an immutable copy that stays
readable after `release()`, for rendering later or on another thread.

## Headers

| Header | Method | Content |
|---|---|---|
| `Runtime` | `toRuntime(digits)` | `0.032s = 0.020s (Decode Input) + 0.012s (rerank)`: the legacy steps, in seconds |
| `Server-Timing` | `toServerTiming()` | `decode;dur=20.000, rerank;dur=11.600, total;dur=31.600`: W3C, in milliseconds, shown by browser dev tools |
| `Tempo-Timing` | `toHeader()`, `toHeader(maxBytes)` | the whole tree, see below |
| `Tempo-Link` | `toLink()`, `toLink(base, maxBytes)` | a URL that opens the trace in Tempo Timeline |

The `Tempo-Timing` value of the quick-start request:

```
v1;w=mwfzt13l;T=main:cpu-1;N=search:decode:rerank:load:match:join:bytes:queued;S=00.0.ods*16e/11.0.ffk/12.ffk.8y8/23.0.2bc:6=48213/24.2bc.668@1~dw*a:7=0/25.dw.668^w;e=6
```

One record per span, in pre-order: depth, name, start and duration (base 36, in
microseconds), then thread (`@`), queue wait (`~`), CPU time (`*`), attributes (`:`) and
flags (`^`). The format, its error codes and its test vectors are
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

So a fork's CI and release builds link to the fork's own viewer without any change. The
viewer is there once the fork turns on Pages (Settings → Pages, Source: GitHub Actions) and
pushes to `master` or runs the `Pages` workflow by hand. At run time,
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
stay ungrouped). Nested spans stack into rows below their parent. A handoff that queued while
its thread was still busy with earlier work of the request moves to a free row of that lane,
so its queue wait stays visible.

```
search  ·  31.600 ms  ·  2026-11-14 05:56:18.513 UTC  ·  6 spans on 2 threads
            ms 0                   10                   20                  30
               ┬───────────────────┬────────────────────┬───────────────────┬───
main           search███████████████████████████████████████████████████████████
               decode███████████████████████████████████rerank▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓
                                                        load██ join─────────────
cpu
  cpu-1                                                       ░match████████████
  queued ≤1                                                   █▄
```

| Glyph | Meaning |
|---|---|
| `█` `▓` | Run time (the two shades separate neighbouring spans). |
| `░` | Queue wait of a handoff, drawn dim in front of its run. |
| `─` | Blocking wait (join, future get), flag `w`. |
| `▚` with `×N` and `┃` | Aggregate of N folded spans over its extent; `┃` marks where the slowest one started. |
| `✗` `⊘` `▶` `∅` `!` | Failed (also red), cancelled, still open at render, never started, misuse. |
| `●` | Lane still live at render (`l=`); its records are best effort. |
| `(mixed)`, `(queued)` | Aggregates on several threads of a pool, and handoffs that never ran. |
| `queued ≤N` | Per pool: how many of this request's handoffs waited in the queue over time. |

The time axis is in milliseconds. The legend follows the chart, then notices for folded or
dropped spans (`x=`), live lanes (`l=`) and cut values.

**Options.**

| Option | Shows |
|---|---|
| `--tree` | Indented tree with start, queue wait, duration, self time, CPU, off-CPU and attributes. |
| `--agg` | Per path: count, sum, self, min, max, wait sum, wait max, CPU. |
| `--pools` | Per pool, over time: this request's running and queued tasks, and the `queued`/`active` gauges recorded at submit (the pool-wide signal). A table gives tasks, threads seen, run, wait, CPU, off-CPU and peaks. |
| `--findings` | Stragglers (ending after their parent), never-started and open handoffs, failures, cancellations, the longest queue waits, and off-CPU time on compute pools. |
| `--critical` | The chain that determined the end time. It walks back from the root's end, always into the child that finished last, and follows handoffs to other threads instead of the blocking wait on them. On an early exit, where the wait ends while the work still runs (an aggregate, or a straggler with nothing else ending within 1 ms of the wake-up), it follows the work running at the wake-up. Each hop shows its queue wait and run time; totals per pool follow. |
| `--diff [OTHER]` | Per path, B (OTHER, a file or link) minus A (the input): Δcount, Δsum, Δself, Δwait, ΔCPU, sorted by the largest absolute change. Without OTHER, B is the `&c=` trace of the input link. |
| `--stats FILES...` | Over many traces (also several per file, such as a log): per path, the number of traces containing it, the median count, and p50/p95/max of sum, self and wait (nearest rank). |
| `--to json` | The JSON form of FORMAT.md. |
| `--to chrome` | Chrome trace events for [Perfetto](https://ui.perfetto.dev) or `chrome://tracing`: one track per thread, queue waits as async slices, handoffs as flow arrows. |
| `--to folded` | Collapsed stacks (`a;b;c <µs>`) for flame-graph tools. Values are self times; queue waits are left out. |
| `--to link` | The Tempo-Link URL of the trace (`z.` form; an input link's `&c=` is kept). JSON input is encoded into a header value first. |
| `--check` | Validate only. Exit status 1 on a malformed or cut value, with the error code of FORMAT.md. |
| `--threads REGEX` | Keep spans on threads whose name matches (`cpu-*` stands for a mixed aggregate). |
| `--focus PATH` | Keep the subtree at a path such as `rerank` or `rerank/load`; the lanes zoom to it. |
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
- **Tabs:** Lanes (zoom with the wheel, pan by dragging, click a span for its details), Tree,
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

JMH, 300-span traces where it matters; Apple M3 Max, JDK 24. Full table and method in
[BENCHMARKS.md](BENCHMARKS.md).

| Operation | Time | Allocation |
|---|---|---|
| span open + close | 33 ns | 0 B |
| detail span past the budget (folded) | 29 ns | 0 B |
| handoff submit | 19 ns | 32 B (the `Handoff`) |
| `Tempo-Timing` value, 300 spans | 15 µs | 5.3 KB, mostly the output string |

CI gates the allocation of every recording operation on JDK 11, 17 and 21
(`./gradlew jmhGate`).

## Building and testing

```
./gradlew test                                   # unit tests
./gradlew test -PtestJdk=11                      # the tests on another installed JDK
./gradlew check                                  # tests and the link-base rule
./gradlew jmhGate                                # the allocation gate CI runs
./gradlew jmh                                    # benchmarks, with -prof gc
./gradlew jcstress                               # concurrency tests (jcstress, quick mode)
./gradlew jar                                    # the jar; its Tempo-Link base follows the git remote origin
python3 tests/run_golden.py                      # format vectors, ladder and samples
python3 -m unittest tests.test_cli               # the command-line viewer
node tests/test_parser.mjs                       # the web viewer's parser
```

Gradle needs JDK 17 or newer; the library is compiled with `--release 11`. See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
