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
import tempfile
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
                "tEmPo-TiMiNg: %s\r\nServer-Timing: total;dur=1\r\n\r\n{\"ok\":true}\n" % self.value)
        self.assertEqual(self.decoded(text), self.expected)

    def test_curl_i_output_wrapped_lines(self):
        text = ("HTTP/2 200\r\ncontent-type: text/plain\r\nTempo-Timing: " + "\r\n".join(self.wrapped())
                + "\r\nx-request-id: 7\r\n\r\nbody\n")
        self.assertEqual(self.decoded(text), self.expected)

    def test_curl_i_output_folded_continuation(self):
        text = ("HTTP/1.1 200 OK\nTempo-Timing:\n " + "\n\t".join(self.wrapped(40))
                + "\nVia: proxy\n\n")
        self.assertEqual(self.decoded(text), self.expected)

    def test_json_body_with_tempo_object_under_info(self):
        body = {"status": "ok", "info": {"processed": {"stages": 4}, "tempo": self.expected}}
        self.assertEqual(self.decoded(json.dumps(body, indent=2)), self.expected)

    def test_curl_i_with_json_body_prefers_the_body(self):
        body = json.dumps({"info": {"tempo": self.expected}})
        text = ("HTTP/1.1 200 OK\r\nTempo-Timing: v1;T=a;N=b;S=00.0.1;e=1\r\n\r\n" + body + "\n")
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
# Tempo-Link (FORMAT.md section 9)
# ======================================================================

sys.path.insert(0, os.path.join(ROOT, "tools"))
try:
    import tempo_ref  # noqa: E402
finally:
    sys.path.pop(0)


def link_table():
    table = {}
    for line in read(os.path.join(INVALID, "README.md")).splitlines():
        m = re.match(r"^\| `([^`]+\.link)` \| `(E_[A-Z]+)` \|", line)
        if m:
            table[m.group(1)] = m.group(2)
    return table


class FakeBrowser(object):
    """Stands in for webbrowser.open: records the URL instead of starting a browser."""

    def __init__(self, ok=True):
        self.ok = ok
        self.urls = []

    def __enter__(self):
        self.saved = tempo.webbrowser.open
        tempo.webbrowser.open = self.open
        return self

    def __exit__(self, *exc):
        tempo.webbrowser.open = self.saved

    def open(self, url, *args, **kw):
        self.urls.append(url)
        return self.ok


class LinkInput(unittest.TestCase):

    def setUp(self):
        self.value = read(os.path.join(SAMPLES, "small.header")).strip()
        self.expected = json.loads(read(os.path.join(SAMPLES, "small.json")))
        self.link = tempo_ref.encode_link(self.value)
        self.other = read(os.path.join(SAMPLES, "pipeline.header")).strip()

    def decoded(self, text=None, args=()):
        code, out, err = cli(["--to", "json"] + list(args), stdin=text or "")
        self.assertEqual(code, 0, err)
        return json.loads(out), err

    def test_golden_links_decode_to_their_json(self):
        links = sorted(glob.glob(os.path.join(GOLDEN, "*.link")))
        self.assertGreaterEqual(len(links), 12)
        for path in links:
            with self.subTest(vector=os.path.basename(path)):
                doc, _ = self.decoded(args=[path])
                self.assertEqual(doc, json.loads(read(path[:-5] + ".json")))

    def test_bare_url_on_stdin_and_as_argument(self):
        self.assertEqual(self.decoded(self.link + "\n")[0], self.expected)
        self.assertEqual(self.decoded(args=[self.link])[0], self.expected)
        code, out, err = cli(["--check", self.link])
        self.assertEqual((code, out.split(":")[0]), (0, "ok  link"), err)

    def test_bare_fragment_and_other_base(self):
        frag = self.link[self.link.index("#"):]
        self.assertEqual(self.decoded(frag)[0], self.expected)
        self.assertEqual(self.decoded("file:///srv/viewer/index.html?x=1" + frag)[0], self.expected)

    def test_query_form(self):
        z = tempo_ref.pack_value(self.value)
        self.assertEqual(self.decoded(args=["https://dualuse.github.io/tempo/?t=" + z])[0], self.expected)
        self.assertEqual(self.decoded("see https://dualuse.github.io/tempo/?tab=tree&t=%s\n" % z)[0], self.expected)
        plain = tempo_ref.pack_value(self.value, form="plain")
        self.assertEqual(self.decoded("http://localhost:8000/?t=" + plain)[0], self.expected)
        # the fragment wins over the query, even when the query one is broken
        both = "https://dualuse.github.io/tempo/?t=z.broken#t=" + z
        self.assertEqual(self.decoded(both)[0], self.expected)
        # a query t= that is not a packed value is just some URL, not a link
        code, out, err = cli(["--check"], stdin="GET https://example.com/search?t=42 200\n")
        self.assertEqual(code, 1)
        self.assertNotIn("E_LINK", err)

    def test_plain_form(self):
        plain = tempo_ref.encode_link(self.value, form="plain")
        self.assertIn("%25", plain)
        self.assertEqual(self.decoded(plain)[0], self.expected)

    def test_link_inside_a_log_line(self):
        log = '12:00:01 INFO GET /search 200 tempo=%s, took 48ms (see "%s").\n' % (self.link, self.link)
        code, out, err = cli(["--check"], stdin=log)
        self.assertEqual(code, 0, err)
        self.assertEqual(out.count("ok "), 2)

    def test_several_links_feed_stats(self):
        other = tempo_ref.encode_link(read(os.path.join(SAMPLES, "batch", "find-01.header")))
        log = "a %s\nb %s\nc %s\n" % (self.link, other, self.link)
        code, out, err = cli(["--stats", "--no-color"], stdin=log)
        self.assertEqual(code, 0, err)
        self.assertIn("over 3 traces", out)
        doc, err = self.decoded(log)
        self.assertEqual(doc, self.expected)
        self.assertIn("3 traces found", err)

    def test_links_and_values_in_one_log(self):
        log = "a %s\nb %s\n" % (self.link, self.other)
        code, out, err = cli(["--check"], stdin=log)
        self.assertEqual((code, out.count("ok ")), (0, 2), err)

    def test_bare_link_wrapped_by_terminal(self):
        lines = [self.link[i:i + 57] for i in range(0, len(self.link), 57)]
        self.assertEqual(self.decoded("\n".join(lines) + "\n")[0], self.expected)
        self.assertEqual(self.decoded("trace: " + "\r\n".join(lines) + "\r\nnext line here\n")[0],
                         self.expected)

    def test_curl_i_tempo_link_header(self):
        text = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                "tEmPo-LiNk: %s\r\nServer-Timing: total;dur=1\r\n\r\n{\"ok\":true}\n" % self.link)
        self.assertEqual(self.decoded(text)[0], self.expected)

    def test_curl_i_tempo_link_wrapped_and_folded(self):
        parts = [self.link[i:i + 70] for i in range(0, len(self.link), 70)]
        wrapped = "HTTP/2 200\r\nTempo-Link: " + "\r\n".join(parts) + "\r\nx-request-id: 7\r\n\r\nbody\n"
        self.assertEqual(self.decoded(wrapped)[0], self.expected)
        folded = "HTTP/1.1 200 OK\nTempo-Link:\n " + "\n\t".join(parts) + "\nVia: proxy\n\n"
        self.assertEqual(self.decoded(folded)[0], self.expected)

    def test_both_headers_agree(self):
        text = "HTTP/1.1 200 OK\r\nTempo-Timing: %s\r\nTempo-Link: %s\r\n\r\n" % (self.value, self.link)
        doc, err = self.decoded(text)
        self.assertEqual(doc, self.expected)
        self.assertEqual(err, "")

    def test_both_headers_disagree_tempo_timing_wins(self):
        other_link = tempo_ref.encode_link(self.other)
        for text in ("HTTP/1.1 200 OK\r\nTempo-Link: %s\r\nTempo-Timing: %s\r\n\r\n" % (other_link, self.value),
                     "HTTP/1.1 200 OK\r\nTempo-Timing: %s\r\nTempo-Link: %s\r\n\r\n" % (self.value, other_link)):
            doc, err = self.decoded(text)
            self.assertEqual(doc, self.expected)
            self.assertIn("disagree; using Tempo-Timing", err)
        code, out, err = cli(["--check"], stdin="HTTP/1.1 200 OK\nTempo-Timing: %s\nTempo-Link: %s\n\n"
                             % (self.value, self.link[:200]))
        self.assertEqual(code, 0, err)
        self.assertIn("Tempo-Link ignored (E_LINK", err)

    def test_rejected_tempo_timing_falls_back_to_the_link(self):
        text = "HTTP/1.1 200 OK\nTempo-Timing: v2;broken\nTempo-Link: %s\n\n" % self.link
        doc, err = self.decoded(text)
        self.assertEqual(doc, self.expected)
        self.assertIn("Tempo-Timing rejected (E_VERSION", err)

    def test_headers_pair_per_response(self):
        # curl -iL: two responses, each with both headers; no cross-talk between them
        other_link = tempo_ref.encode_link(self.other)
        text = ("HTTP/1.1 302 Found\nTempo-Timing: %s\nTempo-Link: %s\nLocation: /x\n\n"
                "HTTP/1.1 200 OK\nTempo-Timing: %s\nTempo-Link: %s\n\n"
                % (self.other, other_link, self.value, self.link))
        code, out, err = cli(["--check"], stdin=text)
        self.assertEqual((code, out.count("ok "), err), (0, 2, ""))

    def test_json_body_still_wins(self):
        body = json.dumps({"info": {"tempo": self.expected}})
        text = "HTTP/1.1 200 OK\r\nTempo-Link: %s\r\n\r\n%s\n" % (tempo_ref.encode_link(self.other), body)
        self.assertEqual(self.decoded(text)[0], self.expected)

    def test_to_link_round_trip(self):
        paths = sample_headers()[:8] + sorted(glob.glob(os.path.join(GOLDEN, "*.link")))
        for path in paths:
            with self.subTest(input=os.path.relpath(path, ROOT)):
                code, url, err = cli(["--to", "link", path])
                self.assertEqual(code, 0, err)
                self.assertTrue(url.startswith(tempo_ref.default_base({}) + "#t=z."), url)
                self.assertEqual(url.count("\n"), 1)
                link = tempo_ref.decode_link(url)
                want = tempo_ref.strip_ws(tempo_ref.decode_link(read(path)).value if path.endswith(".link")
                                          else read(path))
                self.assertEqual(link.value, want)
                code, out, err = cli(["--to", "json", url.strip()])
                self.assertEqual(json.loads(out), tempo_ref.decode(want))

    def test_to_link_from_json_encodes_the_trace(self):
        for name in ("search-example", "names", "aggregates", "attributes"):
            with self.subTest(vector=name):
                code, url, err = cli(["--to", "link", os.path.join(GOLDEN, name + ".json")])
                self.assertEqual(code, 0, err)
                self.assertEqual(tempo_ref.decode_link(url).value,
                                 tempo_ref.strip_ws(read(os.path.join(GOLDEN, name + ".header"))))

    def test_empty_names_survive_a_round_trip_through_json(self):
        header = "v1;T=:w;N=a:b:;S=00.0.5/11.1.2@1:2=7;e=2"
        code, out, err = cli(["--to", "json"], stdin=header)
        self.assertEqual(code, 0, err)
        doc = json.loads(out)
        self.assertEqual(doc["threads"], ["", "w"])
        self.assertEqual(doc["spans"][1]["attrs"], {"": 7})
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            fh.write(out)
        try:
            code, url, err = cli(["--to", "link", fh.name])
        finally:
            os.unlink(fh.name)
        self.assertEqual(code, 0, err)
        self.assertEqual(tempo_ref.decode_link(url).value, header)

    def test_to_link_base(self):
        code, url, err = cli(["--to", "link", "--base", "http://localhost:8000/tempo/",
                              os.path.join(SAMPLES, "small.header")])
        self.assertEqual(code, 0, err)
        self.assertTrue(url.startswith("http://localhost:8000/tempo/#t=z."))
        code, url, err = cli(["--to", "link", os.path.join(SAMPLES, "small.header")],
                             env={"TEMPO_LINK_BASE": "https://example.org/v/"})
        self.assertTrue(url.startswith("https://example.org/v/#t=z."), url)
        for bad in ("https://x/#frag", "has space", ""):
            code, out, err = cli(["--to", "link", "--base", bad, os.path.join(SAMPLES, "small.header")])
            self.assertEqual(code, 2, bad)

    def test_to_link_keeps_the_compare_trace(self):
        pair = tempo_ref.encode_link(self.value, compare=self.other, tab="diff")
        code, url, err = cli(["--to", "link", pair])
        self.assertEqual(code, 0, err)
        link = tempo_ref.decode_link(url)
        self.assertEqual((link.value, link.compare, link.tab), (self.value, self.other, None))

    def test_diff_takes_the_compare_trace_of_a_link(self):
        pair = tempo_ref.encode_link(self.value, compare=self.other)
        code, out, err = cli(["--diff", "--no-color", "--width", "120"], stdin=pair)
        self.assertEqual(code, 0, err)
        self.assertIn("B stdin (c=)", out)
        code2, out2, err2 = cli(["--diff", os.path.join(SAMPLES, "pipeline.header"), "--no-color", "--width", "120"],
                                stdin=self.value)
        self.assertEqual(code2, 0, err2)
        rows = lambda text: text[text.index("\npath "):].splitlines()   # noqa: E731 (the title names the inputs)
        self.assertEqual(rows(out), rows(out2))
        # an explicit OTHER wins over c=; OTHER may be a link too
        code3, out3, err3 = cli([pair, "--no-color", "--width", "120", "--diff", self.link])
        self.assertEqual(code3, 0, err3)
        self.assertIn("Δ +0.000 ms", out3)

    def test_diff_without_other_needs_a_compare_trace(self):
        code, out, err = cli(["--diff"], stdin=self.link)
        self.assertEqual(code, 2)
        self.assertIn("&c=", err)
        broken = self.link + "&c=z.AAAA"
        code, out, err = cli(["--diff"], stdin=broken)
        self.assertEqual(code, 1)
        self.assertIn("E_LINK", err)

    def test_open_builds_the_link_and_starts_the_browser(self):
        with FakeBrowser() as fb:
            code, out, err = cli(["--open", os.path.join(SAMPLES, "small.header")])
            self.assertEqual(code, 0, err)
            self.assertEqual(fb.urls, [out.strip()])
            self.assertEqual(tempo_ref.decode_link(fb.urls[0]), (self.value, None, None))
            code, out, err = cli(["--open", "--tree", "--base", "http://localhost:1/",
                                  os.path.join(SAMPLES, "small.header")])
            self.assertEqual(code, 0, err)
            self.assertTrue(fb.urls[-1].startswith("http://localhost:1/#t=z."))
            self.assertTrue(fb.urls[-1].endswith("&tab=tree"))
            code, out, err = cli(["--open", "--agg", os.path.join(SAMPLES, "small.header")])
            self.assertTrue(fb.urls[-1].endswith("&tab=agg"))
            code, out, err = cli(["--open", os.path.join(SAMPLES, "small.header"),
                                  "--diff", os.path.join(SAMPLES, "pipeline.header")])
            self.assertEqual(code, 0, err)
            self.assertEqual(tempo_ref.decode_link(fb.urls[-1]), (self.value, self.other, "diff"))
            code, out, err = cli(["--open", os.path.join(GOLDEN, "search-example.json")])
            self.assertEqual(code, 0, err)
            self.assertEqual(tempo_ref.decode_link(fb.urls[-1]).value,
                             tempo_ref.strip_ws(read(os.path.join(GOLDEN, "search-example.header"))))
            self.assertEqual(len(fb.urls), 5)

    def test_open_errors(self):
        with FakeBrowser(ok=False) as fb:
            code, out, err = cli(["--open", self.link])
            self.assertEqual(code, 1)
            self.assertIn("could not start a web browser", err)
            self.assertEqual(out.strip(), fb.urls[0])          # the link is still printed
            for extra in (["--to", "json"], ["--check"], ["--stats"]):
                code, out, err = cli(["--open"] + extra + [self.link])
                self.assertEqual(code, 2, extra)
            self.assertEqual(len(fb.urls), 1)
            code, out, err = cli(["--open", self.link[:120]])
            self.assertEqual(code, 1)
            self.assertIn("E_LINK", err)
            self.assertEqual(len(fb.urls), 1)                  # nothing opened for a broken link

    def test_corrupt_links_are_rejected(self):
        table = link_table()
        files = sorted(os.path.basename(p) for p in glob.glob(os.path.join(INVALID, "*.link")))
        self.assertEqual(sorted(table), files)
        self.assertGreaterEqual(len(files), 10)
        for fname, code_expected in sorted(table.items()):
            path = os.path.join(INVALID, fname)
            text = read(path)
            with self.subTest(vector=fname):
                if "&c=" in text:
                    # a broken second trace only matters where it is used
                    for args in (["--diff"], ["--to", "link"]):
                        code, out, err = cli([path] + args)     # (--diff PATH would make PATH the OTHER)
                        self.assertEqual((code, out), (1, ""), (args, err))
                        self.assertIn(code_expected, err)
                    self.assertEqual(cli(["--check", path])[0], 0)
                    continue
                recognised = "t=" in text     # without t= the CLI cannot tell a link from other text
                for args in (["--check"], [], ["--tree"], ["--to", "json"], ["--to", "link"]):
                    code, out, err = cli(args + [path])
                    self.assertEqual((code, out), (1, ""), (args, err))
                    if recognised:
                        self.assertIn(code_expected, err)
                code, out, err = cli(["--check", text.strip()])     # the URL as an argument
                self.assertEqual(code, 1, err)
                if recognised:
                    self.assertIn(code_expected, err)

    def test_generator_writes_links(self):
        gen = os.path.join(ROOT, "tools", "gen_traces.py")
        args = [sys.executable, gen, "--seed", "4", "--candidates", "12"]
        header = subprocess.run(args, stdout=subprocess.PIPE, check=True).stdout.decode().strip()
        env = {k: v for k, v in os.environ.items() if k != "TEMPO_LINK_BASE"}
        for extra, base, more in (([], tempo_ref.default_base({}), {}), (["http://127.0.0.1:8000/t/"], "http://127.0.0.1:8000/t/", {}),
                                  ([], "https://example.org/v/", {"TEMPO_LINK_BASE": "https://example.org/v/"})):
            url = subprocess.run(args + ["--link"] + extra, stdout=subprocess.PIPE, check=True,
                                 env=dict(env, **more)).stdout.decode().strip()
            self.assertTrue(url.startswith(base + "#t=z."), url)
            self.assertEqual(tempo_ref.decode_link(url).value, header)

    def test_default_base_of_a_checkout(self):
        """$TEMPO_LINK_BASE; else a checkout's own GitHub Pages site from its remote origin, as the library build
        derives it; else the upstream viewer. The CLI and the reference agree."""
        self.assertEqual(tempo_ref.default_base({"TEMPO_LINK_BASE": "https://example.org/v/"}), "https://example.org/v/")
        self.assertEqual(tempo.default_base({}), tempo_ref.default_base({}))
        self.assertIn(tempo_ref.checkout_base(), (None, tempo_ref.default_base({})))
        for remote, base in (("git@github.com:Some-Owner/tempo-fork.git", "https://some-owner.github.io/tempo-fork/"),
                             ("https://github.com/dualuse/tempo", "https://dualuse.github.io/tempo/"),
                             ("ssh://git@github.com:22/someone/someone.github.io.git", "https://someone.github.io/"),
                             ("git@gitlab.com:some-owner/tempo-fork.git", None), ("", None)):
            self.assertEqual(tempo_ref.pages_of(tempo_ref.repository_of_remote(remote)), base, remote)
            self.assertEqual(tempo.pages_of(tempo.repository_of_remote(remote)), base, remote)

    def test_link_decoder_matches_reference_decoder(self):
        paths = sorted(glob.glob(os.path.join(GOLDEN, "*.link")) + glob.glob(os.path.join(INVALID, "*.link")))
        for path in paths:
            text = read(path)
            try:
                want = tempo_ref.decode_link(text)
            except tempo_ref.TempoFormatError as e:
                want = e.code
            try:
                p = tempo.link_params(text)
                got = (tempo.unpack_value(p["t"]), tempo.unpack_value(p["c"]) if p.get("c") else None,
                       p.get("tab"))
            except tempo.TempoFormatError as e:
                got = e.code
            self.assertEqual(got, want if isinstance(want, str) else tuple(want), path)


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
        root = t.root
        awaits = [c for c in root.children if "w" in c.flags]
        self.assertTrue(awaits)
        self.assertLess(root.self_time, root.dur - sum(a.dur for a in awaits) + 1000)

    def test_phases_are_sections_not_children(self):
        # FORMAT.md 6.7: a phase is listed with its span, never as a child; its span's
        # self time keeps the phase's time; it has no self time of its own
        t = trace_of(os.path.join(GOLDEN, "phases.header"))
        root = t.root
        self.assertEqual([ph.name for ph in root.phases], ["parse", "store", "verify", "check"])
        self.assertEqual([c.name for c in root.children], ["read", "flush", "", "replay"])
        self.assertEqual(root.self_time, 3000 - 750 - 500 - 40)
        flush = t.spans[4]
        self.assertEqual(([ph.name for ph in flush.phases], flush.children, flush.self_time),
                         (["compress", "write"], [], 1600))
        for ph in root.phases:
            self.assertTrue(ph.phase)
            self.assertIsNone(ph.self_time)
            self.assertFalse(t.is_task(ph))
            self.assertEqual(ph.key, ":" + ph.name)
        self.assertEqual(t.spans[6].key, "flush:write")
        self.assertEqual(t.phase_count, 10)

    def test_unnamed_span_takes_its_title_from_its_phases(self):
        t = trace_of(os.path.join(GOLDEN, "phases.header"))
        unnamed = t.spans[8]
        self.assertEqual((unnamed.name, unnamed.title, unnamed.key), ("", "(open|copy)", "(open|copy)"))
        self.assertEqual(t.spans[10].key, "(open|copy):copy")
        doc = json.loads(read(os.path.join(GOLDEN, "phases.json")))
        doc["spans"] = doc["spans"][:8] + [doc["spans"][8]]
        [(label, bare)] = tempo.load_text(json.dumps(doc), "bare")
        self.assertEqual(bare.spans[8].title, "(unnamed)")

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
        with self.assertRaises(Exception):
            tempo.view_lanes(t, tempo.Filter(focus="rerank"), tempo.Painter(), 100)   # a phase, not a span
        self.assertEqual(tempo.Filter(focus="rerank").apply(t)[1], [])
        vis, roots = tempo.Filter(focus="load").apply(t)
        self.assertEqual(set(r.path for r in roots), {"load"})
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
# Drawing: box-plot bars, phases, names in front, the name column
# ======================================================================

# startup has phases init, loading and warm-up and starts at the left edge (its name
# goes to the name column); scan is a plain span; sync's name cannot stand in front of
# it next to scan, so it moves down a row; fetch is a handoff with phases open and
# read (unphased time after read), its name in front of its queue wait; the unnamed
# span is labelled by its phases parse and check, check too narrow for its name
DRAWING = {"v": 1, "unit": "us", "folded": 0, "dropped": 0, "threads": ["main", "pool-1"],
           "live": [], "truncated": False, "spans": [
               {"id": 0, "parent": -1, "name": "startup", "start": 0, "dur": 4000, "thread": 0},
               {"id": 1, "parent": 0, "name": "init", "start": 0, "dur": 400, "thread": 0, "flags": "p"},
               {"id": 2, "parent": 0, "name": "loading", "start": 400, "dur": 2000, "thread": 0, "flags": "p"},
               {"id": 3, "parent": 0, "name": "scan", "start": 600, "dur": 1000, "thread": 0},
               {"id": 4, "parent": 0, "name": "fetch", "start": 1000, "dur": 2600, "thread": 1, "wait": 200},
               {"id": 5, "parent": 4, "name": "open", "start": 1200, "dur": 100, "thread": 1, "flags": "p"},
               {"id": 6, "parent": 4, "name": "read", "start": 1300, "dur": 1500, "thread": 1, "flags": "p"},
               {"id": 7, "parent": 0, "name": "sync", "start": 1700, "dur": 600, "thread": 0},
               {"id": 8, "parent": 7, "name": "push", "start": 1700, "dur": 600, "thread": 0, "flags": "p"},
               {"id": 9, "parent": 0, "name": "warm-up", "start": 2400, "dur": 1200, "thread": 0, "flags": "p"},
               {"id": 10, "parent": 0, "name": "", "start": 2600, "dur": 1000, "thread": 0},
               {"id": 11, "parent": 10, "name": "parse", "start": 2600, "dur": 600, "thread": 0, "flags": "p"},
               {"id": 12, "parent": 10, "name": "check", "start": 3200, "dur": 400, "thread": 0, "flags": "fp"}]}


class Drawing(unittest.TestCase):
    maxDiff = None

    def lanes(self, *args):
        code, out, err = cli(["--no-color"] + list(args), stdin=json.dumps(DRAWING))
        self.assertEqual(code, 0, err)
        return out

    def test_ascii(self):
        self.assertEqual(self.lanes("--ascii", "--width", "72"), "\n".join([
            "startup  -  4.000 ms  -  5 spans and 8 phases on 2 threads",
            "                     ms 0           1           2           3",
            "                        +-----------+-----------+-----------+-----------",
            "main            startup |init|------- loading -------|-- warm-up --|...|",
            "                               |-- scan --|            |parse-|*1-|",
            "                                       sync | push |",
            "pool",
            "  pool-1                   fetch *2 __|-|----- read ------|..........|",
            "  queued <=1                        @@=",
            "",
            "*1 check  *2 open",
            "",
            "|-| span  | phase boundary  .. unphased time  *1 phase named below",
            "_ queue wait  |~| blocking wait  % aggregate xcount, # slowest  X failed",
            "/ cancelled  > open  0 never started  * live lane",
            "= this request's queued handoffs",
            ""]))

    def test_unicode(self):
        self.assertEqual(self.lanes("--width", "72").splitlines()[2:8], [
            "                       ┬───────────┬───────────┬───────────┬────────────",
            "main           startup ├init┼─────── loading ───────┼── warm-up ───┼···┤",
            "                              ├── scan ───┤            ├parse─┼*1─┤",
            "                                       sync ├push─┤",
            "pool",
            "  pool-1                  fetch *2 ░░░├┼───── read ──────┼···········┤"])

    def test_narrow(self):
        # the name column keeps at most a sixth of the width; more phases need
        # references, numbered in phase order; a phase too narrow even for its
        # reference (init, check, open) has it after its span's name, in front of
        # the bar or in the name column, which shortens the name, not the references
        self.assertEqual(self.lanes("--width", "40").splitlines()[4:13], [
            "main      s… *1 ├─┼─ loading ─┼warm-up┼┤",
            "                    ├scan┤   *4 ├*3┼─┤",
            "                     sync ├*2┤",
            "pool",
            "  pool-1  f… *5       ░├┼─ read ─┼····┤",
            "  queued…             █▂",
            "",
            "*1 init  *2 push  *3 parse  *4 check",
            "*5 open"])

    def test_every_named_phase_is_named_or_referenced(self):
        # at any width each named phase shows its name or a reference that the
        # footnote resolves, in the bar or after its span's name
        for width in range(30, 120, 7):
            for glyphs in ((), ("--ascii",)):
                out = self.lanes("--width", str(width), *glyphs)
                foot = dict(re.findall(r"(\*\d+) (\S+)", out.split("\n\n")[1]))
                shown = set(foot.values())
                for word in re.findall(r"[a-z]+(?:-[a-z]+)*", out.split("\n\n")[0]):
                    shown.add(word)
                with self.subTest(width=width, glyphs=glyphs):
                    for name in ("init", "loading", "warm-up", "scan", "push", "parse", "check",
                                 "read", "open"):
                        self.assertIn(name, shown)

    def test_no_name_column_without_need(self):
        # a root without phases has its name in its bar; stage, with phases, has room
        # for its name in front: no name column
        doc = {"v": 1, "spans": [
            {"id": 0, "parent": -1, "name": "job", "start": 0, "dur": 3000, "thread": 0},
            {"id": 1, "parent": 0, "name": "stage", "start": 1000, "dur": 1500, "thread": 0},
            {"id": 2, "parent": 1, "name": "a", "start": 1000, "dur": 500, "thread": 0, "flags": "p"},
            {"id": 3, "parent": 1, "name": "b", "start": 1500, "dur": 500, "thread": 0, "flags": "p"}],
            "threads": ["main"]}
        code, out, err = cli(["--no-color", "--ascii", "--width", "60"], stdin=json.dumps(doc))
        self.assertEqual(code, 0, err)
        self.assertEqual(out.splitlines()[1:5], [
            "            ms 0              1              2",
            "               +--------------+--------------+--------------",
            "main           |------------------- job -------------------|",
            "                        stage |-- a --|- b --|......|"])

    def test_the_other_views_report_phases_by_start_and_duration(self):
        code, out, err = cli(["--no-color", "--tree", "--width", "100"], stdin=json.dumps(DRAWING))
        self.assertEqual(code, 0, err)
        self.assertEqual(out.splitlines()[1:], [
            "span                     start      wait       dur      self       cpu   off-cpu  thread  flags",
            "startup                  0.000               4.000     1.400                      main",
            "├─ :init                 0.000               0.400                                        p",
            "├─ :loading              0.400               2.000                                        p",
            "├─ scan                  0.600               1.000     1.000                      main",
            "├─ fetch                 1.000     0.200     2.600     2.600                      pool-1",
            "│  ├─ :open              1.200               0.100                                        p",
            "│  └─ :read              1.300               1.500                                        p",
            "├─ sync                  1.700               0.600     0.600                      main",
            "│  └─ :push              1.700               0.600                                        p",
            "├─ :warm-up              2.400               1.200                                        p",
            "└─ (parse|check)         2.600               1.000     1.000                      main",
            "   ├─ :parse             2.600               0.600                                        p",
            "   └─ :check             3.200               0.400                                        fp"])
        code, out, err = cli(["--no-color", "--agg", "--width", "100"], stdin=json.dumps(DRAWING))
        self.assertEqual(code, 0, err)
        rows = dict((line.split()[0], line.split()[1:]) for line in out.splitlines()[2:-1])
        self.assertEqual(rows[":loading"], ["1", "2.000", "2.000", "2.000", "p"])
        self.assertEqual(rows["fetch:read"], ["1", "1.500", "1.500", "1.500", "p"])
        self.assertEqual(rows["(parse|check):check"], ["1", "0.400", "0.400", "0.400", "fp"])
        self.assertEqual(rows["fetch"], ["1", "2.600", "2.600", "2.600", "2.600", "0.200", "0.200"])
        code, out, err = cli(["--no-color", "--critical", "--width", "100"], stdin=json.dumps(DRAWING))
        self.assertNotIn(":", "".join(line.split()[-1] for line in out.splitlines()[3:-3]))
        code, out, err = cli(["--to", "folded"], stdin=json.dumps(DRAWING))
        self.assertEqual(out, "startup 1400\nstartup;scan 1000\nstartup;fetch 2600\n"
                              "startup;sync 600\nstartup;(parse|check) 1000\n")


# ======================================================================
# Snapshots
# ======================================================================

SNAP_SAMPLES = ["small", "find-healthy", "find-saturated", "pipeline", "diff/after"]
VIEWS = {"lanes": [], "tree": ["--tree"], "agg": ["--agg"], "pools": ["--pools"],
         "findings": ["--findings"], "critical": ["--critical"]}
EXTRA = {
    "find-saturated.lanes.ascii": (["--ascii"], "find-saturated"),
    "find-healthy.lanes.threads-io": (["--threads", "^io-"], "find-healthy"),
    "find-healthy.lanes.focus-load": (["--focus", "load"], "find-healthy"),
    "find-healthy.tree.depth-1": (["--tree", "--depth", "1"], "find-healthy"),
    "find-healthy.tree.min-ms-5": (["--tree", "--min-ms", "5"], "find-healthy"),
    "find-healthy.agg.focus-match": (["--agg", "--focus", "match"], "find-healthy"),
    "pipeline.lanes.width-60": (["--width", "60"], "pipeline"),
    "small.lanes.focus-load-200": (["--focus", "load", "--width", "200"], "small"),
    "find-saturated.tree.width-80": (["--tree", "--width", "80"], "find-saturated"),
    "find-saturated.agg.width-80": (["--agg", "--width", "80"], "find-saturated"),
    "pipeline.pools.width-80": (["--pools", "--width", "80"], "pipeline"),
    "pipeline.critical.width-80": (["--critical", "--width", "80"], "pipeline"),
    "search-example.lanes": ([], "../testdata/golden/search-example"),
    "search-example.tree": (["--tree"], "../testdata/golden/search-example"),
    "failed-open.lanes": ([], "../testdata/golden/failed-open"),
    "phases.lanes": ([], "../testdata/golden/phases"),
    "phases.lanes.ascii": (["--ascii"], "../testdata/golden/phases"),
    "phases.tree": (["--tree"], "../testdata/golden/phases"),
    "phases.agg": (["--agg"], "../testdata/golden/phases"),
    "phases.findings": (["--findings"], "../testdata/golden/phases"),
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
            with self.subTest(snapshot="diff.width-80"):
                self.check("diff.width-80", ["--no-color", "--width", "80", rel(before, ROOT),
                                             "--diff", rel(after, ROOT)])
            with self.subTest(snapshot="stats.width-160"):
                self.check("stats.width-160", ["--no-color", "--width", "160", "--stats"]
                           + [rel(b, ROOT) for b in batch])
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

    def test_every_view_fits_the_width(self):
        # numbers are never cut, so tables have a floor (--help: about 80
        # columns for --tree); at 80 and 100 every line of every view fits
        before = os.path.join(SAMPLES, "diff", "before.header")
        batch = sorted(glob.glob(os.path.join(SAMPLES, "batch", "*.header")))
        views = [[], ["--tree"], ["--agg"], ["--pools"], ["--findings"], ["--critical"]]
        for width in (80, 100):
            runs = [(view + [h], h) for h in all_inputs() for view in views]
            runs += [([h, "--diff", before], h) for h in sample_headers()]
            runs.append((["--stats"] + batch, "batch"))
            for args, what in runs:
                for flags in (["--no-color"], ["--color", "--ascii"]):
                    code, out, err = cli(flags + ["--width", str(width)] + args)
                    self.assertEqual(code, 0, err)
                    for line in out.splitlines():
                        self.assertLessEqual(len(ANSI_RE.sub("", line)), width,
                                             "%s %s: %r" % (os.path.relpath(what, ROOT), args[:1], line))

    def test_tables_keep_numbers_whole(self):
        # a narrow table shortens paths and splits into blocks, but every
        # number of the wide table is still there
        path = os.path.join(SAMPLES, "find-saturated.header")
        number = re.compile(r"(?<![\w.])-?\d+\.\d{3}(?![\w.])")
        for view in (["--agg"], ["--pools"], ["--tree"], ["--critical"]):
            wide = cli(view + ["--no-color", "--width", "400", path])[1]
            narrow = cli(view + ["--no-color", "--width", "80", path])[1]
            self.assertNotEqual(narrow, wide)
            self.assertEqual(sorted(number.findall(narrow)), sorted(number.findall(wide)), view)

    def test_width_below_the_minimum_is_raised_with_a_warning(self):
        code, out, err = cli(["--no-color", "--width", "30", os.path.join(SAMPLES, "small.header")])
        self.assertEqual(code, 0)
        self.assertIn("minimum of 40", err)
        self.assertLessEqual(max(len(line) for line in out.splitlines()), 40)
        code, out, err = cli(["--no-color", os.path.join(SAMPLES, "small.header")], env={"COLUMNS": "20"})
        self.assertEqual(err, "")
        self.assertIn("at least 40", tempo.build_parser().format_help())

    def test_queue_wait_shows_in_front_of_every_run(self):
        # a handoff that queued while its thread still ran earlier work of the
        # request moves to a free row of the lane, so its wait stays visible
        p = tempo.Painter()
        moved = 0
        for path in all_inputs():
            t = trace_of(path)
            if not t.spans:
                continue
            groups, by_lane = tempo.lane_groups(t, t.spans, tempo.Filter())
            for width in (60, 100, 200):
                scale = tempo.Scale(0, t.end, width)
                for key, spans in by_lane.items():
                    rowof = {}
                    rows = tempo.draw_lane(spans, scale, p, rowof)
                    for s in spans:
                        if not s.handoff or s.agg or s.not_run or s.wait <= 0:
                            continue
                        c0 = scale.cols(s.run_start, s.end)[0]
                        w0 = scale.rd(s.start)
                        if c0 - w0 < 2:
                            continue
                        cells = rows[rowof[s.id]]
                        with self.subTest(trace=os.path.relpath(path, ROOT), span=s.id, width=width):
                            self.assertEqual(cells[c0 - 1][0], p.g["wait"])
                        moved += rowof[s.id] > 0
        self.assertGreater(moved, 0)
        # the case from the review: the loads on io-1 and io-4 of small
        code, out, err = cli(["--no-color", "--width", "200", "--focus", "load",
                              os.path.join(SAMPLES, "small.header")])
        lanes = {}
        name = None
        for line in out.splitlines():
            if line[:2] == "  " and line[2:3].strip():
                name = line.split()[0]
            elif line[:1].strip():
                name = None
            if name:
                lanes[name] = lanes.get(name, "") + line[14:] + "\n"
        for thread in ("io-1", "io-4"):
            self.assertIn(p.g["wait"], lanes[thread], thread)

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
                self.assertEqual(xs, sum(1 for s in t.spans if not s.not_run and not s.phase))
                sections = [e for e in ev if e.get("cat") == "phase"]
                self.assertEqual(len(sections), 2 * t.phase_count)

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
                self.assertEqual(total, sum(s.self_time for s in t.spans if not s.phase))
                for ph in (s for s in t.spans if s.phase):     # phases are no frames
                    self.assertNotIn(";" + ph.name + " ", out)

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
        self.assertLess(tb["load"]["wait"], ta["load"]["wait"] / 2)
        self.assertLess(b.root.dur, a.root.dur)
        code, out, err = cli(["--no-color", "--width", "400",
                              os.path.join(SAMPLES, "diff", "before.header"),
                              "--diff", os.path.join(SAMPLES, "diff", "after.header")])
        self.assertEqual(code, 0, err)
        rows = [l for l in out.splitlines()[2:] if l and not l.startswith("Δ")]
        mags = []
        for line in rows:
            cols = line.split()
            if cols[0].startswith(":"):
                mags.append(abs(float(cols[3])))       # a phase: its change of duration only
                continue
            path_end = cols.index(next(c for c in cols if "→" in c))
            deltas = [abs(float(x)) for x in cols[path_end + 2:path_end + 6]]
            mags.append(max(deltas))
        self.assertEqual(mags, sorted(mags, reverse=True))
        load = [l for l in rows if l.startswith("load ")][0]
        self.assertIn("%+.3f" % ((tb["load"]["wait"] - ta["load"]["wait"]) / 1000.0), load)
        # a phase row carries its change of duration only
        rerank = [l for l in rows if l.startswith(":rerank ")][0].split()
        self.assertEqual((rerank[1], len(rerank)), ("1→1", 6))
        self.assertEqual(rerank[3], "%+.3f" % ((tb[":rerank"]["sum"] - ta[":rerank"]["sum"]) / 1000.0))

    def test_diff_against_itself_is_zero(self):
        p = os.path.join(SAMPLES, "find-healthy.header")
        code, out, err = cli(["--no-color", "--width", "400", p, "--diff", p])
        self.assertEqual(code, 0, err)
        body = out.splitlines()[2:-1]
        self.assertTrue(body)
        for line in body:
            self.assertNotRegex(line, r"[+-](?!0\.000)\d+\.\d{3}")

    def test_stats_over_the_batch(self):
        files = sorted(glob.glob(os.path.join(SAMPLES, "batch", "*.header")))
        self.assertEqual(len(files), 30)
        traces = [trace_of(f) for f in files]
        sums = sorted(sum(s.dur for s in t.spans if s.key == "match") for t in traces)
        p50, p95 = sums[14], sums[28]          # nearest rank: ceil(0.5*30)=15, ceil(0.95*30)=29
        self.assertEqual(tempo.percentile(sums, 50), p50)
        self.assertEqual(tempo.percentile(sums, 95), p95)
        self.assertEqual(tempo.percentile([7], 95), 7)
        code, out, err = cli(["--no-color", "--width", "200", "--stats"] + files)
        self.assertEqual(code, 0, err)
        self.assertIn("over 30 traces", out)
        row = [l for l in out.splitlines() if l.startswith("match ")][0].split()
        self.assertEqual(row[1], "30/30")
        self.assertEqual(row[3:6], [tempo.ms(p50), tempo.ms(p95), tempo.ms(sums[-1])])
        # a phase row: its durations only, no self time or wait
        rerank = sorted(sum(s.dur for s in t.spans if s.key == ":rerank") for t in traces)
        row = [l for l in out.splitlines() if l.startswith(":rerank ")][0].split()
        self.assertEqual(row[1:], ["30/30", "1", tempo.ms(tempo.percentile(rerank, 50)),
                                   tempo.ms(tempo.percentile(rerank, 95)), tempo.ms(rerank[-1])])
        for line in out.splitlines()[2:-1]:
            if line.startswith(":"):
                continue
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
        self.assertIn("cpu-2   infer", out)
        # phases section the root's own time on the path; they are never hops
        self.assertFalse([h for h in hops if h["span"].phase])

    def test_critical_path_ends_in_the_work_that_ran_last(self):
        # the request's span waits on the matches and ends with the last of them: that
        # match, not 'blocked', is the end of the path, reached through its queue wait
        for name in ("diff/after", "batch/find-05", "batch/find-08"):
            with self.subTest(trace=name):
                t = trace_of(os.path.join(SAMPLES, name + ".header"))
                hops = tempo.critical_hops(t)
                last = max((s for s in t.spans if s.name == "match" and not s.agg), key=lambda s: s.end)
                self.assertIs(hops[-1]["span"], last)
                self.assertEqual((hops[-1]["wait"], hops[-1]["run"]), (last.wait, last.end - last.run_start))
                self.assertLess(sum(h["blocked"] for h in hops), 0.03 * (t.root.end - t.root.start))

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
        for k in ("never-started", "open", "queue-wait"):
            self.assertIn(k, sat)
        # the handoffs are children of the request's span, which ends with them: a
        # straggler outlives its parent, as match 4 outlives rerank here
        t = self.early_exit_trace(5000)
        strag = [(k, s.id) for k, s, _, _ in tempo.findings(t, set(s.id for s in t.spans)) if k == "straggler"]
        self.assertEqual(strag, [("straggler", 4)])
        healthy = kinds("find-healthy")
        self.assertNotIn("never-started", healthy)
        self.assertIn("failed", healthy)
        # a phase is a finding only by its flags: copy failed, check was misused
        t = trace_of(os.path.join(GOLDEN, "phases.header"))
        found = [(k, s.path) for k, s, _, _ in tempo.findings(t, tempo.Filter().apply(t)[0])
                 if s is not None and s.phase]
        self.assertEqual(found, [("failed", "(open|copy):copy"), ("misuse", ":check")])
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
        allowed = {"argparse", "base64", "datetime", "json", "math", "os", "re", "shutil", "subprocess", "sys",
                   "textwrap", "webbrowser", "zlib"}
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for a in node.names:
                    self.assertIn(a.name.split(".")[0], allowed)
            elif isinstance(node, ast.ImportFrom):
                self.assertIn(node.module.split(".")[0], allowed)
            self.assertNotIsInstance(node, getattr(ast, "Match", ()))

    def test_python38_syntax_of_the_other_tools(self):
        for name in ("tempo_ref.py", "gen_traces.py"):
            ast.parse(read(os.path.join(ROOT, "tools", name)), feature_version=(3, 8))
        ast.parse(read(os.path.join(ROOT, "tests", "run_golden.py")), feature_version=(3, 8))
        ast.parse(read(os.path.abspath(__file__)), feature_version=(3, 8))

    def test_link_as_argument_and_pipe(self):
        value = read(os.path.join(SAMPLES, "small.header")).strip()
        r = self.run_cli(["--to", "link", "--base", "http://localhost:1/"], stdin=value)
        self.assertEqual(r.returncode, 0, r.stderr)
        url = r.stdout.decode().strip()
        self.assertTrue(url.startswith("http://localhost:1/#t=z."))
        r = self.run_cli(["--check", url])
        self.assertEqual(r.returncode, 0, r.stderr)
        r = self.run_cli(["--check", url[:150]])
        self.assertEqual(r.returncode, 1)
        self.assertIn(b"E_LINK", r.stderr)
        r = self.run_cli(["--help"])
        for opt in (b"--open", b"--base", b"Tempo-Link", b"link"):
            self.assertIn(opt, r.stdout)


if __name__ == "__main__":
    unittest.main()
