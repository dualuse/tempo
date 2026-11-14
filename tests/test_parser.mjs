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
// * Tempo-Link URLs built with zlib from every golden and sample decode to the same document
//   (fragment, pasted URL, curl -i with a Tempo-Link line); corrupt and truncated links are E_LINK.
// * Input extraction (curl -i, bare values, JSON bodies), number encoding, and, when
//   python3 is available, agreement with tools/tempo_ref.py and tools/gen_traces.py.

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname, basename } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { isDeepStrictEqual } from "node:util";
import { deflateRawSync } from "node:zlib";
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
const curl = "HTTP/2 200 \r\ncontent-type: application/json\r\ntempo-timing: " + wrap(example, 37).replace(/\n/g, "\r\n") + "\r\nserver-timing: total;dur=184.2\r\n\r\n{\"ok\":true}\n";
const cases = [
  ["bare value", example, [example]],
  ["bare value wrapped", wrap(example, 50), [example]],
  ["bare value with surrounding blank lines", "\n\n  " + example + "  \n\n", [example]],
  ["curl -i, wrapped, CRLF, lowercase name", curl, [example]],
  ["curl -v", "> GET /search HTTP/1.1\n> Host: localhost\n>\n< HTTP/1.1 200 OK\n< Tempo-Timing: " + example + "\n< Content-Length: 2\n<\n{}", [example]],
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
// a header cut in transit (no ;e=) followed by another header in curl -i output: the next header
// ends the value instead of being glued on as a wrapped continuation
const small = P.stripWs(read("samples/small.header"));
const smallCut = small.slice(0, Math.floor(small.length * 0.7));
const cutCases = [
  ["cut header, then lowercase header (HTTP/2)", "HTTP/2 200 \r\ntempo-timing: " + smallCut + "\r\ncontent-type: application/json\r\n\r\n{}\n"],
  ["cut header, then Content-Type (HTTP/1.1)", "HTTP/1.1 200 OK\nTempo-Timing: " + smallCut + "\nContent-Type: application/json\n\n{}\n"],
  ["cut wrapped header, then header", "HTTP/1.1 200 OK\nTempo-Timing: " + wrap(smallCut, 41) + "\nx-request-id: 7f3a\nContent-Length: 2\n\n{}\n"],
  ["cut header, then header (curl -v)", "< HTTP/1.1 200 OK\n< Tempo-Timing: " + smallCut + "\n< Content-Type: application/json\n<\n{}"],
  ["cut header as the last header", "HTTP/1.1 200 OK\nTempo-Timing: " + smallCut + "\n\n{}\n"],
  // no space after the colon (legal HTTP): line shape cannot tell it from a wrapped name table,
  // so the decoder decides and the glued header is dropped again
  ["cut header, then Content-Type without a space", "HTTP/1.1 200 OK\nTempo-Timing: " + smallCut + "\nContent-Type:application/json\n\n{}\n"],
  ["cut wrapped header, then two headers without a space", "HTTP/1.1 200 OK\nTempo-Timing: " + wrap(smallCut, 41) + "\nx-request-id:7f3a\nContent-Type:application/json\n\n{}\n"],
  ["cut header, then header without a space (curl -v)", "< HTTP/1.1 200 OK\n< Tempo-Timing: " + smallCut + "\n< Content-Type:application/json\n<\n{}"]
];
const healthy = P.stripWs(read("samples/find-healthy.header"));
const healthyCut = healthy.slice(0, Math.floor(healthy.length * 0.6));
const cutRuns = cutCases.map(([label, input]) => [label, input, smallCut]).concat([
  ["cut find-healthy at 60%, then Content-Type without a space", "HTTP/1.1 200 OK\ntempo-timing: " + healthyCut + "\nContent-Type:application/json\n\n{}\n", healthyCut],
  ["cut wrapped find-healthy at 60%, then Content-Type without a space", "HTTP/1.1 200 OK\nTempo-Timing: " + wrap(healthyCut, 80) + "\nContent-Type:application/json\n\n{}\n", healthyCut]
]);
for (const [label, input, want] of cutRuns) {
  let got;
  try { got = P.extractTraces(input).map(P.load); } catch (e) { check("extract " + label, false, "threw " + e.message); continue; }
  // the header is what arrived, minus glued headers; a glued piece that lands in the discarded
  // last record may stay (it never reaches a span), so compare the decoded document
  check("extract " + label, got.length === 1 && got[0].header.startsWith(want) && got[0].doc.truncated === true && same(got[0].doc, P.decode(want)),
    JSON.stringify(got.map((t) => [t.header.length, want.length, t.doc.truncated])));
}
// a complete wrapped value is never shortened by the retry, even when a header without a space follows
check("extract complete value, then Content-Type without a space",
  same(P.extractTraces("HTTP/1.1 200 OK\nTempo-Timing: " + wrap(example, 37) + "\nContent-Type:application/json\n\n{}\n").map((t) => t.header), [example]));
// a wrapped piece that ends in ":" with trailing terminal padding still continues the value
const colonAt = example.indexOf(":", 40) + 1;
const colonWrapped = "Tempo-Timing: " + example.slice(0, 30) + "\n" + example.slice(30, colonAt) + "   \n" + example.slice(colonAt) + "\n";
check("extract wrap ending in a colon plus padding", colonAt > 30 && same(P.extractTraces(colonWrapped).map((t) => t.header), [example]));

check("extract empty input",P.extractTraces("  \n ").length === 0);
check("extract garbage reports E_VERSION", codeOf(() => P.extractTraces("hello world").map((t) => P.load(t))) === "E_VERSION");
check("extract JSON without trace reports E_INPUT", codeOf(() => P.extractTraces("{\"a\": 1}")) === "E_INPUT");
check("load JSON form re-encodes to the canonical header", P.load({ doc: exampleDoc }).header === example);
check("normalizeJson rejects a bad parent", codeOf(() => P.normalizeJson({ v: 1, threads: ["a"], spans: [{ id: 0, parent: -1, name: "r", start: 0, dur: 1, thread: 0 }, { id: 1, parent: 5, name: "c", start: 0, dur: 1, thread: 0 }] })) === "E_JSON");

// ---------------------------------------------------------------- Tempo-Link (#t=… fragments)
// Links are built here with Node's zlib, independently of the page's packValue, and must decode to
// the same document as the header: via the fragment (what the page does on open), via a pasted URL,
// and via pasted `curl -i` output that has a Tempo-Link line (alone or next to Tempo-Timing).
async function codeOfAsync(promise) {
  try { await promise; return null; } catch (e) { if (e instanceof P.TempoFormatError) return e.code; throw e; }
}
const zOf = (value) => "z." + deflateRawSync(Buffer.from(value, "utf8")).toString("base64url");
const plainOf = (value) => value.replace(/%/g, "%25");
const BASES = ["https://dualuse.de/tempo/", "file:///home/me/tempo/site/index.html", "http://localhost:8080/tools/tempo.html?x=1"];
const linkVectors = goldens.map((n) => [join(goldenDir, n + ".header"), join(goldenDir, n + ".json")])
  .concat(samples.map((p) => [p, p.slice(0, -7) + ".json"]));
let nLinks = 0;
for (const [hp, jp] of linkVectors) {
  const value = P.stripWs(read(hp));
  const want = JSON.parse(read(jp));
  const viaHeader = P.decode(value);
  const label = "link " + hp;
  for (const [form, packed] of [["z", zOf(value)], ["plain", plainOf(value)]]) {
    const base = BASES[nLinks % BASES.length];
    const url = base + "#t=" + packed;
    nLinks++;
    try {
      // the fragment path: what the page does when it is opened with #t=…
      const fromFragment = P.decode(await P.unpackValue(P.linkParams(url).t));
      check(label + " " + form + " fragment", same(fromFragment, want) && same(fromFragment, viaHeader), firstDiff(fromFragment, want));
      // a pasted URL
      const pasted = P.extractTraces(url);
      const r1 = pasted.length === 1 ? await P.loadAsync(pasted[0]) : null;
      check(label + " " + form + " pasted URL", r1 && r1.source === "link" && same(r1.doc, want), JSON.stringify(pasted).slice(0, 120));
      // curl -i with only a Tempo-Link, wrapped by the terminal at 80 columns
      const curlLink = "HTTP/2 200 \r\ncontent-type: application/json\r\n" + wrap("tempo-link: " + url, 80).replace(/\n/g, "\r\n") + "\r\nserver-timing: total;dur=12\r\n\r\n{\"ok\":true}\n";
      const items = P.extractTraces(curlLink);
      const r2 = items.length === 1 ? await P.loadAsync(items[0]) : null;
      check(label + " " + form + " curl -i Tempo-Link only", r2 && same(r2.doc, want), JSON.stringify(items).slice(0, 120));
      // curl -i with both headers: one trace, from Tempo-Timing, no note
      const curlBoth = "HTTP/1.1 200 OK\nTempo-Timing: " + value + "\nTempo-Link: " + url + "\nContent-Type: application/json\n\n{}\n";
      const both = P.extractTraces(curlBoth);
      const r3 = both.length === 1 ? await P.loadAsync(both[0]) : null;
      check(label + " " + form + " curl -i both headers", r3 && r3.source === "header" && !r3.note && same(r3.doc, want), JSON.stringify(both).slice(0, 120));
    } catch (e) {
      check(label + " " + form, false, "threw " + e.message);
    }
  }
}
const sv = P.stripWs(read("samples/find-saturated.header")), hv = P.stripWs(read("samples/find-healthy.header"));
const svDoc = P.decode(sv), hvDoc = P.decode(hv);
const svUrl = "https://dualuse.de/tempo/#t=" + zOf(sv);
const hvUrl = "https://dualuse.de/tempo/#t=" + zOf(hv);
{
  // several links -> several traces (the page sends them to stats)
  const several = P.extractTraces([svUrl, hvUrl, "see " + svUrl + "."].join("\n"));
  const loaded = await Promise.all(several.map(P.loadAsync));
  check("links: three URLs give three traces", loaded.length === 3 && same(loaded[0].doc, svDoc) && same(loaded[1].doc, hvDoc) && same(loaded[2].doc, svDoc));
  const twoCurls = ["HTTP/2 200", "tempo-link: " + svUrl, "", "{}", "HTTP/2 200", "tempo-timing: " + hv, "tempo-link: " + hvUrl, "", "{}"].join("\n");
  const tc2 = await Promise.all(P.extractTraces(twoCurls).map(P.loadAsync));
  check("links: two curl outputs, link only and both headers", tc2.length === 2 && same(tc2[0].doc, svDoc) && tc2[1].source === "header" && same(tc2[1].doc, hvDoc));
  // c= and tab=
  const pair = await P.loadAsync(P.extractTraces(svUrl + "&c=" + zOf(hv) + "&tab=diff")[0]);
  check("links: c= and tab= of a pasted link", same(pair.doc, svDoc) && pair.compare === hv && pair.tab === "diff");
  // the page's own packing round-trips and makeLink keeps the base without its hash
  const made = await P.makeLink("https://example.org/tempo/index.html#t=old&tab=x", sv, hv);
  check("makeLink base and shape", /^https:\/\/example\.org\/tempo\/index\.html#t=z\.[A-Za-z0-9_-]+&c=z\.[A-Za-z0-9_-]+$/.test(made), made.slice(0, 80));
  const mp = P.linkParams(made);
  check("makeLink round trip", (await P.unpackValue(mp.t)) === sv && (await P.unpackValue(mp.c)) === hv);
  check("packValue of a value with % escapes", (await P.unpackValue(await P.packValue("v1;T=a;N=%C3%A4;S=00.0.1;e=1"))) === "v1;T=a;N=%C3%A4;S=00.0.1;e=1");
  check("linkParams without t=", P.linkParams("https://x/#tab=lanes") === null && P.linkParams("https://x/") === null);
  check("curl -v '(#0)' is not a link", P.extractTraces("* Connected to localhost (127.0.0.1) port 80 (#0)\n< HTTP/1.1 200 OK\n< Tempo-Timing: " + example + "\n<\n{}").length === 1);
}
{
  // corrupt and truncated payloads -> E_LINK
  const z = zOf(P.stripWs(read("samples/small.header"))).slice(2);
  let prefixesRejected = 0;
  for (let n = 1; n < z.length; n++) if ((await codeOfAsync(P.unpackValue("z." + z.slice(0, n)))) === "E_LINK") prefixesRejected++;
  check("every truncated z. payload is E_LINK", prefixesRejected === z.length - 1, prefixesRejected + "/" + (z.length - 1));
  const flipped = z.slice(0, 40) + (z[40] === "A" ? "B" : "A") + z.slice(41);
  const corrupt = [
    ["flipped character", "z." + flipped],
    ["not base64url", "z.abc$def"],
    ["impossible base64 length", "z.A"],
    ["empty payload", "z."],
    ["not deflate", "z." + Buffer.from("hello, not deflate at all").toString("base64url")],
    ["trailing junk", "z." + Buffer.concat([deflateRawSync(Buffer.from("v1;T=a;N=r;S=00.0.1;e=1")), Buffer.from("junk")]).toString("base64url")],
    ["not UTF-8", "z." + deflateRawSync(Buffer.from([0x76, 0x31, 0x3b, 0xff, 0xfe])).toString("base64url")]
  ];
  for (const [label, packed] of corrupt) {
    const code = await codeOfAsync(P.loadAsync(P.extractTraces("https://dualuse.de/tempo/#t=" + packed)[0] || { source: "link", link: { t: packed } }));
    check("corrupt link: " + label, code === "E_LINK", "got " + code);
  }
  check("Tempo-Link header without a fragment", (await codeOfAsync(P.loadAsync(P.extractTraces("HTTP/1.1 200 OK\nTempo-Link: https://dualuse.de/tempo/\n\n{}")[0]))) === "E_LINK");
  check("load() refuses a link item", codeOf(() => P.load(P.extractTraces(svUrl)[0])) === "E_LINK");
  // a corrupt Tempo-Link next to a good Tempo-Timing: the header wins, with a note
  const badBeside = await P.loadAsync(P.extractTraces("HTTP/1.1 200 OK\nTempo-Timing: " + sv + "\nTempo-Link: https://dualuse.de/tempo/#t=z." + flipped + "\n\n{}")[0]);
  check("corrupt Tempo-Link beside Tempo-Timing", same(badBeside.doc, svDoc) && /does not decode/.test(badBeside.note || ""), badBeside.note);
  // a cut Tempo-Timing next to a complete Tempo-Link: the link wins, with a note
  const cutBeside = await P.loadAsync(P.extractTraces("HTTP/1.1 200 OK\nTempo-Timing: " + sv.slice(0, 900) + "\nTempo-Link: " + svUrl + "\n\n{}")[0]);
  check("cut Tempo-Timing beside a complete Tempo-Link", cutBeside.source === "link" && same(cutBeside.doc, svDoc) && /was cut/.test(cutBeside.note || ""), cutBeside.note);
  // a different trace in the link: the header wins, with a note
  const differ = await P.loadAsync(P.extractTraces("HTTP/1.1 200 OK\nTempo-Timing: " + sv + "\nTempo-Link: " + hvUrl + "\n\n{}")[0]);
  check("Tempo-Link with a different trace", same(differ.doc, svDoc) && /different/.test(differ.note || ""), differ.note);
  // no DecompressionStream (older browsers): a clear E_LINK, plain links still work
  const DS = globalThis.DecompressionStream;
  delete globalThis.DecompressionStream;
  let noDs = null;
  try { await P.unpackValue("z." + z); } catch (e) { noDs = e; }
  const plainStill = await P.unpackValue(plainOf(sv)).catch(() => null);
  globalThis.DecompressionStream = DS;
  check("no DecompressionStream is E_LINK", noDs && noDs.code === "E_LINK" && /DecompressionStream/.test(noDs.message), noDs && noDs.message);
  check("no DecompressionStream, plain link still decodes", plainStill === sv);
}
console.log("links: " + nLinks + " built with zlib from " + linkVectors.length + " goldens and samples");

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
