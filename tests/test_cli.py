#!/usr/bin/env python3
"""Tests for tools/tempo, the command-line viewer.

    python3 -m unittest discover -s tests -p "test_*.py"
    python3 tests/test_cli.py

Snapshots of every view live in tests/snapshots/. After an intended change of
the output, regenerate them with TEMPO_UPDATE_SNAPSHOTS=1 and review the diff.

Python 3.8+, standard library only.
"""

import ast
import glob
import importlib.machinery
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLI = os.path.join(ROOT, "tools", "tempo")
GOLDEN = os.path.join(ROOT, "testdata", "golden")
INVALID = os.path.join(ROOT, "testdata", "invalid")
SAMPLES = os.path.join(ROOT, "samples")
SNAPSHOTS = os.path.join(ROOT, "tests", "snapshots")
UPDATE = os.environ.get("TEMPO_UPDATE_SNAPSHOTS") == "1"
ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def load_cli():
    loader = importlib.machinery.SourceFileLoader("tempo_cli", CLI)
    spec = importlib.util.spec_from_loader("tempo_cli", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


tempo = load_cli()


def read(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return fh.read()


def cli(args, stdin="", env=None, isatty=False):
    """Run the CLI in-process: (exit status, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    code = tempo.run(list(args), stdin=io.BytesIO(stdin.encode("utf-8")), stdout=out,
                     stderr=err, env=env if env is not None else {}, isatty=isatty)
    return code, out.getvalue(), err.getvalue()


def trace_of(path):
    [(label, t)] = tempo.load_text(read(path), path)
    if isinstance(t, Exception):
        raise t
    return t


def golden_names():
    return sorted(os.path.basename(p)[:-7] for p in glob.glob(os.path.join(GOLDEN, "*.header")))


def sample_headers():
    return sorted(glob.glob(os.path.join(SAMPLES, "**", "*.header"), recursive=True))


def invalid_table():
    table = {}
    for line in read(os.path.join(INVALID, "README.md")).splitlines():
        m = re.match(r"^\| `([^`]+\.header)` \| `(E_[A-Z]+)` \|", line)
        if m:
            table[m.group(1)] = m.group(2)
    return table


# ======================================================================
# Parser conformance
# ======================================================================

class GoldenVectors(unittest.TestCase):

    def test_every_golden_decodes_to_its_json(self):
        names = golden_names()
        self.assertGreaterEqual(len(names), 18)
        for name in names:
            with self.subTest(vector=name):
                code, out, err = cli(["--to", "json", os.path.join(GOLDEN, name + ".header")])
                self.assertEqual(code, 0, err)
                self.assertEqual(json.loads(out), json.loads(read(os.path.join(GOLDEN, name + ".json"))))

    def test_golden_json_form_is_accepted_as_input(self):
        for name in golden_names():
            with self.subTest(vector=name):
                path = os.path.join(GOLDEN, name + ".json")
                code, out, err = cli(["--to", "json", path])
                self.assertEqual(code, 0, err)
                self.assertEqual(json.loads(out), json.loads(read(path)))

    def test_check_accepts_complete_and_flags_truncated(self):
        for name in golden_names():
            with self.subTest(vector=name):
                code, out, err = cli(["--check", os.path.join(GOLDEN, name + ".header")])
                truncated = json.loads(read(os.path.join(GOLDEN, name + ".json")))["truncated"]
                self.assertEqual(code, 1 if truncated else 0, out + err)
                if truncated:
                    self.assertIn("truncated", err)
                else:
                    self.assertTrue(out.startswith("ok "), out)

    def test_every_invalid_vector_is_rejected_with_its_code(self):
        table = invalid_table()
        files = sorted(os.path.basename(p) for p in glob.glob(os.path.join(INVALID, "*.header")))
        self.assertEqual(sorted(table), files)
        for fname, code_expected in sorted(table.items()):
            path = os.path.join(INVALID, fname)
            with self.subTest(vector=fname):
                # raw decoder: exact error code
                with self.assertRaises(tempo.TempoFormatError) as ctx:
                    tempo.decode(read(path))
                self.assertEqual(ctx.exception.code, code_expected)
                # every entry point exits 1
                for args in (["--check"], [], ["--tree"], ["--to", "json"]):
                    code, out, err = cli(args + [path])
                    self.assertEqual(code, 1, (args, out, err))
                    if fname != "empty.header" and fname != "no-version.header":
                        self.assertIn(code_expected, err)

    def test_samples_decode_to_their_json(self):
        headers = sample_headers()
        self.assertGreaterEqual(len(headers), 36)
        for h in headers:
            with self.subTest(sample=os.path.relpath(h, ROOT)):
                code, out, err = cli(["--to", "json", h])
                self.assertEqual(code, 0, err)
                self.assertEqual(json.loads(out), json.loads(read(h[:-7] + ".json")))

    def test_decoder_matches_reference_decoder(self):
        sys.path.insert(0, os.path.join(ROOT, "tools"))
        try:
            import tempo_ref
        finally:
            sys.path.pop(0)
        for name in golden_names():
            text = read(os.path.join(GOLDEN, name + ".header"))
            self.assertEqual(tempo.decode(text), tempo_ref.decode(text), name)


# ======================================================================
# Input forms
# ======================================================================

class InputForms(unittest.TestCase):

    def setUp(self):
        self.value = read(os.path.join(SAMPLES, "small.header")).strip()
        self.expected = json.loads(read(os.path.join(SAMPLES, "small.json")))

    def decoded(self, text):
        code, out, err = cli(["--to", "json"], stdin=text)
        self.assertEqual(code, 0, err)
        return json.loads(out)

    def wrapped(self, n=70):
        return [self.value[i:i + n] for i in range(0, len(self.value), n)]

    def test_bare_value(self):
        self.assertEqual(self.decoded(self.value + "\n"), self.expected)

    def test_bare_value_wrapped_by_terminal(self):
        self.assertEqual(self.decoded("\n".join(self.wrapped(53)) + "\n"), self.expected)

    def test_bare_value_inside_a_log_line(self):
        self.assertEqual(self.decoded('12:00:01 INFO trace="%s" status=200\n' % self.value),
                         self.expected)

    def test_curl_i_output_case_insensitive_crlf(self):
        text = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                "tEmPo-TrAcE: %s\r\nServer-Timing: total;dur=1\r\n\r\n{\"ok\":true}\n" % self.value)
        self.assertEqual(self.decoded(text), self.expected)

    def test_curl_i_output_wrapped_lines(self):
        text = ("HTTP/2 200\r\ncontent-type: text/plain\r\nTempo-Trace: " + "\r\n".join(self.wrapped())
                + "\r\nx-request-id: 7\r\n\r\nbody\n")
        self.assertEqual(self.decoded(text), self.expected)

    def test_curl_i_output_folded_continuation(self):
        text = ("HTTP/1.1 200 OK\nTempo-Trace:\n " + "\n\t".join(self.wrapped(40))
                + "\nVia: proxy\n\n")
        self.assertEqual(self.decoded(text), self.expected)

    def test_json_body_with_tempo_object_under_info(self):
        body = {"status": "ok", "info": {"processed": {"stages": 4}, "tempo": self.expected}}
        self.assertEqual(self.decoded(json.dumps(body, indent=2)), self.expected)

    def test_curl_i_with_json_body_prefers_the_body(self):
        body = json.dumps({"info": {"tempo": self.expected}})
        text = ("HTTP/1.1 200 OK\r\nTempo-Trace: v1;T=a;N=b;S=00.0.1;e=1\r\n\r\n" + body + "\n")
        self.assertEqual(self.decoded(text), self.expected)

    def test_json_with_tempo_header_string(self):
        self.assertEqual(self.decoded(json.dumps({"meta": {"tempo": self.value}})), self.expected)

    def test_several_traces_feed_stats(self):
        other = read(os.path.join(SAMPLES, "batch", "find-01.header")).strip()
        log = "a %s\nb %s\nc %s\n" % (self.value, other, self.value)
        code, out, err = cli(["--stats", "--no-color"], stdin=log)
        self.assertEqual(code, 0, err)
        self.assertIn("over 3 traces", out)
        code, out, err = cli(["--check"], stdin=log)
        self.assertEqual(code, 0, err)
        self.assertEqual(out.count("ok "), 3)
        code, out, err = cli(["--to", "json"], stdin=log)
        self.assertEqual(json.loads(out), self.expected)
        self.assertIn("3 traces found", err)

    def test_nothing_found(self):
        code, out, err = cli([], stdin="hello world\n")
        self.assertEqual(code, 1)
        self.assertIn("E_VERSION", err)
        code, out, err = cli(["--check"], stdin='{"no": "tempo"}')
        self.assertEqual(code, 1)

    def test_bad_json_form(self):
        bad = dict(self.expected)
        bad["spans"] = [dict(s) for s in self.expected["spans"]]
        bad["spans"][3]["parent"] = 7
        code, out, err = cli(["--check"], stdin=json.dumps(bad))
        self.assertEqual(code, 1)
        self.assertIn("E_JSON", err)

    def test_missing_file(self):
        code, out, err = cli([os.path.join(ROOT, "no-such-file.header")])
        self.assertEqual(code, 1)


# ======================================================================
# Model rules
# ======================================================================

class Model(unittest.TestCase):

    def test_pool_grouping(self):
        self.assertEqual(tempo.pool_of("cpu-12"), "cpu")
        self.assertEqual(tempo.pool_of("io-1"), "io")
        self.assertEqual(tempo.pool_of("pool-1-thread-3"), "pool-1-thread")
        self.assertEqual(tempo.pool_of("Thread-7"), "Thread-7")
        self.assertEqual(tempo.pool_of("main"), "main")
        self.assertEqual(tempo.pool_of("-5"), "-5")

    def test_self_time_subtracts_same_lane_children_only(self):
        t = trace_of(os.path.join(GOLDEN, "search-example.header"))
        root = t.root
        same = sum(c.dur for c in root.children if c.thread == root.thread and not c.handoff)
        self.assertEqual(root.self_time, root.dur - same)
        rerank = [s for s in t.spans if s.name == "rerank"][0]
        # all children of rerank are handoffs to other threads: nothing subtracted
        self.assertEqual(rerank.self_time, rerank.dur)
        for s in t.spans:
            self.assertGreaterEqual(s.self_time, 0)

    def test_blocking_wait_is_subtracted_from_parent(self):
        t = trace_of(os.path.join(SAMPLES, "small.header"))
        describe = [s for s in t.spans if s.name == "describe"][0]
        awaits = [c for c in describe.children if "w" in c.flags]
        self.assertTrue(awaits)
        self.assertLess(describe.self_time, describe.dur - sum(a.dur for a in awaits) + 1000)

    def test_handoff_timing(self):
        t = trace_of(os.path.join(GOLDEN, "search-example.header"))
        fp = [s for s in t.spans if s.name == "fingerprint"][0]
        self.assertEqual((fp.start, fp.run_start, fp.end), (8460, 8770, 31670))
        agg = [s for s in t.spans if s.agg and s.name == "match"][0]
        self.assertEqual(agg.end, agg.start + 78000)
        self.assertEqual(agg.lane, ("mixed", "cpu"))

    def test_never_started_goes_to_the_queued_lane_of_its_pool(self):
        t = trace_of(os.path.join(SAMPLES, "find-saturated.header"))
        never = [s for s in t.spans if "n" in s.flags]
        self.assertTrue(never)
        for s in never:
            self.assertTrue(s.not_run)
            self.assertEqual(s.lane, ("queued", "cpu"))

    def test_filters(self):
        t = trace_of(os.path.join(SAMPLES, "find-healthy.header"))
        vis, roots = tempo.Filter(focus="rerank").apply(t)
        self.assertEqual([r.path for r in roots], ["rerank"])
        self.assertTrue(all(t.spans[i].key.startswith("rerank") for i in vis))
        vis, _ = tempo.Filter(focus="load").apply(t)
        self.assertTrue(vis and all(t.spans[i].name == "load" for i in vis))
        vis, _ = tempo.Filter(depth=1).apply(t)
        self.assertTrue(all(t.spans[i].depth <= 1 for i in vis))
        vis, _ = tempo.Filter(threads="^io-").apply(t)
        self.assertTrue(vis and all(tempo.thread_filter_label(t, t.spans[i]).startswith("io-")
                                    for i in vis))
        vis, _ = tempo.Filter(min_ms=5).apply(t)
        self.assertTrue(all(t.spans[i].end - t.spans[i].start >= 5000 or t.spans[i].parent is None
                            for i in vis))


# ======================================================================
# Snapshots
# ======================================================================

SNAP_SAMPLES = ["small", "find-healthy", "find-saturated", "pipeline", "diff/after"]
VIEWS = {"lanes": [], "tree": ["--tree"], "agg": ["--agg"], "pools": ["--pools"],
         "findings": ["--findings"], "critical": ["--critical"]}
EXTRA = {
    "find-saturated.lanes.ascii": (["--ascii"], "find-saturated"),
    "find-healthy.lanes.threads-io": (["--threads", "^io-"], "find-healthy"),
    "find-healthy.lanes.focus-rerank": (["--focus", "rerank"], "find-healthy"),
    "find-healthy.tree.depth-1": (["--tree", "--depth", "1"], "find-healthy"),
    "find-healthy.tree.min-ms-5": (["--tree", "--min-ms", "5"], "find-healthy"),
    "find-healthy.agg.focus-match": (["--agg", "--focus", "rerank/match"], "find-healthy"),
    "pipeline.lanes.width-60": (["--width", "60"], "pipeline"),
    "search-example.lanes": ([], "../testdata/golden/search-example"),
    "search-example.tree": (["--tree"], "../testdata/golden/search-example"),
    "failed-open.lanes": ([], "../testdata/golden/failed-open"),
    "handoff-not-run.lanes": ([], "../testdata/golden/handoff-not-run"),
    "truncated-cut.lanes": ([], "../testdata/golden/truncated-cut"),
    "truncated-empty.lanes": ([], "../testdata/golden/truncated-empty"),
}


class Snapshots(unittest.TestCase):
    maxDiff = None

    def check(self, snap, args):
        code, out, err = cli(args)
        self.assertEqual(code, 0, err)
        path = os.path.join(SNAPSHOTS, snap + ".txt")
        if UPDATE or not os.path.exists(path):
            if not UPDATE:
                self.fail("missing snapshot %s (run with TEMPO_UPDATE_SNAPSHOTS=1)" % path)
            os.makedirs(SNAPSHOTS, exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="") as fh:
                fh.write(out)
            return
        self.assertEqual(out, read(path), "snapshot %s differs" % snap)

    def test_views(self):
        for sample in SNAP_SAMPLES:
            src = os.path.join(SAMPLES, sample + ".header")
            for view, args in VIEWS.items():
                for mode, flag in (("plain", "--no-color"), ("ansi", "--color")):
                    snap = "%s.%s.%s" % (sample.replace("/", "-"), view, mode)
                    with self.subTest(snapshot=snap):
                        self.check(snap, args + [flag, "--width", "110", src])

    def test_filters_and_layouts(self):
        for snap, (args, sample) in sorted(EXTRA.items()):
            with self.subTest(snapshot=snap):
                src = os.path.normpath(os.path.join(SAMPLES, sample + ".header"))
                self.check(snap, args + ["--no-color", "--width", "100"] + [src]
                           if "--width" not in args else args + ["--no-color", src])

    def test_diff_and_stats(self):
        before = os.path.join(SAMPLES, "diff", "before.header")
        after = os.path.join(SAMPLES, "diff", "after.header")
        batch = sorted(glob.glob(os.path.join(SAMPLES, "batch", "*.header")))
        rel = os.path.relpath
        cwd = os.getcwd()
        os.chdir(ROOT)  # labels in the output are the paths as given
        try:
            for mode, flag in (("plain", "--no-color"), ("ansi", "--color")):
                with self.subTest(snapshot="diff." + mode):
                    self.check("diff." + mode, [flag, "--width", "120", rel(before, ROOT),
                                                "--diff", rel(after, ROOT)])
                with self.subTest(snapshot="stats." + mode):
                    self.check("stats." + mode, [flag, "--stats"] + [rel(b, ROOT) for b in batch])
        finally:
            os.chdir(cwd)

    def test_converters(self):
        for fmt in ("folded",):
            for sample in ("small", "find-saturated"):
                with self.subTest(snapshot="%s.%s" % (sample, fmt)):
                    self.check("%s.%s" % (sample, fmt),
                               ["--to", fmt, os.path.join(SAMPLES, sample + ".header")])

    def test_ansi_snapshots_match_plain_text(self):
        for path in glob.glob(os.path.join(SNAPSHOTS, "*.ansi.txt")):
            plain = path[:-len(".ansi.txt")] + ".plain.txt"
            with self.subTest(snapshot=os.path.basename(path)):
                self.assertIn("\x1b[", read(path))
                self.assertEqual(ANSI_RE.sub("", read(path)), read(plain))

    def test_plain_snapshots_have_no_escapes(self):
        for path in glob.glob(os.path.join(SNAPSHOTS, "*.txt")):
            if not path.endswith(".ansi.txt"):
                self.assertNotIn("\x1b", read(path), path)


# ======================================================================
# Layout and terminal handling
# ======================================================================

class Layout(unittest.TestCase):

    def test_lanes_fit_the_width(self):
        for h in sample_headers()[:12] + [os.path.join(GOLDEN, n + ".header") for n in golden_names()]:
            for width in (40, 72, 100, 160):
                for flag in ("--no-color", "--color", "--ascii"):
                    code, out, err = cli([flag, "--width", str(width), h])
                    self.assertEqual(code, 0, err)
                    for line in out.splitlines():
                        self.assertLessEqual(len(ANSI_RE.sub("", line)), width,
                                             "%s width %d %s: %r" % (h, width, flag, line))

    def test_ascii_output_is_ascii(self):
        for view in ([], ["--tree"], ["--pools"], ["--critical"], ["--findings"], ["--agg"]):
            code, out, err = cli(view + ["--ascii", "--no-color", "--width", "100",
                                         os.path.join(SAMPLES, "find-saturated.header")])
            self.assertEqual(code, 0, err)
            out.encode("ascii")

    def test_color_decision(self):
        src = [os.path.join(SAMPLES, "small.header"), "--width", "90"]
        self.assertNotIn("\x1b[", cli(src, isatty=False)[1])
        self.assertIn("\x1b[", cli(src, isatty=True, env={"TERM": "xterm"})[1])
        self.assertNotIn("\x1b[", cli(src, isatty=True, env={"NO_COLOR": "1"})[1])
        self.assertNotIn("\x1b[", cli(src, isatty=True, env={"TERM": "dumb"})[1])
        self.assertNotIn("\x1b[", cli(src + ["--no-color"], isatty=True)[1])
        self.assertIn("\x1b[", cli(src + ["--color"], isatty=False)[1])
        self.assertIn("\x1b[", cli(src + ["--color"], env={"NO_COLOR": "1"})[1])

    def test_width_from_columns_when_piped(self):
        code, out, err = cli([os.path.join(SAMPLES, "small.header")], env={"COLUMNS": "64"})
        self.assertTrue(max(len(line) for line in out.splitlines()) <= 64)

    def test_usage_errors(self):
        code, out, err = cli(["--threads", "(", os.path.join(SAMPLES, "small.header")])
        self.assertEqual(code, 2)
        code, out, err = cli(["--focus", "nope", os.path.join(SAMPLES, "small.header")])
        self.assertEqual(code, 2)
        self.assertIn("--focus", err)
        old = sys.stderr
        sys.stderr = io.StringIO()
        try:
            code, out, err = cli(["--tree", "--agg"])
        finally:
            sys.stderr = old
        self.assertEqual(code, 2)


# ======================================================================
# Converters
# ======================================================================

def all_inputs():
    return sample_headers() + [os.path.join(GOLDEN, n + ".header") for n in golden_names()]


class Converters(unittest.TestCase):

    def test_chrome_trace_is_valid(self):
        phases = set("XMbesf")
        for path in all_inputs():
            with self.subTest(trace=os.path.relpath(path, ROOT)):
                code, out, err = cli(["--to", "chrome", path])
                self.assertEqual(code, 0, err)
                doc = json.loads(out)
                ev = doc["traceEvents"]
                self.assertIsInstance(ev, list)
                self.assertEqual(doc["displayTimeUnit"], "ms")
                named = set(e["tid"] for e in ev if e["ph"] == "M" and e["name"] == "thread_name")
                opened = {}
                flows = {}
                t = trace_of(path)
                xs = 0
                for e in ev:
                    self.assertIn(e["ph"], phases, e)
                    self.assertIsInstance(e["pid"], int)
                    self.assertIsInstance(e["tid"], int)
                    if e["ph"] == "M":
                        continue
                    self.assertIsInstance(e["ts"], int)
                    self.assertGreaterEqual(e["ts"], 0)
                    if e["ph"] == "X":
                        xs += 1
                        self.assertGreaterEqual(e["dur"], 0)
                        self.assertIn(e["tid"], named)
                    elif e["ph"] == "b":
                        opened[e["id"]] = e["ts"]
                    elif e["ph"] == "e":
                        self.assertLessEqual(opened.pop(e["id"]), e["ts"])
                    elif e["ph"] in "sf":
                        flows.setdefault(e["id"], []).append(e["ph"])
                self.assertEqual(opened, {})
                for fid, kinds in flows.items():
                    self.assertEqual(sorted(kinds), ["f", "s"])
                self.assertEqual(xs, sum(1 for s in t.spans if not s.not_run))

    def test_chrome_times_match_spans(self):
        t = trace_of(os.path.join(GOLDEN, "search-example.header"))
        doc = json.loads(tempo.to_chrome(t))
        xs = {e["args"]["id"]: e for e in doc["traceEvents"] if e["ph"] == "X"}
        fp = [s for s in t.spans if s.name == "fingerprint"][0]
        self.assertEqual((xs[fp.id]["ts"], xs[fp.id]["dur"]), (8770, 22900))
        agg = [s for s in t.spans if s.agg and s.name == "load"][0]
        self.assertEqual((xs[agg.id]["ts"], xs[agg.id]["dur"]), (94400, 61000))
        self.assertEqual(doc["otherData"]["wall_ms"], 1794567890123)

    def test_folded_stacks(self):
        for path in all_inputs():
            with self.subTest(trace=os.path.relpath(path, ROOT)):
                code, out, err = cli(["--to", "folded", path])
                self.assertEqual(code, 0, err)
                total = 0
                stacks = set()
                for line in out.splitlines():
                    m = re.match(r"^(.+) (\d+)$", line)
                    self.assertTrue(m, line)
                    self.assertNotIn(m.group(1), stacks)
                    stacks.add(m.group(1))
                    total += int(m.group(2))
                t = trace_of(path)
                self.assertEqual(total, sum(s.self_time for s in t.spans))

    def test_json_round_trips_through_the_reference_encoder(self):
        sys.path.insert(0, os.path.join(ROOT, "tools"))
        try:
            import tempo_ref
        finally:
            sys.path.pop(0)
        for path in sample_headers():
            code, out, err = cli(["--to", "json", path])
            self.assertEqual(tempo_ref.encode(json.loads(out)), read(path).strip())


# ======================================================================
# Diff, statistics, critical path, pools, findings on generator samples
# ======================================================================

class Analysis(unittest.TestCase):

    def test_diff_pool_split(self):
        a = trace_of(os.path.join(SAMPLES, "diff", "before.header"))
        b = trace_of(os.path.join(SAMPLES, "diff", "after.header"))
        ta = {e["key"]: e for e in tempo.path_table(a, tempo.Filter().apply(a)[0])}
        tb = {e["key"]: e for e in tempo.path_table(b, tempo.Filter().apply(b)[0])}
        # splitting the shared pool removes most of the queue wait of the loads
        self.assertLess(tb["rerank/load"]["wait"], ta["rerank/load"]["wait"] / 2)
        self.assertLess(b.root.dur, a.root.dur)
        code, out, err = cli(["--no-color", os.path.join(SAMPLES, "diff", "before.header"),
                              "--diff", os.path.join(SAMPLES, "diff", "after.header")])
        self.assertEqual(code, 0, err)
        rows = [l for l in out.splitlines()[2:] if l and not l.startswith("Δ")]
        mags = []
        for line in rows:
            cols = line.split()
            path_end = cols.index(next(c for c in cols if "→" in c))
            deltas = [abs(float(x)) for x in cols[path_end + 2:path_end + 6]]
            mags.append(max(deltas))
        self.assertEqual(mags, sorted(mags, reverse=True))
        load = [l for l in rows if l.startswith("rerank/load ")][0]
        self.assertIn("%+.3f" % ((tb["rerank/load"]["wait"] - ta["rerank/load"]["wait"]) / 1000.0), load)

    def test_diff_against_itself_is_zero(self):
        p = os.path.join(SAMPLES, "find-healthy.header")
        code, out, err = cli(["--no-color", p, "--diff", p])
        self.assertEqual(code, 0, err)
        body = out.splitlines()[2:-1]
        self.assertTrue(body)
        for line in body:
            self.assertNotRegex(line, r"[+-](?!0\.000)\d+\.\d{3}")

    def test_stats_over_the_batch(self):
        files = sorted(glob.glob(os.path.join(SAMPLES, "batch", "*.header")))
        self.assertEqual(len(files), 30)
        traces = [trace_of(f) for f in files]
        sums = sorted(sum(s.dur for s in t.spans if s.key == "rerank/match") for t in traces)
        p50, p95 = sums[14], sums[28]          # nearest rank: ceil(0.5*30)=15, ceil(0.95*30)=29
        self.assertEqual(tempo.percentile(sums, 50), p50)
        self.assertEqual(tempo.percentile(sums, 95), p95)
        self.assertEqual(tempo.percentile([7], 95), 7)
        code, out, err = cli(["--no-color", "--stats"] + files)
        self.assertEqual(code, 0, err)
        self.assertIn("over 30 traces", out)
        row = [l for l in out.splitlines() if l.startswith("rerank/match ")][0].split()
        self.assertEqual(row[1], "30/30")
        self.assertEqual(row[3:6], [tempo.ms(p50), tempo.ms(p95), tempo.ms(sums[-1])])
        for line in out.splitlines()[2:-1]:
            nums = [float(x) for x in line.split()[-9:]]
            for k in range(0, 9, 3):
                self.assertLessEqual(nums[k], nums[k + 1])
                self.assertLessEqual(nums[k + 1], nums[k + 2])

    def test_stats_skips_invalid_inputs(self):
        code, out, err = cli(["--no-color", "--stats", os.path.join(SAMPLES, "small.header"),
                              os.path.join(INVALID, "comma.header")])
        self.assertEqual(code, 1)
        self.assertIn("over 1 trace", out)
        self.assertIn("skipped", err)

    def test_critical_path_covers_the_root(self):
        for path in all_inputs():
            t = trace_of(path)
            if not t.spans:
                continue
            with self.subTest(trace=os.path.relpath(path, ROOT)):
                segs = tempo.critical_path(t)
                self.assertEqual(segs[0][2], t.root.start)
                self.assertEqual(segs[-1][3], t.root.end)
                for (s1, k1, a1, b1), (s2, k2, a2, b2) in zip(segs, segs[1:]):
                    self.assertEqual(b1, a2)
                for s, kind, a, b in segs:
                    self.assertLess(a, b)
                    self.assertIn(kind, ("self", "run", "wait", "blocked", "agg"))
                    self.assertFalse(s.not_run)
                    if kind == "wait":
                        self.assertTrue(s.handoff)
                        self.assertEqual((a, b), (s.start, s.run_start))
                hops = tempo.critical_hops(t)
                total = sum(h["wait"] + h["run"] + h["blocked"] + h["agg"] for h in hops)
                self.assertEqual(total, t.root.end - t.root.start)

    def test_critical_path_crosses_handoffs(self):
        t = trace_of(os.path.join(SAMPLES, "find-saturated.header"))
        hops = tempo.critical_hops(t)
        cross = [h for h in hops if h["span"].thread != 0]
        self.assertTrue(cross)
        infer = [h for h in hops if h["span"].name == "infer"]
        self.assertEqual(len(infer), 1)
        # the slowest-finishing crop decided the end of describe: wait then run
        last = max((s for s in t.spans if s.name == "infer"), key=lambda s: s.end)
        self.assertIs(infer[0]["span"], last)
        self.assertEqual((infer[0]["wait"], infer[0]["run"]), (last.wait, last.dur))
        code, out, err = cli(["--no-color", "--critical", os.path.join(SAMPLES, "find-saturated.header")])
        self.assertIn("queue wait", out)
        self.assertIn("describe/infer", out)

    def test_critical_path_follows_early_exit_into_the_running_work(self):
        # early exit: the await of rerank ends while the match aggregate (or a
        # straggler) still runs; that work, not 'blocked', belongs on the path
        for name, agg in (("diff/after", 26), ("batch/find-05", 18), ("batch/find-08", 8)):
            with self.subTest(trace=name):
                t = trace_of(os.path.join(SAMPLES, name + ".header"))
                hops = tempo.critical_hops(t)
                match = [h for h in hops if h["span"].path == "rerank/match"]
                self.assertTrue(match, "rerank/match missing from the critical path")
                self.assertIn(agg, [h["span"].agg["count"] for h in match if h["span"].agg])
                blocked = sum(h["blocked"] for h in hops)
                self.assertLess(blocked, 0.01 * (t.root.end - t.root.start))
                rerank = [h for h in hops if h["span"].path == "rerank"]
                self.assertLess(sum(h["blocked"] for h in rerank), 100)   # µs: wake-up gaps only

    @staticmethod
    def early_exit_trace(first_dur):
        # rerank waits (await) on two matches: match A on cpu-1 ends at
        # 200 + first_dur, match B on cpu-2 runs 1250..17250, past the wake-up
        # at 15300 and the end of rerank at 19100
        spans = [
            {"id": 0, "parent": -1, "name": "search", "start": 0, "dur": 20000, "thread": 0},
            {"id": 1, "parent": 0, "name": "rerank", "start": 100, "dur": 19000, "thread": 0},
            {"id": 2, "parent": 1, "name": "match", "start": 200, "dur": first_dur, "thread": 1,
             "wait": 0},
            {"id": 3, "parent": 1, "name": "await", "start": 300, "dur": 15000, "thread": 0,
             "flags": "w"},
            {"id": 4, "parent": 1, "name": "match", "start": 250, "dur": 18500, "thread": 2,
             "wait": 1000},
        ]
        doc = {"v": 1, "unit": "us", "wall": 1794560455433, "folded": 0, "dropped": 0,
               "threads": ["http-1", "cpu-1", "cpu-2"], "live": [], "truncated": False,
               "spans": spans}
        [(label, t)] = tempo.load_text(json.dumps(doc), "early-exit")
        if isinstance(t, Exception):
            raise t
        return t

    def test_critical_path_early_exit_straggler(self):
        # nothing ends within 1 ms of the wake-up: the straggler running then
        # is the work the wait was for (path clipped at the wake-up)
        t = self.early_exit_trace(5000)
        segs = [(s.id, k, a, b) for s, k, a, b in tempo.critical_path(t)]
        self.assertIn((4, "run", 1250, 15300), segs)
        self.assertIn((4, "wait", 250, 1250), segs)
        self.assertEqual([k for _, k, _, _ in segs].count("blocked"), 0)

    def test_critical_path_early_exit_prefers_the_child_that_ended(self):
        # match A ends 50 µs before the wake-up: it released the wait; the gap
        # is wake-up latency and the straggler stays off the path
        t = self.early_exit_trace(15050)
        segs = [(s.id, k, a, b) for s, k, a, b in tempo.critical_path(t)]
        self.assertIn((2, "run", 200, 15250), segs)
        self.assertIn((1, "blocked", 15250, 15300), segs)
        self.assertNotIn(4, [i for i, _, _, _ in segs])

    def test_findings(self):
        def kinds(name):
            t = trace_of(os.path.join(SAMPLES, name + ".header"))
            return [k for k, _, _, _ in tempo.findings(t, set(s.id for s in t.spans))]

        sat = kinds("find-saturated")
        for k in ("never-started", "open", "straggler", "queue-wait"):
            self.assertIn(k, sat)
        healthy = kinds("find-healthy")
        self.assertNotIn("never-started", healthy)
        self.assertIn("failed", healthy)
        # loads on the shared pool: I/O on a compute pool shows as off-CPU
        self.assertIn("off-cpu", kinds("diff/before"))
        self.assertNotIn("off-cpu", kinds("diff/after"))

    def test_pools_share(self):
        t = trace_of(os.path.join(SAMPLES, "find-saturated.header"))
        scale = tempo.Scale(0, t.end, 100)
        act = tempo.pool_activity(t, [s for s in t.spans if s.pool == "io"], scale)
        self.assertLessEqual(act["peak_running"], 8)
        self.assertEqual(act["threads_seen"], set(range(5, 13)))
        self.assertEqual(act["max_gauge_active"], 8)
        cpu = tempo.pool_activity(t, [s for s in t.spans if s.pool == "cpu"], scale)
        self.assertGreater(cpu["peak_queued"], 20)


# ======================================================================
# The executable itself
# ======================================================================

class Executable(unittest.TestCase):

    def run_cli(self, args, stdin="", env=None):
        e = dict(os.environ)
        e.pop("NO_COLOR", None)
        e.update(env or {})
        return subprocess.run([sys.executable, CLI] + args, input=stdin.encode("utf-8"),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=e)

    def test_pipe_and_exit_codes(self):
        value = read(os.path.join(SAMPLES, "small.header"))
        r = self.run_cli(["--width", "80"], stdin=value)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn(b"\x1b[", r.stdout)            # not a terminal: no colour
        self.assertIn("GET /search".encode(), r.stdout)
        r = self.run_cli(["--check", os.path.join(GOLDEN, "truncated-count.header")])
        self.assertEqual(r.returncode, 1)
        r = self.run_cli(["--check", os.path.join(INVALID, "depth-jump.header")])
        self.assertEqual(r.returncode, 1)
        self.assertIn(b"E_DEPTH", r.stderr)
        r = self.run_cli(["--bogus"])
        self.assertEqual(r.returncode, 2)
        r = self.run_cli(["--help"])
        self.assertEqual(r.returncode, 0)
        self.assertIn(b"--critical", r.stdout)

    def test_shebang_and_mode(self):
        with open(CLI, "rb") as fh:
            self.assertEqual(fh.readline(), b"#!/usr/bin/env python3\n")
        self.assertTrue(os.access(CLI, os.X_OK))

    def test_python38_syntax_and_stdlib_only(self):
        src = read(CLI)
        tree = ast.parse(src, feature_version=(3, 8))
        allowed = {"argparse", "datetime", "json", "math", "os", "re", "shutil", "sys", "textwrap"}
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for a in node.names:
                    self.assertIn(a.name.split(".")[0], allowed)
            elif isinstance(node, ast.ImportFrom):
                self.assertIn(node.module.split(".")[0], allowed)
            self.assertNotIsInstance(node, getattr(ast, "Match", ()))


if __name__ == "__main__":
    unittest.main()
