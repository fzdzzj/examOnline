#!/usr/bin/env bash
# =============================================================
# 5000 并发交卷压测 · 数据准备（add-submit-loadtest）
#
# 做三件事：
#   1) 用应用自己的注册接口造一个模板学生 → 取其 BCrypt 哈希，
#      保证 5000 个压测账号的口令哈希与真实注册口径完全一致；
#   2) 把哈希替换进 db/01-prepare.sql 的 __PWD_HASH__ 占位符；
#   3) 执行该 SQL（幂等：先按压测命名空间清理，再重建）。
#
# 前置：dev 实例已在跑（默认 127.0.0.1:8080）、MySQL 主库可连。
# 用法：bash loadtest/prepare-data.sh
# 可覆盖的环境变量见下方「可用环境变量」。
#
# 实现注记（踩过的坑，勿改回）：
#   - 口令经环境变量 MYSQL_PWD 传入：避免 mysql 客户端把口令打进 stderr 警告，
#     从而不必用 `mysql ... 2>&1 | grep -v Warning` —— 那种写法在 `set -o pipefail`
#     下当输出被 grep 全部过滤时会让函数返回 1，直接以 set -e 中止脚本。
#   - 渲染结果落在 target/（本就 gitignored），不用 mktemp + trap rm：
#     本环境的 rm 被安全删除钩子接管，对临时目录外的路径会 fail-closed 报错。
#   - curl 丢弃响应体用 `-o NUL` 而不是 `-o /dev/null`：本机 curl 是 Windows 版
#     （C:\Windows\System32\curl.exe），不认识 POSIX 的 /dev/null，会以
#     CURLE_WRITE_ERROR(23) 失败；脚本开 `set -e` 时表现为"整脚本静默退出 23"。
# =============================================================
set -euo pipefail

# ---------- 可用环境变量 ----------
APP_BASE_URL="${APP_BASE_URL:-http://127.0.0.1:8080}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-13316}"          # 本机 dev 主库端口（从库 13317 只读，造数只写主库）
DB_NAME="${DB_NAME:-exam_online}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-root123}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
TEMPLATE_USER="${TEMPLATE_USER:-lt5k_template}"
TEMPLATE_PASS="${TEMPLATE_PASS:-Load1234!}"
# --------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERED="$SCRIPT_DIR/../target/loadtest-prepare-rendered.sql"

run_sql() {  # 用法: run_sql [-t] [-e "SQL"]  或  run_sql [-t] < file.sql
  # --default-character-set=utf8mb4 是必需的：本机 mysql 客户端默认 character_set_client=gbk，
  # 而本目录 SQL 文件是 UTF-8，混用会让中文串在写入时报 "Data too long for column"（乱码被当成长串）。
  MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    --default-character-set=utf8mb4 "$DB_NAME" "$@"
}

echo "[1/4] 确认 dev 实例可用: $APP_BASE_URL/actuator/health"
code="$(curl -s -o NUL -w '%{http_code}' --noproxy '*' "$APP_BASE_URL/actuator/health")"
[ "$code" = "200" ] || { echo "出错：dev 实例不健康（HTTP $code）——请先启动实例"; exit 1; }
echo "      OK"

echo "[2/4] 取得模板学生口令哈希（用应用注册接口，口径与真实注册一致）"
# 幂等：已存在时注册会失败，此处忽略其失败，直接取库里的哈希
curl -s --noproxy '*' -X POST "$APP_BASE_URL/api/auth/register" \
     -H 'Content-Type: application/json' \
     -d "{\"username\":\"$TEMPLATE_USER\",\"password\":\"$TEMPLATE_PASS\",\"name\":\"压测模板\",\"roleType\":\"STUDENT\"}" \
     >/dev/null || true

PWD_HASH="$(run_sql -N -e "SELECT password FROM users WHERE username='$TEMPLATE_USER';" | tr -d '\r\n')"
case "$PWD_HASH" in
  '$2'*) : ;;
  *) echo "出错：未取到 BCrypt 哈希（得到: '$PWD_HASH'）——模板学生注册失败？"; exit 1 ;;
esac
echo "      哈希前缀 ${PWD_HASH:0:7}...（长度 ${#PWD_HASH}）"

echo "[3/4] 渲染 SQL（替换 __PWD_HASH__ → target/loadtest-prepare-rendered.sql）"
# 用 awk 而非 sed：哈希含 / 与 $，sed 替换串里两者都需转义；awk 的 gsub 用变量传参可规避
awk -v h="$PWD_HASH" '{ gsub(/__PWD_HASH__/, h); print }' "$SCRIPT_DIR/db/01-prepare.sql" > "$RENDERED"

echo "[4/4] 执行数据准备（幂等，可重复运行）"
run_sql -t < "$RENDERED"

echo "完成。后续压测与指标采集都按考试标题 'LOADTEST-5000-并发交卷' 定位 exam_id。"
