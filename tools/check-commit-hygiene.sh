#!/usr/bin/env bash
# The commit rules in CLAUDE.md and R6's content greps (docs/release-proofs.md), over the commits in
# <base>..<head>. CI's hygiene job runs it on every pull request over the pull request's own commits.
#   bash tools/check-commit-hygiene.sh <base> <head>
# Every commit: author and committer GonzRon (committer GitHub only on a merge, the merge button);
# no attribution, session or tool line in the message; a commit that is not a merge is its subject
# line alone; no merge subject names a claude branch. Every added line: no e-mail address but a
# reserved fictional one, no home path, no device serial but emulator-5554.
# Prints one line per violation and exits 1, or prints "commit hygiene ok: N commits".
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: tools/check-commit-hygiene.sh <base> <head>" >&2
  exit 2
fi
base=$1
head=$2
owner=GonzRon
attribution='co-authored-by|claude-session|generated[- ](by|with)|anthropic|claude\.ai|noreply@'
# "from GonzRon/claude/x" (the merge button) and "branch 'claude-x'" (a local merge) alike
claude_branch="(from [^ ]*/|'([^ ']*/)?)claude[/-]"

cd "$(git rev-parse --show-toplevel)"
for rev in "$base" "$head"; do
  git rev-parse --verify --quiet "$rev^{commit}" >/dev/null \
    || { echo "commit hygiene: no such commit: $rev" >&2; exit 2; }
done

violations=0
flag() {
  echo "commit hygiene: $1"
  violations=$((violations + 1))
}

commits=0
range=$(git rev-list --reverse --parents "$base..$head")
while read -r sha _first_parent second_parent _; do
  [ -n "$sha" ] || continue
  commits=$((commits + 1))
  is_merge=false
  [ -z "$second_parent" ] || is_merge=true
  { IFS= read -r short; IFS= read -r author; IFS= read -r committer; IFS= read -r subject; } \
    <<< "$(git show -s --format='%h%n%an%n%cn%n%s' "$sha")"
  message=$(git show -s --format=%B "$sha")

  [ "$author" = "$owner" ] || flag "$short author is '$author', not $owner: $subject"
  if [ "$committer" != "$owner" ] && ! { $is_merge && [ "$committer" = GitHub ]; }; then
    flag "$short committer is '$committer', not $owner: $subject"
  fi
  hit=$(printf '%s\n' "$message" | grep -iE "$attribution" | sed -n 1p || true)
  [ -z "$hit" ] || flag "$short attribution in the message: $hit"
  if ! $is_merge; then
    lines=$(printf '%s\n' "$message" | grep -c . || true)
    [ "$lines" -eq 1 ] || flag "$short has a body, $lines lines (subject line only): $subject"
  elif printf '%s\n' "$subject" | grep -qiE "$claude_branch"; then
    flag "$short merge names a claude branch: $subject"
  fi
done <<< "$range"

# R6's content greps over the lines the range adds. <base>...<head> diffs from the merge base, so a
# base that moved on after the branch forked adds nothing here. Three paths are left out because
# they quote these very patterns: this script, CLAUDE.md and docs/release-proofs.md.
found=$(git diff --no-color --no-ext-diff "$base...$head" -- . \
    ':(exclude)tools/check-commit-hygiene.sh' ':(exclude)CLAUDE.md' ':(exclude)docs/release-proofs.md' |
  awk '
    function report(what, text) {
      sub(/^[ \t]+/, "", text)
      printf "commit hygiene: %s:%d %s: %s\n", file, n, what, substr(text, 1, 120)
    }
    function check(text,   rest, addr, domain, bad) {
      rest = text
      while (match(rest, /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z][A-Za-z]+/)) {
        addr = substr(rest, RSTART, RLENGTH)
        domain = tolower(substr(addr, index(addr, "@") + 1))
        # the reserved fictional names (RFC 2606): *.invalid, *.example, example.com/org/net
        if (domain !~ /(^|[.])(invalid|example)$/ && domain !~ /(^|[.])example[.](com|org|net)$/) bad = addr
        rest = substr(rest, RSTART + RLENGTH)
      }
      if (bad != "") report("e-mail address " bad, text)
      if (text ~ /\/home\/[a-z]/) report("home path", text)
      if (text ~ /(adb -s |ANDROID_SERIAL=)/ && text !~ /emulator-5554/) report("device serial", text)
    }
    /^diff --git / { hunk = 0; next }
    !hunk && /^[+][+][+] / { file = substr($0, 7); next }
    /^@@ / { hunk = 1; match($0, /[+][0-9]+/); n = substr($0, RSTART + 1, RLENGTH - 1) + 0; next }
    !hunk { next }
    /^[+]/ { check(substr($0, 2)); n++; next }
    /^ / { n++ }
  ')
if [ -n "$found" ]; then
  printf '%s\n' "$found"
  violations=$((violations + $(printf '%s\n' "$found" | grep -c .)))
fi

if [ "$violations" -gt 0 ]; then
  echo "commit hygiene FAILED: $violations violations in $commits commits ($base..$head)"
  exit 1
fi
echo "commit hygiene ok: $commits commits"
