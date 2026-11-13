# Tempo-Trace test vectors

The contract for every Tempo-Trace parser and encoder; the format is specified in
[`../FORMAT.md`](../FORMAT.md). Run `python3 tests/run_golden.py` from the repository root.

## `golden/`

`NAME.header` holds one header value, `NAME.json` the expected decoded JSON form.
A decoder passes a vector when its output parses to the same JSON value (key order and
number formatting do not matter).

| Vector | Covers |
|---|---|
| `search-example` | The 415-byte worked example of FORMAT.md §9: handoffs with wait and CPU, a failed handoff, long and fractional attributes, two aggregates (one on mixed threads), `w=`, `x=`. |
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

## `invalid/`

Values that must be rejected, one defect each; [`invalid/README.md`](invalid/README.md)
lists the expected error code per file.
