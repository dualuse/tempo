#!/usr/bin/env python3
"""Reference decoder and encoder for the Tempo-Timing v1 header (see FORMAT.md).

This file exists to validate the golden vectors in testdata/ and to round-trip
them. It is deliberately small and strict; it is not the end-user CLI.

    tempo_ref.py decode [FILE|-]   header value  -> JSON form
    tempo_ref.py encode [FILE|-]   JSON form     -> canonical header value
    tempo_ref.py fit N [FILE|-]    header value  -> the same in at most N bytes (FORMAT.md 7.1)
    tempo_ref.py link   [FILE|-]   header value  -> Tempo-Link URL (FORMAT.md section 9)
    tempo_ref.py unlink [FILE|-]   Tempo-Link URL -> header value

Python 3.8+, standard library only.
"""

import base64
import collections
import json
import os
import re
import subprocess
import sys
import zlib

__all__ = ["TempoFormatError", "decode", "encode", "fit", "fallback", "ladder_steps", "dump_json", "strip_ws",
           "DEFAULT_BASE", "default_base", "Link", "encode_link", "decode_link", "pack_value", "unpack_value", "link_params"]

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
    """Decode a Tempo-Timing value (whitespace allowed) into the JSON form (a dict)."""
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


# ---------------------------------------------------------------- byte budget (FORMAT.md 7.1)

MIN_BYTES = 256


def fit(doc, max_bytes):
    """The canonical value of doc in at most max_bytes: whole if it fits, else the first ladder step
    that fits (FORMAT.md 7.1), else the fallback. Below 256 bytes there is no ladder."""
    value = encode(doc)
    if len(value) <= max_bytes:
        return value
    if max_bytes >= MIN_BYTES:
        for _, value in ladder_steps(doc):
            if len(value) <= max_bytes:
                return value
    return fallback(doc)


def ladder_steps(doc):
    """Every value the ladder tries for doc, in order, as (step number, value)."""
    return _Ladder(doc).steps()


def fallback(doc):
    """v1;x=0.<n>;T=%3F;N=%3F;S=00.0.<dur>;e=1: n counts every span but the root."""
    spans = doc["spans"]
    n = doc.get("folded", 0) + doc.get("dropped", 0) + sum(1 for s in spans[1:] if "agg" not in s)
    return "v1;x=0.%s;T=%%3F;N=%%3F;S=00.0.%s;e=1" % (to_b36(n), to_b36(spans[0]["dur"]))


class _Ladder:
    """The records of a JSON form as flat pre-order lists, rewritten step by step."""

    def __init__(self, doc):
        self.doc = doc
        self.spans = [dict(s) for s in doc["spans"]]
        self.depth = []
        for s in self.spans:
            self.depth.append(0 if s["parent"] < 0 else self.depth[s["parent"]] + 1)
        self.removed = [False] * len(self.spans)
        self.folded = doc.get("folded", 0)
        self.dropped = doc.get("dropped", 0)
        self.attributes = True

    def steps(self):
        """Yields (step, value) after every change, in ladder order."""
        for group in self.groups():
            if self.fold(group):
                yield 1, self.value()
        while self.deepest() >= 3:
            self.drop(self.deepest())
            yield 2, self.value()
        if any("attrs" in s for s in self.spans):
            self.attributes = False
            yield 3, self.value()
        if self.drop(2):
            yield 4, self.value()

    @staticmethod
    def waits(s):
        return "waitSum" in s["agg"] if "agg" in s else "wait" in s

    def groups(self):
        """Same parent, name and waits; each: [first record, records it folds...], folding two or more."""
        by = collections.OrderedDict()
        for i, s in enumerate(self.spans[1:], 1):
            by.setdefault((s["parent"], s["name"], self.waits(s)), []).append(i)
        groups = []
        for members in by.values():
            slowest = None
            for i in members:
                if "agg" not in self.spans[i] and (slowest is None or self.spans[i]["dur"] > self.spans[slowest]["dur"]):
                    slowest = i
            fold = [i for i in members if i != slowest and ("agg" in self.spans[i] or "f" not in self.spans[i].get("flags", ""))]
            if len(fold) >= 2:
                groups.append([members[0]] + fold)
        groups.sort(key=lambda g: (-len(g), g[0]))
        return groups

    def remove(self, i):
        self.removed[i] = True
        agg = self.spans[i].get("agg")
        self.dropped += agg["count"] if agg else 1
        if agg:
            self.folded = max(0, self.folded - agg["count"])

    def fold(self, group):
        t = group[1]
        if self.removed[t]:
            return False
        first = self.spans[t]
        count = total = 0
        lo, hi, end, slow_at = None, -1, 0, 0
        wait_sum = wait_max = 0
        cpu = None
        thread = first["thread"]
        flags = set(FLAG_ORDER)
        for r in group[1:]:
            s = self.spans[r]
            agg = s.get("agg")
            longest = agg["max"] if agg else s["dur"]
            if longest > hi:
                hi = longest
                slow_at = s["start"] + agg["maxAt"] if agg else s["start"]
            count += agg["count"] if agg else 1
            total += s["dur"]
            least = agg["min"] if agg else s["dur"]
            lo = least if lo is None else min(lo, least)
            end = max(end, s["start"] + agg["extent"] if agg else s["start"] + s.get("wait", 0) + s["dur"])
            if self.waits(s):
                wait_sum += agg["waitSum"] if agg else s["wait"]
                wait_max = max(wait_max, agg["waitMax"] if agg else s["wait"])
            if "cpu" in s:
                cpu = (cpu or 0) + s["cpu"]
            if s["thread"] != thread:
                thread = -1
            flags &= set(s.get("flags", ""))
            if not agg:
                self.folded += 1
            i = r + 1
            while i < len(self.spans) and self.depth[i] > self.depth[r]:
                if not self.removed[i]:
                    self.remove(i)
                i += 1
            if r != t:
                self.removed[r] = True
        out = {k: first[k] for k in ("id", "parent", "name", "start")}
        out.update(dur=total, thread=thread)
        if cpu is not None:
            out["cpu"] = cpu
        if flags:
            out["flags"] = "".join(f for f in FLAG_ORDER if f in flags)
        out["agg"] = {"count": count, "min": lo, "max": hi, "extent": end - first["start"], "maxAt": slow_at - first["start"]}
        if self.waits(first):
            out["agg"].update(waitSum=wait_sum, waitMax=wait_max)
        self.spans[t] = out
        return True

    def deepest(self):
        return max(d for d, gone in zip(self.depth, self.removed) if not gone)

    def drop(self, depth):
        hit = [i for i, d in enumerate(self.depth) if d == depth and not self.removed[i]]
        for i in hit:
            self.remove(i)
        return bool(hit)

    def value(self):
        """The remaining records as a JSON form, encoded; threads reduced to those used."""
        keep = [i for i in range(len(self.spans)) if not self.removed[i]]
        new_id = {old: new for new, old in enumerate(keep)}
        used = sorted({self.spans[i]["thread"] for i in keep if self.spans[i]["thread"] >= 0})
        thread = {old: new for new, old in enumerate(used)}
        spans = []
        for i in keep:
            s = dict(self.spans[i])
            s["id"] = new_id[i]
            s["parent"] = -1 if s["parent"] < 0 else new_id[s["parent"]]
            s["thread"] = thread.get(s["thread"], -1)
            if not self.attributes:
                s.pop("attrs", None)
            spans.append(s)
        doc = dict(self.doc, spans=spans, folded=self.folded, dropped=self.dropped,
                   threads=[self.doc["threads"][t] for t in used],
                   live=[thread[t] for t in self.doc.get("live", []) if t in thread])
        return encode(doc)


# ---------------------------------------------------------------- Tempo-Link (FORMAT.md section 9)

DEFAULT_BASE = "https://dualuse.github.io/tempo/"
LINK_TABS = ("lanes", "tree", "agg", "pools", "critical", "diff", "stats")
def pages_of(repository):
    """The GitHub Pages site of owner/repo (owner lower-cased; owner.github.io is the site root), or None."""
    m = re.match(r"^([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)$", (repository or "").strip())
    if not m:
        return None
    owner, repo = m.group(1).lower(), m.group(2)
    return "https://%s.github.io/" % owner + ("" if repo.lower() == owner + ".github.io" else repo + "/")


def repository_of_remote(url):
    """owner/repo of a github.com remote URL (https, ssh or scp-like, with or without .git), or None."""
    m = re.match(r"^(?:(?:https?|ssh|git)://(?:[^@/]+@)?github\.com(?::\d+)?/|(?:[^@/]+@)?github\.com:)"
                 r"([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\.git)?/?$", (url or "").strip())
    return m.group(1) + "/" + m.group(2) if m else None


_CHECKOUT = []


def checkout_base():
    """When this file runs from a checkout of Tempo (tools/ beside site/index.html), the GitHub Pages site of
    its git remote origin, as the library build derives it; else None. A fork's checkout opens the fork's viewer."""
    if not _CHECKOUT:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        url = None
        if os.path.isfile(os.path.join(root, "site", "index.html")) and os.path.exists(os.path.join(root, ".git")):
            try:
                url = subprocess.run(["git", "-C", root, "config", "--get", "remote.origin.url"], stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, universal_newlines=True, timeout=5).stdout
            except (OSError, subprocess.SubprocessError):
                url = None
        _CHECKOUT.append(pages_of(repository_of_remote(url)))
    return _CHECKOUT[0]


def default_base(env=None):
    """The viewer links point at: $TEMPO_LINK_BASE, else the checkout's own Pages site, else DEFAULT_BASE."""
    env = os.environ if env is None else env
    return env.get("TEMPO_LINK_BASE") or checkout_base() or DEFAULT_BASE


B64URL_RE = re.compile(r"^[A-Za-z0-9_-]*$")
MAX_INFLATED = 16 * 1024 * 1024   # FORMAT.md 9.2: decoders accept at least 1 MiB

Link = collections.namedtuple("Link", ["value", "compare", "tab"])
Link.__doc__ = "A decoded Tempo-Link: the t= value, the c= value or None, the tab or None."


def pack_value(value, form="z", level=9):
    """One fragment parameter value: 'z.' + base64url(raw deflate) or the plain form.

    form is "z", "plain" or "auto" (the shorter of the two, as the web viewer does).
    """
    if form not in ("z", "plain", "auto"):
        raise ValueError("form must be z, plain or auto")
    plain = value.replace("%", "%25")
    if form == "plain":
        return plain
    co = zlib.compressobj(level, zlib.DEFLATED, -15)
    raw = co.compress(value.encode("utf-8")) + co.flush()
    z = "z." + base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    if form == "auto" and len(plain) <= len(z):
        return plain
    return z


def inflate_raw(data, limit=MAX_INFLATED):
    """Raw DEFLATE (RFC 1951) -> bytes. The stream must be complete and nothing may follow it."""
    d = zlib.decompressobj(-15)
    try:
        out = d.decompress(data, limit + 1)
    except zlib.error as e:
        raise TempoFormatError("E_LINK", "payload is not raw DEFLATE (%s)" % e)
    if len(out) > limit:
        raise TempoFormatError("E_LINK", "payload inflates to more than %d bytes" % limit)
    if not d.eof:
        raise TempoFormatError("E_LINK", "DEFLATE stream is incomplete (link cut?)")
    if d.unused_data:
        raise TempoFormatError("E_LINK", "%d bytes follow the DEFLATE stream" % len(d.unused_data))
    return out


def b64url_decode(text):
    s = text[:-2] if text.endswith("==") else text[:-1] if text.endswith("=") else text  # tolerated padding
    if not B64URL_RE.match(s):
        bad = next(ch for ch in s if not B64URL_RE.match(ch))
        raise TempoFormatError("E_LINK", "character %r is not base64url" % bad)
    if len(s) % 4 == 1:
        raise TempoFormatError("E_LINK", "base64url length %d is impossible" % len(s))
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def unpack_value(packed):
    """A fragment parameter value -> the Tempo-Timing value it carries (not yet validated)."""
    p = strip_ws(packed)
    if not p:
        raise TempoFormatError("E_LINK", "empty trace parameter")
    if p.startswith("z."):
        return inflate_raw(b64url_decode(p[2:])).decode("utf-8", "replace")
    return re.sub(r"%([0-9A-Fa-f]{2})", lambda m: chr(int(m.group(1), 16)), p)


def _params(text):
    out = {}
    for part in text.split("&"):
        k, eq, v = part.partition("=")
        if k and eq:
            out[k] = v                     # a repeated parameter: the last one wins
    return out


def link_params(url):
    """The parameters of a link: dict name -> raw value.

    Taken from the fragment when it carries t=, otherwise from the query string
    (FORMAT.md 9.1). Encoders always write the fragment form.
    """
    u = strip_ws(url)
    u, _, frag = u.partition("#")
    frag_params = _params(frag)
    if frag_params.get("t"):
        return frag_params
    query_params = _params(u.partition("?")[2])
    if query_params.get("t"):
        return query_params
    raise TempoFormatError("E_LINK", "link has no t= parameter in the fragment or the query")


def encode_link(value, base=DEFAULT_BASE, compare=None, tab=None, form="z", level=9):
    """The Tempo-Link URL for a Tempo-Timing value (and an optional second value to diff)."""
    if "#" in base or any(ch in WHITESPACE or ch == "," for ch in base):
        raise ValueError("base must not contain '#', ',' or whitespace: %r" % base)
    if tab is not None and not re.match(r"^[a-z]+$", tab):
        raise ValueError("bad tab %r" % tab)
    url = base + "#t=" + pack_value(strip_ws(value), form, level)
    if compare is not None:
        url += "&c=" + pack_value(strip_ws(compare), form, level)
    if tab is not None:
        url += "&tab=" + tab
    return url


def decode_link(url):
    """Decode a Tempo-Link URL into Link(value, compare, tab).

    Link-level defects raise E_LINK; the values themselves are validated by decode().
    """
    params = link_params(url)
    value = unpack_value(params["t"])
    compare = unpack_value(params["c"]) if params.get("c") else None
    return Link(value, compare, params.get("tab") or None)


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
    if len(argv) < 2 or argv[1] not in ("decode", "encode", "fit", "link", "unlink") \
            or argv[1] == "fit" and (len(argv) < 3 or not argv[2].isdigit()):
        sys.stderr.write(__doc__)
        return 2
    args = argv[3:] if argv[1] == "fit" else argv[2:]
    text = _read(args[0] if args else None)
    try:
        if argv[1] == "decode":
            sys.stdout.write(dump_json(decode(text)))
        elif argv[1] == "fit":
            sys.stdout.write(fit(decode(text), int(argv[2])) + "\n")
        elif argv[1] == "link":
            decode(text)
            sys.stdout.write(encode_link(text) + "\n")
        elif argv[1] == "unlink":
            link = decode_link(text)
            decode(link.value)
            sys.stdout.write(link.value + "\n")
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
