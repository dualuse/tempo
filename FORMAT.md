# Tempo-Timing format, version 1

This document specifies the two wire forms of a Tempo trace:

- the **header value** carried in the `Tempo-Timing` HTTP response header, a single line
  of at most a few kilobytes, and
- the **JSON form**, which is what a header value decodes to and what a response body
  can carry directly when there is no size limit.

A header value can also travel inside a URL, in the `Tempo-Link` response header, so
that a click in the terminal opens the trace in a web viewer (§9).

Every parser and encoder of the project is tested against the vectors in
[`testdata/`](testdata/). The key words MUST, MUST NOT, SHOULD and MAY are used as in
RFC 2119.

## 1. Model

A trace is a tree of **spans** that starts at one **root** span. Spans run on
**threads**; a child may run on another thread than its parent.

| Term | Meaning |
|---|---|
| span | A named interval: start, duration, thread, optional CPU time, flags and numeric attributes. |
| handoff | A span whose work was submitted to another thread (usually a pool). It has a submit time, a **queue wait** (submit to start) and a run time. |
| aggregate | One record that stands for many same-named sibling spans that were folded to save space (a hot loop). It carries count, sum, min, max, extent and wait statistics. |
| lane | The spans one thread recorded for this trace. A lane still running when the header was rendered is **live**. |

All times are integer **microseconds**. Starts are relative to the root's start. The
wall-clock time of the root's start is carried separately in epoch milliseconds.

## 2. Alphabet

After whitespace removal (§6.1) a header value consists only of

```
0-9  A-Z  a-z  .  /  :  ;  =  @  ~  *  +  ^  _  %  -
```

Why this set:

- **No comma.** HTTP intermediaries may merge several header lines into one value
  separated by commas, or split a value at commas.
- **No whitespace, quote or backslash.** The value is never quoted and survives
  `curl -i`, log lines and copy-paste unchanged.
- **Whitespace is stripped by every decoder.** The real hazard on copy-paste is the
  terminal wrapping a long line. Because no meaningful character is whitespace,
  decoders remove all of it before parsing (§6.1).
- **Base 36 for numbers.** Lowercase `0-9a-z` keeps durations short (a minute in
  microseconds is 6 characters) and never collides with the separators.
- **Shell note.** `;`, `*`, `~` and `^` are shell syntax. Quote the value or pipe it,
  for example `pbpaste | tempo`.

## 3. Grammar (ABNF, RFC 5234)

```
value    = "v1" [";w=" b36] [";x=" b36 "." b36] [";l=" b36 *("." b36)]
           ";T=" name *(":" name)
           ";N=" name *(":" name)
           ";S=" record *("/" record)
           ";e=" b36
record   = depth nameIdx "." startDelta "." dur
           ["@" (b36 / "_")] ["~" b36 ["." b36]] ["*" b36]
           ["+" b36 "." b36 "." b36 "." b36 "." b36]
           ["^" 1*flag] *(":" b36 "=" number)
depth    = b36digit                      ; 0..35, exactly one character
nameIdx  = b36
startDelta = b36
dur      = b36
flag     = "f" / "c" / "o" / "n" / "w" / "m"
number   = ["-"] 1*DIGIT ["." 1*DIGIT]
name     = 1*(ALPHA / DIGIT / "." / "-" / "_" / "%" HEXDIG HEXDIG)
b36      = 1*b36digit                    ; lowercase base 36
b36digit = DIGIT / %x61-7A
```

Fields appear in exactly this order; each at most once. The record is unambiguous
because `depth` is always exactly one character: `26.1e.4pg` is depth 2, name 6.

**Per-record regular expression** (all parsers use this one):

```
^([0-9a-z])([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)(?:@([0-9a-z]+|_))?(?:~([0-9a-z]+)(?:\.([0-9a-z]+))?)?(?:\*([0-9a-z]+))?(?:\+([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+))?(?:\^([a-z]+))?((?::[0-9a-z]+=-?[0-9]+(?:\.[0-9]+)?)*)$
```

Groups: 1 depth, 2 nameIdx, 3 startDelta, 4 dur, 5 thread, 6 wait (or wait sum),
7 wait max, 8 CPU, 9–13 count/min/max/extent/maxAt, 14 flags, 15 attribute list
(split it with `:([0-9a-z]+)=(-?[0-9]+(?:\.[0-9]+)?)`).

## 4. Fields

| Field | Meaning |
|---|---|
| `v1` | Version. A decoder MUST reject any other version. |
| `w` | Wall-clock epoch milliseconds of the root's start. Optional. |
| `x=folded.dropped` | `folded`: spans folded into aggregates. `dropped`: spans that are not on the wire at all (depth or size limits, non-finite attribute values). Absent means `0.0`, i.e. complete. |
| `l` | Indexes of threads whose lane was still active at render; their records are best effort (an open span may have closed a moment later). |
| `T` | Thread table. The index is the identity; duplicate names are legal. The root runs on thread 0. |
| `N` | Name table, shared by span names and attribute keys. |
| `S` | Span records, `/`-separated, in pre-order (§5). |
| `e` | Record count, always last. If it is missing or does not match, the value was cut (§6.2). |

**Names** (in `T` and `N`) are UTF-8 strings written as follows:

- `A-Z a-z 0-9 . -` stand for themselves;
- a space is written `_`;
- every other byte of the UTF-8 encoding, including a literal `_`, is written `%XX`.
  Encoders MUST use uppercase hex; decoders MUST accept either case;
- a null or empty name is written as `?`, i.e. `%3F`.

So `GET /search` is `GET_%2Fsearch`, `snake_case` is `snake%5Fcase` and `Größe` is
`Gr%C3%B6%C3%9Fe`.

**Record fields.**

| Part | Meaning |
|---|---|
| `depth` | Depth in the tree: 0 for the root, parent depth + 1 otherwise. Equivalently: how many path segments the record keeps from the previous record's path before appending its own name. |
| `nameIdx` | Index into `N`. |
| `startDelta` | Microseconds since the start of the previous sibling, or since the parent's start for a first child. Never negative, so siblings are ordered by start. |
| `dur` | Run time in microseconds. Aggregate: the sum of the run times. |
| `@thread` | Index into `T`. Omitted when equal to the parent's thread. `@_` means mixed threads and is legal on aggregates only. |
| `~wait` | Queue wait (submit to start). Its presence makes the record a handoff (§6.5). Aggregate: `~waitSum.waitMax`. |
| `*cpu` | Thread CPU time in microseconds. Absent means not measured. |
| `+count.min.max.extent.maxAt` | Marks an aggregate (§6.6). |
| `^flags` | One or more of the flags below. |
| `:key=number` | Attributes; `key` is an index into `N`. |

**Flags.**

| Flag | Meaning |
|---|---|
| `f` | Failed. |
| `c` | Cancelled or rejected. |
| `o` | Still open at render; `dur` runs to the render time. |
| `n` | Handoff that never started. |
| `w` | Blocking wait (join, future get, queue take/put). |
| `m` | Misuse detected and healed by the recorder (mismatched close, foreign-thread call, …). |

**Numbers.** Attribute values are decimal. Integers are written as is. Fractional values
are fixed-point with at most 6 fraction digits, rounded to nearest (ties to even, like
C's `printf("%.6f")`), trailing zeros and a trailing `.` removed, `-0` written `0`.
Non-finite values are not written and are counted in `x=dropped`. On the wire an
integral double and an integer are the same thing (`2.0` is written `2`).

## 5. Pre-order and the depth/startDelta rules

Records list the tree depth-first, parents before children, siblings in start order.
The decoder keeps one cursor per depth:

```
start  = cur[depth] + startDelta
cur[depth]   = start
cur[depth+1] = start          # the next first child counts from here
```

`cur[depth]` thus holds the previous sibling's start, or the parent's start if the
record is a first child, because entering a parent resets the cursor of the level below.

A tree deeper than 35 cannot be written. Wire depth adds up along handoff chains, so it
can exceed what a single thread records; an encoder folds records deeper than 35 into
their depth-35 ancestor and counts them in `x=dropped`.

## 6. Decoding

### 6.1 Algorithm

```
decode(input):
    v = input with every TAB, LF, VT, FF, CR and SPACE removed
    if v does not start with "v1;":            error E_VERSION
    if v has a character outside §2:           error E_CHAR

    fields = split(v[3:], ";")
    last   = fields[-1]
    if last matches ^e=[0-9a-z]+$:
        expected = b36(last[2:]); drop last; cut = false
    else:
        cut = true                              # the value was cut somewhere
        if last in ("", "e", "e="): drop last   # cut inside the e= field
        if no fields left or the last field does not start with "S=":
                                                error E_CUT
    parse the remaining fields as key=value:
        unknown key, missing "=", duplicate or out of order (w x l T N S)
                                                error E_FIELD
        T, N or S missing                       error E_MISSING
        w, x, l not of their grammar            error E_FIELD
    threads = decode each name of T (§4)       error E_NAME on a bad name
    names   = decode each name of N             error E_NAME
    live    = l indexes, each < len(threads)    error E_INDEX

    records   = split(S, "/")
    truncated = cut or expected != len(records)
    if truncated: drop the last record without looking at it

    for i, rec in enumerate(records):
        m = RECORD_REGEX.match(rec)             error E_RECORD if no match
        d = depth
        if i == 0:
            d must be 0, startDelta 0, and no @, ~ or +      else E_ROOT
        else:
            d == 0                              error E_ROOT (second root)
            d > prevDepth + 1                   error E_DEPTH
            previous record is an aggregate and d > prevDepth
                                                error E_AGG
        nameIdx < len(names)                    else E_INDEX
        start = cur[d] + startDelta; cur[d] = cur[d+1] = start
        parent = path[d-1]  (none for the root)
        thread = @ value if present, -1 for "@_" (aggregates only, else E_AGG),
                 else parent's thread, else 0 for the root
        thread < len(threads)                   else E_INDEX
        flags: each in "fconwm"                 else E_FLAG
        attribute keys < len(names)             else E_INDEX
        aggregate: count >= 1, "~" must have both sum and max   else E_AGG
        plain record: "~" must have a single number             else E_AGG
        emit span i; path[d] = span i
```

A decoder reports the first error it meets. Each vector in `testdata/invalid/` has
exactly one defect, so the order only matters for inputs with several.

### 6.2 Truncation

A value can be cut by a proxy, a log line limit or a careless copy. Because `e=` is always
last, a cut value either lacks `e=` or was cut inside its digits; since counts have no
leading zeros, a cut count is always smaller than the true one and cannot match. In both
cases, and whenever `e=` does not match for any other reason, the decoder:

1. discards the **last** record without validating it, because it may be partial even
   when it looks well formed (`3y4o` cut to `3y` is still valid base 36);
2. validates every other record normally;
3. sets `truncated` in the result.

If the cut falls before `S=`, nothing usable remains and the value is rejected (`E_CUT`).
If it falls inside the first record, the result has no spans and `truncated: true`.

### 6.3 Threads

The root runs on thread 0 and MUST NOT carry `@`. A record without `@` runs on its
parent's thread. The thread index is resolved per record, so a span on `cpu-1` whose
child has no `@` puts that child on `cpu-1` too.

### 6.4 Attributes

`:key=number` pairs decode to a map from name to number. A number with a `.` is a
fraction, otherwise an integer; `-0` is 0. Attribute values beyond ±2^53 are not
exactly representable in JavaScript; encoders SHOULD keep within that range. A repeated
key keeps its first position and takes the last value.

### 6.5 Handoffs and timing

A record with `~` (and no `+`) is a handoff:

- `start` is the **submit** time;
- the work began at `start + wait` and ran for `dur`;
- the span ends at `start + wait + dur`.

A plain record ends at `start + dur`. Handoffs that did not run normally:

| Case | Record |
|---|---|
| Never started (`^n`) | `dur` 0, `~wait` = submit to render. The worker thread is unknown, so `@` is omitted and the record nominally sits on the parent's thread; viewers MUST NOT count it as run time there. |
| Cancelled or rejected before start (`^c`) | `dur` 0, `~wait` = submit to cancellation; thread as above. |
| Cancelled while running (`^c`) | Normal handoff record with the worker thread. |
| Open at render (`^o`) | `dur` = render time − (start + wait). |

A handoff executed inline on the submitting thread is an ordinary child without `~`.
A handoff may land on the parent's own thread (a pool thread submitting to its own
pool); then `~` is present and `@` is omitted.

### 6.6 Aggregates

A record with `+count.min.max.extent.maxAt` stands for `count` folded sibling spans of
the same name:

| Part | Meaning |
|---|---|
| `startDelta` | Positions the **first** occurrence's start (submit time for handoffs). |
| `dur` | Sum of the run times. |
| `count`, `min`, `max` | Over the folded occurrences only; spans emitted individually before folding started are not included. |
| `extent` | Wall time from the first start to the last end. The aggregate ends at `start + extent`. |
| `maxAt` | Offset of the slowest occurrence's start from the aggregate's start. |
| `~waitSum.waitMax` | Queue wait over the occurrences, when they were handoffs. Both numbers are required. |
| `*cpu` | Sum of the CPU times. |
| `@_` | The occurrences ran on more than one thread. |
| `^flags` | Flags shared by every occurrence (for instance a group of cancelled handoffs, `^c`). |

`dur / extent` is the effective parallelism of the group. An aggregate has no children.

## 7. Encoding rules (canonical form)

Encoders MUST produce values that decode as specified. The reference encoder
(`tools/tempo_ref.py`) additionally writes the **canonical form**, which golden vectors
use byte for byte:

- `w` when known; `x` only when either count is non-zero; `l` only when non-empty.
- `N` lists span names in pre-order of first use, then attribute keys that are not
  span names, in order of first use. Each name appears once.
- No leading zeros in base-36 numbers. `@` only when the thread differs from the
  parent's. Flags in the order `f c o n w m`, each once. Uppercase percent-escapes.
- Siblings in start order (required anyway, since `startDelta` cannot be negative).

Decoders MUST accept the non-canonical forms the grammar allows: lowercase hex escapes,
leading zeros, an explicit `@` equal to the parent's thread, `x=0.0`, flags in any order
or repeated (they normalise to the canonical order), and repeated attribute keys.

### 7.1 Byte budget

A header must fit a size limit (`headerBytes`). The renderer encodes once and, while the
value is too long, walks this ladder, re-encoding after each step, stopping at the first
that fits:

1. Fold same-name siblings under one parent into an aggregate, largest group first,
   keeping failed spans and the slowest occurrence individually (counted in `x=folded`).
2. Drop the deepest level, repeatedly (counted in `x=dropped`).
3. Drop attributes.
4. Keep only the root and its direct children, with `T` and `N` reduced to what those
   records use.

The value is never cut mid-record, and `length <= headerBytes` holds for every
`headerBytes >= 256`. Below 256, or if step 4 still does not fit, the value is the
**fallback**:

```
v1;x=0.<n>;T=%3F;N=%3F;S=00.0.<dur>;e=1
```

with `<n>` the number of spans not written and `<dur>` the root duration, both base 36.

## 8. JSON form

The JSON form is what a header value decodes to. A response body may embed it, for
example under a `"tempo"` key; tools look for an object with `"v": 1` and `"spans"`.

```json
{
  "v": 1, "unit": "us", "wall": 1794567890123, "folded": 72, "dropped": 0,
  "threads": ["http-7", "cpu-1", "cpu-2", "io-1"], "live": [], "truncated": false,
  "spans": [
    {"id":0,"parent":-1,"name":"GET /search","start":0,"dur":184200,"thread":0},
    {"id":3,"parent":0,"name":"fingerprint","start":8460,"dur":22900,"thread":1,"wait":310,"cpu":22000},
    {"id":7,"parent":5,"name":"load","start":80310,"dur":7400,"thread":3,"wait":6200,"flags":"f"},
    {"id":10,"parent":5,"name":"load","start":94400,"dur":54000,"thread":3,"cpu":2100,
     "agg":{"count":36,"min":600,"max":4100,"extent":61000,"maxAt":30000,"waitSum":1800,"waitMax":400}}
  ]
}
```

| Key | Type | Meaning |
|---|---|---|
| `v` | 1 | Version. |
| `unit` | `"us"` | Unit of every time value. |
| `wall` | integer | Epoch milliseconds of the root start. Omitted when unknown (no `w=`). |
| `folded`, `dropped` | integer | From `x=`; 0 when absent. |
| `threads` | string[] | Thread table; a span's `thread` indexes it. |
| `live` | integer[] | From `l=`, in wire order; `[]` when absent. |
| `truncated` | boolean | The value was cut (§6.2). |
| `spans` | object[] | In pre-order; `spans[i].id == i`. |

Span keys:

| Key | Presence | Meaning |
|---|---|---|
| `id` | always | Record index. |
| `parent` | always | Parent `id`, `-1` for the root. |
| `name` | always | Decoded name. |
| `start` | always | Absolute start in µs from the root start (submit time for handoffs, first occurrence for aggregates). |
| `dur` | always | Run time; the sum for aggregates. |
| `thread` | always | Thread index; `-1` for a mixed-thread aggregate. |
| `wait` | handoffs | Queue wait. |
| `cpu` | when measured | CPU time (sum for aggregates). |
| `flags` | when any | String of flags in the order `fconwm`. |
| `attrs` | when any | Object of name → number. |
| `agg` | aggregates | `count`, `min`, `max`, `extent`, `maxAt`, and `waitSum`, `waitMax` when the wire carries them. |

Conformance is **structural**: two JSON documents match when they parse to equal values
(object key order does not matter; numbers compare by value). Absent optional keys are
absent, never `null`. The JSON form carries no size limit and no ladder.

## 9. Tempo-Link

A service MAY send a second response header, `Tempo-Link`, whose value is a URL that
opens the same trace in a web viewer. Terminals turn URLs into links, so one click
shows the trace in the browser. The URL carries the whole `v1` value in its fragment,
so the trace does not need to be stored anywhere.

```
Tempo-Link: https://dualuse.de/tempo/#t=z.HU67coMwEPyaNGR86IR4WIxmaJJ0aeI6HhB…
```

(The whole link of the worked example, §10, is `testdata/golden/search-example.link`:
440 characters for the 415-character value.)

The default viewer is the Tempo Timeline at `https://dualuse.de/tempo/`; the base is
configurable, and a copy of the viewer hosted elsewhere, or opened from a `file:` URL,
reads the same links. A service MAY send `Tempo-Link` together with `Tempo-Timing` or
instead of it.

### 9.1 Grammar

```
link     = base "#" params            ; what encoders write
         / base "?" params            ; also read (see below), never written
base     = absolute-URI               ; RFC 3986 §4.3; no "#", "," or whitespace
params   = param *("&" param)
param    = "t=" packed                ; the trace (required)
         / "c=" packed                ; a second trace to compare with (optional)
         / "tab=" tab                 ; the view to open (optional)
         / pname "=" *pchar           ; anything else: ignored
packed   = "z." b64url / plain
b64url   = 1*(ALPHA / DIGIT / "-" / "_")    ; base64url (RFC 4648 §5) without "=" padding
plain    = the value with every "%" written as "%25"
tab      = "lanes" / "tree" / "agg" / "pools" / "critical" / "diff" / "stats"
pname    = 1*(ALPHA / DIGIT / "-" / "_" / ".")
```

The value packed into `t=` and `c=` is a header value as specified in §2–§7, with its
whitespace removed. Parameters are split at `&`, each at its first `=`. Unknown
parameters are ignored, an unknown `tab` too; a repeated parameter takes its last
value.

**Fragment, not query.** Encoders MUST put the parameters in the fragment (`#t=…`).
A browser never sends the fragment to a server (RFC 3986 §3.5; it is not part of the
request line, and it is not part of `Referer`), so the trace stays in the browser even
though the viewer page itself is fetched from a server. Readers MUST also accept the
same parameters in the query string (`<base>?t=z.…&tab=tree`), because people build
such links by hand. If the fragment carries `t=`, the parameters are taken from the
fragment only; otherwise from the query. A query never undergoes form decoding: `+`
stays `+` (a `z.` payload has none).

### 9.2 Packing

**`z.` form.** Take the UTF-8 bytes of the value (ASCII, §2), compress them with raw
DEFLATE (RFC 1951: no zlib header or checksum as in RFC 1950, no gzip wrapper as in
RFC 1952), encode the result as base64url without padding and prefix `z.`.

- Any compression level, strategy or block type is allowed. Two encoders, or two
  levels of the same encoder, give different links for the same value. Decoders MUST
  NOT depend on the exact compressed bytes, only on what they inflate to.
- The DEFLATE stream MUST be complete and MUST end the payload: an incomplete stream
  or bytes after its end are rejected (`E_LINK`). Unlike a cut header value (§6.2), a
  cut `z.` payload cannot be decoded in part.
- Encoders MUST NOT write `=` padding; decoders SHOULD accept it.
- Decoders MUST accept a payload that inflates to 1 MiB and MAY reject a larger one
  (`E_LINK`), as protection against compression bombs. The reference decoder stops
  at 16 MiB.

**Plain form.** The value itself, with every `%` written `%25`, so that the name
escapes of §4 survive the URL. Decoders replace every `%XX` escape by its byte; for a
link written by this grammar that only turns `%25` back into `%`, and it also undoes a
tool on the way that escaped, say, `^` as `%5E`.

**Which form.** Values of up to a few hundred characters come out about as long or
longer in the `z.` form; from about 500 characters on it is clearly shorter (§9.4). Encoders MAY use the plain form when it is shorter; the
viewer's share button picks the shorter of the two. A `Tempo-Link` header SHOULD use
the `z.` form anyway: base64url never ends a URL in a terminal's link detection or a
chat tool, whereas the plain form contains `;`, `*`, `~` and `^`, which some of them
treat as the end of a URL or as shell syntax (`^` is not even a URI character in
RFC 3986, although browsers accept it).

**Whitespace.** Decoders remove all whitespace from a link before parsing it, like
from a value (§6.1), because terminals wrap long URLs too.

### 9.3 Decoding

```
decode_link(url):
    url = url with all whitespace removed
    params = fragment parameters if they have a non-empty t=, else query parameters
    if neither has a non-empty t=:              error E_LINK
    for t (and c, when it is used):
        if it starts with "z.":
            base64url-decode the rest           error E_LINK (bad character, impossible length)
            inflate as raw DEFLATE              error E_LINK (not DEFLATE, incomplete,
                                                trailing bytes, too large)
            value = the bytes as UTF-8
        else:
            value = the parameter with every %XX replaced by its byte
        decode(value) as in §6                  its own error codes, truncation as in §6.2
```

A link only transports a value: a defect in the link is `E_LINK`, a defect in the value
it carries keeps the code of §11 (a link carrying a value with a depth jump is
`E_DEPTH`). `c=` is decoded the same way when a reader uses it, for example to open
the diff of the two traces; a reader that ignores `c=` need not decode it.

### 9.4 Size

A link carries the same `v1` value as `Tempo-Timing`, so the same byte budget and
ladder (§7.1) apply to the value **before** compression: `headerBytes` limits the
value, and the link comes out shorter. On the bundled samples the `z.` payload is
0.69–0.80 of the value's length (raw DEFLATE level 9):

| Sample | Value | `z.` payload | Ratio |
|---|---|---|---|
| `small` | 895 | 717 | 0.80 |
| `pipeline` | 1140 | 873 | 0.77 |
| `diff/before` | 1990 | 1409 | 0.71 |
| `diff/after` | 2067 | 1492 | 0.72 |
| `find-healthy` | 2218 | 1532 | 0.69 |
| `find-saturated` | 2226 | 1580 | 0.71 |
| `batch/find-01` … `30` | | | 0.69–0.80 |

The link adds the base and `#t=` (28 characters with the default base). Small values
do not compress: on the golden vectors, values of 27–415 characters give `z.` payloads
from 1 % shorter to 52 % longer; a deep but repetitive one (785) gives 0.60.

A response with both headers carries the trace twice, about 1.7–1.8 times the value
plus the base. A service with a limit on the total header size budgets both or sends
one. Browsers take URLs far longer than these; terminals, chat tools and log
pipelines that cut long lines are the practical limit, and a cut link fails as a
whole (`E_LINK`) where a cut header value still yields a truncated trace.

### 9.5 Both headers in one response

A response that carries both headers SHOULD carry the same value in both. Decoders
SHOULD use `Tempo-Timing` and MAY warn when the link decodes to a different trace
(compare the decoded JSON forms, not the bytes). If `Tempo-Timing` is rejected, a
decoder MAY fall back to the link. When several responses follow each other (`curl
-iL`), the headers are paired per response. A JSON form in the body still wins over
both, as it is not size-limited.

### 9.6 Privacy

The fragment stays on the client: the server that hosts the viewer sees a request for
the page and nothing of the trace, and the viewer decodes it locally. But the link
**is** the trace. It ends up in the browser history (and whatever syncs it), in
bookmarks, and in every chat, ticket or log it is pasted into or that records response
headers; anyone who has the link can read every span and thread name in it. Treat
`Tempo-Link` like `Tempo-Timing`: a service that only sends traces to some callers
sends the link only to them. A query-string link (§9.1) gives up the first protection,
since the query is sent to the viewer's server.

## 10. Worked example

A search request, 415 bytes (all names invented):

```
v1;w=mwevdy2z;x=20.0;T=http-7:cpu-1:cpu-2:io-1;N=GET_%2Fsearch:decode:describe:fingerprint:index:rerank:load:match:encode:k:bytes:score;S=00.0.3y4o/11.3c.6ek/12.6fe.vsg*uxw/13.a.ho4@1~8m*gz4/14.vtk.nj8:9=40/15.nkm.26js/26.1e.4pg@3~19:a=48213/26.a.5pk@3~4s8^f/27.4ry.794@1~k*77q:b=0.83/27.5sc.6sg@2~f*6po:b=0.41/26.b4.15o0@3~1e0.b4*1mc+10.go.35w.1b2g.n5c/27.4mo.35w0@_~p0.1o*35ck+10.1mc.794.1o6o.144g/18.26l6.1jk;e=d
```

`w=mwevdy2z` is 1794567890123 (2026-11-13T11:04:50.123Z). Names: 0 `GET /search`,
1 `decode`, 2 `describe`, 3 `fingerprint`, 4 `index`, 5 `rerank`, 6 `load`, 7 `match`,
8 `encode`, 9 `k`, a `bytes`, b `score`.

| Record | Decoded (µs) |
|---|---|
| `00.0.3y4o` | `GET /search`, start 0, duration 184200, thread `http-7` |
| `11.3c.6ek` | `decode`, start 0+120 = 120, duration 8300 |
| `12.6fe.vsg*uxw` | `describe`, start 120+8330 = 8450, duration 41200, CPU 40100 |
| `13.a.ho4@1~8m*gz4` | `fingerprint` (handoff), submitted 8450+10 = 8460, on `cpu-1`, waited 310, ran 22900, CPU 22000 |
| `14.vtk.nj8:9=40` | `index`, start 8460+41240 = 49700, duration 30500, `k=40` |
| `15.nkm.26js` | `rerank`, start 49700+30550 = 80250, duration 101800 |
| `26.1e.4pg@3~19:a=48213` | `rerank/load`, first child: submitted 80250+50 = 80300, on `io-1`, waited 45, ran 6100, `bytes=48213` |
| `26.a.5pk@3~4s8^f` | `rerank/load`, submitted 80310, waited 6200, ran 7400, failed |
| `27.4ry.794@1~k*77q:b=0.83` | `rerank/match`, submitted 80310+6190 = 86500, on `cpu-1`, waited 20, ran 9400, CPU 9350, `score=0.83` |
| `27.5sc.6sg@2~f*6po:b=0.41` | `rerank/match`, submitted 94000, on `cpu-2`, waited 15, ran 8800, CPU 8700, `score=0.41` |
| `26.b4.15o0@3~1e0.b4*1mc+10.go.35w.1b2g.n5c` | `rerank/load` aggregate: 36 calls from 94400, sum 54000, wait sum 1800, wait max 400, CPU 2100, min 600, max 4100, extent 61000, slowest at +30000 |
| `27.4mo.35w0@_~p0.1o*35ck+10.1mc.794.1o6o.144g` | `rerank/match` aggregate: 36 calls from 100400, sum 147600, wait sum 900, wait max 60, CPU 146900, min 2100, max 9400, extent 78000, slowest at +52000, mixed threads |
| `18.26l6.1jk` | `encode`, start 80250+101850 = 182100, duration 2000 |

`x=20.0` is 72 folded (the two aggregates of 36), none dropped; `e=d` is 13 records.
The full decoded JSON is `testdata/golden/search-example.json`.

## 11. Errors

| Code | Meaning |
|---|---|
| `E_VERSION` | Empty, or does not start with `v1;`. |
| `E_CHAR` | A character outside the alphabet (after whitespace removal). |
| `E_CUT` | Cut before the span list; nothing to decode. |
| `E_FIELD` | Unknown, malformed, duplicate or out-of-order field. |
| `E_MISSING` | `T=`, `N=` or `S=` missing from a value that is not cut. |
| `E_NAME` | Bad name: empty, a character outside the name set, a bad `%` escape, or invalid UTF-8. |
| `E_RECORD` | A record does not match the record expression. |
| `E_INDEX` | Name, thread, attribute-key or `l=` index out of range. |
| `E_ROOT` | First record not a plain depth-0 record with startDelta 0, or a second depth-0 record. |
| `E_DEPTH` | Depth grows by more than one. |
| `E_FLAG` | Unknown flag. |
| `E_AGG` | Aggregate rules broken: `@_` or `~sum.max` on a plain record, single `~` on an aggregate, count 0, or a child under an aggregate. |
| `E_LINK` | Bad `Tempo-Link` (§9): no `t=` in the fragment or the query, a `z.` payload that is not base64url or not raw DEFLATE, an incomplete stream, bytes after it, or too large. The value inside a good link reports the codes above. |

## 12. Test vectors

```
testdata/golden/NAME.header   one header value (may contain whitespace if that is the point)
testdata/golden/NAME.json     the expected JSON form
testdata/golden/NAME.link     the value as a Tempo-Link (canonical vectors; zlib level 9)
testdata/invalid/NAME.header  values that must be rejected; README.md lists the code per file
testdata/invalid/NAME.link    links that must be rejected; listed in the same README
```

`python3 tests/run_golden.py` decodes every golden vector and compares it with its JSON,
round-trips each JSON through the encoder, checks that canonical vectors re-encode byte
for byte, checks every invalid vector's error code, and validates the synthetic traces in
`samples/`. For links it checks that every `.link` yields its vector's value, and that
the value re-packed at other DEFLATE levels, in the plain form, in the query string,
padded or wrapped yields it too. Other implementations SHOULD run the same checks:
decode equality for every golden vector and link, and the listed error code for every
invalid one. See
[`testdata/README.md`](testdata/README.md) for what each vector covers.
