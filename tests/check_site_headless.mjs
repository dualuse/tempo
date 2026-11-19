#!/usr/bin/env node
// Renders Tempo Timeline (site/index.html) in headless Chrome: every sample, in light and dark, at
// phone (390x844, touch) and desktop (1440x900) size, through every tab. Fails on any console
// message, uncaught exception or log entry (a CSP violation is one), any request besides the page
// itself and data:/blob: URLs, a trace that does not open, an error in the notice, or a page wider
// than the window. Traces with phases are also checked for what the page draws of them (PHASES below).
//
//   node tests/check_site_headless.mjs [page.html]   CHROME=/path/to/chrome overrides the lookup
//
// Talks to Chrome over --remote-debugging-pipe (the DevTools protocol, NUL-terminated JSON on fds
// 3 and 4), so it needs nothing but Node 20+. Without a Chrome it skips, except under CI.

import { spawn, execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { makeLink, decode } from "../tools/tempo_parse.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PAGE = pathToFileURL(process.argv[2] || join(ROOT, "site", "index.html")).href;
const sample = (name) => readFileSync(join(ROOT, "samples", name), "utf8").trim();

// "phase < span" for every phase of a value, as the tooltip names them (an unnamed span by its phases).
function phaseList(value) {
  const spans = decode(value).spans;
  const title = (s) => s.name || spans.filter((q) => q.parent === s.id && (q.flags || "").includes("p")).map((q) => q.name).join(" · ") || "(unnamed)";
  const path = (s) => (s.parent <= 0 ? title(s) : path(spans[s.parent]) + "/" + title(s));
  return spans.filter((s) => (s.flags || "").includes("p")).map((s) => s.name + " < " + (s.parent === 0 ? title(spans[0]) : path(spans[s.parent])));
}

function findChrome() {
  if (process.env.CHROME) return process.env.CHROME;
  const paths = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  ];
  for (const p of paths) if (existsSync(p)) return p;
  for (const name of ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"]) {
    try { return execFileSync("which", [name], { encoding: "utf8" }).trim(); } catch (e) { /* next */ }
  }
  return null;
}

// ------------------------------------------------------------ the DevTools protocol over a pipe
class Cdp {
  constructor(child) {
    this.out = child.stdio[3];
    this.next = 1;
    this.calls = new Map();
    this.listeners = new Set();
    let buf = "";
    child.stdio[4].setEncoding("utf8");
    child.stdio[4].on("data", (chunk) => {
      buf += chunk;
      let i;
      while ((i = buf.indexOf("\0")) >= 0) {
        const msg = JSON.parse(buf.slice(0, i));
        buf = buf.slice(i + 1);
        const call = msg.id && this.calls.get(msg.id);
        if (call) {
          this.calls.delete(msg.id);
          if (msg.error) call.reject(new Error(call.method + ": " + msg.error.message));
          else call.resolve(msg.result);
        } else if (msg.method) {
          for (const l of this.listeners) l(msg);
        }
      }
    });
  }
  send(method, params = {}, sessionId) {
    const id = this.next++;
    this.out.write(JSON.stringify({ id, method, params, sessionId }) + "\0");
    return new Promise((resolve, reject) => this.calls.set(id, { method, resolve, reject }));
  }
}

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

// The page's state as the checks see it.
const PROBE = `(() => {
  const body = document.body, root = document.documentElement, msg = document.getElementById("msg");
  const notice = document.getElementById("notice");
  return JSON.stringify({
    loaded: body.classList.contains("loaded"),
    prompting: body.classList.contains("prompting"),
    dark: root.classList.contains("dark"),
    background: getComputedStyle(body).backgroundColor,
    overflow: root.scrollWidth - root.clientWidth,
    error: notice && !notice.hidden && msg.querySelector(".err") ? msg.textContent : "",
    tab: (document.querySelector('.tab[aria-selected="true"]') || {}).dataset?.tab || "",
    tabs: [...document.querySelectorAll(".tab")].filter((b) => b.offsetParent !== null).map((b) => b.dataset.tab),
  });
})()`;

// Checks on a trace with phases (FORMAT.md §6.7), run in the page once it is open on the lanes tab.
// Hovers the lanes canvas point by point and collects the tooltips: every phase must be reachable by
// hover, narrow ones too, and say which span it sections. Then the tree lists each phase once below its
// span, and a phase's detail shows its own start and duration only. Returns a list of problems.
const PHASES = `(async () => {
  const out = [];
  const frames = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  const cv = document.getElementById("lanes"), tip = document.getElementById("tip");
  const want = JSON.parse(cv.dataset.expectPhases || "[]");
  if (!(Number(cv.dataset.phases) > 0)) out.push("no phases drawn");
  if (!(Number(cv.dataset.nameColumn) > 0)) out.push("no name column");
  const box = cv.getBoundingClientRect(), seen = new Set();
  for (let y = 2; y < box.height; y += 3) for (let x = 1; x < box.width; x += 2) {
    cv.dispatchEvent(new PointerEvent("pointermove", { clientX: box.left + x, clientY: box.top + y, pointerType: "mouse", bubbles: true }));
    if (tip.classList.contains("on") && tip.textContent.indexOf("phase of ") >= 0) seen.add(tip.querySelector("b").textContent + " < " + tip.querySelector(".micro").textContent.slice(9));
  }
  for (const w of want) if (!seen.has(w)) out.push("phase not reachable by hover: " + w);
  cv.dispatchEvent(new PointerEvent("pointerleave", { bubbles: true }));
  document.querySelector('.tab[data-tab="tree"]').click();
  await frames();
  const rows = [...document.querySelectorAll("#pane-tree tr.phase-row")];
  if (rows.length !== want.length) out.push("tree lists " + rows.length + " phases, not " + want.length);
  if (rows.some((r) => r.querySelector(".tog button"))) out.push("a phase in the tree can be expanded");
  if (rows.length) {
    rows[0].click();
    await frames();
    const d = document.getElementById("detail");
    const dts = [...d.querySelectorAll("dt")].map((e) => e.textContent);
    if (!/^Phase #/.test(d.querySelector(".eyebrow").textContent)) out.push("detail of a phase: " + d.querySelector(".eyebrow").textContent);
    if (dts.indexOf("Start") < 0 || dts.indexOf("Duration") < 0 || dts.some((t) => /^(Self|CPU|End|Thread)$/.test(t))) out.push("detail of a phase lists " + dts.join(", "));
  }
  document.querySelector('.tab[data-tab="lanes"]').click();
  await frames();
  return JSON.stringify(out);
})()`;

async function run(cdp, c, view, scheme) {
  const problems = [];
  const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
  const doc = c.url.split("#")[0];
  const onEvent = (m) => {
    if (m.sessionId !== sessionId) return;
    const p = m.params;
    if (m.method === "Runtime.consoleAPICalled") {
      problems.push("console." + p.type + ": " + p.args.map((a) => a.value ?? a.description ?? a.type).join(" "));
    } else if (m.method === "Runtime.exceptionThrown") {
      const d = p.exceptionDetails;
      problems.push("exception: " + (d.exception?.description || d.text) + " at " + d.lineNumber + ":" + d.columnNumber);
    } else if (m.method === "Log.entryAdded") {
      problems.push("log " + p.entry.level + ": " + p.entry.text);
    } else if (m.method === "Network.requestWillBeSent") {
      const u = p.request.url;
      if (u.split("#")[0] !== doc && !/^(?:data|blob):/.test(u)) problems.push("request: " + u.slice(0, 120));
    } else if (m.method === "Network.webSocketCreated") {
      problems.push("websocket: " + p.url);
    }
  };
  cdp.listeners.add(onEvent);
  const send = (method, params) => cdp.send(method, params, sessionId);
  const probe = async () => { // null while the document is not there yet
    const r = await send("Runtime.evaluate", { expression: PROBE, returnByValue: true });
    return r.exceptionDetails || typeof r.result.value !== "string" ? null : JSON.parse(r.result.value);
  };
  const frames = () => send("Runtime.evaluate", {
    expression: "new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(1))))", awaitPromise: true,
  });
  try {
    await Promise.all(["Runtime.enable", "Log.enable", "Network.enable", "Page.enable"].map((m) => send(m)));
    await send("Emulation.setDeviceMetricsOverride", { width: view.width, height: view.height, deviceScaleFactor: view.scale, mobile: view.mobile });
    if (view.mobile) await send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 5 });
    await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: scheme }] });
    await send("Page.navigate", { url: c.url });

    let s;
    for (let i = 0; i < 100; i++) { // up to 10 s for the trace to decode and render
      s = await probe();
      if (s && (c.landing ? s.prompting : s.loaded || s.error)) break;
      await delay(100);
    }
    await frames();
    s = await probe();
    if (!s) throw new Error("the page did not load");
    if (c.landing ? !s.prompting : !s.loaded) problems.push(c.landing ? "the landing prompt did not show" : "the trace did not open");
    if (s.error) problems.push("notice: " + s.error);
    if (s.dark !== (scheme === "dark")) problems.push("theme is " + (s.dark ? "dark" : "light") + " (background " + s.background + ")");
    if (c.tab && s.tab !== c.tab) problems.push("opened on tab " + s.tab + ", not " + c.tab);
    if (s.overflow > 0) problems.push("page " + s.overflow + "px wider than the window on tab " + s.tab);
    if (c.phases) {
      await send("Runtime.evaluate", { expression: "document.getElementById('lanes').dataset.expectPhases = " + JSON.stringify(JSON.stringify(c.phases)) });
      const r = await send("Runtime.evaluate", { expression: PHASES, awaitPromise: true, returnByValue: true });
      if (r.exceptionDetails) problems.push("phase checks threw: " + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
      else problems.push(...JSON.parse(r.result.value));
    }
    if (!c.landing) {
      for (const tab of s.tabs) {
        await send("Runtime.evaluate", { expression: `document.querySelector('.tab[data-tab="${tab}"]').click()` });
        await frames();
        const t = await probe();
        if (t.tab !== tab) problems.push("tab " + tab + " did not open");
        if (t.overflow > 0) problems.push("page " + t.overflow + "px wider than the window on tab " + tab);
      }
    }
  } finally {
    cdp.listeners.delete(onEvent);
    await cdp.send("Target.closeTarget", { targetId });
  }
  return problems;
}

async function main() {
  const chrome = findChrome();
  if (!chrome) {
    const msg = "check_site_headless: no Chrome found (set CHROME=/path/to/chrome)";
    if (process.env.CI) { console.error(msg); process.exit(1); }
    console.log(msg + "; skipped");
    return;
  }
  const cases = [{ name: "landing", url: PAGE, landing: true }];
  for (const n of ["small", "find-healthy", "find-saturated", "pipeline"]) {
    cases.push({ name: n, url: await makeLink(PAGE, sample(n + ".header")) });
  }
  cases.push({ name: "diff", url: await makeLink(PAGE, sample("diff/before.header"), sample("diff/after.header"), "diff"), tab: "diff" });
  cases.push({ name: "batch", url: PAGE + "#demo=batch", tab: "stats" });
  // phases on the root, on a handoff and on an unnamed span; narrow, failed and misused ones
  const phases = readFileSync(join(ROOT, "testdata", "golden", "phases.header"), "utf8").trim();
  cases.push({ name: "phases", url: await makeLink(PAGE, phases), phases: phaseList(phases) });
  const views = [
    { name: "phone", width: 390, height: 844, scale: 3, mobile: true },
    { name: "desktop", width: 1440, height: 900, scale: 1, mobile: false },
  ];

  const profile = mkdtempSync(join(tmpdir(), "tempo-chrome-"));
  const args = [
    "--headless=new", "--remote-debugging-pipe", "--user-data-dir=" + profile, "--no-first-run",
    "--no-default-browser-check", "--disable-background-networking", "--disable-component-update",
    "--disable-sync", "--disable-extensions", "--mute-audio", "about:blank",
  ];
  if (process.env.CI) args.unshift("--no-sandbox"); // CI runners may not allow Chrome's sandbox
  const child = spawn(chrome, args, { stdio: ["ignore", "ignore", "pipe", "pipe", "pipe"] });
  let stderr = "";
  child.stderr.on("data", (d) => { stderr += d; });
  const exited = new Promise((r) => child.on("exit", r));
  const cdp = new Cdp(child);

  let failed = 0, runs = 0;
  try {
    const version = await Promise.race([
      cdp.send("Browser.getVersion"),
      exited.then(() => { throw new Error("Chrome exited: " + stderr.slice(-2000)); }),
    ]);
    console.log("check_site_headless: " + version.product);
    for (const c of cases) {
      for (const view of views) {
        for (const scheme of ["light", "dark"]) {
          const problems = await run(cdp, c, view, scheme);
          runs++;
          const label = c.name + " " + view.name + " " + scheme;
          if (problems.length) {
            failed++;
            console.log("FAIL " + label + "\n  " + problems.join("\n  "));
          } else {
            console.log("ok   " + label);
          }
        }
      }
    }
  } finally {
    cdp.send("Browser.close").catch(() => {});
    await Promise.race([exited, delay(5000)]);
    child.kill();
    rmSync(profile, { recursive: true, force: true, maxRetries: 3 });
  }
  console.log(`check_site_headless: ${runs - failed}/${runs} renders clean`);
  if (failed) process.exit(1);
}

main().catch((e) => { console.error(e); process.exit(1); });
