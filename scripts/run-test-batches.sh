#!/bin/sh
# MAIN-RED-02 AC1: run the whole backend suite as 4 sequential batches (Postgres connection slots do not allow one JVM to
# keep every Spring context alive) and fail the run when any batch fails or skips a test. Batch 4 is the COMPLEMENT of
# batches 1 to 3 over the test packages, so a new package is picked up automatically.
#   b1 checkout, contract, isolation   b2 tenantauth, tenancy   b3 platform, theme, security   b4 everything else
# usage: scripts/run-test-batches.sh [extra mvn args]        DRY_RUN=1 prints the four -Dtest selections and exits.
set -u
cd "$(dirname "$0")/.." || exit 2
P='**/com/example/perfume_budget'
TESTDIR=src/test/java/com/example/perfume_budget

selection() {
  case $1 in
    b1) echo "$P/checkout/**/*,$P/contract/**/*,$P/isolation/**/*" ;;
    b2) echo "$P/tenantauth/**/*,$P/tenancy/**/*" ;;
    b3) echo "$P/platform/**/*,$P/theme/**/*,$P/security/**/*" ;;
    b4)
      t=""
      for d in "$TESTDIR"/*/; do
        n=$(basename "$d")
        case $n in
          checkout|contract|isolation|tenantauth|tenancy|platform|theme|security) ;;
          *) t="$t$P/$n/**/*," ;;
        esac
      done
      echo "${t}$P/PerfumeBudgetApplicationTests"
      ;;
  esac
}

if [ "${DRY_RUN:-}" = "1" ]; then
  for b in b1 b2 b3 b4; do echo "$b=$(selection $b)"; done
  exit 0
fi

rc=0
for b in b1 b2 b3 b4; do
  rm -rf target/surefire-reports
  echo "== batch $b"
  SHOPFI_TEST_DB_REQUIRED=true sh ./mvnw -B test "-Dtest=$(selection $b)" \
    -Dspring.datasource.hikari.minimum-idle=0 -Dspring.datasource.hikari.idle-timeout=10000 \
    -Dsurefire.forkedProcessExitTimeoutInSeconds=60 -Dsurefire.timeout=1200 "$@" || { echo "BATCH $b FAILED" >&2; rc=1; }
  sh scripts/check-no-skipped.sh target/surefire-reports || { echo "BATCH $b INVALID (skipped or no report)" >&2; rc=1; }
done
exit $rc
