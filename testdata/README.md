# Tempo-Timing test vectors

The contract for every Tempo-Timing parser and encoder; the format is specified in
[`../FORMAT.md`](../FORMAT.md). Run `python3 tests/run_golden.py` from the repository root.

## `golden/`

`NAME.header` holds one header value, `NAME.json` the expected decoded JSON form.
A decoder passes a vector when its output parses to the same JSON value (key order and
number formatting do not matter).

Every canonical vector also has `NAME.link`: the same value as a `Tempo-Link` URL
(FORMAT.md §9) with the default base, packed with raw DEFLATE at zlib level 9. A link
decoder passes when the link yields exactly the value of `NAME.header`. Another
DEFLATE implementation or level gives other bytes; that is fine, which is why
`run_golden.py` also re-packs every value at levels 0, 1 and 6, in the plain form,
padded, line-wrapped and in the query string (`?t=`, which readers accept and encoders
never write), and checks that each decodes to the same value and that a `t=` in the
fragment wins over one in the query.

| Vector | Covers |
|---|---|
| `search-example` | The 415-byte worked example of FORMAT.md §10: handoffs with wait and CPU, a failed handoff, long and fractional attributes, two aggregates (one on mixed threads), `w=`, `x=`. |
| `fallback` | The fixed last-resort value `v1;x=0.<n>;T=%3F;N=%3F;S=00.0.<dur>;e=1`. |
| `single-root` | One record, nothing else. |
| `deep-nesting` | A chain down to depth 35 (`z`), then back up to depth 3 and 1: the `cur[d+1]` reset makes first children count from their new parent. |
| `threads` | Several threads, duplicate thread names, `@` without `~` (cross-thread child that is no handoff), inheritance of the parent's thread, explicit `@0` switching back. |
| `handoff` | Queue wait and CPU, children on the worker thread inheriting it, a handoff from a worker to another pool, a handoff to the submitter's own thread (`~` without `@`), `~0`, an inline child, a child that ends after its parent. |
| `handoff-not-run` | Cancelled before start (`^c`, `dur` 0, wait until cancel), cancelled while running, never started (`^n`, wait until render). |
| `failed-open` | Flags `f`, `o`, `w`, `m` and combinations; a trace rendered while open (root `^o`), live lanes. |
| `aggregates` | Aggregate on an explicit thread, on mixed threads (`@_`), without wait on the parent's thread, with flags, with attributes, with count 1. |
| `attributes` | Integers (0, negative, 2^53−1), fractions (0.5, −0.25, 0.000001, 123456.789, −3.141593), a key that is also a span name. |
| `names` | Percent-encoding: space as `_`, literal `_` as `%5F`, `/ : ; = %`, UTF-8 of 2, 3 and 4 bytes, `?` (`%3F`), raw `.` and `-`, encoded thread names. |
| `live-and-folded` | `x=` with folded and dropped counts, `l=` with three live lanes holding open spans. |
| `truncated-cut` | The example cut inside record 8: no `e=`, last record discarded. |
| `truncated-semicolon` | Cut right after the `;` ending `S=`: the last record looks complete but is discarded. |
| `truncated-count` | `e=` present but larger than the record count. |
| `truncated-empty` | Cut inside the root record: no spans, `truncated: true`. |
| `whitespace` | The example wrapped with CRLF, LF, spaces and a tab. |
| `lenient` | Non-canonical forms decoders must accept: `x=0.0`, lowercase `%2f`, leading zeros, explicit `@` equal to the parent's thread, unordered and repeated flags, a repeated attribute key, `1.50` and `-0`. |

All but `lenient`, `whitespace` and the four `truncated-*` vectors are in canonical form:
the reference encoder reproduces them byte for byte from their JSON.

### Wire-only vectors

Six canonical vectors are **wire-only**: they were written for the format and describe
traces the Java recorder cannot produce by design. Decoders and the reference encoder
must match them like any other vector; the Java encoder's test (`GoldenTest`) records the
closest trace it can and checks that byte for byte instead. The other six canonical
vectors, the worked example among them, are recorded through the public API and rendered
byte for byte.

| Vector | Why the recorder cannot produce it |
|---|---|
| `names` | Two children on other threads without `~`. Work reaches another thread only through a handoff, which always carries its queue wait. |
| `threads` | The same: children on other threads without `~`, among them a child back on the root's thread (`@0`). |
| `deep-nesting` | Depth 35 recorded by one thread: a thread's lane holds 35 open spans, depths 0 to 34. |
| `failed-open` | New children of the root after `rerank`, which stays open on the same thread until the render: one stack per thread. |
| `live-and-folded` | Open spans ending at different instants, and CPU time on open spans: a render has one instant, and CPU time is measured when a span ends. |
| `aggregates` | Two `load` tasks overlapping on `io-1` (a thread runs one task at a time), an aggregate with an attribute (a folded span keeps none), and two `match` aggregates under one parent (one per parent, lap and name). |

## `invalid/`

Values (`*.header`) and links (`*.link`) that must be rejected, one defect each;
[`invalid/README.md`](invalid/README.md) lists the expected error code per file.
