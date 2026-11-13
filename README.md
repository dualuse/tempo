# Tempo

Tempo is a small phase-profiling library for Java. It records where the time of a
request or job goes — nested spans, phases and laps, work handed to other threads —
cheaply enough to stay switched on in production, and renders the result as a
compact report.

It is a single source file with no runtime dependencies and targets Java 11.

## Status

**Rewrite in progress.** The recording core, the API and the output formats are
being redesigned on this branch; nothing here is stable or released yet.

## Building

```
./gradlew test                                   # unit tests
./gradlew test -PtestJdk=11                      # run the tests on another installed JDK
./gradlew jmh                                    # benchmarks, with -prof gc
./gradlew jmh -Pjmh.args="Tempo -f 1 -prof gc"   # explicit JMH command line
```

Gradle itself needs JDK 17 or newer; the library is compiled with `--release 11`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
