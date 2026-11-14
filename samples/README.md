# Sample traces

Synthetic traces with invented names, produced by `tools/gen_traces.py`. Each trace
comes as `NAME.header` (the `Tempo-Timing` value) and `NAME.json` (the same trace in the
JSON form of [`../FORMAT.md`](../FORMAT.md)). All headers fit 3,000 bytes.

| File | Shape |
|---|---|
| `small` | A short search request: few candidates, idle pools, nothing folded. |
| `find-healthy` | Search request with 48 candidates, 8 cpu and 8 io threads, light background load. Short queue waits; the rerank hot loop folds into aggregates. |
| `find-saturated` | The same request against a 4-thread cpu pool that other requests keep busy. Queue wait grows through the rerank, the early exit leaves matches queued (`^n` aggregate) and running (`^o`), and those lanes are live at render. |
| `pipeline` | `PUT /video`: a 4-stage frame pipeline (read, decode, describe, store) over io and cpu pools, folded into per-stage aggregates on mixed threads. |
| `diff/before`, `diff/after` | The same request (same seed) with loads and matches sharing one 12-thread pool, and with the pool split into 6 cpu and 6 io threads. |
| `batch/find-01` … `find-30` | 30 requests of the same shape (40 candidates, moderately loaded pools), for per-path statistics over many traces. |

Regenerate the whole set (deterministic; `tests/run_golden.py` checks it):

```
python3 tools/gen_traces.py --samples samples
```

Single traces:

```
python3 tools/gen_traces.py --seed 3 --candidates 64 --cpu 4 --saturation 1.1
python3 tools/gen_traces.py --mode pipeline --frames 300 --format json
python3 tools/gen_traces.py --help
```
