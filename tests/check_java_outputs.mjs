#!/usr/bin/env node
// Checks values and links the Java encoder wrote with the viewer's parser (tools/tempo_parse.mjs).
//
//   node tests/check_java_outputs.mjs CORPUS
//
// CORPUS is the file tests/check_java_outputs.py reads (KIND LIMIT FULL OUTPUT per line). Every value,
// and the value inside every link, decodes without truncation and round-trips through the encoder; values and
// links keep their limit (a link when the limit leaves 256 bytes besides its base).

import { readFileSync } from "node:fs";
import { isDeepStrictEqual } from "node:util";
import * as P from "../tools/tempo_parse.mjs";

const MIN_BYTES = 256;
const failed = [];
let cases = 0;
for (const line of readFileSync(process.argv[2], "utf8").split("\n")) {
  if (!line.trim()) continue;
  cases++;
  const [kind, limitText, , output] = line.split("\t");
  const limit = Number(limitText);
  try {
    let value = output;
    if (kind === "link") {
      value = await P.unpackValue(P.linkParams(output).t);
      const base = output.split("#")[0];
      if (limit - base.length >= MIN_BYTES && output.length > limit) throw new Error(output.length + " bytes over " + limit);
    } else if (limit >= MIN_BYTES && output.length > limit) {
      throw new Error(output.length + " bytes over " + limit);
    }
    const doc = P.decode(value);
    if (doc.truncated) throw new Error("decodes as truncated");
    if (!isDeepStrictEqual(P.decode(P.encode(doc)), doc)) throw new Error("re-encodes to " + P.encode(doc));
  } catch (e) {
    failed.push(kind + " " + limitText + ": " + (e.code ? e.code + " " : "") + e.message + "\n     " + output);
  }
}
for (const f of failed) console.log("FAIL " + f);
console.log(cases + " cases, " + (failed.length ? failed.length + " failed" : "all passed"));
process.exit(failed.length ? 1 : 0);
