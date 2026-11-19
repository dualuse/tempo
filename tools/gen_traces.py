#!/usr/bin/env python3
"""Synthetic Tempo traces for viewers and tests (all names are invented).

Two shapes, both produced by a small discrete-event simulation of thread pools:

  find      GET /search on a request thread whose span has the phases
            decode, scale, describe (per-crop "infer" on the cpu pool),
            index (per-collection "query" on the io pool) and rerank
            (per-candidate "load" on the io pool, then per-image "match" on
            the cpu pool), early exit once enough confident matches arrived
            (remaining queued work is cancelled; running work straggles on
            and shows up as open "o"; with --leaky-cancel queued matches are
            not cancelled and show up as never-started "n"), then encode.
            Background jobs from other requests occupy the cpu pool according
            to --saturation, so queue wait grows when the pool is overloaded.
            Detail spans beyond --budget fold into aggregates.

  pipeline  PUT /video with a 4-stage frame pipeline (read, decode, describe,
            store) across io and cpu pools, with per-stage aggregates, in the
            phase pipeline of the request's span.

Phases are records with flag p (FORMAT.md 6.7): sections of the request's span. The
handoffs and blocking waits are children of the request's span, as a recorder writes
them, whatever phase they fall in.

Examples:
  gen_traces.py --seed 3 --candidates 48 --saturation 1.2           # header
  gen_traces.py --format json --mode pipeline --frames 200
  gen_traces.py --seed 3 --link                                      # Tempo-Link URL
  gen_traces.py --mode pipeline --link http://localhost:8000/        # self-hosted viewer
  gen_traces.py --samples samples                                    # rebuild samples/

Output is deterministic for a given set of arguments. Python 3.8+, stdlib only;
uses tempo_ref.py (next to this file) to encode.
"""

import argparse
import heapq
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tempo_ref  # noqa: E402

WALL_BASE = 1794560400000  # 2026-11-13T09:40:00Z, epoch ms


# ---------------------------------------------------------------- simulation

class Job(object):
    __slots__ = ("pool", "submit", "run", "cpu", "name", "attrs", "result", "ours", "on_done",
                 "start", "end", "thread", "cancel_at", "failed", "queued", "active")

    def __init__(self, pool, submit, run, cpu=None, name=None, attrs=None, ours=True, on_done=None):
        self.pool, self.submit, self.run, self.cpu = pool, submit, run, cpu
        self.name, self.attrs, self.ours, self.on_done = name, dict(attrs or {}), ours, on_done
        self.start = self.end = self.thread = self.cancel_at = None
        self.failed = False
        self.result = {}
        self.queued = self.active = 0


class Pool(object):
    def __init__(self, prefix, size):
        self.threads = ["%s-%d" % (prefix, i + 1) for i in range(size)]
        self.free = list(range(size))
        self.queue = []
        self.qhead = 0

    def waiting(self):
        return sum(1 for j in self.queue[self.qhead:] if j.cancel_at is None)


class Sim(object):
    def __init__(self):
        self.heap = []
        self.seq = 0
        self.now = 0

    def at(self, t, kind, job):
        self.seq += 1
        heapq.heappush(self.heap, (t, self.seq, kind, job))

    def submit(self, job):
        self.at(job.submit, "submit", job)
        return job

    def cancel(self, job, t):
        if job.start is None and job.cancel_at is None:
            job.cancel_at = t

    def _dispatch(self, pool, t):
        while pool.free and pool.qhead < len(pool.queue):
            job = pool.queue[pool.qhead]
            pool.qhead += 1
            if job.cancel_at is not None:
                continue
            pool.free.sort()
            job.thread = pool.free.pop(0)
            job.start = t
            self.at(t + job.run, "done", job)

    def step(self):
        t, _, kind, job = heapq.heappop(self.heap)
        self.now = t
        pool = job.pool
        if kind == "submit":
            job.queued = pool.waiting()
            job.active = len(pool.threads) - len(pool.free)
            pool.queue.append(job)
        else:
            job.end = t
            pool.free.append(job.thread)
            if job.on_done is not None:
                job.on_done(job, t)
        self._dispatch(pool, t)

    def run_until(self, done, limit=10 ** 9):
        while self.heap and not done() and self.heap[0][0] <= limit:
            self.step()
        return self.now

    def run_to(self, t):
        while self.heap and self.heap[0][0] <= t:
            self.step()
        self.now = t


def background(sim, rng, pool, saturation, mean_us, t0, t1):
    """Jobs of other requests: utilisation `saturation` of the pool between t0 and t1."""
    if saturation <= 0:
        return
    rate = saturation * len(pool.threads) / float(mean_us)
    t = t0
    while True:
        t += -math.log(1.0 - rng.random()) / rate
        if t >= t1:
            break
        run = max(50, int(mean_us * rng.lognormvariate(0, 0.5) / math.exp(0.125)))
        sim.submit(Job(pool, int(t), run, ours=False))


# ---------------------------------------------------------------- trace tree

class Span(object):
    def __init__(self, name, start, dur, thread, **kw):
        self.name, self.start, self.dur, self.thread = name, start, dur, thread
        self.wait = kw.get("wait")
        self.cpu = kw.get("cpu")
        self.flags = kw.get("flags", "")
        self.attrs = kw.get("attrs") or {}
        self.agg = kw.get("agg")
        self.children = []

    def add(self, child):
        self.children.append(child)
        return child


def handoff_span(job, render, tid):
    """The span a job leaves in the trace at render time, or None if it is not in it."""
    if job.submit > render:
        return None
    attrs = dict(job.attrs)
    attrs["queued"] = job.queued
    attrs["active"] = job.active
    if job.cancel_at is not None and job.cancel_at <= render:
        return Span(job.name, job.submit, 0, None, wait=job.cancel_at - job.submit, flags="c", attrs=attrs)
    if job.start is None or job.start > render:
        return Span(job.name, job.submit, 0, None, wait=render - job.submit, flags="n", attrs=attrs)
    thread = tid(job)
    if job.end is None or job.end > render:
        run = render - job.start
        cpu = None if job.cpu is None else int(job.cpu * run / float(job.run))
        return Span(job.name, job.submit, run, thread, wait=job.start - job.submit, cpu=cpu,
                    flags="o", attrs=attrs)
    attrs.update(job.result)
    return Span(job.name, job.submit, job.run, thread, wait=job.start - job.submit, cpu=job.cpu,
                flags="f" if job.failed else "", attrs=attrs)


def fold(parent, spans, budget):
    """Attach spans to parent: the first `budget` ones individually, the rest folded
    into one aggregate per (name, flag). Failed and open spans always stay individual;
    cancelled and never-started ones fold into aggregates flagged c or n.
    Returns (budget left, number folded)."""
    groups = {}
    order = []
    for s in sorted(spans, key=lambda s: s.start):
        if "f" in s.flags or "o" in s.flags:
            parent.add(s)
        elif budget > 0:
            budget -= 1
            parent.add(s)
        else:
            key = (s.name, s.flags)
            if key not in groups:
                groups[key] = []
                order.append(key)
            groups[key].append(s)
    folded = 0
    for key in order:
        name, flags = key
        g = groups[key]
        folded += len(g)
        first = min(s.start for s in g)
        last_end = max(s.start + (s.wait or 0) + s.dur for s in g)
        slow = max(g, key=lambda s: (s.dur, -s.start))
        threads = set(s.thread for s in g)
        agg = {"count": len(g), "min": min(s.dur for s in g), "max": slow.dur,
               "extent": last_end - first, "maxAt": slow.start - first}
        if any(s.wait is not None for s in g):
            agg["waitSum"] = sum(s.wait or 0 for s in g)
            agg["waitMax"] = max(s.wait or 0 for s in g)
        cpu = sum(s.cpu for s in g if s.cpu is not None) if any(s.cpu is not None for s in g) else None
        thread = threads.pop() if len(threads) == 1 else -1
        parent.add(Span(name, first, sum(s.dur for s in g), thread, cpu=cpu, agg=agg, flags=flags))
    return budget, folded


def to_doc(root, threads, wall, folded=0, dropped=0, live=()):
    spans = []

    def walk(s, parent_id, parent_thread):
        thread = parent_thread if s.thread is None else s.thread
        d = {"id": len(spans), "parent": parent_id, "name": s.name, "start": s.start,
             "dur": s.dur, "thread": thread}
        if s.wait is not None and s.agg is None:
            d["wait"] = s.wait
        if s.cpu is not None:
            d["cpu"] = s.cpu
        if s.flags:
            d["flags"] = "".join(f for f in tempo_ref.FLAG_ORDER if f in s.flags)
        if s.attrs:
            d["attrs"] = dict(s.attrs)
        if s.agg is not None:
            d["agg"] = dict(s.agg)
        spans.append(d)
        me = d["id"]
        for c in sorted(s.children, key=lambda c: c.start):
            walk(c, me, thread)

    walk(root, -1, 0)
    return {"v": 1, "unit": "us", "wall": wall, "folded": folded, "dropped": dropped,
            "threads": list(threads), "live": sorted(live), "truncated": False, "spans": spans}


class Threads(object):
    """Thread table: request thread first, then pool threads in pool order, as used."""

    def __init__(self, request_thread, pools):
        self.request = request_thread
        self.pools = pools
        self.used = set()

    def mark(self, job):
        self.used.add((job.pool, job.thread))

    def table(self):
        names = [self.request]
        index = {}
        for p in self.pools:
            for i, t in enumerate(p.threads):
                if (p, i) in self.used:
                    index[(p, i)] = len(names)
                    names.append(t)
        return names, index


def io_pool_size(pool):
    return len(pool.threads)


def lognorm(rng, median, sigma):
    return median * rng.lognormvariate(0, sigma)


# ---------------------------------------------------------------- find

def find_trace(seed=1, candidates=40, cpu=4, io=8, saturation=0.5, budget=48, crops=None,
               collections=None, shared_pool=False, need=2, window=None, leaky_cancel=False):
    rng = random.Random(seed)
    sim = Sim()
    cpu_pool = Pool("work" if shared_pool else "cpu", cpu + (io if shared_pool else 0))
    io_pool = cpu_pool if shared_pool else Pool("io", io)
    pools = [cpu_pool] if shared_pool else [cpu_pool, io_pool]
    request_thread = "http-%d" % (1 + int(rng.random() * 16))
    background(sim, rng, cpu_pool, saturation, 8000, -400000, 3000000)
    if not shared_pool:
        background(sim, rng, io_pool, saturation * 0.35, 3000, -400000, 3000000)
    crops = crops or 1 + int(rng.random() * 4)
    collections = collections or 2 + int(rng.random() * 4)
    jobs = []        # (parent span key, job)
    phases = []      # (name, start, end, extra) on the request thread
    waits = []       # (phase name, start, end) blocking waits on the request thread

    t = int(rng.random() * 300) + 40                        # routing before the first phase
    d = int(lognorm(rng, 6000, 0.4))
    phases.append(("decode", t, t + d, {"attrs": {"bytes": int(lognorm(rng, 180000, 0.6))}}))
    t += d + 15
    d = int(lognorm(rng, 1500, 0.3))
    phases.append(("scale", t, t + d, {"attrs": {"px": 512 if rng.random() < 0.7 else 768}}))
    t += d + 15

    # describe: per-crop inference on the cpu pool
    start = t
    batch = []
    for c in range(crops):
        run = int(lognorm(rng, 14000, 0.25))
        j = sim.submit(Job(cpu_pool, t + 5 + 3 * c, run, cpu=int(run * (0.95 + 0.04 * rng.random())),
                           name="infer", attrs={"crop": c + 1}))
        batch.append(j)
        jobs.append(("describe", j))
    sub_end = t + 5 + 3 * crops
    end = sim.run_until(lambda: all(j.end is not None for j in batch))
    end = max(end, sub_end)
    waits.append(("describe", sub_end + 2, end))
    fp = int(lognorm(rng, 700, 0.3))
    waits.append(("describe:fingerprint", end + 10, end + 10 + fp))
    t = end + 20 + fp
    phases.append(("describe", start, t, {"attrs": {"crops": crops}}))
    t += 15

    # index: per-collection queries on the io pool
    start = t
    batch = []
    for q in range(collections):
        run = int(lognorm(rng, 6000, 0.5))
        j = sim.submit(Job(io_pool, t + 4 + 2 * q, run, cpu=int(run * (0.04 + 0.1 * rng.random())),
                           name="query", attrs={"collection": q + 1, "hits": int(lognorm(rng, 30, 0.5))}))
        batch.append(j)
        jobs.append(("index", j))
    sub_end = t + 4 + 2 * collections
    end = max(sim.run_until(lambda: all(j.end is not None for j in batch)), sub_end)
    waits.append(("index", sub_end + 2, end))
    t = end + int(lognorm(rng, 400, 0.3))
    phases.append(("index", start, t, {"attrs": {"k": candidates}}))
    t += 15

    # rerank: load on io, then match on cpu; early exit after `need` confident matches
    start = t
    state = {"confident": 0, "exit": None, "next": 0, "open": 0}
    loads = []
    matches = []
    window = min(candidates, window or io_pool_size(io_pool) + 2)

    def match_done(job, now):
        state["open"] -= 1
        if job.result["score"] >= 0.8:
            state["confident"] += 1
            if state["confident"] >= need and state["exit"] is None:
                state["exit"] = now + 30

    def submit_load(now):
        c = state["next"]
        state["next"] += 1
        state["open"] += 1
        run = int(lognorm(rng, 2200, 0.6))
        j = Job(io_pool, now, run, cpu=int(run * (0.03 + 0.08 * rng.random())), name="load",
                attrs={"bytes": int(lognorm(rng, 60000, 0.5))}, on_done=load_done)
        j.failed = rng.random() < 0.03
        loads.append(sim.submit(j))
        jobs.append(("rerank", j))

    def load_done(job, now):
        state["open"] -= 1
        if state["exit"] is None and state["next"] < candidates:
            submit_load(now + 6)        # the completion callback keeps the window full
        if job.failed:
            return
        run = int(lognorm(rng, 4500, 0.35))
        p = 0.12 if len(matches) < 3 else 0.05
        score = round(0.8 + 0.19 * rng.random(), 2) if rng.random() < p else round(0.15 + 0.6 * rng.random(), 2)
        m = Job(cpu_pool, now + 8, run, cpu=int(run * (0.96 + 0.03 * rng.random())), name="match",
                on_done=match_done)
        m.result = {"score": score}
        state["open"] += 1
        matches.append(sim.submit(m))
        jobs.append(("rerank", m))

    for c in range(window):
        submit_load(t + 5 + 2 * c)
    sub_end = t + 5 + 2 * window
    sim.run_until(lambda: state["exit"] is not None or (state["next"] >= candidates and state["open"] == 0))
    t_exit = state["exit"] if state["exit"] is not None else sim.now + 30
    sim.run_to(t_exit)
    cancelled = 0
    for j in loads + ([] if leaky_cancel else matches):
        if j.start is None and j.cancel_at is None:
            sim.cancel(j, t_exit)
            cancelled += 1
    waits.append(("rerank", sub_end + 2, t_exit))
    t = t_exit + int(lognorm(rng, 2500, 0.3))    # merge and sort the top hits
    phases.append(("rerank", start, t, {"attrs": {"candidates": candidates, "cancelled": cancelled}}))
    t += 15
    d = int(lognorm(rng, 1200, 0.3))
    phases.append(("encode", t, t + d, {}))
    render = t + d + 30

    sim.run_to(render)
    return build_find(rng, request_thread, pools, jobs, phases, waits, render, budget, seed)


def build_find(rng, request_thread, pools, jobs, phases, waits, render, budget, seed):
    th = Threads(request_thread, pools)
    for _, j in jobs:
        if j.start is not None and j.start <= render:
            th.mark(j)
    names, index = th.table()

    def tid(job):
        return index[(job.pool, job.thread)]

    busy = sum(e - s for _, s, e, _ in phases) - sum(e - s for p, s, e in waits if ":" not in p)
    root = Span("GET /search", 0, render, 0, cpu=int(busy * 0.9))
    for name, s, e, extra in phases:
        root.add(Span(name, s, e - s, None, attrs=extra.get("attrs"), flags="p"))
    for p, s, e in waits:
        if ":" in p:
            root.add(Span(p.split(":")[1], s, e - s, None))
        elif e > s:
            root.add(Span("await", s, e - s, None, flags="w"))
    live = set()
    spans = []
    for _, j in jobs:
        s = handoff_span(j, render, tid)
        if s is None:
            continue
        if "o" in s.flags:
            live.add(s.thread)
        spans.append(s)
    _, folded = fold(root, spans, budget)
    wall = WALL_BASE + seed * 7919
    return to_doc(root, names, wall, folded=folded, live=live)


# ---------------------------------------------------------------- pipeline

STAGES = [("read", "io", 2500, 0.08), ("decode", "cpu", 5200, 0.97),
          ("describe", "cpu", 9000, 0.97), ("store", "io", 1800, 0.1)]


def pipeline_trace(seed=1, frames=120, cpu=4, io=4, saturation=0.3, budget=32, inflight=8):
    rng = random.Random(seed)
    sim = Sim()
    cpu_pool, io_pool = Pool("cpu", cpu), Pool("io", io)
    pools = {"cpu": cpu_pool, "io": io_pool}
    request_thread = "http-%d" % (1 + int(rng.random() * 16))
    background(sim, rng, cpu_pool, saturation, 8000, -400000, 60000000)
    phases = []
    t = 200
    d = int(lognorm(rng, 40000, 0.3))
    phases.append(("upload", t, t + d, {"attrs": {"bytes": int(lognorm(rng, 24000000, 0.3))}}))
    t += d + 20
    d = int(lognorm(rng, 3000, 0.3))
    phases.append(("probe", t, t + d, {"attrs": {"frames": frames}}))
    t += d + 20
    pstart = t
    jobs = []
    stored = {"n": 0}
    nxt = {"f": 0}

    def submit_stage(frame, stage, now):
        name, pool, median, cpu_share = STAGES[stage]
        run = int(lognorm(rng, median, 0.3))

        def done(job, when):
            if stage + 1 < len(STAGES):
                submit_stage(frame, stage + 1, when + 5)
            else:
                stored["n"] += 1
                if nxt["f"] < frames:
                    submit_stage(nxt["f"], 0, when + 5)
                    nxt["f"] += 1

        j = sim.submit(Job(pools[pool], now, run, cpu=int(run * cpu_share), name=name,
                           attrs={"frame": frame} if stage == 0 else None, on_done=done))
        jobs.append(j)

    for f in range(min(inflight, frames)):
        submit_stage(f, 0, t + 5 + 3 * f)
        nxt["f"] += 1
    end = sim.run_until(lambda: stored["n"] >= frames)
    t = end + 40
    phases.append(("pipeline", pstart, t, {"attrs": {"frames": frames}}))
    t += 20
    d = int(lognorm(rng, 6000, 0.3))
    phases.append(("commit", t, t + d, {}))
    render = t + d + 30

    th = Threads(request_thread, [cpu_pool, io_pool])
    for j in jobs:
        th.mark(j)
    names, index = th.table()
    root = Span("PUT /video", 0, render, 0)
    for name, s, e, extra in phases:
        root.add(Span(name, s, e - s, None, attrs=extra.get("attrs"), flags="p"))
    root.add(Span("await", pstart + 5 + 3 * min(inflight, frames), end - pstart - 3 * min(inflight, frames),
                  None, flags="w"))
    spans = [handoff_span(j, render, lambda job: index[(job.pool, job.thread)]) for j in jobs]
    _, folded = fold(root, spans, budget)
    return to_doc(root, names, WALL_BASE + seed * 7919, folded=folded)


# ---------------------------------------------------------------- output

def fit(make, max_bytes, **kw):
    """Lower the span budget until the header fits (ladder step 1: fold more)."""
    budget = kw.pop("budget")
    while True:
        doc = make(budget=budget, **kw)
        header = tempo_ref.encode(doc)
        if max_bytes <= 0 or len(header) <= max_bytes or budget == 0:
            return doc, header
        budget = max(0, budget - 4)


def write_pair(base, doc, header):
    d = os.path.dirname(base)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(base + ".header", "w", encoding="utf-8") as fh:
        fh.write(header + "\n")
    with open(base + ".json", "w", encoding="utf-8") as fh:
        fh.write(tempo_ref.dump_json(doc))


SAMPLES = [
    ("small", find_trace, dict(seed=7, candidates=6, cpu=4, io=4, saturation=0.2, budget=64, crops=1, collections=2)),
    ("find-healthy", find_trace, dict(seed=11, candidates=48, cpu=8, io=8, saturation=0.3, budget=48)),
    ("find-saturated", find_trace, dict(seed=11, candidates=48, cpu=4, io=8, saturation=1.05, budget=48, leaky_cancel=True)),
    ("pipeline", pipeline_trace, dict(seed=5, frames=120, cpu=4, io=4, saturation=0.4, budget=24)),
    ("diff/before", find_trace, dict(seed=21, candidates=48, cpu=6, io=6, saturation=0.9, budget=40, shared_pool=True)),
    ("diff/after", find_trace, dict(seed=21, candidates=48, cpu=6, io=6, saturation=0.9, budget=40)),
] + [("batch/find-%02d" % (i + 1), find_trace,
      dict(seed=100 + i, candidates=40, cpu=4, io=8, saturation=0.8, budget=40)) for i in range(30)]


def write_samples(out, max_bytes):
    rows = []
    for name, make, kw in SAMPLES:
        doc, header = fit(make, max_bytes, **dict(kw))
        write_pair(os.path.join(out, name), doc, header)
        rows.append((name, len(header), len(doc["spans"]), doc["folded"]))
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("--mode", choices=["find", "pipeline"], default="find")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--candidates", type=int, default=40, help="find: rerank candidates")
    ap.add_argument("--frames", type=int, default=120, help="pipeline: frames")
    ap.add_argument("--cpu", type=int, default=4, help="cpu pool size")
    ap.add_argument("--io", type=int, default=8, help="io pool size")
    ap.add_argument("--saturation", type=float, default=0.5,
                    help="background utilisation of the cpu pool by other requests (>1 overloads it)")
    ap.add_argument("--budget", type=int, default=48, help="individual detail spans before folding")
    ap.add_argument("--shared-pool", action="store_true", help="find: run io work on the cpu pool too")
    ap.add_argument("--leaky-cancel", action="store_true",
                    help="find: early exit cancels queued loads only; queued matches run on as stragglers")
    ap.add_argument("--max-bytes", type=int, default=3000, help="fold more until the header fits (0: no limit)")
    ap.add_argument("--format", choices=["header", "json"], default="header")
    ap.add_argument("--link", nargs="?", const="", metavar="BASE",
                    help="print the Tempo-Link URL (FORMAT.md section 9) instead of the header value; "
                         "BASE is the viewer URL (default $TEMPO_LINK_BASE, else in a checkout of Tempo "
                         "its own GitHub Pages site, else %s)" % tempo_ref.DEFAULT_BASE)
    ap.add_argument("-o", "--output", help="write to this file instead of stdout")
    ap.add_argument("--samples", metavar="DIR", help="regenerate the bundled sample set into DIR")
    a = ap.parse_args(argv)
    if a.link is not None and a.format == "json":
        ap.error("--link writes a URL; it cannot be combined with --format json")
    if a.link is not None and a.samples:
        ap.error("--link does not apply to --samples")

    if a.samples:
        for name, size, spans, folded in write_samples(a.samples, a.max_bytes):
            print("%-18s %5d bytes %4d spans %4d folded" % (name, size, spans, folded))
        return 0
    if a.mode == "find":
        doc, header = fit(find_trace, a.max_bytes, seed=a.seed, candidates=a.candidates, cpu=a.cpu, io=a.io,
                          saturation=a.saturation, budget=a.budget, shared_pool=a.shared_pool,
                          leaky_cancel=a.leaky_cancel)
    else:
        doc, header = fit(pipeline_trace, a.max_bytes, seed=a.seed, frames=a.frames, cpu=a.cpu, io=a.io,
                          saturation=a.saturation, budget=a.budget)
    if a.link is not None:
        try:
            text = tempo_ref.encode_link(header, base=a.link or tempo_ref.default_base()) + "\n"
        except ValueError as e:
            ap.error(str(e))
    else:
        text = header + "\n" if a.format == "header" else tempo_ref.dump_json(doc)
    if a.output:
        with open(a.output, "w", encoding="utf-8") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
