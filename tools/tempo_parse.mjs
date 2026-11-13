// Tempo-Trace v1 parser as an ES module for Node and other tools.
// GENERATED from site/index.html by `node tests/check_spa_parser_sync.mjs --write`. Do not edit.

// ==== BEGIN TEMPO PARSER ====
// Tempo-Trace v1: decoder, canonical encoder and input extraction (see FORMAT.md).
// This block is copied verbatim into tools/tempo_parse.mjs; tests/check_spa_parser_sync.mjs
// fails when the two differ and `node tests/check_spa_parser_sync.mjs --write` re-copies it.
const TempoParse = (function () {
  "use strict";

  const ALPHABET_CHARS = "0-9A-Za-z./:;=@~*+^_%-";
  const BAD_CHAR_RE = /[^0-9A-Za-z.\/:;=@~*+^_%-]/;
  const PURE_RE = /^[0-9A-Za-z.\/:;=@~*+^_%-]+$/;
  const WS_RE = /[\t\n\x0b\x0c\r ]/g;
  const FLAG_ORDER = "fconwm";
  const FLAG_NAMES = { f: "failed", c: "cancelled", o: "open", n: "never started", w: "blocking wait", m: "misuse healed" };
  const MAX_DEPTH = 35;
  const RECORD_RE = /^([0-9a-z])([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)(?:@([0-9a-z]+|_))?(?:~([0-9a-z]+)(?:\.([0-9a-z]+))?)?(?:\*([0-9a-z]+))?(?:\+([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+)\.([0-9a-z]+))?(?:\^([a-z]+))?((?::[0-9a-z]+=-?[0-9]+(?:\.[0-9]+)?)*)$/;
  const ATTR_RE = /:([0-9a-z]+)=(-?[0-9]+(?:\.[0-9]+)?)/g;
  const B36_RE = /^[0-9a-z]+$/;
  const NAME_RE = /^(?:[A-Za-z0-9._-]|%[0-9A-Fa-f]{2})+$/;
  const FIELD_ORDER = ["w", "x", "l", "T", "N", "S"];
  const ERRORS = {
    E_VERSION: "Empty, or does not start with v1;",
    E_CHAR: "A character outside the alphabet",
    E_CUT: "Cut before the span list; nothing to decode",
    E_FIELD: "Unknown, malformed, duplicate or out-of-order field",
    E_MISSING: "T=, N= or S= missing from a value that is not cut",
    E_NAME: "Bad name",
    E_RECORD: "A record does not match the record expression",
    E_INDEX: "Name, thread, attribute-key or l= index out of range",
    E_ROOT: "First record not a plain depth-0 record, or a second root",
    E_DEPTH: "Depth grows by more than one",
    E_FLAG: "Unknown flag",
    E_AGG: "Aggregate rules broken",
    E_INPUT: "No Tempo trace found in the input",
    E_JSON: "JSON form is not a valid trace"
  };

  class TempoFormatError extends Error {
    constructor(code, message) {
      super(code + ": " + message);
      this.name = "TempoFormatError";
      this.code = code;
    }
  }

  function fail(code, message) { throw new TempoFormatError(code, message); }

  function stripWs(value) { return String(value).replace(WS_RE, ""); }

  function b36(text, what) {
    if (!B36_RE.test(text)) fail("E_FIELD", "bad base-36 " + (what || "number") + " " + JSON.stringify(text));
    return parseInt(text, 36);
  }

  function toB36(n) {
    if (!(n >= 0) || !Number.isInteger(n)) throw new RangeError("cannot encode " + n + " in base 36");
    return n.toString(36);
  }

  // ------------------------------------------------------------ names

  let utf8Decoder = null;
  function decodeName(text) {
    if (!text) fail("E_NAME", "empty name");
    if (!NAME_RE.test(text)) fail("E_NAME", "bad name " + JSON.stringify(text));
    const bytes = [];
    for (let i = 0; i < text.length;) {
      const ch = text[i];
      if (ch === "%") { bytes.push(parseInt(text.substr(i + 1, 2), 16)); i += 3; }
      else { bytes.push(ch === "_" ? 0x20 : ch.charCodeAt(0)); i += 1; }
    }
    if (!utf8Decoder) utf8Decoder = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });
    try {
      return utf8Decoder.decode(new Uint8Array(bytes));
    } catch (e) {
      return fail("E_NAME", "name " + JSON.stringify(text) + " is not valid UTF-8");
    }
  }

  function encodeName(name) {
    if (name === null || name === undefined || name === "") name = "?";
    const bytes = new TextEncoder().encode(String(name));
    let out = "";
    for (const b of bytes) {
      if ((b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5a) || (b >= 0x61 && b <= 0x7a) || b === 0x2e || b === 0x2d) out += String.fromCharCode(b);
      else if (b === 0x20) out += "_";
      else out += "%" + (b < 16 ? "0" : "") + b.toString(16).toUpperCase();
    }
    return out;
  }

  // ------------------------------------------------------------ numbers

  function decodeNumber(text) {
    const v = Number(text);
    return v === 0 ? 0 : v;
  }

  // Fixed point with at most 6 fraction digits, ties to even (like printf("%.6f")).
  function encodeNumber(v) {
    if (typeof v !== "number") throw new TypeError("attribute values are numbers");
    if (!isFinite(v)) return null;
    const a = Math.abs(v);
    if (Number.isInteger(v) && a < 1e21) return BigInt(v).toString();
    if (a >= 1e21) return (v < 0 ? "-" : "") + BigInt(a).toString();
    let s = a.toFixed(6);
    const exact = a.toFixed(30);
    const dot = exact.indexOf(".");
    if (exact.slice(dot + 7) === "5" + "0".repeat(23)) {
      const down = exact.slice(0, dot + 7);
      if (Number(down[down.length - 1]) % 2 === 0) s = down;
    }
    s = s.replace(/0+$/, "").replace(/\.$/, "");
    if (s === "0" || s === "") return "0";
    return (v < 0 ? "-" : "") + s;
  }

  // ------------------------------------------------------------ decoder

  function decode(value) {
    const v = stripWs(value);
    if (!v.startsWith("v1;")) fail("E_VERSION", "value does not start with 'v1;'");
    const bad = BAD_CHAR_RE.exec(v);
    if (bad) fail("E_CHAR", "character " + JSON.stringify(bad[0]) + " outside the alphabet");

    let fields = v.slice(3).split(";");
    let expected = null;
    let cut = false;
    const last = fields[fields.length - 1];
    if (last.startsWith("e=") && B36_RE.test(last.slice(2))) {
      expected = parseInt(last.slice(2), 36);
      fields = fields.slice(0, -1);
    } else {
      cut = true;
      if (last === "" || last === "e" || last === "e=") fields = fields.slice(0, -1);
      if (!fields.length || !fields[fields.length - 1].startsWith("S=")) fail("E_CUT", "value cut before the span list");
    }

    const seen = {};
    let pos = -1;
    for (const f of fields) {
      const eq = f.indexOf("=");
      if (eq < 0) fail("E_FIELD", "field " + JSON.stringify(f) + " has no '='");
      const key = f.slice(0, eq);
      const idx = FIELD_ORDER.indexOf(key);
      if (idx < 0) fail("E_FIELD", "unknown field " + JSON.stringify(key));
      if (idx <= pos) fail("E_FIELD", "field " + JSON.stringify(key) + " duplicated or out of order");
      pos = idx;
      seen[key] = f.slice(eq + 1);
    }
    for (const key of ["T", "N", "S"]) if (!(key in seen)) fail("E_MISSING", "required field " + key + "= is missing");

    const doc = { v: 1, unit: "us" };
    if ("w" in seen) doc.wall = b36(seen.w, "w=");
    let folded = 0, dropped = 0;
    if ("x" in seen) {
      const parts = seen.x.split(".");
      if (parts.length !== 2) fail("E_FIELD", "x= must be folded.dropped");
      folded = b36(parts[0], "x=");
      dropped = b36(parts[1], "x=");
    }
    doc.folded = folded;
    doc.dropped = dropped;
    const threads = seen.T.split(":").map(decodeName);
    const names = seen.N.split(":").map(decodeName);
    const live = [];
    if ("l" in seen) {
      for (const p of seen.l.split(".")) {
        const i = b36(p, "l=");
        if (i >= threads.length) fail("E_INDEX", "l= thread index " + i + " out of range");
        live.push(i);
      }
    }
    doc.threads = threads;
    doc.live = live;
    let records = seen.S.split("/");
    const truncated = cut || (expected !== null && expected !== records.length);
    if (truncated) records = records.slice(0, -1);
    doc.truncated = truncated;
    doc.spans = decodeRecords(records, names, threads);
    return doc;
  }

  function decodeRecords(records, names, threads) {
    const spans = [];
    const cur = new Array(MAX_DEPTH + 2).fill(0);
    const path = new Array(MAX_DEPTH + 1).fill(null);
    let prevDepth = -1;
    let prevAgg = false;
    records.forEach(function (rec, i) {
      const m = RECORD_RE.exec(rec);
      if (!m) fail("E_RECORD", "record " + i + " " + JSON.stringify(rec) + " is malformed");
      const depth = parseInt(m[1], 36);
      const thrS = m[5], waitS = m[6], wmaxS = m[7], cpuS = m[8], flagsS = m[14], attrsS = m[15];
      const isAgg = m[9] !== undefined;
      if (i === 0) {
        if (depth !== 0) fail("E_ROOT", "first record has depth " + depth);
        if (thrS !== undefined || waitS !== undefined || isAgg) fail("E_ROOT", "root record carries @, ~ or +");
        if (parseInt(m[3], 36) !== 0) fail("E_ROOT", "root startDelta is not 0");
      } else {
        if (depth === 0) fail("E_ROOT", "record " + i + " is a second root");
        if (depth > prevDepth + 1) fail("E_DEPTH", "record " + i + " jumps from depth " + prevDepth + " to " + depth);
        if (prevAgg && depth > prevDepth) fail("E_AGG", "record " + i + " is a child of an aggregate");
      }
      const nameIdx = parseInt(m[2], 36);
      if (nameIdx >= names.length) fail("E_INDEX", "record " + i + " name index " + nameIdx + " out of range");
      const start = cur[depth] + parseInt(m[3], 36);
      cur[depth] = start;
      cur[depth + 1] = start;
      const parent = depth > 0 ? path[depth - 1] : null;

      let thread;
      if (thrS === undefined) thread = parent ? parent.thread : 0;
      else if (thrS === "_") {
        if (!isAgg) fail("E_AGG", "record " + i + ": '@_' on a non-aggregate");
        thread = -1;
      } else {
        thread = parseInt(thrS, 36);
        if (thread >= threads.length) fail("E_INDEX", "record " + i + " thread index " + thread + " out of range");
      }

      const span = { id: i, parent: parent ? parent.id : -1, name: names[nameIdx], start: start, dur: parseInt(m[4], 36), thread: thread };
      if (waitS !== undefined && !isAgg) {
        if (wmaxS !== undefined) fail("E_AGG", "record " + i + ": '~sum.max' on a non-aggregate");
        span.wait = parseInt(waitS, 36);
      }
      if (cpuS !== undefined) span.cpu = parseInt(cpuS, 36);
      if (flagsS !== undefined) {
        for (const f of flagsS) if (FLAG_ORDER.indexOf(f) < 0) fail("E_FLAG", "record " + i + ": unknown flag " + JSON.stringify(f));
        span.flags = FLAG_ORDER.split("").filter(function (f) { return flagsS.indexOf(f) >= 0; }).join("");
      }
      if (attrsS) {
        const attrs = {};
        ATTR_RE.lastIndex = 0;
        let km;
        while ((km = ATTR_RE.exec(attrsS)) !== null) {
          const k = parseInt(km[1], 36);
          if (k >= names.length) fail("E_INDEX", "record " + i + " attribute key " + k + " out of range");
          attrs[names[k]] = decodeNumber(km[2]);   // a repeated key keeps its first position; the last value wins
        }
        span.attrs = attrs;
      }
      if (isAgg) {
        const agg = { count: parseInt(m[9], 36), min: parseInt(m[10], 36), max: parseInt(m[11], 36), extent: parseInt(m[12], 36), maxAt: parseInt(m[13], 36) };
        if (agg.count < 1) fail("E_AGG", "record " + i + ": aggregate count 0");
        if (waitS !== undefined) {
          if (wmaxS === undefined) fail("E_AGG", "record " + i + ": aggregate wait needs sum.max");
          agg.waitSum = parseInt(waitS, 36);
          agg.waitMax = parseInt(wmaxS, 36);
        }
        span.agg = agg;
      }
      path[depth] = span;
      prevDepth = depth;
      prevAgg = isAgg;
      spans.push(span);
    });
    return spans;
  }

  // ------------------------------------------------------------ JSON form

  function isInt(x) { return typeof x === "number" && Number.isInteger(x); }

  // Validates a JSON-form document (as produced by any Tempo tool) and fills defaults.
  function normalizeJson(input) {
    if (!input || typeof input !== "object" || input.v !== 1 || !Array.isArray(input.spans)) fail("E_JSON", "expected an object with \"v\": 1 and \"spans\"");
    const threads = Array.isArray(input.threads) ? input.threads.map(String) : ["?"];
    const doc = { v: 1, unit: "us" };
    if (isInt(input.wall)) doc.wall = input.wall;
    doc.folded = isInt(input.folded) ? input.folded : 0;
    doc.dropped = isInt(input.dropped) ? input.dropped : 0;
    doc.threads = threads;
    doc.live = Array.isArray(input.live) ? input.live.filter(isInt) : [];
    doc.truncated = input.truncated === true;
    doc.spans = input.spans.map(function (s, i) {
      if (!s || typeof s !== "object") fail("E_JSON", "span " + i + " is not an object");
      if (s.id !== i) fail("E_JSON", "span " + i + " has id " + s.id + " (ids must equal the array index)");
      if (!isInt(s.parent) || s.parent >= i || (i === 0) !== (s.parent === -1) || s.parent < -1) fail("E_JSON", "span " + i + " has a bad parent");
      for (const k of ["start", "dur", "thread"]) if (!isInt(s[k])) fail("E_JSON", "span " + i + " lacks an integer " + k);
      if (s.thread < -1 || s.thread >= threads.length) fail("E_JSON", "span " + i + " thread out of range");
      const o = { id: i, parent: s.parent, name: s.name === null || s.name === undefined ? "?" : String(s.name), start: s.start, dur: s.dur, thread: s.thread };
      if (isInt(s.wait)) o.wait = s.wait;
      if (isInt(s.cpu)) o.cpu = s.cpu;
      if (typeof s.flags === "string" && s.flags) o.flags = FLAG_ORDER.split("").filter(function (f) { return s.flags.indexOf(f) >= 0; }).join("");
      if (s.attrs && typeof s.attrs === "object") {
        const a = {};
        for (const k of Object.keys(s.attrs)) if (typeof s.attrs[k] === "number") a[k] = s.attrs[k];
        if (Object.keys(a).length) o.attrs = a;
      }
      if (s.agg && typeof s.agg === "object") {
        const g = s.agg, agg = {};
        for (const k of ["count", "min", "max", "extent", "maxAt"]) {
          if (!isInt(g[k])) fail("E_JSON", "span " + i + " aggregate lacks " + k);
          agg[k] = g[k];
        }
        if (isInt(g.waitSum) && isInt(g.waitMax)) { agg.waitSum = g.waitSum; agg.waitMax = g.waitMax; }
        o.agg = agg;
      }
      return o;
    });
    if (!doc.spans.length) fail("E_JSON", "a trace needs at least the root span");
    return doc;
  }

  // Canonical encoder (FORMAT.md §7), no byte budget. Used for share links of JSON input.
  function encode(doc) {
    const spans = doc.spans, threads = doc.threads;
    if (!spans || !spans.length) throw new Error("a trace needs at least the root span");
    const depthOf = [], threadOf = [], stack = [];
    spans.forEach(function (s, i) {
      if (s.id !== i) throw new Error("span ids must equal their array index (pre-order)");
      if (i === 0) {
        if (s.parent !== -1 || s.thread !== 0 || s.start !== 0) throw new Error("span 0 must be the root: parent -1, thread 0, start 0");
        depthOf[i] = 0;
      } else {
        while (stack.length && stack[stack.length - 1] !== s.parent) stack.pop();
        if (!stack.length) throw new Error("span " + i + ": parent " + s.parent + " is not on the current path");
        if (spans[s.parent].agg) throw new Error("span " + i + ": an aggregate cannot have children");
        depthOf[i] = depthOf[s.parent] + 1;
        if (depthOf[i] > MAX_DEPTH) throw new Error("span " + i + " deeper than " + MAX_DEPTH);
      }
      stack.push(i);
      threadOf[i] = s.thread;
    });
    const nameIdx = new Map();
    const nm = function (n) { return n === null || n === undefined || n === "" ? "?" : n; };
    for (const s of spans) if (!nameIdx.has(nm(s.name))) nameIdx.set(nm(s.name), nameIdx.size);
    for (const s of spans) for (const k of Object.keys(s.attrs || {})) if (!nameIdx.has(nm(k))) nameIdx.set(nm(k), nameIdx.size);

    const out = ["v1"];
    if (doc.wall !== undefined && doc.wall !== null) out.push("w=" + toB36(doc.wall));
    const folded = doc.folded || 0;
    let dropped = doc.dropped || 0;
    const recs = [];
    const cur = new Array(MAX_DEPTH + 2).fill(0);
    spans.forEach(function (s, i) {
      const d = depthOf[i];
      const delta = s.start - cur[d];
      if (delta < 0) throw new Error("span " + i + " starts before its previous sibling or parent");
      cur[d] = cur[d + 1] = s.start;
      let r = toB36(d) + toB36(nameIdx.get(nm(s.name))) + "." + toB36(delta) + "." + toB36(s.dur);
      const agg = s.agg;
      if (i > 0 && s.thread !== threadOf[s.parent]) {
        if (s.thread === -1) {
          if (!agg) throw new Error("span " + i + ": thread -1 only on aggregates");
          r += "@_";
        } else r += "@" + toB36(s.thread);
      }
      if (agg) { if (agg.waitSum !== undefined) r += "~" + toB36(agg.waitSum) + "." + toB36(agg.waitMax); }
      else if (s.wait !== undefined) r += "~" + toB36(s.wait);
      if (s.cpu !== undefined) r += "*" + toB36(s.cpu);
      if (agg) r += "+" + [agg.count, agg.min, agg.max, agg.extent, agg.maxAt].map(toB36).join(".");
      const flags = s.flags || "";
      if (flags) r += "^" + FLAG_ORDER.split("").filter(function (f) { return flags.indexOf(f) >= 0; }).join("");
      for (const k of Object.keys(s.attrs || {})) {
        const num = encodeNumber(s.attrs[k]);
        if (num === null) { dropped += 1; continue; }
        r += ":" + toB36(nameIdx.get(nm(k))) + "=" + num;
      }
      recs.push(r);
    });
    if (folded || dropped) out.push("x=" + toB36(folded) + "." + toB36(dropped));
    if (doc.live && doc.live.length) out.push("l=" + doc.live.map(toB36).join("."));
    out.push("T=" + threads.map(encodeName).join(":"));
    out.push("N=" + Array.from(nameIdx.keys()).map(encodeName).join(":"));
    out.push("S=" + recs.join("/"));
    out.push("e=" + toB36(recs.length));
    return out.join(";");
  }

  // Stable rendering of the JSON form: one span per line (like tools/tempo_ref.py).
  function dumpJson(doc) {
    const top = ["v", "unit", "wall", "folded", "dropped", "threads", "live", "truncated"];
    const lines = ["{"];
    for (const k of top) if (k in doc) lines.push("  " + JSON.stringify(k) + ": " + JSON.stringify(doc[k]).replace(/","/g, "\", \"").replace(/,(\d)/g, ", $1") + ",");
    for (const k of Object.keys(doc)) if (top.indexOf(k) < 0 && k !== "spans") lines.push("  " + JSON.stringify(k) + ": " + JSON.stringify(doc[k]) + ",");
    const spans = doc.spans || [];
    if (!spans.length) lines.push("  \"spans\": []");
    else {
      lines.push("  \"spans\": [");
      spans.forEach(function (s, i) { lines.push("    " + JSON.stringify(s) + (i + 1 < spans.length ? "," : "")); });
      lines.push("  ]");
    }
    lines.push("}");
    return lines.join("\n") + "\n";
  }

  // ------------------------------------------------------------ input extraction
  //
  // Accepts what the CLI accepts: `curl -i` / `curl -v` output (the Tempo-Trace line, case-insensitive,
  // with terminal-wrapped continuation lines), bare header values starting with "v1;" (several, one
  // after another, each possibly wrapped), or JSON (a body, an array or NDJSON) holding objects with
  // "v": 1 and "spans" anywhere, or a header string under a "tempo" key.
  // Returns [{source: "header"|"value"|"json", header?: string, doc?: object}].

  const HEADER_LINE_RE = /^\s*(?:[<>]\s*)?tempo-trace\s*:(.*)$/i;
  const COMPLETE_RE = /;e=[0-9a-z]+$/;

  function collectJson(x, out) {
    if (Array.isArray(x)) { for (const y of x) collectJson(y, out); return; }
    if (!x || typeof x !== "object") return;
    if (x.v === 1 && Array.isArray(x.spans)) { out.push({ source: "json", doc: x }); return; }
    for (const k of Object.keys(x)) {
      const val = x[k];
      if (typeof val === "string" && /^tempo(-trace)?$/i.test(k) && stripWs(val).startsWith("v1;")) out.push({ source: "json", header: stripWs(val) });
      else collectJson(val, out);
    }
  }

  function jsonTraces(text) {
    const t = text.trim();
    if (!t || (t[0] !== "{" && t[0] !== "[")) return null;
    const out = [];
    try { collectJson(JSON.parse(t), out); return out; } catch (e) { /* maybe NDJSON */ }
    const lines = t.split(/\r?\n/).filter(function (l) { return l.trim(); });
    try { for (const l of lines) collectJson(JSON.parse(l), out); return out; } catch (e) { return null; }
  }

  function extractTraces(input) {
    const text = String(input || "").replace(/^﻿/, "");
    if (!text.trim()) return [];
    const asJson = jsonTraces(text);
    if (asJson) {
      if (!asJson.length) fail("E_INPUT", "the JSON holds no object with \"v\": 1 and \"spans\"");
      return asJson;
    }
    const out = [];
    let cur = null;
    const flush = function () { if (cur) { out.push({ source: cur.source, header: cur.value }); cur = null; } };
    for (const line of text.split(/\r\n|\n|\r/)) {
      const m = HEADER_LINE_RE.exec(line);
      if (m) { flush(); cur = { source: "header", value: stripWs(m[1]) }; continue; }
      const s = stripWs(line);
      if (cur) {
        const done = COMPLETE_RE.test(cur.value);
        if (s && PURE_RE.test(s) && !s.startsWith("v1;") && (!done || /^[0-9a-z]+$/.test(s))) { cur.value += s; continue; }
        flush();
      }
      if (s.startsWith("v1;")) cur = { source: "value", value: s };
    }
    flush();
    if (out.length) return out;
    // curl -i output without the header: look for a JSON body after the blank line
    const blank = text.search(/\r?\n\r?\n/);
    if (blank >= 0) {
      const body = jsonTraces(text.slice(blank));
      if (body && body.length) return body;
    }
    return [{ source: "value", header: stripWs(text) }];   // let decode() say what is wrong
  }

  // One extracted item -> {doc, header}. header is the raw value (or the canonical encoding of JSON input).
  function load(item) {
    if (item.header !== undefined) return { doc: decode(item.header), header: item.header, source: item.source };
    const doc = normalizeJson(item.doc);
    let header = null;
    try { header = encode(doc); } catch (e) { header = null; }
    return { doc: doc, header: header, source: item.source };
  }

  return {
    ALPHABET_CHARS, FLAG_ORDER, FLAG_NAMES, MAX_DEPTH, RECORD_RE, ERRORS,
    TempoFormatError, stripWs, decode, decodeName, encodeName, encodeNumber, encode,
    normalizeJson, dumpJson, extractTraces, load
  };
})();
// ==== END TEMPO PARSER ====

export default TempoParse;
export const {
  ALPHABET_CHARS, FLAG_ORDER, FLAG_NAMES, MAX_DEPTH, RECORD_RE, ERRORS,
  TempoFormatError, stripWs, decode, decodeName, encodeName, encodeNumber, encode,
  normalizeJson, dumpJson, extractTraces, load
} = TempoParse;
