#!/bin/sh
# Private-term check: greps the tracked tree and the full history (git log --all -p, commit
# messages included) for the patterns of a deny-list kept OUTSIDE the repository, and exits 1
# on a hit. Meant as a pre-push hook (see CONTRIBUTING.md); CI runs it on pushes to master.
#
#   tools/check-private.sh [--names-only] [<remote> <url>]
#
# Git runs a pre-push hook with the remote's name and URL as arguments; they are ignored.
# The deny-list is the file named by $TEMPO_DENYLIST, default ~/.config/tempo/denylist: one
# extended regular expression per line, matched case-insensitively and unanchored; blank lines
# and lines starting with # are ignored. Without the file the check is skipped with a warning.
# --names-only reports only paths and commit ids, never the matching text, so a public log
# does not repeat the terms it looks for.

set -eu

names_only=false
for arg in "$@"; do
  case "$arg" in
    --names-only) names_only=true ;;
    -*) echo "usage: $0 [--names-only] [<remote> <url>]" >&2; exit 2 ;;
    *) ;;  # the pre-push hook's <remote> <url>
  esac
done

list=${TEMPO_DENYLIST:-$HOME/.config/tempo/denylist}
if [ ! -f "$list" ]; then
  echo "check-private: no deny-list at $list; skipped (set TEMPO_DENYLIST)" >&2
  exit 0
fi

cd "$(git rev-parse --show-toplevel)"

patterns=$(mktemp)
trap 'rm -f "$patterns"' EXIT
# A blank pattern would match every line, so only real patterns go to grep.
grep -v -e '^[[:space:]]*$' -e '^[[:space:]]*#' "$list" | tr -d '\r' > "$patterns" || true
if [ ! -s "$patterns" ]; then
  echo "check-private: $list has no patterns; skipped" >&2
  exit 0
fi
# A malformed pattern makes grep exit 2, which would read as "no hit" below.
if ! { grep -i -E -f "$patterns" /dev/null; [ $? -eq 1 ]; }; then
  echo "check-private: $list has a malformed pattern" >&2
  exit 2
fi

hits=0

# Tracked files as they are in the working tree.
if $names_only; then
  out=$(git grep -I -i -l -E -f "$patterns" || true)
else
  out=$(git grep -I -i -n -E -f "$patterns" || true)
fi
if [ -n "$out" ]; then
  echo "check-private: private terms in tracked files:" >&2
  printf '%s\n' "$out" >&2
  hits=1
fi

# Every commit reachable from any ref, messages and patches. Each line is prefixed with its
# commit id; the ids of commits with hits are listed (with the lines unless --names-only).
out=$(git log --all -p --no-color --no-ext-diff --no-textconv \
  | awk '/^commit [0-9a-f]+/ { c = substr($2, 1, 12) } { print c ": " $0 }' \
  | grep -i -E -f "$patterns" || true)
if [ -n "$out" ]; then
  echo "check-private: private terms in the history:" >&2
  if $names_only; then
    printf '%s\n' "$out" | cut -d: -f1 | sort -u >&2
  else
    printf '%s\n' "$out" >&2
  fi
  hits=1
fi

if [ "$hits" -ne 0 ]; then
  echo "check-private: remove the terms (and rewrite the commits that carry them) before pushing" >&2
  exit 1
fi
echo "check-private: tree and history clean ($(wc -l < "$patterns" | tr -d ' ') patterns)" >&2
