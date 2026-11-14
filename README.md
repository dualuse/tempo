# Tempo

Tempo is a small phase-profiling library for Java. It records where the time of a
request or job goes — nested spans, phases and laps, work handed to other threads —
cheaply enough to stay switched on in production, and renders the result as a
compact report.

It is a single source file with no runtime dependencies and targets Java 11.

A trace travels in the `Tempo-Timing` response header or as JSON in a response body;
the format is specified in [FORMAT.md](FORMAT.md). The web viewer, Tempo Timeline, runs at
<https://dualuse.de/tempo/>: paste a header or `curl -i` output, nothing is uploaded. A service
can also send a `Tempo-Link` header: a URL to the viewer that carries the compressed trace in
its `#fragment`, so a click in the terminal opens it in the browser without the trace being
sent to any server ([FORMAT.md §9](FORMAT.md#9-tempo-link)).

## Status

**Rewrite in progress.** The recording core, the API and the output formats are
being redesigned on this branch; nothing here is stable or released yet.

## Usage

```java
static final Tempo.Name SEARCH = Tempo.name("search"), DECODE = Tempo.name("decode"),
    RERANK = Tempo.name("rerank"), LOAD = Tempo.name("load"), MATCH = Tempo.name("match");

Tempo.Trace t = Tempo.start(SEARCH, new Tempo.Options().level(Tempo.DETAIL));
try (t) {
  Tempo.Span s = t.root();
  Image img = decode(body);
  s.done(DECODE, "Decode Input");               // lap, labelled after the fact; shown in Runtime
  s.phase(RERANK).legacy();                     // phase, labelled first; children nest inside
  try (Tempo.Span l = s.detail(LOAD)) {         // only recorded at DETAIL and above
    load(img);
  }
  Tempo.Handoff h = s.handoff(MATCH);           // work for another thread, with its queue wait
  pool.submit(h.runnable(() -> match(img)));
}
String runtime = t.toRuntime(3);                // "0.412s = 0.020s (Decode Input) + 0.392s (rerank)"
String timing = t.toServerTiming();             // "decode;dur=20.000, rerank;dur=392.000, total;dur=412.000"
t.release();                                    // optional: buffers go back to the pool
```

- **Spans** (`span`, `detail`, `deep`, `run`, `call`) nest under the innermost open span of the
  thread. A `Span` handle is used only on its own thread.
- **Phases and laps** split a span into consecutive steps: `phase(name)` names a step before it
  runs, `done(name)` names it after it ran, `lap(name, body)` runs it. Steps marked legacy
  (`done(name, label)` or `legacy()`) are what `toRuntime` and `toServerTiming` print.
- **Handoffs** carry a span to a pool thread: `handoff(name)` where the task is submitted,
  `begin()` or `runnable`/`callable`/`supplier` where it runs.
- **Always-on names** (`Name.aggregate()`, `Name.sink(listener)`, `Trace.on(name, listener)`)
  are measured at every level; `Trace.aggregate(name)` sums them.
- **Output:** `toRuntime(digits)` gives `<total>s = <d1>s (<label1>) + ...` with `digits` fixed
  decimals, or with `-1` as `Double.toString` prints seconds; `toServerTiming()` gives a W3C
  `Server-Timing` value in milliseconds; `forEachTop` visits the legacy steps and `forEachSpan`
  the whole tree; `printOnClose(out, label)` prints the `Runtime` line when the trace closes.
  `snapshot()` keeps a copy that stays readable after `release()`.

## Command-line viewer: `tools/tempo`

`tools/tempo` reads a trace and draws it in the terminal. It is one Python file that
needs Python 3.8 or newer and nothing outside the standard library: copy it anywhere
on your `PATH`.

```
curl -si "$URL" | tempo                  # the Tempo-Timing line of curl -i output
pbpaste | tempo --tree                   # a value copied from a log or a terminal
tempo response.json                      # JSON with a tempo object anywhere (e.g. under "info")
tempo before.header --diff after.header  # what changed between two requests
tempo --stats traces/*.header            # p50/p95/max per path over many requests
tempo 'https://dualuse.de/tempo/#t=z.…'  # a Tempo-Link, as an argument
tempo --open --critical trace.header     # the same trace in the web viewer
```

**Input.** Files, stdin or link arguments, in any of these forms:

- `curl -i` output. The `Tempo-Timing:` or `Tempo-Link:` line is found
  case-insensitively, and a value that a proxy folded or a terminal wrapped over
  several lines is joined again. When one response has both headers, `Tempo-Timing`
  wins; a warning on stderr says when the link carries a different trace (and the link
  is used when `Tempo-Timing` is rejected).
- A bare header value starting with `v1;`, also embedded in a log line. Whitespace
  and line breaks inside the value are ignored.
- A Tempo-Link URL (anything with `#t=`, or `?t=z.…` in the query), also embedded in a
  log line or wrapped over several lines, or given directly as an argument instead of
  a file name. Several values or links in one input are several traces, as for
  `--stats`. A link may carry a second trace (`&c=`); `--diff` without `OTHER`
  compares with it.
- A JSON document holding a tempo object (`"v": 1` and `"spans"`) at any depth. When a
  response has both the header and such a body, the body wins, because it is not
  size-limited.

Quote a value or link you type on the command line, or pipe it: `;`, `*`, `~`, `^` and
`&` are shell syntax.

**Default view: lanes.** One row group per thread, grouped into pools by stripping a
trailing `-<digits>` from the thread name (`cpu-1`, `cpu-2` form the pool `cpu`;
`Thread-N` threads stay ungrouped). Nested spans stack into rows below their parent.
A handoff that queued while its thread was still busy with earlier work of the request
moves to a free row of that lane, so its queue wait stays visible.

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

The time axis is in milliseconds. The legend follows the chart, then notices for
folded or dropped spans (`x=`), live lanes (`l=`) and cut values.

**Views.**

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

Self time is the duration minus the children that ran nested on the same thread,
blocking waits included. Children on other threads are never subtracted. Off-CPU
time is duration minus CPU time, where CPU was measured.

**Web viewer.** `--open` writes the trace's Tempo-Link to stdout and opens it with
Python's `webbrowser` module, that is, in the local browser. With `--diff` (or an
input link with `&c=`) the link holds both traces; with `--tree`, `--agg`, `--pools`, `--critical` or `--diff` it opens
on that tab. `--base URL` (or `$TEMPO_LINK_BASE`) points `--to link` and `--open` at
another copy of the viewer, such as a self-hosted one; the default is
`https://dualuse.de/tempo/`. The trace travels in the URL fragment, which the browser
keeps to itself: nothing is uploaded, and the CLI sends nothing anywhere. The link
does end up in the browser history.

**Filters**, for every view except the converters and `--critical`:

| Option | Keeps |
|---|---|
| `--threads REGEX` | Spans on threads whose name matches (`cpu-*` stands for a mixed aggregate). |
| `--focus PATH` | The subtree at a path such as `rerank` or `rerank/load`; the lanes zoom to it. |
| `--depth N` | At most N levels below the root or focus. |
| `--min-ms X` | Spans of at least X ms, with their subtrees. |

**Layout.** `--width N` (default: the terminal width, or `$COLUMNS` when piped),
`--no-color`, `--color` (force colours into a pipe), `--ascii` (no Unicode glyphs).
Colours are off when output is not a terminal, when `NO_COLOR` is set, or when
`TERM=dumb`.

Every view fits its lines to the width. The lanes and charts need at least 40
columns; a smaller `--width` is raised to 40 (with a warning). Tables never cut a
number: they shorten span names and paths (keeping the end of a path), wrap
attributes, aggregate details and the span text of `--critical`, shorten the file
names in the `--diff` title, and split a table that is still too wide into blocks
stacked one under the other, each repeating the path column. So tables have a floor
of their own, about 80 columns for `--tree`, 70 for `--critical` and 60 for the
others; below it their lines run over.

**Exit status.** 0 on success, 1 on malformed input (or a cut value with `--check`),
2 on a usage error.

Try it on the bundled synthetic traces (all names are invented):

```
tools/tempo samples/find-saturated.header
tools/tempo --critical samples/find-saturated.header
tools/tempo samples/diff/before.header --diff samples/diff/after.header
tools/tempo --stats samples/batch/*.header
```

Tests: `python3 tests/test_cli.py` (snapshots in `tests/snapshots/`; after an intended
output change, rerun with `TEMPO_UPDATE_SNAPSHOTS=1` and review the diff) and
`python3 tests/run_golden.py` for the format vectors.

## Building

```
./gradlew test                                   # unit tests
./gradlew test -PtestJdk=11                      # run the tests on another installed JDK
./gradlew jmh                                    # benchmarks, with -prof gc
./gradlew jmh -Pjmh.args="Tempo -f 1 -prof gc"   # explicit JMH command line
./gradlew jmhGate                                # the allocation gate CI runs
./gradlew jcstress                               # concurrency tests (jcstress, quick mode)
```

Gradle itself needs JDK 17 or newer; the library is compiled with `--release 11`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
