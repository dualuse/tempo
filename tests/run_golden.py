#!/usr/bin/env python3
"""Run the Tempo-Trace golden vectors against the reference decoder/encoder.

  * testdata/golden/NAME.header must decode to testdata/golden/NAME.json
    (structural JSON equality; key order inside objects does not matter).
  * Round trip: decode(encode(json)) == json, with "truncated" set to false.
  * Canonical form: encode(json) == the header byte for byte, except for the
    vectors listed in NON_CANONICAL (they exercise forms decoders must accept
    but encoders never write).
  * Every prefix of every canonical vector either is rejected as cut
    (E_VERSION/E_CUT) or decodes to a prefix of the full span list.
  * testdata/invalid/*.header must be rejected with the code listed in
    testdata/invalid/README.md.
  * samples/**/*.header (if present) must decode to the matching .json, and
    tools/gen_traces.py must regenerate the samples byte for byte.

Exit status 0 when everything passes. Python 3.8+, standard library only.
"""

import glob
import json
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import tempo_ref  # noqa: E402

NON_CANONICAL = {
    "lenient": "explicit @ equal to the parent, lowercase hex, leading zeros, x=0.0, flag order, repeated keys",
    "whitespace": "line-wrapped",
    "truncated-cut": "truncated",
    "truncated-semicolon": "truncated",
    "truncated-count": "truncated",
    "truncated-empty": "truncated",
}


class Result(object):
    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, label, ok, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failed.append("%s %s" % (label, detail))
        return ok


def read(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return fh.read()


def first_diff(a, b):
    sa = json.dumps(a, sort_keys=True, ensure_ascii=False).splitlines()
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k == "spans":
                continue
            if a.get(k) != b.get(k):
                return "field %r: got %r, want %r" % (k, a.get(k), b.get(k))
        sa, sb = a.get("spans", []), b.get("spans", [])
        for i in range(max(len(sa), len(sb))):
            x = sa[i] if i < len(sa) else None
            y = sb[i] if i < len(sb) else None
            if x != y:
                return "span %d: got %s, want %s" % (i, json.dumps(x), json.dumps(y))
    return "differs: %s" % sa[:1]


def run_golden(res):
    names = sorted(os.path.basename(p)[:-7] for p in glob.glob(os.path.join(ROOT, "testdata", "golden", "*.header")))
    for name in names:
        base = os.path.join(ROOT, "testdata", "golden", name)
        header = read(base + ".header")
        want = json.loads(read(base + ".json"))
        try:
            got = tempo_ref.decode(header)
        except tempo_ref.TempoFormatError as e:
            res.check("decode   " + name, False, str(e))
            continue
        res.check("decode   " + name, got == want, "" if got == want else first_diff(got, want))

        if not want["spans"]:
            continue  # nothing survived the cut; there is no trace to re-encode
        enc = tempo_ref.encode(want)
        back = tempo_ref.decode(enc)
        expect = dict(want, truncated=False)
        res.check("roundtrip " + name, back == expect, "" if back == expect else first_diff(back, expect))
        if name not in NON_CANONICAL:
            stripped = tempo_ref.strip_ws(header)
            res.check("canonical " + name, enc == stripped,
                      "" if enc == stripped else "\n  got  %s\n  want %s" % (enc, stripped))
            res.check("prefixes " + name, *check_prefixes(stripped, want))
    return len(names)


def check_prefixes(value, full):
    """A cut value must never yield a record the full value does not have."""
    for k in range(len(value)):
        try:
            got = tempo_ref.decode(value[:k])
        except tempo_ref.TempoFormatError as e:
            if e.code not in ("E_VERSION", "E_CUT"):
                return False, "cut at %d: %s" % (k, e)
            continue
        n = len(got["spans"])
        if not got["truncated"] or got["spans"] != full["spans"][:n]:
            return False, "cut at %d: not a truncated prefix" % k
    return True, ""


def run_invalid(res):
    readme = read(os.path.join(ROOT, "testdata", "invalid", "README.md"))
    expected = dict(re.findall(r"^\| `([^`]+\.header)` \| `(E_[A-Z]+)` \|", readme, re.M))
    files = sorted(os.path.basename(p) for p in glob.glob(os.path.join(ROOT, "testdata", "invalid", "*.header")))
    res.check("invalid  README lists every file", set(files) == set(expected),
              "files %s vs README %s" % (sorted(set(files) ^ set(expected)), ""))
    for f in files:
        try:
            tempo_ref.decode(read(os.path.join(ROOT, "testdata", "invalid", f)))
            res.check("invalid  " + f, False, "was accepted")
        except tempo_ref.TempoFormatError as e:
            res.check("invalid  " + f, e.code == expected.get(f), "got %s, want %s" % (e.code, expected.get(f)))
    return len(files)


def run_samples(res):
    paths = sorted(glob.glob(os.path.join(ROOT, "samples", "**", "*.header"), recursive=True))
    n = 0
    for p in paths:
        j = p[:-7] + ".json"
        if not os.path.exists(j):
            continue
        n += 1
        rel = os.path.relpath(p, ROOT)
        try:
            got = tempo_ref.decode(read(p))
        except tempo_ref.TempoFormatError as e:
            res.check("sample   " + rel, False, str(e))
            continue
        want = json.loads(read(j))
        res.check("sample   " + rel, got == want, "" if got == want else first_diff(got, want))
        enc = tempo_ref.encode(want)
        res.check("sample canonical " + rel, enc == tempo_ref.strip_ws(read(p)))
    return n


def run_regenerate(res):
    if not os.path.isdir(os.path.join(ROOT, "samples")):
        return 0
    import gen_traces
    tmp = tempfile.mkdtemp(prefix="tempo-samples-")
    try:
        gen_traces.write_samples(tmp, 3000)
        n = 0
        for dirpath, _, files in os.walk(tmp):
            for f in sorted(files):
                n += 1
                rel = os.path.relpath(os.path.join(dirpath, f), tmp)
                committed = os.path.join(ROOT, "samples", rel)
                same = os.path.exists(committed) and read(committed) == read(os.path.join(dirpath, f))
                res.check("regenerate samples/" + rel, same, "differs from the generator output")
        return n
    finally:
        shutil.rmtree(tmp)


def main():
    res = Result()
    g = run_golden(res)
    i = run_invalid(res)
    s = run_samples(res)
    r = run_regenerate(res)
    for f in res.failed:
        print("FAIL " + f)
    print("golden vectors: %d, invalid vectors: %d, samples: %d, regenerated files: %d" % (g, i, s, r))
    print("checks passed: %d, failed: %d" % (res.passed, len(res.failed)))
    return 1 if res.failed else 0


if __name__ == "__main__":
    sys.exit(main())
