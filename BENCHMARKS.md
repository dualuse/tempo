# Benchmarks

The JMH benchmarks live in `src/jmh/java/io/github/dualuse/tempo/TempoBenchmark.java`.
CI gates only their allocation (`./gradlew jmhGate`, see `AllocationGate.java`), because
allocation per operation is deterministic and time is not. Times are a local check
before a release; record them here with the machine they were taken on.

```
./gradlew jmhGate                                    # the allocation gate, about 2.5 minutes
./gradlew jmh -Pjmh.args="TempoBenchmark -f 1 -prof gc"   # everything, with times
```

## Allocation gate

`gc.alloc.rate.norm` per operation. The cycling benchmarks start a new trace every
`cycle` operations; the gate solves the per-operation bytes from two cycle lengths and
reports the per-trace setup apart.

| Benchmark | Measured | Gate |
|---|---|---|
| `span`, `spanNotInlined` | 0.0002 B/op (1,024 B per trace) | 0.01 B/op |
| `phase` | 0.0001 B/op (1,800 B per trace) | 0.01 B/op |
| `done` | 0.0001 B/op (1,736 B per trace) | 0.01 B/op |
| `setLong` | 0.0000 B/op (1,232 B per trace) | 0.01 B/op |
| `detailDisabled`, `currentDetailNoTrace`, `currentDetailCoarse` | 0.0000 B/op | 0.01 B/op |
| `alwaysOn` | 0.0002 B/op | 0.01 B/op |
| `folded` | 0.0003 B/op | 0.01 B/op |
| `waited` | 0.0002 B/op | 0.01 B/op |
| `handoff` | 32.0001 B/op (960 B per trace) | 32.01 B/op: the one `Handoff` |
| `handoffInline` | 32.0004 B/op (1,296 B per trace) | 32.01 B/op |
| `handoffFolded` | 32.0005 B/op | 32.01 B/op |
| `traceLifecycle` | 960 B per trace | 1,024 B |
| `snapshotToHeader` (300 spans) | 5,240 B per render | 6,144 B |
| `toHeader` (300 spans) | 22,568 B per render | 24,576 B |
| `snapshotToHeaderLadder` (300 spans, 1,000 bytes) | 57,712 B per render | reported |
| `snapshotToLinkLadder` (300 spans, 1,000-byte link) | 64,259 B per render | reported |

The 300-span render writes a 3,440-character value. The encoder alone
(`snapshotToHeader`) allocates the output `String` (about 3.5 KB) and about 1.7 KB of
tables sized to the trace's own names and threads, plus walk state; its builder is reused
per thread. `toHeader` also takes the snapshot, which copies the trace's rows, events and
aggregates (about 17 KB here), so it is above the plan's "one output String". Rendering
straight from the live lanes would avoid that copy, at the cost of a second walker.

A value over its limit goes down the ladder of FORMAT.md §7.1, which reads the whole value
back into per-record arrays (about 40 KB for 300 records) and re-encodes after every step
into the same builder; a link limited as a whole also compresses each step with one
`Deflater`, ended after the render. Neither is gated: only a value over its limit pays.

## Times

Apple M3 Max, macOS, Amazon Corretto 24.0.2, JMH 1.37, one fork, 3 × 1 s warm-up,
5 × 1 s measurement, `cycle=1000`, 2026-11-13.

| Benchmark | ns/op | Note |
|---|---|---|
| `baselineNanoTime` | 10.2 ± 0.2 | one `System.nanoTime()` |
| `span` | 32.6 ± 4.8 | open + close, two clock reads (a second run; the first gave 36.6 ± 27.8) |
| `alwaysOn` | 27.8 ± 0.4 | aggregate-only span at `COARSE` |
| `folded` | 29.0 ± 2.1 | detail span past the budget |
| `handoff` | 18.8 ± 0.3 | submit: the row and the `Handoff` |
| `handoffInline` | 60.2 ± 2.7 | submit, begin and close on the submitting thread, recorded |
| `handoffFolded` | 50.3 ± 7.1 | the same past the budget: no row, the task folded |
| `traceLifecycle` | 114 ± 40 | start, close, release |
| `snapshotToHeader` | 14,772 ± 218 | encoder alone, 300 spans |
| `snapshotToHeaderLadder` | 47,513 ± 457 | the same under 1,000 bytes: the value read back, the largest group (the root's 30 children) folded, re-encoded (2026-11-14) |
| `snapshotToLinkLadder` | 94,863 ± 2,273 | a link of at most 1,000 bytes: each step also compressed (2026-11-14) |
| `toHeader` | 16,370 ± 118 | snapshot and encoder, 300 spans |
