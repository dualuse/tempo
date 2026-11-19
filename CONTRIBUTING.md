# Contributing

Issues and pull requests are welcome. A few rules keep the library small and the format
stable.

## Before you open a pull request

Run what CI runs:

```
./gradlew check                    # unit tests, the golden vectors in Java, the link-base rule, the demos
./gradlew jmhGate                  # cost gate: the four cost promises and the allocation ceilings
./gradlew jcstress                 # concurrency tests of the lane state, handles and tasks
python3 tests/run_golden.py        # vectors, ladder and samples against tools/tempo_ref.py
python3 -m unittest tests.test_cli # the command-line viewer (Python 3.8+)
python3 -m unittest tests.test_check_private  # tools/check-private.sh
node tests/test_parser.mjs && node tests/test_analysis.mjs && node tests/check_spa_parser_sync.mjs
node tests/check_site_headless.mjs   # Tempo Timeline in headless Chrome: samples, themes, phone and desktop
```

- **One file.** The library is `Tempo.java`, plus the optional `TempoJackson.java`. It targets
  Java 11 and has no runtime dependencies.
- **Four cost promises.** They are gates, not goals, and `./gradlew jmhGate` (`CostGate.java`)
  checks each one; see [BENCHMARKS.md](BENCHMARKS.md).
  1. A recorded span allocates 0 B once warm: its handle comes from the thread's pool. So do
     phases, attributes, flags and steps.
  2. A task allocates one object, itself: the `RunnableTask`, `CallableTask` or supplier task,
     which through `Tempo.executor` is also the `Future`.
  3. A tick the trace does not record costs about a branch through `s.tick(name)`; the static
     `Tempo.tick(name)` may add the `ThreadLocal` read. A step the trace does not draw allocates
     nothing.
  4. A recorded span through the static API costs at most 6.4 times one `System.nanoTime()`,
     measured in the same run.

  A change that cannot keep a promise is discussed first; a gate is never loosened to let it
  pass. Rendering may allocate its output, within the ceilings of the gate.
- **Exact tests.** Tests use the fake clock (`Options.clock`) and assert exact numbers; no
  sleeps for timing.
- **Misuse heals.** Outside strict mode the library never throws into the caller: misuse is
  healed and counted. In strict mode (`Options.strict`) every misuse is an `AssertionError`.
- **The format is a contract.** [FORMAT.md](FORMAT.md) is normative. A change to the wire or
  JSON form changes FORMAT.md, the vectors in `testdata/`, `tools/tempo_ref.py`, the Java
  encoder and both viewers in one pull request.
- **Demos are checked.** Each demo in `src/demo` checks what it claims about its own trace, and
  `./gradlew demos` (part of `check`) runs them all. A change to the API or its behaviour updates
  the demos that show it; see [src/demo/README.md](src/demo/README.md).
- **One style in examples.** The README, the demos and the Javadoc open spans with the static
  calls (`Tempo.span`, `Tempo.step`, `Tempo.tick`), try-with-resources first and `Tempo.run` /
  `Tempo.call` as the compact form. No example passes a trace or a span into a method; code
  that needs the innermost span asks `Tempo.current()`.
- **Invented names only.** Examples, tests, samples and commit messages use made-up span names,
  hosts and timings, never ones taken from a real service.

## Private-term check (maintainers)

`tools/check-private.sh` greps the tracked files and the whole history (`git log --all -p`,
commit messages included) for the patterns of a deny-list kept outside the repository, and
fails on a hit. The list is the file named by `$TEMPO_DENYLIST`, by default
`~/.config/tempo/denylist`: one extended regular expression per line, matched
case-insensitively; blank lines and lines starting with `#` are ignored. Without the file the
check is skipped with a warning. Never commit the list or its terms.

Install it as the pre-push hook of your clone:

```
mkdir -p ~/.config/tempo && $EDITOR ~/.config/tempo/denylist
ln -s ../../tools/check-private.sh .git/hooks/pre-push
```

Git passes the hook the remote's name and URL; the script ignores them.

CI runs the same check on every push to `master`, with the list taken from the repository
secret `TEMPO_DENYLIST` (the file's contents). Forks and pull requests have no such secret, so
there the check is skipped. On CI it prints only file paths and commit ids
(`--names-only`), never the matching text.

## Licence

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE).
