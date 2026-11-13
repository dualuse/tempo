# Invalid Tempo-Trace values

Every `*.header` file here must be rejected by a decoder. The expected error code
(see FORMAT.md, "Errors") is listed per file; `tests/run_golden.py` reads this table.
Each file has exactly one defect; `empty.header` is an empty file. Trailing newlines
are whitespace and are stripped like any other whitespace before decoding.

| File | Error | Defect |
|---|---|---|
| `version-2.header` | `E_VERSION` | Version other than `v1`. |
| `no-version.header` | `E_VERSION` | Does not start with `v1;`. |
| `empty.header` | `E_VERSION` | Empty value. |
| `comma.header` | `E_CHAR` | Comma is outside the alphabet. |
| `quote.header` | `E_CHAR` | Double quote is outside the alphabet. |
| `unknown-field.header` | `E_FIELD` | Field `q=` does not exist in v1. |
| `field-order.header` | `E_FIELD` | `N=` before `T=`. |
| `duplicate-field.header` | `E_FIELD` | `w=` twice. |
| `bad-x.header` | `E_FIELD` | `x=` needs `folded.dropped`. |
| `bad-wall.header` | `E_FIELD` | Uppercase base-36 digits. |
| `missing-names.header` | `E_MISSING` | No `N=` although `e=` says the value is complete. |
| `bad-percent.header` | `E_NAME` | `%G1` is not a hex escape. |
| `bad-utf8.header` | `E_NAME` | Escapes decode to invalid UTF-8. |
| `raw-slash-name.header` | `E_NAME` | `/` must be written `%2F` inside a name. |
| `empty-name.header` | `E_NAME` | Empty thread name between two colons. |
| `record-uppercase.header` | `E_RECORD` | Uppercase digits in a record. |
| `record-missing-dur.header` | `E_RECORD` | Record without a duration. |
| `bad-attr-number.header` | `E_RECORD` | Attribute number `1.` has no fraction digits. |
| `name-index.header` | `E_INDEX` | Name index 9 with three names. |
| `thread-index.header` | `E_INDEX` | Thread index 5 with two threads. |
| `attr-key-index.header` | `E_INDEX` | Attribute key 7 with three names. |
| `live-index.header` | `E_INDEX` | `l=` names thread 2 with two threads. |
| `depth-jump.header` | `E_DEPTH` | Depth 1 followed by depth 3. |
| `root-depth.header` | `E_ROOT` | First record is not at depth 0. |
| `second-root.header` | `E_ROOT` | A second depth-0 record. |
| `root-thread.header` | `E_ROOT` | The root carries `@` (it is thread 0 by definition). |
| `root-delta.header` | `E_ROOT` | The root startDelta is not 0. |
| `unknown-flag.header` | `E_FLAG` | Flag `x` does not exist. |
| `mixed-thread-plain.header` | `E_AGG` | `@_` on a record that is not an aggregate. |
| `aggregate-children.header` | `E_AGG` | A record nested under an aggregate. |
| `wait-pair-plain.header` | `E_AGG` | `~sum.max` on a record that is not an aggregate. |
| `aggregate-wait-single.header` | `E_AGG` | Aggregate wait without the max. |
| `aggregate-count-zero.header` | `E_AGG` | Aggregate with count 0. |
| `cut-in-tables.header` | `E_CUT` | Cut inside the name table: no span list survives. |
| `cut-before-spans.header` | `E_CUT` | Cut right before `S=`. |
