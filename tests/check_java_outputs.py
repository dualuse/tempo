#!/usr/bin/env python3
"""Checks values and links the Java encoder wrote against the reference and the CLI.

    python3 tests/check_java_outputs.py CORPUS

CORPUS has one case per line, tab-separated: KIND LIMIT FULL OUTPUT, where FULL is the whole
Tempo-Timing value of a trace and OUTPUT what Java rendered for it under LIMIT bytes:

    header  Trace.toHeader(LIMIT): tools/tempo_ref.py fits FULL into LIMIT bytes (FORMAT.md 7.1)
            to the same bytes, and the value is at most LIMIT long when LIMIT >= 256.
    link    Trace.toLink(base, LIMIT): at most LIMIT long when LIMIT - len(base) >= 256; it
            decodes (tempo_ref.decode_link) to FULL, a value of the ladder for FULL, or the fallback.
            DEFLATE implementations differ in their bytes, so which step fits is not compared.

Every OUTPUT must decode without truncation, and `tools/tempo --check` must accept all of them.
Exit status 0 when every case passes, 1 otherwise.
"""

import os
import subprocess
import sys
import tempfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
sys.path.insert(0, os.path.join(ROOT, "tools"))
import tempo_ref  # noqa: E402


def check(kind, limit, full, output):
    """Problems with one case, as a list of strings."""
    doc = tempo_ref.decode(full)
    if kind == "header":
        want = tempo_ref.fit(doc, limit)
        problems = [] if output == want else ["reference fits it to %r" % want]
        if limit >= tempo_ref.MIN_BYTES and len(output) > limit:
            problems.append("%d bytes over %d" % (len(output), limit))
        value = output
    else:
        link = tempo_ref.decode_link(output)
        value = link.value
        base = output.partition("#")[0]
        problems = []
        if limit - len(base) >= tempo_ref.MIN_BYTES and len(output) > limit:
            problems.append("%d bytes over %d" % (len(output), limit))
        candidates = {full, tempo_ref.fallback(doc)} | {v for _, v in tempo_ref.ladder_steps(doc)}
        if value not in candidates:
            problems.append("carries a value that is no step of the ladder: %r" % value)
    if tempo_ref.decode(value)["truncated"]:
        problems.append("decodes as truncated")
    return problems


def main(argv):
    if len(argv) != 2:
        sys.stderr.write(__doc__)
        return 2
    with open(argv[1], encoding="utf-8") as fh:
        cases = [line.rstrip("\n").split("\t") for line in fh if line.strip()]
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        files = []
        for i, (kind, limit, full, output) in enumerate(cases):
            try:
                problems = check(kind, int(limit), full, output)
            except tempo_ref.TempoFormatError as e:
                problems = [str(e)]
            for p in problems:
                failed += 1
                print("FAIL %s %s: %s\n     %s" % (kind, limit, p, output))
            files.append(os.path.join(tmp, "%04d.%s" % (i, kind)))
            with open(files[-1], "w", encoding="utf-8") as fh:
                fh.write(output + "\n")
        cli = subprocess.run([sys.executable, os.path.join(ROOT, "tools", "tempo"), "--check"] + files,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        ok = sum(1 for line in cli.stdout.splitlines() if line.startswith("ok "))
        if cli.returncode != 0 or ok != len(files):
            failed += 1
            print("FAIL tools/tempo --check accepted %d of %d (exit %d)\n%s" % (ok, len(files), cli.returncode, cli.stderr))
    print("%d cases, %s" % (len(cases), "%d failed" % failed if failed else "all passed"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
