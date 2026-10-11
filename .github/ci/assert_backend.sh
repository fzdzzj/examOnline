#!/usr/bin/env bash
# 后端 CI 门禁解析断言：surefire 汇总 + BUILD SUCCESS banner 对比 gate-baseline.json。
# 任一被包含检查失败都以非零退出码结束（声明的门禁必须能够失败）。
# 用法：assert_backend.sh <backend-log> <gate-baseline.json>
set -u

fail() { echo "BACKEND GATE ASSERT FAIL: $1" >&2; exit 1; }

[ "$#" -eq 2 ] || { echo "usage: $0 <backend-log> <gate-baseline.json>" >&2; exit 2; }
LOG="$1"
BASE="$2"

[ -f "$LOG" ] || fail "log not found: $LOG"
[ -s "$LOG" ] || fail "log is empty: $LOG"
[ -f "$BASE" ] || fail "baseline not found: $BASE"

get_baseline() {
  local v
  v=$(grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[0-9]+" "$BASE" | head -n 1 | grep -oE '[0-9]+$')
  [ -n "$v" ] || fail "baseline key missing: $1"
  echo "$v"
}

MIN_TESTS=$(get_baseline backendTests)

grep -aq "BUILD SUCCESS" "$LOG" || fail "BUILD SUCCESS banner not found in backend log"

# 汇总行取最后一次出现（单模块 Maven 的 Results 汇总），格式：
# Tests run: N, Failures: F, Errors: E, Skipped: S
LAST=$(grep -aE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+" "$LOG" | tail -n 1)
[ -n "$LAST" ] || fail "no surefire summary line found in backend log"

TESTS=$(printf '%s\n' "$LAST" | grep -oE "Tests run: [0-9]+" | grep -oE "[0-9]+")
FAILS=$(printf '%s\n' "$LAST" | grep -oE "Failures: [0-9]+" | grep -oE "[0-9]+")
ERRS=$(printf '%s\n' "$LAST" | grep -oE "Errors: [0-9]+" | grep -oE "[0-9]+")
SKIPS=$(printf '%s\n' "$LAST" | grep -oE "Skipped: [0-9]+" | grep -oE "[0-9]+")

[ -n "$TESTS" ] || fail "could not parse Tests run count from: $LAST"
[ -n "$FAILS" ] || fail "could not parse Failures count from: $LAST"
[ -n "$ERRS" ] || fail "could not parse Errors count from: $LAST"

# Skipped 只记录不硬断言零：exportContract 开关使 OpenApiContractTest 同口径出现 Skipped: 1
[ "$FAILS" = "0" ] || fail "Failures=$FAILS (must be 0)"
[ "$ERRS" = "0" ] || fail "Errors=$ERRS (must be 0)"
[ "$TESTS" -ge "$MIN_TESTS" ] || fail "Tests run=$TESTS < baseline $MIN_TESTS (test count must never decrease)"

echo "BACKEND GATE ASSERT OK: Tests run=$TESTS, Failures=$FAILS, Errors=$ERRS, Skipped=${SKIPS:-?} (baseline >= $MIN_TESTS)"
