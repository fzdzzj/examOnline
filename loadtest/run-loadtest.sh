#!/usr/bin/env bash
# =============================================================
# 5000 并发交卷压测 · 执行与采集（add-submit-loadtest）
#
# 一条命令完成：定位 exam_id → 跑 JMeter（非 GUI）→ 同步采样 MQ 队列深度
#              → 等积压归零 → 打印该轮全部原始数据落盘位置。
#
# 前置：1) docker 栈在跑（exam-mysql-master/slave、exam-rabbitmq、exam-redis）
#       2) dev 实例在跑（默认 127.0.0.1:8080）
#       3) 已执行 loadtest/prepare-data.sh 造好数据
#       4) 环境变量 JMETER_HOME 指向 JMeter 安装目录
#
# 用法：
#   bash loadtest/run-loadtest.sh                      # 正式一轮（5000 并发 / ramp 10s）
#   TAG=dryrun SUBMIT_THREADS=20 SUBMIT_RAMP=2 LOGIN_RAMP=2 bash loadtest/run-loadtest.sh
#
# 可覆盖的环境变量见下方「可用环境变量」。
# 产物（全部落在 target/loadtest/，target/ 本就 gitignored）：
#   jmeter-stdout-<TAG>.log       JMeter 原始标准输出（含 summariser 汇总表）
#   submit-results-<TAG>.csv      交卷逐笔明细（P99 / 错误率的唯一数据源）
#   login-results-<TAG>.csv       登录逐笔明细
#   tokens-<TAG>.csv              setUp 组落盘的用户名+token
#   mq-depth-<TAG>.csv            MQ 队列深度 + 未落库计数时间线（1s 一次）
#   html-<TAG>/                   JMeter HTML 仪表盘
# =============================================================
set -euo pipefail

# ---------- 可用环境变量 ----------
APP_BASE_URL="${APP_BASE_URL:-http://127.0.0.1:8080}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-13316}"
DB_NAME="${DB_NAME:-exam_online}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-root123}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
RABBIT_CONTAINER="${RABBIT_CONTAINER:-exam-rabbitmq}"
SUBMIT_QUEUE="${SUBMIT_QUEUE:-exam.submit.queue}"
EXAM_TITLE="${EXAM_TITLE:-LOADTEST-5000-并发交卷}"
TAG="${TAG:-run}"
SUBMIT_THREADS="${SUBMIT_THREADS:-5000}"
SUBMIT_RAMP="${SUBMIT_RAMP:-10}"
LOGIN_RAMP="${LOGIN_RAMP:-30}"
DRAIN_TIMEOUT_S="${DRAIN_TIMEOUT_S:-180}"      # 等积压归零的上限
# JMETER_HOME 必须是「Windows 绝对路径 + 正斜杠」：bin/jmeter 脚本会把
# $JMETER_HOME/bin/ApacheJMeter.jar 直接交给 java，而 Windows 版 java 不认 /d/... 形态
# （本环境 bash 不做 POSIX→Windows 转换），会报 "Unable to access jarfile"；
# 反斜杠形态又会被 bash 当转义符，故用 D:/... 兼顾两端。
JMETER_HOME="${JMETER_HOME:-D:/develop1/jmeter/apache-jmeter-5.6.3/apache-jmeter-5.6.3}"
JAVA_HOME_FOR_JMETER="${JAVA_HOME_FOR_JMETER:-D:\\develop\\jdk177}"
# JMeter 默认只给 1g 堆（bin/jmeter 的 HEAP 默认值），5000 线程下不够；
# 本机 16G 物理内存还要同时容纳应用 + Docker 栈，故抬到 2g 而不是更高。
JMETER_HEAP="${JMETER_HEAP:--Xms1g -Xmx2g -XX:MaxMetaspaceSize=256m}"
PYTHON_BIN="${PYTHON_BIN:-python}"
# --------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT="$(cd "$SCRIPT_DIR/.." && pwd)"
WT_WIN="$(cd "$WT" && pwd -W)"                  # 交给 JMeter 的必须是 Windows 风格路径
OUT="$WT/target/loadtest"
OUT_WIN="$WT_WIN/target/loadtest"               # 交给 Java / python.exe 的路径必须是 Windows 形态
mkdir -p "$OUT"

TOKENS="$OUT/tokens-$TAG.csv"
RESULTS="$OUT/submit-results-$TAG.csv"
JM_LOG="$OUT/jmeter-stdout-$TAG.log"
MQ_CSV="$OUT/mq-depth-$TAG.csv"

now_ms() { python -c 'import time;print(int(time.time()*1000))'; }

run_sql() {
  # --default-character-set=utf8mb4：本机客户端默认 gbk，与 UTF-8 库/脚本混用会乱码
  MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    --default-character-set=utf8mb4 -N "$DB_NAME" "$@"
}

echo "=== [1/6] 前置检查 ==="
code="$(curl -s -o NUL -w '%{http_code}' --noproxy '*' "$APP_BASE_URL/actuator/health")"
[ "$code" = "200" ] || { echo "出错：dev 实例不健康（HTTP $code）"; exit 1; }
EXAM_ID="$(run_sql -e "SELECT id FROM exams WHERE title='$EXAM_TITLE';" | tr -d '\r\n')"
[ -n "$EXAM_ID" ] || { echo "出错：未找到考试 '$EXAM_TITLE'——请先跑 prepare-data.sh"; exit 1; }
PENDING="$(run_sql -e "SELECT COUNT(*) FROM exam_submissions WHERE exam_id=$EXAM_ID AND status=1;")"
echo "      exam_id=$EXAM_ID 进行中答卷=$PENDING  JMeter=$JMETER_HOME"

# 先删旧文件再让 JMeter 新建：JMeter 只在「文件不存在」时写字段名表头，
# 用 `: >` 截断成 0 字节的空文件它不写表头，导致结果 CSV 无表头、
# analyze-results.py 会把第一行数据当表头、`-g` 生成仪表盘也会报列数不匹配。
rm -f "$TOKENS" "$RESULTS" "$MQ_CSV" "$JM_LOG"

echo "=== [2/6] 启动 MQ 深度采样（1s 一次，只读） ==="
(
  while true; do
    ts="$(now_ms)"
    depth="$(docker exec "$RABBIT_CONTAINER" rabbitmqctl list_queues name messages_ready messages_unacknowledged 2>/dev/null \
             | awk -v q="$SUBMIT_QUEUE" '$1==q {printf "%s|%s", $2, $3}')" || true
    [ -n "$depth" ] || depth="NA|NA"
    missing="$(run_sql -e "SELECT COUNT(*) FROM exam_submissions WHERE exam_id=$EXAM_ID AND status=2 AND answers IS NULL;" 2>/dev/null | tr -d '\r\n')" || true
    echo "$ts|${depth%%|*}|${depth##*|}|${missing:-NA}" >> "$MQ_CSV"
    sleep 1
  done
) &
POLLER_PID=$!
# 退出时务必杀掉采样器及其 docker exec 子进程，否则它会一直占着终端
stop_poller() {
  pkill -P "$POLLER_PID" 2>/dev/null || true
  kill "$POLLER_PID" 2>/dev/null || true
}
trap stop_poller EXIT

echo "=== [3/6] 运行 JMeter（非 GUI） ==="
T0="$(now_ms)"
HEAP="$JMETER_HEAP" JAVA_HOME="$JAVA_HOME_FOR_JMETER" bash "$JMETER_HOME/bin/jmeter" -n \
  -t "$WT_WIN/loadtest/jmeter/submit-5000.jmx" \
  -Jhost=127.0.0.1 -Jport=8080 \
  -Jexam.id="$EXAM_ID" \
  -Jsubmit.threads="$SUBMIT_THREADS" -Jsubmit.ramp="$SUBMIT_RAMP" \
  -Jlogin.threads="$SUBMIT_THREADS" -Jlogin.ramp="$LOGIN_RAMP" \
  -Jtokens.file="$WT_WIN/target/loadtest/tokens-$TAG.csv" \
  -Jresults.file="$WT_WIN/target/loadtest/submit-results-$TAG.csv" \
  -Jjmeter.save.saveservice.print_field_names=true \
  > "$JM_LOG" 2>&1 || echo "警告：JMeter 退出码非 0（见 $JM_LOG）"
T1="$(now_ms)"
echo "      提交阶段墙钟窗口: $T0 → $T1 （$(python -c "print(f'{($T1-$T0)/1000:.1f}')")s）"

echo "=== [4/6] 等积压归零（队列 ready+unacked=0 且 未落库计数=0） ==="
DEADLINE=$(( $(now_ms) + DRAIN_TIMEOUT_S * 1000 ))
while :; do
  # `|| true` 必需：process substitution 无尾随换行时 read 返回非 0（EOF），
  # 在 set -e 下会直接中止整个脚本、并被静默吞掉。
  read -r ready unack < <(docker exec "$RABBIT_CONTAINER" rabbitmqctl list_queues name messages_ready messages_unacknowledged 2>/dev/null \
      | awk -v q="$SUBMIT_QUEUE" '$1==q {printf "%s %s\n", $2, $3}') || true
  miss="$(run_sql -e "SELECT COUNT(*) FROM exam_submissions WHERE exam_id=$EXAM_ID AND status=2 AND answers IS NULL;" | tr -d '\r\n')"
  [ "${ready:-1}" = "0" ] && [ "${unack:-1}" = "0" ] && [ "${miss:-1}" = "0" ] && break
  [ "$(now_ms)" -ge "$DEADLINE" ] && { echo "      超时（${DRAIN_TIMEOUT_S}s）：ready=$ready unack=$unack 未落库=$miss"; break; }
  sleep 1
done
T2="$(now_ms)"
echo "      积压归零: $T2 （提交阶段结束后 $(python -c "print(f'{($T2-$T1)/1000:.1f}')")s）"
stop_poller
trap - EXIT

echo "=== [5/6] 采集 DB 指标 ==="
MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
  --default-character-set=utf8mb4 -t "$DB_NAME" < "$SCRIPT_DIR/db/02-metrics.sql" \
  | tee "$OUT/metrics-$TAG.txt"

echo "=== [6/6] 生成 JMeter HTML 仪表盘 ==="
# JMeter 要求输出目录为空/不存在，重跑同一 TAG 时先清掉上一次的仪表盘
[ -e "$OUT/html-$TAG" ] && rm -rf "$OUT/html-$TAG" || true
JAVA_HOME="$JAVA_HOME_FOR_JMETER" bash "$JMETER_HOME/bin/jmeter" -g "$OUT_WIN/submit-results-$TAG.csv" \
  -o "$OUT_WIN/html-$TAG" > "$OUT/jmeter-html-$TAG.log" 2>&1 || echo "警告：HTML 仪表盘生成失败（不影响数据）"

echo
echo "=== 逐笔统计 ==="
"$PYTHON_BIN" "$WT_WIN/loadtest/analyze-results.py" "$OUT_WIN/submit-results-$TAG.csv" || true
echo
echo "本轮原始产物（target/ 已 gitignored）："
echo "  $JM_LOG"
echo "  $RESULTS"
echo "  $MQ_CSV"
echo "  $OUT/metrics-$TAG.txt"
echo "  $OUT/html-$TAG/index.html"
