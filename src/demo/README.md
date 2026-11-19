# Demos

Eleven small programs that use Tempo the way an application does, from a first trace to a
service with several thread pools. They live in their own source set (`src/demo/java`), see
`main` and nothing else, and ship nowhere.

None of them starts a server or opens a socket. A "request" is a method call, "I/O" is a
sleep and "compute" is a busy loop (`Demo.io`, `Demo.cpu`), so that each demo shows only how
Tempo is used around that work. Every demo prints its story, then the response a service would
send, the way `curl -i` shows it. Every demo also checks what it claims about its own trace
(`Demo.check`): which spans, threads, flags and attributes it has, never exact times, except
where the demo sets the times itself. `./gradlew check` runs all of them, so a demo cannot
drift away from what Tempo does.

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

## How the demos use Tempo

All demos write their code the same way:

- **try-with-resources first.** A block is timed by `try (Tempo.Span s = Tempo.span("load")) { ... }`.
  `Tempo.run("store", () -> ...)` and `Tempo.call("render", () -> ...)` are the compact form for a
  lambda; a throw from the body flags the span failed and is rethrown as it was.
- **Static calls.** `Tempo.span`, `Tempo.step` and `Tempo.tick` open a span under the innermost
  open span of the calling thread, so spans assemble themselves. No method takes a span or a
  trace as a parameter; a helper that opens a span lands under whatever its caller has open.
- **`Tempo.current()` is the escape hatch.** Code that was handed no span and wants to annotate
  the one it runs in asks for it explicitly: a helper puts a number on its caller's span, a task's
  body on the task's span, a handler on the request span a filter opened.
- **The trace is declared before its `try`**, as in `Tempo.Trace t = Tempo.trace(...); try (t) { ... }`,
  so that it can be read and rendered after it closed.
- **Names.** Every method that takes a `Tempo.Name` also takes a `String`, looked up in a global
  table. `FirstTrace` uses strings; the other demos keep `Tempo.name(...)` constants, which a hot
  loop needs because they cost no lookup per span.
- **Tasks.** Work for another thread goes through `Tempo.executor(pool, name)`, or is a task object
  made with `Tempo.runnable`, `Tempo.callable` or `Tempo.task` where the demo needs the object
  itself: to set attributes at the submit, to cancel only what has not begun, or to begin it by hand.

### `@SuppressWarnings("try")`

The demo source set compiles with `-Xlint:all -Werror`, because the demos are read as examples.
javac's `try` lint warns about `try (Tempo.Span s = Tempo.span("x")) { work(); }` when the block
never names `s`: it assumes a resource exists to be used. Timing a block is exactly that pattern,
so a demo that has such blocks puts `@SuppressWarnings("try")` on the class or the method, with
a short comment pointing here. Builds without `-Xlint` need nothing. Where the block uses the
span anyway (to set a number on it), or `Tempo.run` fits, no annotation is needed.

## Reading order

Read them in the order of the tables below. Each grade builds on the one before: the simple
demos run on one thread, the medium ones introduce one concept each (steps and ticks, thread
pools, totals and listeners, headers), and the complex ones combine them into a service.

### Simple: one thread, one trace

| Demo | What it teaches | Tempo API it introduces |
|---|---|---|
| `FirstTrace` | One request recorded as a trace: spans opened with static calls, a nested span, a number on a span, the four response headers. | `Tempo.trace`, `Options.strict`, `Tempo.span` / `run` / `call`, `Span.set`, `Trace.forEachSpan`, `release` |
| `StopwatchSteps` | A job that used to time itself with a stopwatch: phases named before or after they run, a span timed by hand from `System.nanoTime()` readings, an unnamed span labelled by its phases, and the `Runtime` and `Server-Timing` headers from the phase labels. | `phase`, `done` / `done(name)`, `label`, `start` / `lap(nanos)` / `stop`, `Tempo.span()`, `printOnClose`, `forEachTop`, `toRuntime`, `toServerTiming` |
| `AttributesAndFailures` | Numbers on spans, failed and cancelled work, shared code that opens its own span or annotates its caller's span, and what happens to a span left open. | long and double attributes, `fail`, `cancel`, `Tempo.current`, `Tempo.NOOP`, healed misuse (`m`) versus `strict` |

### Medium: one concept each

| Demo | What it teaches | Tempo API it introduces |
|---|---|---|
| `LevelsAndOptIn` | Spans for every request, steps and ticks when the client asks for them, a hot loop folded into one aggregate once the budget is spent, and totals that count every step whatever the trace drew. | `Tempo.step` / `tick`, `SPAN` / `STEP` / `TICK`, `Trace.records`, `Options.budget`, `Trace.total`, `Total.mean`, aggregates |
| `WorkerPool` | Work submitted to thread pools: queue wait, CPU time per task, a blocking join, and the ways a task ends without running on the pool (caller-runs, rejected, cancelled through its future by an early exit). | `Tempo.executor`, `Future.cancel`, `waiting`, `Tempo.current` in a task, CPU time at `STEP` |
| `MetricsAndListeners` | Spans that also feed a service's metrics, totals of steps and of pool tasks that the trace does not draw, with the tasks' queue wait, and a measurement taken elsewhere. | `Tempo.on`, `Trace.on`, `Trace.total` (`count`, `mean`, `waitSum`, `waitMax`), `Span.add`, `Tempo.run` outside a trace |
| `ResponseHeaders` | Getting a trace out of a service: four headers under one byte limit, a link to a self-hosted viewer, JSON in the body, reading a trace in code. | `toHeader(maxBytes)`, `toLink(base, maxBytes)`, `Options.linkBase`, `snapshot`, `Snapshot.writeJson`, `x=folded.dropped` |

### Complex: a service

| Demo | What it teaches | Combines |
|---|---|---|
| `SearchRequest` | One request through a filter and a handler with an `io` pool and a `cpu` pool: a chain of tasks request → `io-N` → `cpu-N`, a handler that reaches the request span with `Tempo.current()` to mark its phases, an early exit that cancels every task not yet begun (`Task.cancel`), a straggler still open at render. | `LevelsAndOptIn`, `WorkerPool`, `ResponseHeaders` |
| `IngestPipeline` | An upload processed by stage workers connected by bounded queues: hand-written workers that take jobs from their own queue and begin the request's tasks with `Task.tryBegin`, backpressure, end of stream, a summary from totals. | `WorkerPool`, `MetricsAndListeners`, `ResponseHeaders` |
| `BatchUpload` | A batch processed in parallel with nested fan-out (two hops), one corrupt item, a bounded pool that refuses work, and a deadline enforced by the workers and the request thread at once, one cancel winning per item; each item is a `Tempo.callable` task and its own future. | `WorkerPool`, `AttributesAndFailures` |
| `PoolDiagnosis` | One pool or two? The same concurrent load under both layouts, compared by reading the traces in code (totals for the queue waits, a walk for threads and CPU), with `--diff`, `--stats` and `--pools`. | `WorkerPool`, `MetricsAndListeners`, reading a trace (`ResponseHeaders`) |

## What to look for

In the lanes view of `tools/tempo` each thread is a lane, and each span is a bar from its start
to its end (`tools/tempo` prints this legend under the lanes too; `--ascii` draws `|---- name ----|`):

| Glyph | Meaning |
|---|---|
| `├── name ──┤` | a span, its name centred in its bar |
| `job ├─ fetch ─┼ parse ┼·┤` | a span with phases: its name in front of the bar, each phase named in its section, `┼` at a boundary, `·` for time in no phase |
| `*1` | a phase too narrow for its name, named in the footnote line under the lanes; one too narrow even for that has its `*1` after its span's name |
| `░` | queue wait: a task submitted but not yet begun |
| `├╌╌ join ╌╌┤` | a blocking wait (a span marked `waiting()`, such as a join) |
| `▚` | an aggregate: many spans folded into one record, labelled `name ×N`, `┃` at its slowest |
| `✗` | failed |
| `⊘` | cancelled or rejected |
| `∅` | a task that never started |
| `▶` | still open when the response was rendered |
| `●` | a lane still live at render |
| `!` | misuse that Tempo healed |

In a `Tempo-Timing` value and in `--tree`, a span's flags follow `^`:

| Flag | Meaning |
|---|---|
| `f` | failed |
| `c` | cancelled or rejected; with no run time, before it began |
| `o` | still open at render |
| `n` | a task that never started (neither begun nor cancelled) |
| `w` | a blocking wait |
| `m` | misuse detected and healed |
| `p` | a phase: a section of its span's timeline, listed as `:name` in `--tree` |

A record with `~` is a task, followed by its queue wait. A record with `*` carries the thread's
CPU time, and `--tree` shows it next to the wall time and the time off CPU. `x=` in the value
counts the spans folded into aggregates and the spans dropped to keep a byte limit
(`folded.dropped`, base 36). [FORMAT.md](../../FORMAT.md) has the full grammar.

## Adding a demo

Put it under `simple`, `medium` or `complex` in `io.github.dualuse.tempo.demo`, with a
`public static void main(String[])`, and add a line to `Demos.ALL`. Use invented names only.
Write it in the style above. Use `Demo` for the work, the pools, the narration, the response and
the checks. Make each check true by the demo's construction, not by timing luck: `./gradlew
demos` runs on loaded CI machines.
