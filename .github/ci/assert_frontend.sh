#!/usr/bin/env bash
# 前端 CI 门禁解析断言：三份门禁日志非空、type-check 双真实 banner（vue-tsc 与 tsc）、
# vitest passed 数对比 gate-baseline.json。
# 任一被包含检查失败都以非零退出码结束（声明的门禁必须能够失败）。
# 用法：assert_frontend.sh <lint-log> <typecheck-log> <test-log> <gate-baseline.json>
set -u

fail() { echo "FRONTEND GATE ASSERT FAIL: $1" >&2; exit 1; }

[ "$#" -eq 4 ] || { echo "usage: $0 <lint-log> <typecheck-log> <test-log> <gate-baseline.json>" >&2; exit 2; }
LOG_LINT="$1"
LOG_TSC="$2"
LOG_TEST="$3"
BASE="$4"

for f in "$LOG_LINT" "$LOG_TSC" "$LOG_TEST"; do
  [ -f "$f" ] || fail "log not found: $f"
  [ -s "$f" ] || fail "log is empty: $f"
done
[ -f "$BASE" ] || fail "baseline not found: $BASE"

get_baseline() {
  local v
  v=$(grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[0-9]+" "$BASE" | head -n 1 | grep -oE '[0-9]+$')
  [ -n "$v" ] || fail "baseline key missing: $1"
  echo "$v"
}

MIN_TESTS=$(get_baseline frontendTests)

# 双真实 banner：npm/pnpm 跑 type-check:check 时打印的两条命令行头，
# 证明 vue-tsc（app）与 tsc（config）各自真实执行过（缺任一即链路被截断或日志失真）。
grep -aq "vue-tsc --project ./tsconfig.app.json" "$LOG_TSC" \
  || fail "vue-tsc banner not found in type-check log"
grep -aq "tsc --project ./tsconfig.config.json" "$LOG_TSC" \
  || fail "tsc banner not found in type-check log"

# vitest 汇总（剥 ANSI 色码后解析）：Tests  N passed (N)
SUMMARY=$(sed -e 's/\x1b\[[?0-9;]*[A-Za-z]//g' "$LOG_TEST" | grep -aE "Tests[[:space:]]+[0-9]+ passed" | tail -n 1)
[ -n "$SUMMARY" ] || fail "no vitest summary line found in test log"

PASSED=$(printf '%s\n' "$SUMMARY" | grep -oE "Tests[[:space:]]+[0-9]+" | grep -oE "[0-9]+")
[ -n "$PASSED" ] || fail "could not parse vitest passed count from: $SUMMARY"
[ "$PASSED" -ge "$MIN_TESTS" ] || fail "vitest passed=$PASSED < baseline $MIN_TESTS (test count must never decrease)"

echo "FRONTEND GATE ASSERT OK: vitest passed=$PASSED (baseline >= $MIN_TESTS), lint/typecheck logs non-empty, both banners present"
