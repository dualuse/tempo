#!/usr/bin/env node
// Runs the JS parser of site/index.html (via tools/tempo_parse.mjs) against every test vector.
//
//   node tests/test_parser.mjs
//
// * testdata/golden/NAME.header decodes to NAME.json (structural equality).
// * Round trip decode(encode(json)) == json; canonical vectors re-encode byte for byte.
// * Every prefix of every canonical vector is rejected as cut or decodes to a span prefix.
// * testdata/invalid/*.header fail with the code listed in testdata/invalid/README.md.
// * samples/**/*.header decode to their .json.
// * Input extraction (curl -i, bare values, JSON bodies), number encoding, and, when
//   python3 is available, agreement with tools/tempo_ref.py and tools/gen_traces.py.

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname, basename } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { isDeepStrictEqual } from "node:util";
import * as P from "../tools/tempo_parse.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const NON_CANONICAL = new Set(["lenient", "whitespace", "truncated-cut", "truncated-semicolon", "truncated-count", "truncated-empty"]);
let passed = 0;
const failed = [];

function check(label, ok, detail) {
  if (ok) passed++;
  else failed.push(label + (detail ? " " + detail : ""));
  return ok;
}
const read = (p) => readFileSync(join(ROOT, p), "utf8");
// JSON.parse already compares numbers by value (2.0 == 2); plain deep equality, key order ignored.
const same = (a, b) => isDeepStrictEqual(sortKeys(a), sortKeys(b));
function sortKeys(x) {
  if (Array.isArray(x)) return x.map(sortKeys);
  if (x && typeof x === "object") { const o = {}; for (const k of Object.keys(x).sort()) o[k] = sortKeys(x[k]); return o; }
  return x;
}
function firstDiff(a, b) {
  for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) if (k !== "spans" && !same(a[k], b[k])) return "field " + k + ": got " + JSON.stringify(a[k]) + " want " + JSON.stringify(b[k]);
  const sa = a.spans || [], sb = b.spans || [];
  for (let i = 0; i < Math.max(sa.length, sb.length); i++) if (!same(sa[i], sb[i])) return "span " + i + ": got " + JSON.stringify(sa[i]) + " want " + JSON.stringify(sb[i]);
  return "";
}
function codeOf(fn) {
  try { fn(); return null; } catch (e) { if (e instanceof P.TempoFormatError) return e.code; throw e; }
}

// ---------------------------------------------------------------- golden
const goldenDir = "testdata/golden";
const goldens = readdirSync(join(ROOT, goldenDir)).filter((f) => f.endsWith(".header")).map((f) => f.slice(0, -7)).sort();
let nGolden = 0;
for (const name of goldens) {
  nGolden++;
  const header = read(join(goldenDir, name + ".header"));
  const want = JSON.parse(read(join(goldenDir, name + ".json")));
  let got;
  try { got = P.decode(header); } catch (e) { check("golden " + name, false, "threw " + e.message); continue; }
  check("golden " + name + " decode", same(got, want), firstDiff(got, want));
  if (!want.spans.length) continue;   // nothing to encode (truncated-empty)
  const enc = P.encode(want);
  const back = P.decode(enc);
  check("golden " + name + " round trip", same(back, Object.assign({}, want, { truncated: false })), firstDiff(back, want));
  if (!NON_CANONICAL.has(name)) {
    const raw = P.stripWs(header);
    check("golden " + name + " canonical", enc === raw, "\n  got  " + enc + "\n  want " + raw);
    // truncation: every prefix is rejected as cut or decodes to a prefix of the span list
    let bad = null;
    for (let n = 0; n < raw.length && !bad; n++) {
      const prefix = raw.slice(0, n);
      let d;
      try { d = P.decode(prefix); } catch (e) {
        if (!(e instanceof P.TempoFormatError) || (e.code !== "E_VERSION" && e.code !== "E_CUT")) bad = "prefix " + n + " threw " + e.message;
        continue;
      }
      if (!d.truncated) bad = "prefix " + n + " not marked truncated";
      else if (d.spans.length >= want.spans.length || !same(d.spans, want.spans.slice(0, d.spans.length))) bad = "prefix " + n + " yields a false record";
    }
    check("golden " + name + " prefixes", !bad, bad || "");
  }
}

// ---------------------------------------------------------------- invalid
const table = new Map();
for (const line of read("testdata/invalid/README.md").split("\n")) {
  const m = /^\|\s*`([^`]+\.header)`\s*\|\s*`(E_[A-Z]+)`/.exec(line);
  if (m) table.set(m[1], m[2]);
}
const invalidFiles = readdirSync(join(ROOT, "testdata/invalid")).filter((f) => f.endsWith(".header")).sort();
check("invalid README lists every file", invalidFiles.length === table.size && invalidFiles.every((f) => table.has(f)), invalidFiles.filter((f) => !table.has(f)).join(", "));
for (const f of invalidFiles) {
  const code = codeOf(() => P.decode(read(join("testdata/invalid", f))));
  check("invalid " + f, code === table.get(f), "got " + code + ", want " + table.get(f));
}

// ---------------------------------------------------------------- samples
function walk(dir) {
  const out = [];
  for (const f of readdirSync(join(ROOT, dir)).sort()) {
    const p = join(dir, f);
    if (statSync(join(ROOT, p)).isDirectory()) out.push(...walk(p));
    else if (f.endsWith(".header")) out.push(p);
  }
  return out;
}
const samples = walk("samples");
for (const p of samples) {
  const got = P.decode(read(p));
  const want = JSON.parse(read(p.slice(0, -7) + ".json"));
  check("sample " + p, same(got, want), firstDiff(got, want));
}

// ---------------------------------------------------------------- input extraction
const example = P.stripWs(read("testdata/golden/search-example.header"));
const exampleDoc = JSON.parse(read("testdata/golden/search-example.json"));
const wrap = (s, w) => s.match(new RegExp(".{1," + w + "}", "g")).join("\n");
const curl = "HTTP/2 200 \r\ncontent-type: application/json\r\ntempo-trace: " + wrap(example, 37).replace(/\n/g, "\r\n") + "\r\nserver-timing: total;dur=184.2\r\n\r\n{\"ok\":true}\n";
const cases = [
  ["bare value", example, [example]],
  ["bare value wrapped", wrap(example, 50), [example]],
  ["bare value with surrounding blank lines", "\n\n  " + example + "  \n\n", [example]],
  ["curl -i, wrapped, CRLF, lowercase name", curl, [example]],
  ["curl -v", "> GET /search HTTP/1.1\n> Host: localhost\n>\n< HTTP/1.1 200 OK\n< Tempo-Trace: " + example + "\n< Content-Length: 2\n<\n{}", [example]],
  ["two curl outputs", curl + "\n" + curl, [example, example]],
  ["three bare values, one per line", [example, example, example].join("\n"), [example, example, example]],
  ["wrapped inside e= digits", example.slice(0, -1) + "\n" + example.slice(-1), [example]],
  ["JSON body with a header string", JSON.stringify({ result: [], tempo: example }), [example]]
];
for (const [label, input, want] of cases) {
  let got;
  try { got = P.extractTraces(input).map((t) => t.header); } catch (e) { got = "threw " + e.message; }
  check("extract " + label, same(got, want), JSON.stringify(got).slice(0, 200));
}
const jsonCases = [
  ["JSON form", JSON.stringify(exampleDoc), 1],
  ["JSON body with tempo object", JSON.stringify({ info: { tempo: exampleDoc }, data: [1, 2] }), 1],
  ["JSON array of traces", JSON.stringify([exampleDoc, exampleDoc]), 2],
  ["NDJSON", JSON.stringify(exampleDoc) + "\n" + JSON.stringify(exampleDoc) + "\n", 2],
  ["curl -i with JSON body, no header", "HTTP/1.1 200 OK\nContent-Type: application/json\n\n" + JSON.stringify({ tempo: exampleDoc }), 1]
];
for (const [label, input, n] of jsonCases) {
  let items;
  try { items = P.extractTraces(input); } catch (e) { check("extract " + label, false, e.message); continue; }
  const ok = items.length === n && items.every((it) => it.doc && same(P.load(it).doc, exampleDoc));
  check("extract " + label, ok, JSON.stringify(items.map((i) => i.source)));
}
check("extract empty input", P.extractTraces("  \n ").length === 0);
check("extract garbage reports E_VERSION", codeOf(() => P.extractTraces("hello world").map((t) => P.load(t))) === "E_VERSION");
check("extract JSON without trace reports E_INPUT", codeOf(() => P.extractTraces("{\"a\": 1}")) === "E_INPUT");
check("load JSON form re-encodes to the canonical header", P.load({ doc: exampleDoc }).header === example);
check("normalizeJson rejects a bad parent", codeOf(() => P.normalizeJson({ v: 1, threads: ["a"], spans: [{ id: 0, parent: -1, name: "r", start: 0, dur: 1, thread: 0 }, { id: 1, parent: 5, name: "c", start: 0, dur: 1, thread: 0 }] })) === "E_JSON");

// ---------------------------------------------------------------- numbers
const numbers = [[2, "2"], [2.0, "2"], [-0, "0"], [-0.0000001, "0"], [1.5, "1.5"], [0.83, "0.83"], [0.000001, "0.000001"],
  [0.0078125, "0.007812"], [0.0234375, "0.023438"], [-0.0078125, "-0.007812"], [123456.789, "123456.789"], [-3.14159265, "-3.141593"],
  [9007199254740991, "9007199254740991"], [NaN, null], [Infinity, null]];
for (const [v, want] of numbers) check("encodeNumber " + v, P.encodeNumber(v) === want, "got " + P.encodeNumber(v));

// ---------------------------------------------------------------- python cross-checks
const py = spawnSync("python3", ["--version"]);
if (py.status === 0) {
  // number formatting against Python's "%.6f" (ties to even)
  const vals = [];
  let seed = 12345;
  const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  for (let i = 0; i < 400; i++) vals.push((rnd() - 0.5) * Math.pow(10, Math.floor(rnd() * 12) - 6));
  for (let i = 1; i < 200; i += 2) vals.push(i / 128, -i / 256, i / 2048);
  const r = spawnSync("python3", ["-c", "import sys,json\nsys.path.insert(0,'tools')\nimport tempo_ref\nprint(json.dumps([tempo_ref.encode_number(v) for v in json.load(sys.stdin)]))"], { cwd: ROOT, input: JSON.stringify(vals) });
  const pyOut = JSON.parse(r.stdout.toString());
  const mism = vals.filter((v, i) => P.encodeNumber(v) !== pyOut[i]);
  check("encodeNumber agrees with python on " + vals.length + " values", mism.length === 0, mism.slice(0, 5).join(", "));
  // generator traces: JS decode of the header equals the generator's JSON
  const configs = [];
  for (let s = 1; s <= 12; s++) configs.push(["--seed", String(s * 7), "--candidates", String(8 + s * 5), "--cpu", String(1 + (s % 6)), "--saturation", String((s % 5) * 0.4)]);
  configs.push(["--mode", "pipeline", "--seed", "3", "--frames", "60"], ["--seed", "9", "--leaky-cancel", "--saturation", "1.5", "--cpu", "2"], ["--seed", "4", "--shared-pool"], ["--seed", "5", "--max-bytes", "900"]);
  let agree = 0;
  for (const c of configs) {
    const h = spawnSync("python3", ["tools/gen_traces.py", ...c], { cwd: ROOT });
    const j = spawnSync("python3", ["tools/gen_traces.py", ...c, "--format", "json"], { cwd: ROOT });
    if (h.status !== 0 || j.status !== 0) { check("gen_traces " + c.join(" "), false, h.stderr.toString() + j.stderr.toString()); continue; }
    const got = P.decode(h.stdout.toString());
    const want = JSON.parse(j.stdout.toString());
    if (check("gen_traces " + c.join(" "), same(got, want), firstDiff(got, want))) agree++;
  }
  console.log("python cross-checks: " + vals.length + " numbers, " + agree + "/" + configs.length + " generated traces");
} else {
  console.log("python3 not found: skipped the cross-checks with tools/tempo_ref.py");
}

console.log("golden vectors: " + nGolden + ", invalid vectors: " + invalidFiles.length + ", samples: " + samples.length);
for (const f of failed) console.log("FAIL " + f);
console.log("checks passed: " + passed + ", failed: " + failed.length);
process.exit(failed.length ? 1 : 0);
