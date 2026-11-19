#!/usr/bin/env node
// Tests the viewer analysis of site/index.html (via tools/tempo_analysis.mjs) on hand-built
// traces, the golden vectors and the generator samples: self-time, critical path, diff,
// multi-trace statistics, findings, pools and the converters.
//
//   node tests/test_analysis.mjs

import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import * as P from "../tools/tempo_parse.mjs";
import * as A from "../tools/tempo_analysis.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
let passed = 0;
const failed = [];
function check(label, ok, detail) {
  if (ok) passed++;
  else failed.push(label + (detail !== undefined && detail !== "" ? " " + detail : ""));
  return ok;
}
const read = (p) => readFileSync(join(ROOT, p), "utf8");
const load = (p) => A.model(P.decode(read(p)));
const json = (spans, threads, extra) => A.model(P.normalizeJson(Object.assign({ v: 1, threads: threads || ["http-1", "cpu-1", "cpu-2", "io-1"], spans: spans }, extra || {})));
const sp = (id, parent, name, start, dur, thread, more) => Object.assign({ id, parent, name, start, dur, thread }, more || {});

const SAMPLES = ["small", "find-healthy", "find-saturated", "pipeline", "diff/before", "diff/after"].map((n) => "samples/" + n + ".header");
const BATCH = Array.from({ length: 30 }, (_, i) => "samples/batch/find-" + String(i + 1).padStart(2, "0") + ".header");
const GOLDEN = readdirSync(join(ROOT, "testdata/golden")).filter((f) => f.endsWith(".header")).map((f) => "testdata/golden/" + f);
const ALL = SAMPLES.concat(BATCH, GOLDEN);

// ---------------------------------------------------------------- self-time
{
  // root 0..1000 on http-1: a same-thread child (300), a handoff to cpu-1 (500, never subtracted),
  // a blocking wait (500, subtracted, own self 0) and a handoff onto the root's own thread (30).
  const m = json([
    sp(0, -1, "GET /x", 0, 1000, 0),
    sp(1, 0, "parse", 100, 300, 0),
    sp(2, 0, "work", 100, 500, 1, { wait: 50, cpu: 120 }),
    sp(3, 0, "await", 400, 500, 0, { flags: "w" }),
    sp(4, 0, "tail", 950, 30, 0, { wait: 10 }),
    sp(5, 0, "never", 960, 0, 0, { wait: 40, flags: "n" })
  ]);
  const self = m.spans.map((s) => s.self);
  check("self: root subtracts same-thread children and the blocking wait only", self[0] === 1000 - 300 - 500 - 30, "got " + self[0]);
  check("self: blocking wait has no self-time", self[3] === 0);
  check("self: handoff keeps its run time", self[2] === 500);
  check("self: never-started handoff is 0", self[5] === 0 && m.spans[5].notRun);
  check("self: off-CPU is dur - cpu", m.spans[2].offCpu === 380);
}
{
  const m = load("testdata/golden/search-example.header");
  const by = Object.fromEntries(m.spans.map((s) => [s.label, s.self]));
  check("self: example root = 184200 - 183800", by["GET /search"] === 400, by["GET /search"]);
  check("self: cross-thread handoff not subtracted from describe", by["describe"] === 41200);
  check("self: aggregate self is its sum", m.spans[11].self === 147600);
}
let selfOk = 0;
for (const p of ALL) {
  const m = load(p);
  let bad = null;
  for (const s of m.spans) {
    if (s.self < 0 || s.self > s.dur) bad = "span " + s.id + " self " + s.self + " outside 0.." + s.dur;
    if (A.has(s, "w") && s.self !== 0) bad = "blocking span " + s.id + " has self " + s.self;
    if (s.isPhase) {
      // a phase is no tree child: no self time, no children, no row of its own
      if (s.self !== 0 || s.children.length || s.phases.length || s.parent.children.includes(s) || !s.parent.phases.includes(s) || s.row !== s.parent.row) bad = "phase " + s.id + " treated as a child";
      continue;
    }
    if (s.children.some((c) => c.isPhase)) bad = "span " + s.id + " has a phase among its children";
    // a plain span whose same-thread children all lie inside it: self = dur - sum(children)
    const same = s.children.filter((c) => c.thread === s.thread && !c.notRun);
    if (!s.isAgg && !A.has(s, "w") && same.every((c) => c.runStart >= s.runStart && c.runEnd <= s.runEnd)) {
      const want = Math.max(0, s.dur - same.reduce((a, c) => a + c.dur, 0));
      if (s.self !== want) bad = "span " + s.id + " self " + s.self + " want " + want;
    }
  }
  if (check("self invariants " + p, !bad, bad || "")) selfOk++;
}

// ---------------------------------------------------------------- critical path
{
  // root waits (w) for two handoffs; the later one (submitted at 20, queued 300, ran 400) explains the wait
  const m = json([
    sp(0, -1, "GET /x", 0, 1000, 0),
    sp(1, 0, "prep", 10, 10, 0),
    sp(2, 0, "a", 20, 400, 1, { wait: 300 }),
    sp(3, 0, "b", 25, 200, 2, { wait: 5 }),
    sp(4, 0, "await", 30, 700, 0, { flags: "w" }),
    sp(5, 0, "send", 800, 150, 0)
  ]);
  const cp = A.criticalPath(m);
  const seq = cp.segments.map((g) => m.spans[g.id].name + ":" + g.kind + ":" + g.from + "-" + g.to).join(" ");
  const want = "GET /x:run:0-10 prep:run:10-20 a:wait:20-320 a:run:320-720 await:block:720-730 GET /x:run:730-800 send:run:800-950 GET /x:run:950-1000";
  check("critical path crosses the handoff behind a blocking wait", seq === want, "\n  got  " + seq + "\n  want " + want);
  check("critical path totals by kind", cp.byKind.run === 690 && cp.byKind.wait === 300 && cp.byKind.block === 10 && cp.total === 1000, JSON.stringify(cp.byKind));
}
{
  // a straggler that ends after its parent is not on the path; a never-started handoff never is
  const m = json([
    sp(0, -1, "r", 0, 100, 0),
    sp(1, 0, "late", 10, 200, 1, { wait: 0 }),
    sp(2, 0, "q", 20, 0, 0, { wait: 300, flags: "n" }),
    sp(3, 0, "x", 30, 50, 0)
  ]);
  const ids = new Set(A.criticalPath(m).segments.map((g) => g.id));
  check("critical path skips stragglers and never-started work", !ids.has(1) && !ids.has(2) && ids.has(3));
}
let cpOk = 0;
const share = {};
for (const p of ALL) {
  const m = load(p);
  if (!m.spans.length) continue;
  const cp = A.criticalPath(m);
  let bad = null, at = 0;
  for (const g of cp.segments) {
    const s = m.spans[g.id];
    if (g.from !== at) bad = "gap or overlap at " + at + " (next segment starts " + g.from + ")";
    at = g.to;
    if (g.from < s.start || g.to > s.end) bad = "segment of span " + s.id + " outside it: " + g.from + "-" + g.to;
    if (g.kind === "wait" && !s.isHandoff) bad = "wait segment on non-handoff " + s.id;
    if (g.kind === "agg" && !s.isAgg) bad = "agg segment on plain span " + s.id;
    if (s.notRun) bad = "never-started span " + s.id + " on the path";
  }
  if (!bad && at !== m.root.end) bad = "path ends at " + at + ", root ends at " + m.root.end;
  if (!bad && cp.total !== m.root.end) bad = "total " + cp.total;
  if (check("critical path covers the root " + p, !bad, bad || "")) cpOk++;
  share[p] = cp.byKind.wait / cp.total;
}
check("critical path: saturated pool shows more queue wait than healthy", share["samples/find-saturated.header"] > 3 * share["samples/find-healthy.header"],
  share["samples/find-saturated.header"] + " vs " + share["samples/find-healthy.header"]);

// ---------------------------------------------------------------- diff
{
  const before = load("samples/diff/before.header"), after = load("samples/diff/after.header");
  const same = A.diff(before, before);
  check("diff of a trace with itself is all zero", same.every((r) => !r.dCount && !r.dSum && !r.dSelf && !r.dWait && !r.dCpu));
  const rows = A.diff(before, after);
  const pa = new Map(A.byPath(before).map((r) => [r.key, r])), pb = new Map(A.byPath(after).map((r) => [r.key, r]));
  const keys = new Set([...pa.keys(), ...pb.keys()]);
  check("diff aligns every path once", rows.length === keys.size && new Set(rows.map((r) => r.key)).size === keys.size);
  let bad = null;
  rows.forEach((r, i) => {
    const a = pa.get(r.key), b = pb.get(r.key);
    if (r.dSum !== (b ? b.sum : 0) - (a ? a.sum : 0)) bad = r.key + " dSum";
    if (r.dWait !== (b ? b.waitSum : 0) - (a ? a.waitSum : 0)) bad = r.key + " dWait";
    if (r.dCount !== (b ? b.count : 0) - (a ? a.count : 0)) bad = r.key + " dCount";
    if (i && Math.abs(rows[i - 1].dSum) < Math.abs(r.dSum)) bad = "not sorted by |dSum| at " + r.key;
  });
  check("diff deltas are after - before, sorted by |Δsum|", !bad, bad || "");
  const root = rows.find((r) => r.key === "");
  check("diff root delta is the duration change", root && root.dSum === after.root.dur - before.root.dur);
  const w = (k) => rows.find((r) => r.ref === "span:" + k).dWait;
  check("diff: splitting the shared pool cuts the queue wait of loads and matches", w("load") < 0 && w("match") < 0, w("load") + " " + w("match"));
  const healthy = load("samples/find-healthy.header"), pipe = load("samples/pipeline.header");
  const cross = A.diff(healthy, pipe);
  check("diff of unrelated traces keeps one-sided paths", cross.some((r) => r.a && !r.b) && cross.some((r) => !r.a && r.b));
}

// ---------------------------------------------------------------- stats
{
  const p = A.percentile;
  const ten = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  check("percentile nearest rank", p(ten, 50) === 5 && p(ten, 95) === 10 && p(ten, 10) === 1 && p([7], 95) === 7 && Number.isNaN(p([], 50)));
  const models = BATCH.map(load);
  const rows = A.stats(models);
  const root = rows.find((r) => r.key === "");
  const durs = models.map((m) => m.root.dur).sort((a, b) => a - b);
  check("stats: root present in all 30 traces", root.n === 30 && root.traces === 30);
  check("stats: root p50/p95/max by nearest rank", root.sum.p50 === durs[14] && root.sum.p95 === durs[28] && root.sum.max === durs[29], JSON.stringify(root.sum));
  let bad = null;
  for (const r of rows) {
    for (const f of ["sum", "self", "wait", "count"]) {
      const s = r[f];
      if (!(s.min <= s.p50 && s.p50 <= s.p95 && s.p95 <= s.max)) bad = r.key + " " + f + " not ordered";
    }
    if (r.n > 30 || r.n < 1) bad = r.key + " n=" + r.n;
  }
  check("stats: p50 <= p95 <= max for every path", !bad, bad || "");
  const match = rows.find((r) => r.ref === "span:match");
  const direct = models.map((m) => m.spans.filter((s) => s.key === "match").reduce((a, s) => a + s.dur, 0)).filter((x, i) => models[i].spans.some((s) => s.key === "match")).sort((a, b) => a - b);
  check("stats: per-path sum matches a direct computation", match && match.sum.max === direct[direct.length - 1] && match.n === direct.length);
  check("stats: one trace gives p50 = p95 = max", A.stats([models[0]]).every((r) => r.sum.p50 === r.sum.max && r.sum.p95 === r.sum.max));
}

// ---------------------------------------------------------------- findings
{
  const kinds = (p) => A.findings(load(p)).map((f) => f.kind);
  const sat = kinds("samples/find-saturated.header");
  check("findings: saturated trace reports never-started and open matches and queue waits", sat.includes("never") && sat.includes("open") && sat.includes("wait") && sat.includes("live"), sat.join(","));
  check("findings: healthy trace reports failed loads", kinds("samples/find-healthy.header").includes("failed"));
  const late = A.findings(json([sp(0, -1, "r", 0, 100, 0), sp(1, 0, "late", 10, 200, 1, { wait: 0 }), sp(2, 0, "a", 0, 50, 0, { flags: "p" })]));
  check("findings: a child that ends after its parent is a straggler", late.some((f) => f.kind === "straggler" && f.id === 1), JSON.stringify(late));
  check("findings: truncated vector", kinds("testdata/golden/truncated-cut.header")[0] === "truncated");
  const m = json([
    sp(0, -1, "r", 0, 10000, 0),
    sp(1, 0, "crunch", 10, 5000, 1, { wait: 20, cpu: 1000 }),
    sp(2, 0, "fetch", 10, 5000, 3, { wait: 20, cpu: 100 }),
    sp(3, 0, "hold", 6000, 3000, 2, { flags: "w" })
  ]);
  const off = A.findings(m).filter((f) => f.kind === "offcpu").map((f) => f.id);
  check("findings: off-CPU and blocking on a cpu pool, not on io", off.includes(1) && off.includes(3) && !off.includes(2), JSON.stringify(off));
  for (const p of ALL) {
    const mm = load(p);
    if (!A.findings(mm).every((f) => f.id === -1 || (f.id >= 0 && f.id < mm.spans.length))) check("findings ids valid " + p, false);
  }
}

// ---------------------------------------------------------------- pools
{
  const pool = (p, n) => A.pools(load(p)).find((x) => x.name === n);
  const sat = pool("samples/find-saturated.header", "cpu"), ok = pool("samples/find-healthy.header", "cpu");
  check("pools: saturated cpu pool has a longer queue than the healthy one", sat.peakPend > ok.peakPend && sat.waitMax > ok.waitMax);
  check("pools: gauges read from queued/active attributes", sat.gauges.length > 0 && sat.gauges.every((g) => g.queued !== null));
  check("pools: never-started matches are attributed to the cpu pool", sat.notStarted === 13);
  check("pools: grouping strips -<digits>, Thread-N stays", A.poolOf("cpu-12") === "cpu" && A.poolOf("Thread-3") === "Thread-3" && A.poolOf("main") === "main");
  // aggregates are spread over their extent, an estimate; it must never claim more threads than the pool has
  const over = [];
  for (const f of ALL) for (const q of A.pools(load(f))) {
    const n = Math.max(q.threads.length, q.distinct);
    if (!n) continue;
    if (q.peakRun > n + 1e-9 || q.running.some((r) => r[1] > n + 1e-9)) over.push(f + ":" + q.name + " " + q.peakRun + "/" + n);
  }
  check("pools: peak running never exceeds the pool's threads (" + ALL.length + " traces)", over.length === 0, over.join(", "));
  check("pools: healthy cpu peak is an estimate, capped at 8 threads, spans-only peak beside it", ok.runEstimated && ok.peakRun === 8 && ok.peakRunSpans <= 8 && ok.peakRunSpans > 0, ok.peakRun + " " + ok.peakRunSpans);
}

// ---------------------------------------------------------------- phases (FORMAT.md §6.7)
{
  // root 0..1000 with phases a 0..400 and b 400..900 (100 unphased at the end); a same-thread child
  // 300..600 crosses the boundary; an unnamed span 600..800 labelled by its phases x, y
  const m = json([
    sp(0, -1, "GET /x", 0, 1000, 0),
    sp(1, 0, "a", 0, 400, 0, { flags: "p", attrs: { rows: 3 } }),
    sp(2, 0, "cross", 300, 300, 0),
    sp(3, 0, "b", 400, 500, 0, { flags: "fp" }),
    sp(4, 0, "", 600, 200, 0),
    sp(5, 4, "x", 600, 50, 0, { flags: "p" }),
    sp(6, 4, "y", 650, 150, 0, { flags: "p" }),
    sp(7, 0, "fan", 700, 400, 1, { wait: 10 })
  ]);
  const [root, a, cross, b, un, x, y, fan] = m.spans;
  check("phases: not children of their span", root.children.map((s) => s.id).join() === "2,4,7" && root.phases.map((s) => s.id).join() === "1,3");
  check("phases: self time of the span ignores them", root.self === 1000 - 300 - 200, "got " + root.self);
  check("phases: a phase has no self time", a.self === 0 && b.self === 0 && x.self === 0);
  check("phases: drawn on their span's row", a.row === root.row && x.row === un.row && un.row === root.row + 1, [a.row, root.row, x.row, un.row].join());
  check("phases: an unnamed span is titled by its phases", un.unnamed && un.title === "x · y" && un.key === "x · y" && x.key === "x · y/x", un.title + " " + un.key + " " + x.key);
  const cp = A.criticalPath(m);
  check("phases: never on the critical path", cp.segments.every((g) => !m.spans[g.id].isPhase) && cp.total === 1000, JSON.stringify(cp.segments));
  const rows = A.byPath(m);
  const ra = rows.find((r) => r.ref === "phase:a");
  check("phases: a row of their own per path, durations only", ra && ra.phase && ra.count === 1 && ra.sum === 400 && ra.self === 0 && ra.cpuSum === 0 && ra.handoffs === 0);
  check("phases: a failed phase is a finding, never a straggler", A.findings(m).some((f) => f.kind === "failed" && f.id === 3) && !A.findings(m).some((f) => f.kind === "straggler" && m.spans[f.id].isPhase));
  check("phases: not tasks of a pool", A.pools(m).every((p) => p.tasks <= 1));
  const chrome = A.toChrome(m);
  const pev = chrome.traceEvents.filter((e) => e.cat === "phase");
  check("phases: own Chrome track per thread", pev.length === 4 && pev.every((e) => e.tid === 10001) && chrome.traceEvents.some((e) => e.ph === "M" && e.tid === 10001 && e.args.name === "http-1 · phases"));
  check("phases: no frames in folded stacks", !/;(a|b|x|y) \d+$/m.test(A.toFolded(m)) && /^GET \/x;x · y \d+$/m.test(A.toFolded(m)), A.toFolded(m));
  // a span and a phase of the same path stay apart, also across traces
  const twin = json([sp(0, -1, "r", 0, 100, 0), sp(1, 0, "load", 0, 40, 0, { flags: "p" }), sp(2, 0, "load", 10, 20, 1, { wait: 0 })]);
  const tr = A.byPath(twin).filter((r) => r.key === "load");
  check("phases: a phase and a span of one path are two rows", tr.length === 2 && tr.some((r) => r.phase && r.sum === 40) && tr.some((r) => !r.phase && r.sum === 20));
  const d = A.diff(twin, twin);
  check("phases: diff keeps the phase and the span apart", d.filter((r) => r.key === "load").length === 2);
  check("phases: stats keep the phase and the span apart", A.stats([twin, twin]).filter((r) => r.key === "load").length === 2);
}
{
  const m = load("testdata/golden/phases.header");
  const ph = m.spans.filter((s) => s.isPhase);
  check("phases vector: 10 phases, none a child", ph.length === 10 && m.spans.every((s) => s.children.every((c) => !c.isPhase)));
  check("phases vector: the handoff's phases sit on its worker", m.spans[5].lane === m.spans[4].lane && m.spans[4].thread === 1);
  check("phases vector: unnamed span titled by its phases", m.spans[8].title === "open · copy");
}

// ---------------------------------------------------------------- converters
let convOk = 0;
for (const p of SAMPLES.concat(["testdata/golden/search-example.header", "testdata/golden/handoff-not-run.header", "testdata/golden/names.header", "testdata/golden/phases.header"])) {
  const m = load(p);
  const chrome = JSON.parse(JSON.stringify(A.toChrome(m)));
  const xs = chrome.traceEvents.filter((e) => e.ph === "X");
  const flows = chrome.traceEvents.filter((e) => e.ph === "s").length === chrome.traceEvents.filter((e) => e.ph === "f").length;
  const okChrome = xs.length === m.spans.length && xs.every((e) => e.ts >= 0 && e.dur >= 0 && Number.isInteger(e.tid)) && flows;
  const folded = A.toFolded(m);
  const lines = folded.trim().split("\n");
  const total = lines.reduce((a, l) => a + Number(l.slice(l.lastIndexOf(" ") + 1)), 0);
  const want = m.spans.reduce((a, s) => a + s.self, 0);
  const okFolded = lines.every((l) => /^[^\n]+ \d+$/.test(l)) && new Set(lines.map((l) => l.slice(0, l.lastIndexOf(" ")))).size === lines.length && Math.abs(total - want) <= lines.length;
  if (check("converters " + p, okChrome && okFolded, "chrome " + okChrome + " folded " + okFolded)) convOk++;
}

console.log("traces: " + ALL.length + " (self-time ok " + selfOk + ", critical path ok " + cpOk + "), converters ok " + convOk);
for (const f of failed) console.log("FAIL " + f);
console.log("checks passed: " + passed + ", failed: " + failed.length);
process.exit(failed.length ? 1 : 0);
