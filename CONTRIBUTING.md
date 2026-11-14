# Contributing

Issues and pull requests are welcome. A few rules keep the library small and the format
stable.

## Before you open a pull request

Run what CI runs:

```
./gradlew check                    # unit tests, the golden vectors in Java, the link-base rule
./gradlew jmhGate                  # allocation gate: recording must not allocate
python3 tests/run_golden.py        # vectors, ladder and samples against tools/tempo_ref.py
python3 -m unittest tests.test_cli # the command-line viewer (Python 3.8+)
node tests/test_parser.mjs && node tests/test_analysis.mjs && node tests/check_spa_parser_sync.mjs
```

- **One file.** The library is `Tempo.java`, plus the optional `TempoJackson.java`. It targets
  Java 11 and has no runtime dependencies.
- **No allocation while recording.** Opening and closing spans, phases, attributes and flags
  allocate nothing once warm; a handoff allocates its one 32-byte `Handoff`. `./gradlew jmhGate`
  enforces this. Rendering may allocate its output.
- **Exact tests.** Tests use the fake clock (`Options.clock`) and assert exact numbers; no
  sleeps for timing.
- **The format is a contract.** [FORMAT.md](FORMAT.md) is normative. A change to the wire or
  JSON form changes FORMAT.md, the vectors in `testdata/`, `tools/tempo_ref.py`, the Java
  encoder and both viewers in one pull request.
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

CI runs the same check on every push to `master`, with the list taken from the repository
secret `TEMPO_DENYLIST` (the file's contents). Forks and pull requests have no such secret, so
there the check is skipped. On CI it prints only file paths and commit ids
(`--names-only`), never the matching text.

## Licence

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE).
