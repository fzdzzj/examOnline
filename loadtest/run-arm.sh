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
# 用法（注入参数用环境变量传给 start-app.sh）。
# **入口门禁在任何停/启应用、造数、复位之前执行**，不通过即非零退出且零副作用：
#   * LT_WRITE_AUTHORIZED=1   必填。显式写入授权——本脚本会停/启应用、造数与复位数据库；
#                             环境变量声明不是用户授权。
#   * JMETER_SSH=user@loadgen 分离模式。另需 LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1
#                             （不同 hostname/容器/VM 名不足以证明不抢同一物理宿主 CPU/磁盘）。
#   * 不设 JMETER_SSH 时**默认拒绝**；确要走历史同机跑法须显式 LT_ALLOW_SAME_HOST=1，
#     该臂只能当「未分离」的历史记录，不得当作分离后的容量结论。
#
#   ARM=default ROUNDS=3 LT_WRITE_AUTHORIZED=1 bash loadtest/run-arm.sh
#   JMETER_SSH=user@loadgen JMETER_REMOTE_HOME=/opt/apache-jmeter \
#     LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1 LT_WRITE_AUTHORIZED=1 \
#     ARM=default ROUNDS=3 bash loadtest/run-arm.sh
#
# 双宿主（isolate-submit-load-generator）：本脚本必须跑在 **SUT 宿主**（它要停/启应用、造数、复位），
#   JMeter 交给负载宿主（隔离与凭据由人工/安全环境变量提供，不写进仓库）。
#
# 失败传播：任一硬失败（JMeter 非零退出 / 队列未归零 / 排空超时 / 产物未回收）的轮会被本脚本
#   排除出臂级中位数；只要本臂有失败轮，脚本以非零退出，不允许把失败轮报成成功。
#
# 产物：target/loadtest/ 下 <ARM>-r1..rN（正式轮）、<ARM>-w1..wN（预热轮）各一套；
#       臂级汇总在脚本末尾用 compare-runs.py 打印。
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT="$(cd "$SCRIPT_DIR/.." && pwd)"
WT_WIN="$(cd "$WT" && pwd -W)"
OUT="$WT/target/loadtest"

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

# 共享前置（与 run-loadtest.sh 同一套判定；可被替身测试单独 source 后直接驱动）。
# shellcheck source=loadtest/lib-loadtest.sh
. "$SCRIPT_DIR/lib-loadtest.sh"

# ============================================================
# 入口门禁：必须在**任何**应用生命周期 / 造数 / 复位之前。
#
# 修复的缺口：此前双宿主校验留在 run-loadtest.sh（单轮入口），于是本脚本会先
#   stop-app → start-app → prepare-data，再在单轮里发现配置无效并拒绝——拒绝得太晚，
#   副作用（停/启应用、造数）已经发生，且缺省 JMETER_SSH 的调用会静默进入真实执行。
#
# 现在的顺序：判定 → （通过才）mkdir 日志、stop/start-app、prepare-data、reset。
# 不通过：非零退出，且 stop-app / start-app / prepare-data / 04-reset.sql 一次都不会被调用。
# ============================================================
if ! lt_preflight_isolation; then
  echo "!! 臂 $ARM 未启动：入口门禁拒绝（宿主配置 / 物理隔离声明 / 显式写入授权）。" >&2
  echo "   已确认未调用：loadtest/stop-app.sh、loadtest/start-app.sh、loadtest/prepare-data.sh、db/04-reset.sql" >&2
  exit 1
fi

mkdir -p "$OUT"

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

# 跑法必须显式出现在臂日志里：同机采样只能当历史记录，不得当作分离后的结论。
# （门禁已在文件顶部执行；能走到这里说明配置有效，且同机模式是**显式**选择的。）
if [ -z "${JMETER_SSH:-}" ]; then
  echo "!! 同机历史跑法（未分离）：本臂只能作为历史记录，不得当作分离后的容量结论" | tee -a "$ARM_LOG"
fi
{ echo "# 跑法=$([ -n "${JMETER_SSH:-}" ] && echo '双宿主（已分离）' || echo '同机（未分离）')"; \
  echo "# APP_BASE_URL=${APP_BASE_URL:-(默认 http://127.0.0.1:8080)}"; \
  echo "# JMETER_SSH=${JMETER_SSH:-<未设置=同机，未分离>}  JMETER_REMOTE_HOME=${JMETER_REMOTE_HOME:-(不适用)}"; \
  echo "# 物理隔离声明 LOADGEN_PHYSICAL_ISOLATION_ATTESTED=${LOADGEN_PHYSICAL_ISOLATION_ATTESTED:-0}  写入授权 LT_WRITE_AUTHORIZED=${LT_WRITE_AUTHORIZED:-0}"; } | tee -a "$ARM_LOG"

echo "=== [臂 $ARM] 重启实例（容量参数是启动期读取的） ==="
bash "$SCRIPT_DIR/stop-app.sh" 2>&1 | tee -a "$ARM_LOG"
bash "$SCRIPT_DIR/start-app.sh" 2>&1 | tee -a "$ARM_LOG"

echo "=== [臂 $ARM] 造数（幂等；已存在则秒回） ==="
bash "$SCRIPT_DIR/prepare-data.sh" 2>&1 | tail -5 | tee -a "$ARM_LOG"

# 逐轮成败记录：失败轮一律排除出臂级中位数（失败轮不参与 compare-runs.py 的判定）。
OK_ROUNDS=()
FAILED_ROUNDS=()

for i in $(seq 1 "$ROUNDS"); do
  echo
  echo "================ [臂 $ARM] 第 $i/$ROUNDS 轮 ================" | tee -a "$ARM_LOG"
  ROUND_TAG="$ARM-r$i"
  round_failed=0

  # 轮前队列必须归零，否则上一轮积压会污染本轮落库时效 ⇒ 归零失败即本轮作废，不再往下跑。
  if ! assert_queues_zero; then
    echo "      !! 轮前队列未归零，本轮作废（不得作为容量结论）" | tee -a "$ARM_LOG"
    FAILED_ROUNDS+=("$ROUND_TAG")
    continue
  fi
  reset_data

  echo "--- 预热轮（G4：冷 JVM 的 P99 比热 JVM 高 68%，不预热就等于在量 JIT）---"
  set +e
  TAG="$ARM-w$i" SUBMIT_THREADS="$WARMUP_THREADS" SUBMIT_RAMP="$WARMUP_SUBMIT_RAMP" \
    LOGIN_RAMP="$WARMUP_LOGIN_RAMP" \
    bash "$SCRIPT_DIR/run-loadtest.sh" 2>&1 | tee -a "$ARM_LOG" \
    | grep -E '===|P99|失败|已排空|已到本机基线|超时|积压归零|本轮判定'
  warm_rc=${PIPESTATUS[0]}
  set -e
  # 预热失败 ⇒ 正式轮会半冷（§8.1：半冷轮不得悄悄混入容量结论），本轮作废。
  if [ "$warm_rc" -ne 0 ]; then
    echo "      !! 预热轮 $ARM-w$i 失败（exit=$warm_rc）——正式轮会半冷，本轮作废" | tee -a "$ARM_LOG"
    round_failed=1
  fi

  echo "--- 预热后复位（预热轮的 500 笔会把答卷推到 status=2）---"
  if ! assert_queues_zero; then round_failed=1; fi
  reset_data

  echo "--- 正式轮 TAG=$ROUND_TAG ---"
  set +e
  TAG="$ROUND_TAG" bash "$SCRIPT_DIR/run-loadtest.sh" 2>&1 | tee -a "$ARM_LOG" \
    | grep -E '===|P99|平均延迟|失败|到达窗口|已排空|已到本机基线|超时|积压归零|丢单|persist_tail|本轮判定'
  off_rc=${PIPESTATUS[0]}
  set -e
  if [ "$off_rc" -ne 0 ]; then
    echo "      !! 正式轮 $ROUND_TAG 失败（exit=$off_rc）——不进入臂级中位数" | tee -a "$ARM_LOG"
    round_failed=1
  fi

  if [ "$round_failed" -eq 0 ]; then
    OK_ROUNDS+=("$ROUND_TAG")
    echo "--- 该轮原始产物已落盘：target/loadtest/*-$ROUND_TAG.* ---"
  else
    FAILED_ROUNDS+=("$ROUND_TAG")
  fi
done

echo
echo "=== [臂 $ARM] 臂级汇总（中位数判定） ===" | tee -a "$ARM_LOG"
# 只有成功轮进入中位数；失败轮的 tag 不传给 compare-runs.py。
TAGS=""
if [ "${#OK_ROUNDS[@]}" -gt 0 ]; then
  TAGS="$(IFS=,; echo "${OK_ROUNDS[*]}")"
fi
if [ "${#FAILED_ROUNDS[@]}" -gt 0 ]; then
  echo "!! 本臂 ${#FAILED_ROUNDS[@]} 轮失败：${FAILED_ROUNDS[*]} —— 已排除，不参与中位数，也不得报成达标/未达标" | tee -a "$ARM_LOG"
fi
if [ -n "$TAGS" ]; then
  "$PYTHON_BIN" "$WT_WIN/loadtest/compare-runs.py" "$ARM=$TAGS" 2>&1 | tee -a "$ARM_LOG"
else
  echo "!! 本臂无成功轮，不能给臂级中位数（不得报成达标/未达标）" | tee -a "$ARM_LOG"
fi

echo
if [ "${#FAILED_ROUNDS[@]}" -gt 0 ] || [ -z "$TAGS" ]; then
  echo "!! 臂 $ARM 判定：失败 —— 有轮未通过硬失败传播检查，本臂不得作为容量结论" | tee -a "$ARM_LOG"
  echo "逐轮明细日志：$ARM_LOG"
  exit 1
fi
echo "臂 $ARM 完成：$(date '+%Y-%m-%d %H:%M:%S %z')" | tee -a "$ARM_LOG"
echo "逐轮明细日志：$ARM_LOG"
