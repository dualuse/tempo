#!/usr/bin/env python3
"""Reference decoder and encoder for the Tempo-Trace v1 header (see FORMAT.md).

This file exists to validate the golden vectors in testdata/ and to round-trip
them. It is deliberately small and strict; it is not the end-user CLI.

    tempo_ref.py decode [FILE|-]   header value  -> JSON form
    tempo_ref.py encode [FILE|-]   JSON form     -> canonical header value

Python 3.8+, standard library only.
"""

import json
import re
import sys

__all__ = ["TempoFormatError", "decode", "encode", "dump_json", "strip_ws"]

ALPHABET = frozenset("0123456789abcdefghijklmnopqrstuvwxyz"
                     "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                     "./:;=@~*+^_%-")
WHITESPACE = "\t\n\x0b\x0c\r "
FLAG_ORDER = "fconwm"
MAX_DEPTH = 35

RECORD_RE = re.compile(
    r"^([0-9a-z])([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)"
    r"(?:@([0-9a-z]+|_))?"
    r"(?:~([0-9a-z]+)(?:\.([0-9a-z]+))?)?"
    r"(?:\*([0-9a-z]+))?"
    r"(?:\+([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+))?"
    r"(?:\^([a-z]+))?"
    r"((?::[0-9a-z]+=-?[0-9]+(?:\.[0-9]+)?)*)$")
ATTR_RE = re.compile(r":([0-9a-z]+)=(-?[0-9]+(?:\.[0-9]+)?)")
B36_RE = re.compile(r"^[0-9a-z]+$")
NAME_RE = re.compile(r"^(?:[A-Za-z0-9._-]|%[0-9A-Fa-f]{2})+$")
FIELD_ORDER = ["w", "x", "l", "T", "N", "S"]


class TempoFormatError(ValueError):
    """Raised for a value that must be rejected. `code` is one of the E_* codes in FORMAT.md."""

    def __init__(self, code, message):
        ValueError.__init__(self, "%s: %s" % (code, message))
        self.code = code


def strip_ws(value):
    return "".join(ch for ch in value if ch not in WHITESPACE)


def b36(text, code="E_FIELD", what="number"):
    if not B36_RE.match(text):
        raise TempoFormatError(code, "bad base-36 %s %r" % (what, text))
    return int(text, 36)


def to_b36(n):
    if n < 0:
        raise ValueError("negative value cannot be encoded: %r" % n)
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if n == 0:
        return "0"
    out = []
    while n:
        n, r = divmod(n, 36)
        out.append(digits[r])
    return "".join(reversed(out))


# ---------------------------------------------------------------- names

def decode_name(text):
    if not text:
        raise TempoFormatError("E_NAME", "empty name")
    if not NAME_RE.match(text):
        raise TempoFormatError("E_NAME", "bad name %r" % text)
    raw = bytearray()
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "%":
            raw.append(int(text[i + 1:i + 3], 16))
            i += 3
        else:
            raw.append(0x20 if ch == "_" else ord(ch))
            i += 1
    try:
        return bytes(raw).decode("utf-8")
    except UnicodeDecodeError:
        raise TempoFormatError("E_NAME", "name %r is not valid UTF-8" % text)


def encode_name(name):
    if name is None or name == "":
        name = "?"
    out = []
    for byte in name.encode("utf-8"):
        ch = chr(byte)
        if (0x30 <= byte <= 0x39 or 0x41 <= byte <= 0x5A or 0x61 <= byte <= 0x7A
                or byte == 0x2E or byte == 0x2D):
            out.append(ch)
        elif byte == 0x20:
            out.append("_")
        else:
            out.append("%%%02X" % byte)
    return "".join(out)


# ---------------------------------------------------------------- numbers

def decode_number(text):
    if "." in text:
        v = float(text)
        return 0.0 if v == 0 else v
    v = int(text)
    return 0 if v == 0 else v


def encode_number(v):
    if isinstance(v, bool):
        raise ValueError("booleans are not attribute values")
    if isinstance(v, int):
        return str(v)
    if v != v or v in (float("inf"), float("-inf")):
        return None  # non-finite: dropped by the encoder
    s = "%.6f" % v
    s = s.rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    return s


# ---------------------------------------------------------------- decoder

def decode(value):
    """Decode a Tempo-Trace value (whitespace allowed) into the JSON form (a dict)."""
    v = strip_ws(value)
    if not v.startswith("v1;"):
        raise TempoFormatError("E_VERSION", "value does not start with 'v1;'")
    for ch in v:
        if ch not in ALPHABET:
            raise TempoFormatError("E_CHAR", "character %r outside the alphabet" % ch)

    fields = v[3:].split(";")
    expected = None
    cut = False
    last = fields[-1]
    if last.startswith("e=") and B36_RE.match(last[2:]):
        expected = int(last[2:], 36)
        fields = fields[:-1]
    else:
        cut = True
        if last in ("", "e", "e="):
            fields = fields[:-1]
        if not fields or not fields[-1].startswith("S="):
            raise TempoFormatError("E_CUT", "value cut before the span list")

    seen = {}
    pos = -1
    for f in fields:
        if "=" not in f:
            raise TempoFormatError("E_FIELD", "field %r has no '='" % f)
        key, val = f.split("=", 1)
        if key not in FIELD_ORDER:
            raise TempoFormatError("E_FIELD", "unknown field %r" % key)
        idx = FIELD_ORDER.index(key)
        if idx <= pos:
            raise TempoFormatError("E_FIELD", "field %r duplicated or out of order" % key)
        pos = idx
        seen[key] = val
    for key in ("T", "N", "S"):
        if key not in seen:
            raise TempoFormatError("E_MISSING", "required field %s= is missing" % key)

    doc = {"v": 1, "unit": "us"}
    if "w" in seen:
        doc["wall"] = b36(seen["w"], what="w=")
    folded = dropped = 0
    if "x" in seen:
        parts = seen["x"].split(".")
        if len(parts) != 2:
            raise TempoFormatError("E_FIELD", "x= must be folded.dropped")
        folded, dropped = b36(parts[0], what="x="), b36(parts[1], what="x=")
    doc["folded"] = folded
    doc["dropped"] = dropped
    threads = [decode_name(t) for t in seen["T"].split(":")]
    names = [decode_name(n) for n in seen["N"].split(":")]
    live = []
    if "l" in seen:
        for p in seen["l"].split("."):
            i = b36(p, what="l=")
            if i >= len(threads):
                raise TempoFormatError("E_INDEX", "l= thread index %d out of range" % i)
            live.append(i)
    doc["threads"] = threads
    doc["live"] = live

    records = seen["S"].split("/")
    truncated = cut or (expected is not None and expected != len(records))
    if truncated:
        records = records[:-1]
    doc["truncated"] = truncated
    doc["spans"] = decode_records(records, names, threads)
    return doc


def decode_records(records, names, threads):
    spans = []
    cur = [0] * (MAX_DEPTH + 2)
    path = [None] * (MAX_DEPTH + 1)   # span dict at each depth on the current path
    prev_depth = -1
    prev_agg = False
    for i, rec in enumerate(records):
        m = RECORD_RE.match(rec)
        if not m:
            raise TempoFormatError("E_RECORD", "record %d %r is malformed" % (i, rec))
        (depth_s, name_s, delta_s, dur_s, thr_s, wait_s, wmax_s, cpu_s,
         a_count, a_min, a_max, a_ext, a_at, flags_s, attrs_s) = m.groups()
        depth = int(depth_s, 36)
        is_agg = a_count is not None
        if i == 0:
            if depth != 0:
                raise TempoFormatError("E_ROOT", "first record has depth %d" % depth)
            if thr_s is not None or wait_s is not None or is_agg:
                raise TempoFormatError("E_ROOT", "root record carries @, ~ or +")
            if int(delta_s, 36) != 0:
                raise TempoFormatError("E_ROOT", "root startDelta is not 0")
        else:
            if depth == 0:
                raise TempoFormatError("E_ROOT", "record %d is a second root" % i)
            if depth > prev_depth + 1:
                raise TempoFormatError("E_DEPTH", "record %d jumps from depth %d to %d"
                                       % (i, prev_depth, depth))
            if prev_agg and depth > prev_depth:
                raise TempoFormatError("E_AGG", "record %d is a child of an aggregate" % i)

        name_idx = int(name_s, 36)
        if name_idx >= len(names):
            raise TempoFormatError("E_INDEX", "record %d name index %d out of range" % (i, name_idx))
        start = cur[depth] + int(delta_s, 36)
        cur[depth] = start
        cur[depth + 1] = start
        parent = path[depth - 1] if depth > 0 else None

        if thr_s is None:
            thread = parent["thread"] if parent is not None else 0
        elif thr_s == "_":
            if not is_agg:
                raise TempoFormatError("E_AGG", "record %d: '@_' on a non-aggregate" % i)
            thread = -1
        else:
            thread = int(thr_s, 36)
            if thread >= len(threads):
                raise TempoFormatError("E_INDEX", "record %d thread index %d out of range" % (i, thread))

        span = {"id": i, "parent": parent["id"] if parent is not None else -1,
                "name": names[name_idx], "start": start, "dur": int(dur_s, 36),
                "thread": thread}
        if wait_s is not None and not is_agg:
            if wmax_s is not None:
                raise TempoFormatError("E_AGG", "record %d: '~sum.max' on a non-aggregate" % i)
            span["wait"] = int(wait_s, 36)
        if cpu_s is not None:
            span["cpu"] = int(cpu_s, 36)
        if flags_s is not None:
            for f in flags_s:
                if f not in FLAG_ORDER:
                    raise TempoFormatError("E_FLAG", "record %d: unknown flag %r" % (i, f))
            span["flags"] = "".join(f for f in FLAG_ORDER if f in flags_s)
        if attrs_s:
            attrs = {}
            for km in ATTR_RE.finditer(attrs_s):
                k = int(km.group(1), 36)
                if k >= len(names):
                    raise TempoFormatError("E_INDEX", "record %d attribute key %d out of range" % (i, k))
                # a repeated key keeps its first position; the last value wins
                attrs[names[k]] = decode_number(km.group(2))
            span["attrs"] = attrs
        if is_agg:
            agg = {"count": int(a_count, 36), "min": int(a_min, 36), "max": int(a_max, 36),
                   "extent": int(a_ext, 36), "maxAt": int(a_at, 36)}
            if agg["count"] < 1:
                raise TempoFormatError("E_AGG", "record %d: aggregate count 0" % i)
            if wait_s is not None:
                if wmax_s is None:
                    raise TempoFormatError("E_AGG", "record %d: aggregate wait needs sum.max" % i)
                agg["waitSum"] = int(wait_s, 36)
                agg["waitMax"] = int(wmax_s, 36)
            span["agg"] = agg
        path[depth] = span
        prev_depth = depth
        prev_agg = is_agg
        spans.append(span)
    return spans


# ---------------------------------------------------------------- encoder

def encode(doc):
    """Encode the JSON form into the canonical header value (no budget ladder)."""
    spans = doc["spans"]
    threads = doc["threads"]
    if not spans:
        raise ValueError("a trace needs at least the root span")
    depth_of = {}
    thread_of = {}
    stack = []           # ids on the current path
    for i, s in enumerate(spans):
        if s["id"] != i:
            raise ValueError("span ids must equal their array index (pre-order)")
        p = s["parent"]
        if i == 0:
            if p != -1 or s["thread"] != 0 or s["start"] != 0:
                raise ValueError("span 0 must be the root: parent -1, thread 0, start 0")
            depth_of[i] = 0
        else:
            while stack and stack[-1] != p:
                stack.pop()
            if not stack:
                raise ValueError("span %d: parent %r is not on the current path" % (i, p))
            if "agg" in spans[p]:
                raise ValueError("span %d: an aggregate cannot have children" % i)
            depth_of[i] = depth_of[p] + 1
            if depth_of[i] > MAX_DEPTH:
                raise ValueError("span %d deeper than %d" % (i, MAX_DEPTH))
        stack.append(i)
        thread_of[i] = s["thread"]

    # name table: span names in pre-order of first use, then attribute-only keys
    name_idx = {}
    for s in spans:
        name_idx.setdefault(s["name"] or "?", len(name_idx))
    for s in spans:
        for k in s.get("attrs", {}):
            name_idx.setdefault(k or "?", len(name_idx))

    out = ["v1"]
    if doc.get("wall") is not None:
        out.append("w=" + to_b36(doc["wall"]))
    folded, dropped = doc.get("folded", 0), doc.get("dropped", 0)
    recs = []
    cur = [0] * (MAX_DEPTH + 2)
    for i, s in enumerate(spans):
        d = depth_of[i]
        delta = s["start"] - cur[d]
        if delta < 0:
            raise ValueError("span %d starts before its previous sibling or parent" % i)
        cur[d] = cur[d + 1] = s["start"]
        r = [to_b36(d), to_b36(name_idx[s["name"] or "?"]), ".", to_b36(delta), ".", to_b36(s["dur"])]
        agg = s.get("agg")
        if i > 0 and s["thread"] != thread_of[s["parent"]]:
            if s["thread"] == -1:
                if agg is None:
                    raise ValueError("span %d: thread -1 only on aggregates" % i)
                r.append("@_")
            else:
                r.append("@" + to_b36(s["thread"]))
        if agg is not None:
            if "waitSum" in agg:
                r.append("~%s.%s" % (to_b36(agg["waitSum"]), to_b36(agg["waitMax"])))
        elif "wait" in s:
            r.append("~" + to_b36(s["wait"]))
        if "cpu" in s:
            r.append("*" + to_b36(s["cpu"]))
        if agg is not None:
            r.append("+" + ".".join(to_b36(agg[k]) for k in ("count", "min", "max", "extent", "maxAt")))
        flags = s.get("flags", "")
        if flags:
            r.append("^" + "".join(f for f in FLAG_ORDER if f in flags))
        for k, val in s.get("attrs", {}).items():
            num = encode_number(val)
            if num is None:
                dropped += 1
                continue
            r.append(":%s=%s" % (to_b36(name_idx[k or "?"]), num))
        recs.append("".join(r))
    if folded or dropped:
        out.append("x=%s.%s" % (to_b36(folded), to_b36(dropped)))
    if doc.get("live"):
        out.append("l=" + ".".join(to_b36(t) for t in doc["live"]))
    out.append("T=" + ":".join(encode_name(t) for t in threads))
    out.append("N=" + ":".join(encode_name(n) for n in name_idx))
    out.append("S=" + "/".join(recs))
    out.append("e=" + to_b36(len(recs)))
    return ";".join(out)


# ---------------------------------------------------------------- JSON output

TOP_KEYS = ["v", "unit", "wall", "folded", "dropped", "threads", "live", "truncated"]


def dump_json(doc):
    """Stable, diff-friendly rendering of the JSON form: one span per line."""
    lines = ["{"]
    for k in TOP_KEYS:
        if k in doc:
            lines.append("  %s: %s," % (json.dumps(k), json.dumps(doc[k], ensure_ascii=False)))
    extra = [k for k in doc if k not in TOP_KEYS and k != "spans"]
    for k in extra:
        lines.append("  %s: %s," % (json.dumps(k), json.dumps(doc[k], ensure_ascii=False)))
    spans = doc.get("spans", [])
    if not spans:
        lines.append('  "spans": []')
    else:
        lines.append('  "spans": [')
        for i, s in enumerate(spans):
            sep = "," if i + 1 < len(spans) else ""
            lines.append("    " + json.dumps(s, ensure_ascii=False, separators=(",", ":")) + sep)
        lines.append("  ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


def _read(path):
    if path in (None, "-"):
        return sys.stdin.read()
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def main(argv):
    if len(argv) < 2 or argv[1] not in ("decode", "encode"):
        sys.stderr.write(__doc__)
        return 2
    text = _read(argv[2] if len(argv) > 2 else None)
    try:
        if argv[1] == "decode":
            sys.stdout.write(dump_json(decode(text)))
        else:
            doc = json.loads(text)
            if "tempo" in doc and "spans" not in doc:
                doc = doc["tempo"]
            sys.stdout.write(encode(doc) + "\n")
    except TempoFormatError as e:
        sys.stderr.write("invalid: %s\n" % e)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
