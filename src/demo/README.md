# Demos

Eleven small programs that use Tempo the way an application does, from a first trace to a
service with several thread pools. They live in their own source set (`src/demo/java`), see
`main` and nothing else, and ship nowhere.

None of them starts a server or opens a socket. A "request" is a method call, "I/O" is a
sleep and "compute" is a busy loop (`Demo.io`, `Demo.cpu`), so that each demo shows only how
Tempo is used around that work. Every demo prints its story, then the response a service
would send, the way `curl -i` shows it. Every demo also checks what it claims about its own
trace (`Demo.check`): which spans, threads, flags and attributes it has, never exact times.
`./gradlew check` runs all of them, so a demo cannot drift away from what Tempo does.

## Running them

```
./gradlew demo                                      the list
./gradlew -q demo -Pdemo=FirstTrace                 one demo: its story (lines starting with #), then its response
./gradlew -q demo -Pdemo=FirstTrace | tools/tempo   the trace drawn as lanes in the terminal
./gradlew -q demo -Pdemo=FirstTrace | tools/tempo --tree    the same as a tree, with attributes and flags
./gradlew demos                                     every demo, quietly, with its checks
./gradlew demos -PtestJdk=11                        the same on another installed JDK
```

`tools/tempo` needs Python 3.8 or newer and nothing else; `--agg`, `--pools`, `--findings` and
`--critical` are other views worth trying on the larger demos. Instead of `tools/tempo`, open
the printed `Tempo-Link` in a browser: it opens Tempo Timeline with the trace in the URL
fragment, which the browser never sends, so nothing is uploaded. When a demo also prints a
response body holding the trace as JSON, both viewers read the body, which has no byte limit.

The source of each demo starts with a comment in four parts: the scenario, what it shows,
how to run it, and what to look for in the output.

## Reading order

Read them in the order of the tables below. Each grade builds on the one before: the simple
demos run on one thread, the medium ones introduce one concept each (levels, thread pools,
metrics, headers), and the complex ones combine them into a service.

### Simple: one thread, one trace

| Demo | What it teaches | Tempo API it introduces |
|---|---|---|
| `FirstTrace` | One request recorded as a trace: spans, a nested span, the four response headers. | `Tempo.name`, `Tempo.start`, `Options.strict`, `Span.span` / `call` / `run`, `Span.set`, `Trace.forEachSpan`, `release` |
| `StopwatchSteps` | A job that used to time itself with a stopwatch: steps marked before or after they run, and the legacy `Runtime` and `Server-Timing` headers. | `phase`, `done`, `lap`, `legacy`, `phaseSet`, `printOnClose`, `forEachTop`, `toRuntime`, `toServerTiming` |
| `AttributesAndFailures` | Numbers on spans, failed and cancelled work, a helper that finds its parent span, and what happens to a span left open. | long and double attributes, `fail`, `cancel`, `Tempo.current`, `Tempo.NOOP`, healed misuse (`m`) versus `strict` |

### Medium: one concept each

| Demo | What it teaches | Tempo API it introduces |
|---|---|---|
| `LevelsAndOptIn` | Coarse tracing for every request, detail when the client asks for it, and a hot loop folded into one aggregate once the budget is spent. | `COARSE` / `DETAIL` / `DEEP`, `detail`, `deep`, `Trace.raise`, `detailed`, `Options.budget`, aggregates, `Trace.aggregate` |
| `WorkerPool` | Work handed to a thread pool: queue wait, CPU time per task, a blocking join, and the ways a task ends without running on the pool (caller-runs, rejected, cancelled by an early exit). | `handoff`, `Handoff.callable` / `runnable`, `waiting`, `Handoff.reject`, `Handoff.cancel`, CPU time at `DETAIL` |
| `MetricsAndListeners` | Spans that also feed a service's metrics, and queue wait of pool tasks without recording them as spans. | `Name.sink`, `Name.aggregate`, `Trace.on`, `Tempo.serialized`, `Tempo.seconds`, `Tempo.time`, `Trace.add`, `submitted` / `waited` |
| `ResponseHeaders` | Getting a trace out of a service: four headers under one byte limit, a link to a self-hosted viewer, JSON in the body, reading a trace in code. | `toHeader(maxBytes)`, `toLink(base, maxBytes)`, `Options.linkBase`, `snapshot`, `Snapshot.writeJson`, `x=folded.dropped` |

### Complex: a service

| Demo | What it teaches | Combines |
|---|---|---|
| `SearchRequest` | One request through a filter and a handler with an `io` pool and a `cpu` pool: a chain of handoffs request → `io-N` → `cpu-N`, an early exit that cancels queued work without racing running work, a straggler still open at render. | `LevelsAndOptIn`, `WorkerPool`, `ResponseHeaders` |
| `IngestPipeline` | An upload processed by stage workers connected by bounded queues: long-running workers joining a request's trace, backpressure, end of stream, a summary from aggregates. | `WorkerPool`, `MetricsAndListeners`, `ResponseHeaders` |
| `BatchUpload` | A batch processed in parallel with nested fan-out (two hops), one corrupt item, a bounded pool that refuses work, and a deadline. | `WorkerPool`, `AttributesAndFailures` |
| `PoolDiagnosis` | One pool or two? The same concurrent load under both layouts, compared by reading the traces in code, with `--diff`, `--stats` and `--pools`. | `WorkerPool`, reading a trace (`ResponseHeaders`) |

## What to look for

In the lanes view of `tools/tempo` each thread is a lane, and each span is a bar labelled
with its name (`tools/tempo` prints this legend under the lanes too):

| Glyph | Meaning |
|---|---|
| `█` | a span running on its thread |
| `░` | queue wait: a handed-off task submitted but not yet begun |
| `─` | a blocking wait (a span marked `waiting()`, such as a join) |
| `▚` | an aggregate: many spans folded into one record, labelled `name ×N` |
| `✗` | failed |
| `⊘` | cancelled or rejected before it began |
| `∅` | a handoff that never started |
| `▶` | still open when the response was rendered |
| `●` | a lane still live at render |
| `!` | misuse that Tempo healed |

In a `Tempo-Timing` value and in `--tree`, a span's flags follow `^`:

| Flag | Meaning |
|---|---|
| `f` | failed |
| `c` | cancelled or rejected before it started |
| `o` | still open at render |
| `n` | a handoff that never started (neither begun nor cancelled) |
| `w` | a blocking wait |
| `m` | misuse detected and healed |

A record with `~` is a handoff, followed by its queue wait. A record with `*` carries the
thread's CPU time, and `--tree` shows it next to the wall time and the time off CPU. `x=` in
the value counts the spans folded into aggregates and the spans dropped to keep a byte limit
(`folded.dropped`, base 36). [FORMAT.md](../../FORMAT.md) has the full grammar.

## Adding a demo

Put it under `simple`, `medium` or `complex` in `io.github.dualuse.tempo.demo`, with a
`public static void main(String[])`, and add a line to `Demos.ALL`. Use invented names only.
Use `Demo` for the work, the pools, the narration, the response and the checks. Make each
check true by the demo's construction, not by timing luck: `./gradlew demos` runs on loaded CI
machines. The source set compiles with `-Xlint:all -Werror`; see the note in `Demo` about
spans that only time their block.
