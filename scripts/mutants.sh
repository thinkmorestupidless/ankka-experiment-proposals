#!/usr/bin/env bash
# SC-013: each named safeguard is disabled in a disposable copy of the tree, and the independent suite
# must fail there ("killed"). The working tree is never changed. Results go to
# scripts/mutants/last-run.json, which the living features read.
#
#   scripts/mutants.sh [bypass-eligibility|remove-species-rule|accept-mismatched-span ...]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MUTANTS=("$@")
if [ ${#MUTANTS[@]} -eq 0 ]; then MUTANTS=(bypass-eligibility remove-species-rule accept-mismatched-span); fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/edp-mutants.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
results=()
for m in "${MUTANTS[@]}"; do
  copy="$WORK/$m"
  mkdir -p "$copy"
  rsync -a --exclude target --exclude .bloop --exclude .metals --exclude .git "$ROOT/" "$copy/"
  if ! (cd "$copy" && patch -p1 --silent < "$ROOT/scripts/mutants/$m.patch"); then
    echo "$m: the patch does not apply" >&2
    results+=("{\"mutant\":\"$m\",\"applied\":false,\"killed\":false}")
    continue
  fi
  log="$ROOT/scripts/mutants/logs/$m.log"
  mkdir -p "$ROOT/scripts/mutants/logs"
  (cd "$copy" && sbt -batch "testOnly com.thinkmorestupidless.experimentproposals.independent.*" > "$log" 2>&1)
  failed=$(grep -c '==> X' "$log" || true)
  if grep -q 'Passed: Total' "$log" && [ "$failed" -eq 0 ]; then
    echo "$m: SURVIVED (the independent suite still passes)"
    results+=("{\"mutant\":\"$m\",\"applied\":true,\"killed\":false,\"failedTests\":0}")
  elif [ "$failed" -gt 0 ]; then
    echo "$m: killed ($failed independent test(s) failed)"
    results+=("{\"mutant\":\"$m\",\"applied\":true,\"killed\":true,\"failedTests\":$failed}")
  else
    echo "$m: the suite did not run; see $log" >&2
    tail -5 "$log" >&2
    results+=("{\"mutant\":\"$m\",\"applied\":true,\"killed\":false,\"ran\":false}")
  fi
done
mkdir -p "$ROOT/scripts/mutants"
printf '{"ranAt":"%s","results":[%s]}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(IFS=,; echo "${results[*]}")" > "$ROOT/scripts/mutants/last-run.json"
cat "$ROOT/scripts/mutants/last-run.json"
# The run fails when any mutant survived or did not apply.
! printf '%s' "${results[*]}" | grep -q '"killed":false'
