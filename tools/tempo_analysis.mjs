// Tempo viewer analysis (self-time, critical path, diff, stats, converters) as an ES module.
// GENERATED from site/index.html by `node tests/check_spa_parser_sync.mjs --write`. Do not edit.

import TempoParse from "./tempo_parse.mjs";

// ==== BEGIN TEMPO ANALYSIS ====
// Viewer analysis: span model, self-time, critical path, findings, pools, per-path aggregates,
// diff, multi-trace statistics and converters. Copied verbatim into tools/tempo_analysis.mjs.
const TempoAnalysis = (function (P) {
  "use strict";

  const CPU_POOL_RE = /cpu|compute|calc|worker|forkjoin|fj/i;
  const LANE_MIXED = -1, LANE_LIMBO = -2;

  function has(s, f) { return !!s.flags && s.flags.indexOf(f) >= 0; }

  // Pool of a thread: the name without a trailing -<digits>; Thread-N stays on its own.
  function poolOf(name) {
    if (/^Thread-\d+$/.test(name)) return name;
    const m = /^(.+?)-\d+$/.exec(name);
    return m ? m[1] : name;
  }

  function isCpuPool(pool) { return CPU_POOL_RE.test(pool); }

  // ------------------------------------------------------------ model

  function model(doc) {
    const spans = doc.spans.map(function (s) {
      const isAgg = !!s.agg;
      const isHandoff = !isAgg && s.wait !== undefined;
      // never started, or cancelled before it started (a handoff, or an aggregate of them, with dur 0)
      const notRun = has(s, "n") || (has(s, "c") && s.dur === 0 && (isHandoff || (isAgg && s.agg.waitSum !== undefined)));
      const runStart = isHandoff ? s.start + s.wait : s.start;
      const runEnd = isAgg ? s.start + s.agg.extent : runStart + s.dur;
      return {
        id: s.id, parentId: s.parent, name: s.name, start: s.start, dur: s.dur, thread: s.thread,
        wait: isAgg ? (s.agg.waitSum !== undefined ? s.agg.waitSum : undefined) : s.wait,
        waitMax: isAgg ? s.agg.waitMax : s.wait,
        cpu: s.cpu, flags: s.flags || "", attrs: s.attrs || null, agg: s.agg || null, src: s,
        isAgg: isAgg, isHandoff: isHandoff, notRun: notRun,
        runStart: runStart, runEnd: runEnd, end: runEnd,
        offCpu: s.cpu !== undefined ? Math.max(0, s.dur - s.cpu) : undefined,
        children: [], parent: null, depth: 0, key: "", label: "",
        lane: notRun ? LANE_LIMBO : s.thread, row: 0, self: 0, pool: null
      };
    });
    const root = spans[0] || null;
    for (const s of spans) {
      if (s.parentId >= 0) {
        s.parent = spans[s.parentId];
        s.parent.children.push(s);
        s.depth = s.parent.depth + 1;
        s.key = s.parent.parentId < 0 ? s.name : s.parent.key + "/" + s.name;
      }
      s.label = s.parentId < 0 ? s.name : s.key;
      s.pool = s.thread >= 0 ? poolOf(doc.threads[s.thread]) : null;
    }
    // never-started work and mixed-thread aggregates: infer the pool from same-name siblings
    for (const s of spans) {
      if (s.parent && (s.notRun || s.thread < 0)) {
        const votes = {};
        for (const c of s.parent.children) if (c !== s && c.name === s.name && !c.notRun && c.thread >= 0 && c.thread !== s.parent.thread) votes[c.pool] = (votes[c.pool] || 0) + 1;
        let best = null;
        for (const k of Object.keys(votes)) if (best === null || votes[k] > votes[best]) best = k;
        s.inferredPool = best;
        if (s.notRun || s.thread < 0) s.pool = best;
      }
    }
    for (const s of spans) s.self = selfTime(s);
    packRows(spans);
    const end = spans.reduce(function (e, s) { return Math.max(e, s.end); }, 0);
    return { doc: doc, spans: spans, root: root, threads: doc.threads, end: end, live: new Set(doc.live || []) };
  }

  // Self-time: run time minus same-thread children (overlap with this span's run) and zero for
  // blocking waits (flag w). Cross-thread children are never subtracted.
  function selfTime(s) {
    if (has(s, "w") || s.notRun) return 0;
    if (s.isAgg) return s.dur;
    let sub = 0;
    for (const c of s.children) {
      if (c.notRun || c.thread !== s.thread || c.thread < 0) continue;
      const lo = Math.max(c.runStart, s.runStart), hi = Math.min(c.runEnd, s.runEnd);
      if (hi > lo) sub += Math.min(c.dur, hi - lo);
    }
    return Math.max(0, s.dur - sub);
  }

  // Rows inside a lane: a child sits below its same-lane parent; overlapping siblings move down.
  function packRows(spans) {
    const rows = new Map();   // lane -> array of rows, each an array of [lo, hi]
    for (const s of spans) {
      if (!rows.has(s.lane)) rows.set(s.lane, []);
      const laneRows = rows.get(s.lane);
      let r = s.parent && s.parent.lane === s.lane ? s.parent.row + 1 : 0;
      const lo = s.notRun ? s.start : s.runStart, hi = Math.max(s.end, lo + 1);
      for (;; r++) {
        if (!laneRows[r]) laneRows[r] = [];
        if (!laneRows[r].some(function (iv) { return iv[0] < hi && lo < iv[1]; })) break;
      }
      laneRows[r].push([lo, hi]);
      s.row = r;
    }
  }

  // ------------------------------------------------------------ critical path
  //
  // Walks back from the root's end. At each step the child of the current span that ends last
  // (not after the cursor) is on the path; the time between children is the span's own work.
  // A blocking wait (flag w) is explained by the latest handoff sibling that finished inside it,
  // so the path crosses threads: the handoff's queue wait and run join the path, and the gap
  // between the handoff's end and the wake-up stays with the wait span.

  function criticalPath(m) {
    const segs = [];
    if (!m.root) return { segments: [], total: 0, byKind: { run: 0, wait: 0, block: 0, agg: 0 } };
    const push = function (s, kind, from, to) { if (to > from) segs.push({ id: s.id, kind: kind, from: from, to: to }); };

    function walkSpan(s, limit) {
      if (s.isAgg) { push(s, "agg", s.start, Math.min(s.end, limit)); return; }
      explain(s, s.runStart, Math.min(s.runEnd, limit));
      if (s.isHandoff) push(s, "wait", s.start, s.runStart);
    }

    function explain(s, lo, hi) {
      const own = has(s, "w") ? "block" : "run";
      const pool = s.children.filter(function (c) { return !c.notRun && c.end > c.start; });
      let cursor = hi;
      for (;;) {
        let best = null;
        for (const c of pool) {
          if (c.end > cursor || c.end <= lo) continue;
          if (!best || c.end > best.end || (c.end === best.end && has(best, "w") && !has(c, "w"))) best = c;
        }
        if (!best) { push(s, own, lo, cursor); return; }
        push(s, own, best.end, cursor);
        pool.splice(pool.indexOf(best), 1);
        let next = best;
        if (has(best, "w")) {
          let h = null;
          for (const c of pool) {
            if (has(c, "w") || c.thread === best.thread && !c.isHandoff && !c.isAgg) continue;
            if (c.end > best.end || c.end <= best.start) continue;
            if (!h || c.end > h.end) h = c;
          }
          if (h) {
            push(best, "block", h.end, best.end);
            pool.splice(pool.indexOf(h), 1);
            next = h;
          }
        }
        walkSpan(next, next === best ? best.end : next.end);
        cursor = Math.max(lo, next.start);
        if (cursor <= lo) return;
      }
    }

    walkSpan(m.root, m.root.end);
    segs.sort(function (a, b) { return a.from - b.from || a.to - b.to; });
    // merge adjacent pieces of the same span and kind
    const merged = [];
    for (const g of segs) {
      const p = merged[merged.length - 1];
      if (p && p.id === g.id && p.kind === g.kind && p.to === g.from) p.to = g.to;
      else merged.push({ id: g.id, kind: g.kind, from: g.from, to: g.to });
    }
    const byKind = { run: 0, wait: 0, block: 0, agg: 0 };
    let total = 0;
    for (const g of merged) { byKind[g.kind] += g.to - g.from; total += g.to - g.from; }
    return { segments: merged, total: total, byKind: byKind };
  }

  // ------------------------------------------------------------ per-path aggregates

  function byPath(m) {
    const rows = new Map();
    for (const s of m.spans) {
      let r = rows.get(s.key);
      if (!r) {
        r = { key: s.key, label: s.label, name: s.name, depth: s.depth, records: 0, count: 0, sum: 0, self: 0, min: Infinity, max: 0,
              waitSum: 0, waitMax: 0, handoffs: 0, cpuSum: 0, cpuDur: 0, offCpu: 0, notStarted: 0, failed: 0, firstId: s.id };
        rows.set(s.key, r);
      }
      const n = s.isAgg ? s.agg.count : 1;
      r.records += 1;
      r.count += n;
      r.sum += s.dur;
      r.self += s.self;
      if (s.notRun) r.notStarted += n;
      else {
        r.min = Math.min(r.min, s.isAgg ? s.agg.min : s.dur);
        r.max = Math.max(r.max, s.isAgg ? s.agg.max : s.dur);
      }
      if (s.wait !== undefined) { r.waitSum += s.wait; r.waitMax = Math.max(r.waitMax, s.waitMax || 0); r.handoffs += n; }
      if (s.cpu !== undefined) { r.cpuSum += s.cpu; r.cpuDur += s.dur; r.offCpu += s.offCpu; }
      if (has(s, "f")) r.failed += n;
    }
    for (const r of rows.values()) if (r.min === Infinity) r.min = 0;
    return Array.from(rows.values());
  }

  // ------------------------------------------------------------ diff of two traces

  function diff(ma, mb) {
    const a = new Map(byPath(ma).map(function (r) { return [r.key, r]; }));
    const b = new Map(byPath(mb).map(function (r) { return [r.key, r]; }));
    const keys = [];
    for (const k of a.keys()) keys.push(k);
    for (const k of b.keys()) if (!a.has(k)) keys.push(k);
    const v = function (r, f) { return r ? r[f] : 0; };
    const rows = keys.map(function (k) {
      const ra = a.get(k) || null, rb = b.get(k) || null;
      return {
        key: k, label: k === "" ? (ra || rb).label : k, a: ra, b: rb,
        dCount: v(rb, "count") - v(ra, "count"), dSum: v(rb, "sum") - v(ra, "sum"), dSelf: v(rb, "self") - v(ra, "self"),
        dWait: v(rb, "waitSum") - v(ra, "waitSum"), dCpu: v(rb, "cpuSum") - v(ra, "cpuSum"), dOffCpu: v(rb, "offCpu") - v(ra, "offCpu")
      };
    });
    rows.sort(function (x, y) { return Math.abs(y.dSum) - Math.abs(x.dSum) || Math.abs(y.dWait) - Math.abs(x.dWait) || (x.key < y.key ? -1 : 1); });
    return rows;
  }

  // ------------------------------------------------------------ statistics over N traces

  // Nearest-rank percentile of an ascending array.
  function percentile(sorted, p) {
    if (!sorted.length) return NaN;
    const rank = Math.ceil(p / 100 * sorted.length);
    return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
  }

  function summary(values) {
    const s = values.slice().sort(function (x, y) { return x - y; });
    return { n: s.length, p50: percentile(s, 50), p95: percentile(s, 95), max: s.length ? s[s.length - 1] : NaN, min: s.length ? s[0] : NaN };
  }

  function stats(models) {
    const acc = new Map();
    models.forEach(function (m) {
      for (const r of byPath(m)) {
        let a = acc.get(r.key);
        if (!a) { a = { key: r.key, label: r.key === "" ? "(root)" : r.key, names: new Set(), sum: [], self: [], wait: [], waitMax: [], count: [], cpu: [] }; acc.set(r.key, a); }
        if (r.key === "") a.names.add(r.label);
        a.sum.push(r.sum); a.self.push(r.self); a.wait.push(r.waitSum); a.waitMax.push(r.waitMax); a.count.push(r.count); a.cpu.push(r.cpuSum);
      }
    });
    return Array.from(acc.values()).map(function (a) {
      return {
        key: a.key, label: a.key === "" ? Array.from(a.names).join(" | ") : a.key, n: a.sum.length, traces: models.length,
        sum: summary(a.sum), self: summary(a.self), wait: summary(a.wait), waitMax: summary(a.waitMax), count: summary(a.count), cpu: summary(a.cpu)
      };
    });
  }

  // ------------------------------------------------------------ findings (plan §5)

  function findings(m, opts) {
    opts = opts || {};
    const minWait = opts.minWait !== undefined ? opts.minWait : 1000;
    const minOffCpu = opts.minOffCpu !== undefined ? opts.minOffCpu : 1000;
    const out = [];
    const add = function (kind, s, title, detail, weight) { out.push({ kind: kind, id: s ? s.id : -1, title: title, detail: detail || "", weight: weight || 0 }); };
    const d = m.doc;
    if (d.truncated) add("truncated", null, "Trace was cut", "The value lost its tail in transit; the last record was discarded and later records are missing.", Infinity);
    if (d.folded || d.dropped) add("budget", null, "Budget applied", d.folded + " spans folded into aggregates, " + d.dropped + " not on the wire.", 0);
    if (d.live && d.live.length) add("live", null, "Lanes still active at render", d.live.map(function (i) { return d.threads[i]; }).join(", ") + " (records are best effort)", 0);
    for (const s of m.spans) {
      const n = s.isAgg ? s.agg.count : 1;
      const times = n > 1 ? " ×" + n : "";
      if (has(s, "f")) add("failed", s, s.label + times, "failed after " + s.dur + " µs", s.dur);
      if (has(s, "o")) add("open", s, s.label + times, "still running at render", s.dur);
      if (has(s, "n")) add("never", s, s.label + times, "never started; queued " + (s.isAgg ? s.agg.waitMax : s.wait) + " µs until render", s.wait || 0);
      if (has(s, "c") && !has(s, "n")) add("cancelled", s, s.label + times, s.notRun ? "cancelled before it started" : "cancelled while running", 0);
      if (has(s, "m")) add("misuse", s, s.label + times, "the recorder healed a misuse here", 0);
      if (s.parent && !has(s, "n") && !has(s, "o") && s.end > s.parent.end) add("straggler", s, s.label + times, "ends " + (s.end - s.parent.end) + " µs after its parent", s.end - s.parent.end);
    }
    const waits = m.spans.filter(function (s) { return (s.waitMax || 0) >= minWait && !has(s, "n"); })
      .sort(function (a, b) { return b.waitMax - a.waitMax; }).slice(0, opts.topWaits || 5);
    for (const s of waits) add("wait", s, s.label + (s.isAgg ? " ×" + s.agg.count : ""), (s.isAgg ? "worst of " + s.agg.count + " waited " : "waited ") + s.waitMax + " µs in queue" + (s.pool ? " (" + s.pool + ")" : ""), s.waitMax);
    const off = m.spans.filter(function (s) {
      if (!s.pool || !isCpuPool(s.pool) || s.notRun) return false;
      if (has(s, "w") && s.thread >= 0 && s.dur >= minOffCpu) return true;
      return s.offCpu !== undefined && s.offCpu >= minOffCpu && s.offCpu >= 0.2 * s.dur;
    }).sort(function (a, b) { return (b.offCpu !== undefined ? b.offCpu : b.dur) - (a.offCpu !== undefined ? a.offCpu : a.dur); }).slice(0, opts.topOffCpu || 5);
    for (const s of off) {
      const v = has(s, "w") && s.offCpu === undefined ? s.dur : s.offCpu;
      add("offcpu", s, s.label + (s.isAgg ? " ×" + s.agg.count : ""), (has(s, "w") ? "blocked " : "off-CPU ") + v + " µs of " + s.dur + " µs on " + s.pool, v);
    }
    const order = ["truncated", "failed", "never", "open", "straggler", "wait", "offcpu", "cancelled", "misuse", "live", "budget"];
    out.sort(function (a, b) { return order.indexOf(a.kind) - order.indexOf(b.kind) || b.weight - a.weight; });
    return out;
  }

  // ------------------------------------------------------------ pools ("this request's share")

  function pools(m) {
    const byName = new Map();
    const get = function (name) {
      if (!byName.has(name)) byName.set(name, { name: name, cpu: isCpuPool(name), threads: [], seen: new Set(), tasks: 0, notStarted: 0, runSum: 0, waitSum: 0, waitMax: 0, cpuSum: 0, offCpu: 0, events: [], gauges: [] });
      return byName.get(name);
    };
    m.threads.forEach(function (t, i) { get(poolOf(t)).threads.push(i); });
    for (const s of m.spans) {
      const task = !s.parent || s.isHandoff || s.isAgg || s.thread !== s.parent.thread;
      if (!task || !s.pool || (s.isAgg && s.parent && s.thread === s.parent.thread && s.wait === undefined)) continue;
      const p = get(s.pool);
      const n = s.isAgg ? s.agg.count : 1;
      p.tasks += n;
      if (s.thread >= 0 && !s.notRun) p.seen.add(s.thread);
      if (s.notRun) p.notStarted += n;
      p.runSum += s.dur;
      if (s.wait !== undefined) { p.waitSum += s.wait; p.waitMax = Math.max(p.waitMax, s.waitMax || 0); }
      if (s.cpu !== undefined) { p.cpuSum += s.cpu; p.offCpu += s.offCpu; }
      if (s.isAgg) {
        const ext = Math.max(1, s.agg.extent);
        if (s.dur > 0) { p.events.push([s.start, "run", s.dur / ext, true]); p.events.push([s.start + ext, "run", -s.dur / ext, true]); }
        if (s.wait) { p.events.push([s.start, "pend", s.wait / ext]); p.events.push([s.start + ext, "pend", -s.wait / ext]); }
      } else {
        if (s.isHandoff && s.runStart > s.start) { p.events.push([s.start, "pend", 1]); p.events.push([s.runStart, "pend", -1]); }
        if (!s.notRun && s.runEnd > s.runStart) { p.events.push([s.runStart, "run", 1]); p.events.push([s.runEnd, "run", -1]); }
      }
      if (s.attrs) {
        let q = null, a = null;
        for (const k of Object.keys(s.attrs)) {
          if (/^(queued|queue|qdepth|queue-?size)$/i.test(k)) q = s.attrs[k];
          else if (/^active$/i.test(k)) a = s.attrs[k];
        }
        if (q !== null || a !== null) p.gauges.push({ t: s.start, queued: q, active: a, id: s.id });
      }
    }
    return Array.from(byName.values()).filter(function (p) { return p.tasks > 0 || p.threads.length; }).map(function (p) {
      p.events.sort(function (x, y) { return x[0] - y[0]; });
      // Aggregates are spread evenly over their extent, which can claim more parallelism than the
      // pool has; the running line and its peak are capped at the pool's threads. peakRunSpans
      // counts individual spans only (exact); runEstimated says whether aggregates contributed.
      const cap = Math.max(p.threads.length, p.seen.size) || Infinity;
      const running = [[0, 0]], pending = [[0, 0]];
      let run = 0, spanRun = 0, pend = 0, peakRun = 0, peakRunSpans = 0, peakPend = 0, estimated = false;
      for (const e of p.events) {
        if (e[1] === "run") {
          run += e[2]; if (Math.abs(run) < 1e-9) run = 0;
          if (e[3]) estimated = true; else { spanRun += e[2]; peakRunSpans = Math.max(peakRunSpans, spanRun); }
          const v = Math.min(cap, run);
          running.push([e[0], v]); peakRun = Math.max(peakRun, v);
        } else { pend += e[2]; if (Math.abs(pend) < 1e-9) pend = 0; pending.push([e[0], pend]); peakPend = Math.max(peakPend, pend); }
      }
      delete p.events;
      p.running = running; p.pending = pending; p.peakRun = peakRun; p.peakPend = peakPend;
      p.peakRunSpans = peakRunSpans; p.runEstimated = estimated;
      p.distinct = p.seen.size;
      delete p.seen;
      return p;
    });
  }

  // ------------------------------------------------------------ converters

  // Chrome Trace Event JSON (Perfetto, chrome://tracing). Times in µs from the root start.
  function toChrome(m) {
    const ev = [];
    const pid = 1;
    const tidOf = function (s) { return s.lane === LANE_LIMBO ? 9001 : s.thread < 0 ? 9000 : s.thread + 1; };
    ev.push({ ph: "M", pid: pid, name: "process_name", args: { name: m.root ? m.root.name : "trace" } });
    m.threads.forEach(function (t, i) {
      ev.push({ ph: "M", pid: pid, tid: i + 1, name: "thread_name", args: { name: t } });
      ev.push({ ph: "M", pid: pid, tid: i + 1, name: "thread_sort_index", args: { sort_index: i } });
    });
    if (m.spans.some(function (s) { return s.thread < 0; })) ev.push({ ph: "M", pid: pid, tid: 9000, name: "thread_name", args: { name: "mixed threads (aggregates)" } });
    if (m.spans.some(function (s) { return s.notRun; })) ev.push({ ph: "M", pid: pid, tid: 9001, name: "thread_name", args: { name: "not started" } });
    let flow = 1;
    for (const s of m.spans) {
      const args = { path: s.label };
      if (s.src.wait !== undefined) args.wait_us = s.src.wait;
      if (s.cpu !== undefined) { args.cpu_us = s.cpu; args.off_cpu_us = s.offCpu; }
      if (s.flags) args.flags = s.flags;
      if (s.attrs) for (const k of Object.keys(s.attrs)) args["attr." + k] = s.attrs[k];
      if (s.agg) for (const k of Object.keys(s.agg)) args["agg." + k] = s.agg[k];
      const cat = s.isAgg ? "aggregate" : s.isHandoff ? "handoff" : "span";
      if (s.notRun) {
        ev.push({ ph: "X", name: s.name + " (queued)", cat: "queue", ts: s.start, dur: Math.max(0, s.end - s.start), pid: pid, tid: tidOf(s), args: args });
        continue;
      }
      ev.push({ ph: "X", name: s.isAgg ? s.name + " ×" + s.agg.count : s.name, cat: cat, ts: s.isAgg ? s.start : s.runStart, dur: s.isAgg ? s.agg.extent : s.dur, pid: pid, tid: tidOf(s), args: args });
      if (s.isHandoff && s.parent && s.thread >= 0) {
        ev.push({ ph: "s", id: flow, name: "handoff", cat: "handoff", ts: s.start, pid: pid, tid: tidOf(s.parent) });
        ev.push({ ph: "f", bp: "e", id: flow, name: "handoff", cat: "handoff", ts: s.runStart, pid: pid, tid: tidOf(s) });
        flow++;
      }
    }
    const meta = { source: "tempo-timing v1", folded: m.doc.folded, dropped: m.doc.dropped, truncated: m.doc.truncated };
    if (m.doc.wall !== undefined) meta.wall_ms = m.doc.wall;
    return { traceEvents: ev, displayTimeUnit: "ms", otherData: meta };
  }

  // Collapsed stacks (flamegraph.pl, speedscope): "root;child;leaf <self µs>", merged per stack.
  function toFolded(m) {
    const clean = function (n) { return String(n).replace(/;/g, ":").replace(/[\r\n]+/g, " "); };
    const stackOf = new Map();
    const totals = new Map();
    for (const s of m.spans) {
      const st = s.parent ? stackOf.get(s.parent.id) + ";" + clean(s.name) : clean(s.name);
      stackOf.set(s.id, st);
      if (s.self > 0) totals.set(st, (totals.get(st) || 0) + s.self);
    }
    let out = "";
    for (const [k, v] of totals) out += k + " " + Math.round(v) + "\n";
    return out;
  }

  return { poolOf, isCpuPool, has, model, selfTime, criticalPath, byPath, diff, percentile, summary, stats, findings, pools, toChrome, toFolded, LANE_MIXED, LANE_LIMBO };
})(TempoParse);
// ==== END TEMPO ANALYSIS ====

export default TempoAnalysis;
export const {
  poolOf, isCpuPool, has, model, selfTime, criticalPath, byPath, diff, percentile, summary,
  stats, findings, pools, toChrome, toFolded, LANE_MIXED, LANE_LIMBO
} = TempoAnalysis;
