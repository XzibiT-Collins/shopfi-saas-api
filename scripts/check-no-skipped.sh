#!/bin/sh
# MAIN-RED-02 AC1: a test run with skipped > 0 is INVALID (the Postgres suites skip silently when the database is
# unreachable unless SHOPFI_TEST_DB_REQUIRED=true), so a batch run must fail when any report says skipped > 0.
# usage: scripts/check-no-skipped.sh [surefire-reports-dir ...]   (default: target/surefire-reports)
# exit 0: reports found, 0 skipped. exit 1: some test was skipped. exit 2: no report found (nothing ran: also invalid).
dirs="$*"
[ -n "$dirs" ] || dirs="target/surefire-reports"
reports=0
tests=0
skipped=0
for d in $dirs; do
  for f in "$d"/TEST-*.xml; do
    [ -e "$f" ] || continue
    reports=$((reports + 1))
    t=$(grep -o ' tests="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
    s=$(grep -o ' skipped="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
    tests=$((tests + ${t:-0}))
    skipped=$((skipped + ${s:-0}))
    if [ "${s:-0}" -gt 0 ]; then
      echo "SKIPPED ${s} test(s) in $f" >&2
    fi
  done
done
echo "reports=$reports tests=$tests skipped=$skipped"
if [ "$reports" -eq 0 ]; then
  echo "INVALID RUN: no surefire report found in: $dirs" >&2
  exit 2
fi
if [ "$skipped" -gt 0 ]; then
  echo "INVALID RUN: $skipped skipped test(s); a green run with skipped > 0 does not count (see QUICK_START.md)" >&2
  exit 1
fi
