#!/usr/bin/env bash
# =============================================================
# 单「臂」压测编排（G4：每臂 ≥3 轮、每轮前预热、轮间等 TIME_WAIT 排空）——tune-submit-capacity
#
# 为什么单独成脚本：
#   原资产一条命令跑「一轮」，把「预热 / 排空 / ≥3 轮 / 取中位数」全留给人工纪律，
#   于是报告只能写区间、不能写归因（§5：冷热 JVM 差 68% > 参数效应 <5%）。
#   本脚本把方法学变成可执行动作：换臂 = 重启实例（容量参数是启动期读的），
#   同一臂内每轮 = 复位数据 → 预热轮 → 复位数据 → 正式轮，轮前由 run-loadtest.sh
#   自动等 TIME_WAIT 排空。
#
# 用法（注入参数用环境变量传给 start-app.sh）：
#   ARM=default ROUNDS=3 bash loadtest/run-arm.sh
#   ARM=tuned SERVER_TOMCAT_THREADS_MAX=400 ROUNDS=3 bash loadtest/run-arm.sh
#   ARM=tuned2 SERVER_TOMCAT_THREADS_MAX=400 DB_POOL_MAX=40 ROUNDS=3 bash loadtest/run-arm.sh
#
# 产物：target/loadtest/ 下 <ARM>-r1..rN（正式轮）、<ARM>-w1..wN（预热轮）各一套；
#       臂级汇总在脚本末尾用 compare-runs.py 打印。
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT="$(cd "$SCRIPT_DIR/.." && pwd)"
WT_WIN="$(cd "$WT" && pwd -W)"
OUT="$WT/target/loadtest"
mkdir -p "$OUT"

ARM="${ARM:?必须给 ARM（臂名，决定产物前缀与实例日志名）}"
ROUNDS="${ROUNDS:-3}"
WARMUP_THREADS="${WARMUP_THREADS:-500}"
WARMUP_SUBMIT_RAMP="${WARMUP_SUBMIT_RAMP:-3}"
WARMUP_LOGIN_RAMP="${WARMUP_LOGIN_RAMP:-5}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-13316}"
DB_NAME="${DB_NAME:-exam_online}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-root123}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
RABBIT_CONTAINER="${RABBIT_CONTAINER:-exam-rabbitmq}"
SUBMIT_QUEUE="${SUBMIT_QUEUE:-exam.submit.queue}"
PYTHON_BIN="${PYTHON_BIN:-python}"
ARM_LOG="$OUT/arm-$ARM.log"

run_sql_file() {
  MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    --default-character-set=utf8mb4 -t "$DB_NAME" < "$1"
}

# 复位 + 自证（in_progress 须 5000，submitted/dedups/answers_nonnull 须 0）
reset_data() {
  echo "      --- 复位压测数据（04-reset.sql 自证） ---"
  run_sql_file "$SCRIPT_DIR/db/04-reset.sql" | sed 's/^/      /'
}

# 队列归零断言（不清队列：清队列会真实丢消息，只断言）
assert_queues_zero() {
  local ready unack
  read -r ready unack < <(docker exec "$RABBIT_CONTAINER" rabbitmqctl list_queues name messages_ready messages_unacknowledged 2>/dev/null \
      | awk -v q="$SUBMIT_QUEUE" '$1==q {printf "%s %s\n", $2, $3}') || true
  echo "      队列 $SUBMIT_QUEUE ready=${ready:-NA} unack=${unack:-NA}"
  if [ "${ready:-1}" != "0" ] || [ "${unack:-1}" != "0" ]; then
    echo "      !! 队列未归零：本轮落库时效会被上一轮积压污染，禁止把它当容量结论"
    return 1
  fi
  return 0
}

{
  echo "############################################################"
  echo "# 臂 $ARM 开始：$(date '+%Y-%m-%d %H:%M:%S %z')  轮数=$ROUNDS"
  echo "# SERVER_TOMCAT_THREADS_MAX=${SERVER_TOMCAT_THREADS_MAX:-(未注入)}"
  echo "# DB_POOL_MAX=${DB_POOL_MAX:-(未注入)} SLAVE_DB_POOL_MAX=${SLAVE_DB_POOL_MAX:-(未注入)}"
  echo "# RABBIT_BATCH_CONCURRENCY=${RABBIT_BATCH_CONCURRENCY:-(未注入)}"
  echo "############################################################"
} | tee -a "$ARM_LOG"

echo "=== [臂 $ARM] 重启实例（容量参数是启动期读取的） ==="
bash "$SCRIPT_DIR/stop-app.sh" 2>&1 | tee -a "$ARM_LOG"
bash "$SCRIPT_DIR/start-app.sh" 2>&1 | tee -a "$ARM_LOG"

echo "=== [臂 $ARM] 造数（幂等；已存在则秒回） ==="
bash "$SCRIPT_DIR/prepare-data.sh" 2>&1 | tail -5 | tee -a "$ARM_LOG"

for i in $(seq 1 "$ROUNDS"); do
  echo
  echo "================ [臂 $ARM] 第 $i/$ROUNDS 轮 ================" | tee -a "$ARM_LOG"

  assert_queues_zero || true
  reset_data

  echo "--- 预热轮（G4：冷 JVM 的 P99 比热 JVM 高 68%，不预热就等于在量 JIT）---"
  TAG="$ARM-w$i" SUBMIT_THREADS="$WARMUP_THREADS" SUBMIT_RAMP="$WARMUP_SUBMIT_RAMP" \
    LOGIN_RAMP="$WARMUP_LOGIN_RAMP" \
    bash "$SCRIPT_DIR/run-loadtest.sh" 2>&1 | tee -a "$ARM_LOG" \
    | grep -E '===|P99|失败|已排空|已到本机基线|超时|积压归零' || true

  echo "--- 预热后复位（预热轮的 500 笔会把答卷推到 status=2）---"
  assert_queues_zero || true
  reset_data

  echo "--- 正式轮 TAG=$ARM-r$i ---"
  TAG="$ARM-r$i" bash "$SCRIPT_DIR/run-loadtest.sh" 2>&1 | tee -a "$ARM_LOG" \
    | grep -E '===|P99|平均延迟|失败|到达窗口|已排空|已到本机基线|超时|积压归零|丢单|persist_tail' || true

  echo "--- 该轮原始产物已落盘：target/loadtest/*-$ARM-r$i.* ---"
done

echo
echo "=== [臂 $ARM] 臂级汇总（中位数判定） ===" | tee -a "$ARM_LOG"
TAGS=""
for i in $(seq 1 "$ROUNDS"); do TAGS="$TAGS,$ARM-r$i"; done
TAGS="${TAGS#,}"
"$PYTHON_BIN" "$WT_WIN/loadtest/compare-runs.py" "$ARM=$TAGS" 2>&1 | tee -a "$ARM_LOG"

echo
echo "臂 $ARM 完成：$(date '+%Y-%m-%d %H:%M:%S %z')" | tee -a "$ARM_LOG"
echo "逐轮明细日志：$ARM_LOG"
