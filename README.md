# Tempo

Tempo is a small phase-profiling library for Java. It records where the time of a
request or job goes — nested spans, phases and laps, work handed to other threads —
cheaply enough to stay switched on in production, and renders the result as a
compact report.

It is a single source file with no runtime dependencies and targets Java 11.

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
