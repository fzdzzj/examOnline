#!/usr/bin/env bash
# OpenAPI 契约门槛下防线：仓库根 openapi.yaml 的 paths 键数对比 gate-baseline.json。
# 不替代 OpenApiContractTest 的既有契约校验。任一检查失败以非零退出码结束。
# 用法：check_openapi_paths.sh <openapi.yaml> <gate-baseline.json>
set -u

fail() { echo "OPENAPI PATHS ASSERT FAIL: $1" >&2; exit 1; }

[ "$#" -eq 2 ] || { echo "usage: $0 <openapi.yaml> <gate-baseline.json>" >&2; exit 2; }
YAML="$1"
BASE="$2"

[ -f "$YAML" ] || fail "openapi.yaml not found: $YAML"
[ -s "$YAML" ] || fail "openapi.yaml is empty: $YAML"
[ -f "$BASE" ] || fail "baseline not found: $BASE"

get_baseline() {
  local v
  v=$(grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[0-9]+" "$BASE" | head -n 1 | grep -oE '[0-9]+$')
  [ -n "$v" ] || fail "baseline key missing: $1"
  echo "$v"
}

MIN_PATHS=$(get_baseline openapiPaths)

# 计数：进入顶层 paths: 块后，统计缩进恰两格、以 / 起始的键行；
# 遇到下一个顶层键（非空非注释、顶格）即离开 paths 块。
COUNT=$(awk '
  /^[^[:space:]#]/ { inblock = ($0 ~ /^paths:/) ? 1 : 0; next }
  inblock && /^  \// { n++ }
  END { print n + 0 }
' "$YAML")

[ "$COUNT" -gt 0 ] || fail "parsed 0 paths keys — counting logic or file is broken"
[ "$COUNT" -ge "$MIN_PATHS" ] || fail "openapi paths=$COUNT < baseline $MIN_PATHS (contract paths must never decrease)"

echo "OPENAPI PATHS ASSERT OK: paths=$COUNT (baseline >= $MIN_PATHS)"
