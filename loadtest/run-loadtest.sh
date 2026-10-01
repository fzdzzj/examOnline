#!/usr/bin/env bash
# =============================================================
# 5000 并发交卷压测 · 执行与采集（add-submit-loadtest → tune-submit-capacity 修订）
#
# 一条命令完成：轮前等 TIME_WAIT 排空 → 取 prom-before 快照 → 启动持续采样器
#              → 跑 JMeter（非 GUI）→ 立刻取 prom-after 快照 → 等积压归零
#              → 采集 DB 指标 → 打印该轮全部原始数据落盘位置。
#
# 前置：1) docker 栈在跑（exam-mysql-master/slave、exam-rabbitmq）
#       2) dev 实例在跑（默认 127.0.0.1:8080），建议用 loadtest/start-app.sh 起（含参数注入）
#       3) 已执行 loadtest/prepare-data.sh 造好数据
#       4) 环境变量 JMETER_HOME 指向 JMeter 安装目录
#
# 用法：
#   bash loadtest/run-loadtest.sh                      # 正式一轮（5000 并发 / ramp 10s）
#   TAG=dryrun SUBMIT_THREADS=20 SUBMIT_RAMP=2 LOGIN_RAMP=2 bash loadtest/run-loadtest.sh
#
# 本变更（G4/G5）相对原资产的三处修订：
#   G4-a 轮前等 TIME_WAIT 排空（原为人工纪律，现为脚本动作，过程写 timewait-*.csv）
#   G4-b prom-before / prom-after 成对快照 ⇒ 服务端 sum/count 可算「本轮净增量」，
#        不再依赖 compare-runs.py「扣减前一累计轮」的启发式（且 prom-after 必须紧跟
#        JMeter 退出取，因为 Micrometer TimeWindowMax 有 2 分钟衰减窗口）
#   G5   持续采样落两条时间线（取峰值而非突发后快照）：
#          sample-<TAG>.csv  应用侧（0.25s）：Tomcat 忙/当前/上限线程、Hikari pending/active/max、
#                            进程与系统 CPU —— 单次 /actuator/prometheus 抓取
#          mq-depth-<TAG>.csv DB/MQ 侧（1s）：队列 ready/unacked、未落库计数、MySQL Threads_connected/Running
#
# 可覆盖的环境变量见下方「可用环境变量」。
# 产物（全部落在 target/loadtest/，target/ 本就 gitignored）：
#   jmeter-stdout-<TAG>.log       JMeter 原始标准输出（含 summariser 汇总表）
#   submit-results-<TAG>.csv      交卷逐笔明细（P99 / 错误率的唯一数据源）
#   login-results-<TAG>.csv       登录逐笔明细
#   tokens-<TAG>.csv              setUp 组落盘的用户名+token
#   mq-depth-<TAG>.csv            DB/MQ 侧时间线（1s 一次）
#   sample-<TAG>.csv              应用侧时间线（G5，0.25s 一次）
#   timewait-<TAG>.csv            轮前 TIME_WAIT 排空过程
#   prom-before-<TAG>.txt         JMeter 启动前 Prometheus 裸文本
#   prom-after-<TAG>.txt          JMeter 退出瞬间 Prometheus 裸文本
#   metrics-<TAG>.txt             DB 硬指标（丢单/落库时效）
#   html-<TAG>/                   JMeter HTML 仪表盘
# =============================================================
set -euo pipefail

# ---------- 可用环境变量 ----------
APP_BASE_URL="${APP_BASE_URL:-http://127.0.0.1:8080}"
# JMeter 的**实际请求目标**（isolate-submit-load-generator）：从 APP_BASE_URL 推导，SUT_HOST/SUT_PORT 可显式覆盖。
# 历史版本在 [4/8] 里硬编码 `-Jhost=127.0.0.1 -Jport=8080`，于是「只改 APP_BASE_URL」并不能把压测目标指向
# 另一台宿主——这正是本变更要修的缺陷：JMeter 的目标必须与本脚本第 1/3/5 步真正访问的地址同源。
_sut_authority="${APP_BASE_URL#*://}"; _sut_authority="${_sut_authority%%/*}"
case "$_sut_authority" in
  *:*) _sut_host_default="${_sut_authority%%:*}"; _sut_port_default="${_sut_authority##*:}";;
  *)   _sut_host_default="$_sut_authority";       _sut_port_default="80";;
esac
SUT_HOST="${SUT_HOST:-$_sut_host_default}"
SUT_PORT="${SUT_PORT:-$_sut_port_default}"
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
# G5 采样：应用侧（线程水位/连接池/CPU）走单次 /actuator/prometheus 抓取，每次约 0.3s，
# 所以间隔可以给到 0.25s；DB/MQ 侧（Threads_connected、队列深度、未落库计数）每次要
# 跑 2~3 个外部进程（实测 ~1.5s），故单独一条 1s 的时间线，不拖慢前者。
SAMPLE_INTERVAL_S="${SAMPLE_INTERVAL_S:-0.25}"
ENV_SAMPLE_INTERVAL_S="${ENV_SAMPLE_INTERVAL_S:-1}"
RABBITMQ_USER="${RABBITMQ_USER:-exam}"          # docker-compose.yml 的 RABBITMQ_DEFAULT_USER 默认值
RABBITMQ_PASS="${RABBITMQ_PASS:-exam123}"
RABBITMQ_MGMT_URL="${RABBITMQ_MGMT_URL:-http://127.0.0.1:15672}"
TIME_WAIT_MAX="${TIME_WAIT_MAX:-1500}"         # 轮前 TIME_WAIT 低于该值即认为排空（本机空闲基线约 80）
TIME_WAIT_FLOOR_ACCEPT="${TIME_WAIT_FLOOR_ACCEPT:-3000}" # 连续 30s 不再下降且低于此值 = 已到本机基线（不再干等）
TIME_WAIT_TIMEOUT_S="${TIME_WAIT_TIMEOUT_S:-300}"
SKIP_TIME_WAIT_WAIT="${SKIP_TIME_WAIT_WAIT:-0}" # 1 = 跳过轮前排空（仅调试用，不得用于容量结论）
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
# ---- 双宿主：把 JMeter 放到负载宿主上运行（isolate-submit-load-generator）----
# 不设 JMETER_SSH = 同机历史跑法：该轮只能当「未分离」的历史记录，不得当作分离后的达标/未达标结论。
# 设 JMETER_SSH=user@loadgen 后：JMeter 在负载宿主运行，本脚本（控制端）留在 SUT 侧跑采样器；
#   轮前 TIME_WAIT 排空改查负载宿主的 netstat（临时端口耗尽发生在压测机一侧）；
#   JMX 推到负载宿主，原始产物（逐笔 CSV / token CSV / 日志）拉回 $OUT 并核验非空。
# 负载宿主按 POSIX 约定：LOADGEN_DIR 用 POSIX 路径，需有 ssh/scp/netstat 与 JMeter。
JMETER_SSH="${JMETER_SSH:-}"
LOADGEN_DIR="${LOADGEN_DIR:-/tmp/loadtest-$TAG}"
JMETER_REMOTE_HOME="${JMETER_REMOTE_HOME:-}"
JAVA_HOME_FOR_JMETER_REMOTE="${JAVA_HOME_FOR_JMETER_REMOTE:-}"
JMETER_REMOTE_HEAP="${JMETER_REMOTE_HEAP:-$JMETER_HEAP}"
# 物理隔离声明（人工）：不同 hostname/容器/VM 名**不足以**证明不抢同一物理宿主 CPU/磁盘，故要求显式声明；
# 未声明即 fail-closed（见 lib-loadtest.sh 的 lt_preflight_isolation）。
LOADGEN_PHYSICAL_ISOLATION_ATTESTED="${LOADGEN_PHYSICAL_ISOLATION_ATTESTED:-0}"
# --------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT="$(cd "$SCRIPT_DIR/.." && pwd)"
WT_WIN="$(cd "$WT" && pwd -W)"                  # 交给 JMeter 的必须是 Windows 风格路径
OUT="$WT/target/loadtest"
OUT_WIN="$WT_WIN/target/loadtest"               # 交给 Java / python.exe 的路径必须是 Windows 形态
mkdir -p "$OUT"

# 共享前置（前置门禁 / 毫秒时间戳 / TIME_WAIT 探测）。
# 抽到 lib-loadtest.sh 是为了让 run-arm.sh 与本脚本用**同一套**判定，且能被替身测试
# 单独 source 后直接驱动真实函数（而不是靠 grep 文本证明控制流）。
# shellcheck source=loadtest/lib-loadtest.sh
. "$SCRIPT_DIR/lib-loadtest.sh"

TOKENS="$OUT/tokens-$TAG.csv"
RESULTS="$OUT/submit-results-$TAG.csv"
JM_LOG="$OUT/jmeter-stdout-$TAG.log"
MQ_CSV="$OUT/mq-depth-$TAG.csv"
SAMPLE_CSV="$OUT/sample-$TAG.csv"
TW_CSV="$OUT/timewait-$TAG.csv"
PROM_BEFORE="$OUT/prom-before-$TAG.txt"
PROM_AFTER="$OUT/prom-after-$TAG.txt"

# 时间戳 now_ms 定义在 lib-loadtest.sh：优先 bash5 的 EPOCHREALTIME（0 次 fork），
# 在 Git Bash 4.4（无 EPOCHREALTIME，set -u 下会 unbound variable）回退 GNU `date +%s%3N`。
# 回退路径多一次进程 spawn ⇒ 采样周期会变长，属口径变化，实测开销见 loadtest/README.md。

# ---- 失败传播（isolate-submit-load-generator）----
# 硬失败（JMeter 非零退出 / 队列未归零 / 排空超时 / 远端产物没拉回来）必须让本轮以非零退出。
# 否则 run-arm.sh 的管道与 `|| true` 会把失败轮当成成功轮吸进臂级中位数，报成「达标/未达标」。
ROUND_FAILED=0
fail_round() { echo "!!! 本轮失败：$*" >&2; ROUND_FAILED=1; }

# ---- 双宿主：宿主身份 / 物理隔离 / 写入授权 → 统一走 lib-loadtest.sh 的 lt_preflight_isolation ----
# （同一判定必须被 run-arm.sh 的**入口**复用，故不再在本文件内联一份，避免两处漂移。）

# ---- 双宿主：在负载宿主上跑 JMeter，并把原始产物拉回并核验 ----
# 为什么用 ssh/scp 而不是共享盘：编排必须能证明「JMeter 真的在另一台宿主跑、结果真的传回来了」，
# 共享盘/同一目录会把「其实没传」藏起来。缺 ssh/scp 一律 fail-closed，不退回同机。
run_jmeter_remote() {
  command -v scp >/dev/null 2>&1 || { echo "出错：JMETER_SSH 已设置但本机无 scp"; return 1; }
  [ -n "$JMETER_REMOTE_HOME" ] || { echo "出错：JMETER_SSH 模式下必须给 JMETER_REMOTE_HOME（负载宿主上的 JMeter 目录）"; return 1; }
  local remote_tokens remote_results remote_log remote_jmx remote_cmd rc
  remote_tokens="$LOADGEN_DIR/tokens-$TAG.csv"
  remote_results="$LOADGEN_DIR/submit-results-$TAG.csv"
  remote_log="$LOADGEN_DIR/jmeter-stdout-$TAG.log"
  remote_jmx="$LOADGEN_DIR/submit-5000.jmx"
  ssh "$JMETER_SSH" "mkdir -p '$LOADGEN_DIR' && rm -f '$remote_tokens' '$remote_results' '$remote_log'" || return 1
  scp -q "$SCRIPT_DIR/jmeter/submit-5000.jmx" "$JMETER_SSH:$remote_jmx" || return 1
  remote_cmd="HEAP='$JMETER_REMOTE_HEAP' JAVA_HOME='$JAVA_HOME_FOR_JMETER_REMOTE' bash '$JMETER_REMOTE_HOME/bin/jmeter' -n -t '$remote_jmx' -Jhost='$SUT_HOST' -Jport='$SUT_PORT' -Jexam.id='$EXAM_ID' -Jsubmit.threads='$SUBMIT_THREADS' -Jsubmit.ramp='$SUBMIT_RAMP' -Jlogin.threads='$SUBMIT_THREADS' -Jlogin.ramp='$LOGIN_RAMP' -Jtokens.file='$remote_tokens' -Jresults.file='$remote_results' -Jjmeter.save.saveservice.print_field_names=true > '$remote_log' 2>&1"
  echo "      负载宿主执行：ssh $JMETER_SSH ... jmeter -Jhost=$SUT_HOST -Jport=$SUT_PORT"
  ssh "$JMETER_SSH" "$remote_cmd"; rc=$?
  # 无论成败都把远端原始产物拉回（失败轮的原始数据同样要留档，只是不得进入判定）
  scp -q "$JMETER_SSH:$remote_results" "$RESULTS" 2>/dev/null || echo "警告：远端 $remote_results 未拉回"
  scp -q "$JMETER_SSH:$remote_tokens"  "$TOKENS"  2>/dev/null || true
  scp -q "$JMETER_SSH:$remote_log"     "$JM_LOG"  2>/dev/null || true
  [ "$rc" -ne 0 ] && return "$rc"
  # 结果回收的可验证判据：逐笔 CSV 必须存在且非空（拉回失败/空文件 = 没有可判定的证据）
  if [ ! -s "$RESULTS" ]; then
    echo "出错：远端结果未成功回收（$RESULTS 缺失或为空）"
    return 1
  fi
  echo "      已回收 $(wc -l < "$RESULTS") 行逐笔结果 → $RESULTS"
  return 0
}

run_sql() {
  # --default-character-set=utf8mb4：本机客户端默认 gbk，与 UTF-8 库/脚本混用会乱码
  MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    --default-character-set=utf8mb4 -N "$DB_NAME" "$@"
}

# 一次 awk 扫完整份 /actuator/prometheus 裸文本，按固定列序输出一行 CSV 的「指标部分」。
# 为什么不逐指标各调一次：本机每次进程 spawn ~0.4s，9 个指标各起一次 awk 就把
# 采样周期顶到 4s+（实测过：19.6s 窗口只采到 2 个样本）。取不到一律输出 NA ——
# 「NA」（端点不可用）与「0」（真的是 0）在证据里必须可分。
parse_app_metrics() {
  awk '
    index($0, "tomcat_threads_busy_threads")          == 1 { busy = $NF }
    index($0, "tomcat_threads_current_threads")       == 1 { cur  = $NF }
    index($0, "tomcat_threads_config_max_threads")    == 1 { cmax = $NF }
    index($0, "hikaricp_connections_pending") == 1 && index($0, "pool=\"master\"") > 0 { hp = $NF }
    index($0, "hikaricp_connections_active")  == 1 && index($0, "pool=\"master\"") > 0 { ha = $NF }
    index($0, "hikaricp_connections_max")     == 1 && index($0, "pool=\"master\"") > 0 { hm = $NF }
    index($0, "process_cpu_usage")                    == 1 { pcpu = $NF }
    index($0, "system_cpu_usage")                     == 1 { scpu = $NF }
    END {
      printf "%s,%s,%s,%s,%s,%s,%s,%s\n",
             (busy == "" ? "NA" : busy), (cur == "" ? "NA" : cur), (cmax == "" ? "NA" : cmax),
             (hp == "" ? "NA" : hp), (ha == "" ? "NA" : ha), (hm == "" ? "NA" : hm),
             (pcpu == "" ? "NA" : pcpu), (scpu == "" ? "NA" : scpu)
    }
  '
}

# TIME_WAIT 探测（G4）→ 统一走 lib-loadtest.sh 的 lt_tw_count。
# 旧实现把 SSH 非零 / 远端无 netstat / 非法输出一律 `n=0`，会把「探测失败」伪装成
# 「确实排空」，从而放行一轮根本没排空的压测；现在探测失败**返回非零**，由调用方让本轮失败。
# 双宿主下查的是**负载宿主**的 TIME_WAIT（临时端口耗尽发生在压测机一侧）。

echo "=== [1/8] 前置检查 ==="
# `|| echo 000` 必需：set -e 下 curl 连不上（exit 7）会直接终止脚本，连「实例不健康」这句
# 提示都打不出来 —— 实测表现为「只打印了本行标题就退出」，极易被误判成脚本坏了。
code="$(curl -s -o NUL -w '%{http_code}' --noproxy '*' --max-time 5 "$APP_BASE_URL/actuator/health" 2>/dev/null || echo 000)"
[ "$code" = "200" ] || { echo "出错：dev 实例不健康（HTTP $code）——先跑 loadtest/start-app.sh"; exit 1; }
EXAM_ID="$(run_sql -e "SELECT id FROM exams WHERE title='$EXAM_TITLE';" | tr -d '\r\n')"
[ -n "$EXAM_ID" ] || { echo "出错：未找到考试 '$EXAM_TITLE'——请先跑 prepare-data.sh"; exit 1; }
PENDING="$(run_sql -e "SELECT COUNT(*) FROM exam_submissions WHERE exam_id=$EXAM_ID AND status=1;")"
echo "      exam_id=$EXAM_ID 进行中答卷=$PENDING  JMeter=$JMETER_HOME"
echo "      JMeter 目标=$SUT_HOST:$SUT_PORT  负载端=${JMETER_SSH:-<同机，未分离>}"

# 前置门禁（宿主配置 / 物理隔离声明 / 显式写入授权）：run-arm.sh 的入口已判过一次，
# 这里再判一次是为了让**本脚本被单独调用**时也不会「缺配置却进入真实执行」。
# 未设置 JMETER_SSH 时不再默认放行——必须显式 LT_ALLOW_SAME_HOST=1 才走同机历史跑法。
if ! lt_preflight_isolation; then
  echo "出错：前置门禁未通过（见上），拒绝启动压测"
  exit 1
fi

# 先删旧文件再让 JMeter 新建：JMeter 只在「文件不存在」时写字段名表头，
# 用 `: >` 截断成 0 字节的空文件它不写表头，导致结果 CSV 无表头、
# analyze-results.py 会把第一行数据当表头、`-g` 生成仪表盘也会报列数不匹配。
rm -f "$TOKENS" "$RESULTS" "$MQ_CSV"

echo "=== [2/8] 轮前等 TIME_WAIT 排空（G4：连续压测会耗尽客户端临时端口，制造假 401） ==="
echo "ts,time_wait" > "$TW_CSV"
if [ "$SKIP_TIME_WAIT_WAIT" = "1" ]; then
  echo "      已按 SKIP_TIME_WAIT_WAIT=1 跳过轮前排空"
  # 跳过排空 = 无法证明临时端口已排空 ⇒ 本轮不是合格的分离容量样本，必须失败而非静默放行。
  fail_round "跳过了轮前 TIME_WAIT 排空：无法证明临时端口已排空，本轮不得作为合格的分离容量样本"
else
  TW_START="$(date +%s)"
  TW_DEADLINE=$(( TW_START + TIME_WAIT_TIMEOUT_S ))
  # 起始读数探测失败同样致命：没有起点就无法谈「排空」。
  if ! tw0="$(lt_tw_count)"; then
    fail_round "轮前 TIME_WAIT 探测失败（起始读数）：无法证明已排空，本轮不得作为合格的分离容量样本"
    tw0=""
  fi
  if [ -n "$tw0" ]; then
    echo "      起始 TIME_WAIT=$tw0  阈值=$TIME_WAIT_MAX  上限=${TIME_WAIT_TIMEOUT_S}s"
    tw_prev="$tw0"
    plateau=0
    while :; do
      # 探测失败绝不允许被改写成 0（那会伪装成「已排空」）；直接作废本轮。
      if ! tw="$(lt_tw_count)"; then
        echo "$(now_ms),PROBE_FAIL" >> "$TW_CSV"
        fail_round "轮前 TIME_WAIT 探测失败（探测中）：无法证明已排空，本轮不得作为合格的分离容量样本"
        break
      fi
      echo "$(now_ms),$tw" >> "$TW_CSV"
      if [ "$tw" -le "$TIME_WAIT_MAX" ]; then
        echo "      已排空：TIME_WAIT=$tw0 → $tw（耗时 $(( $(date +%s) - TW_START ))s）"
        break
      fi
      # 自然排空的尾巴会停在本机基线（历史实测 9531→3329），死等阈值等于白等；
      # 连续 30s 不下降且已低于 FLOOR_ACCEPT 就认定到位，并把判据写进 timewait-*.csv。
      if [ "$tw" -ge "$tw_prev" ]; then plateau=$((plateau + 1)); else plateau=0; fi
      tw_prev="$tw"
      if [ "$plateau" -ge 6 ] && [ "$tw" -le "$TIME_WAIT_FLOOR_ACCEPT" ]; then
        echo "      已到本机基线：连续 30s 不再下降，TIME_WAIT=$tw0 → $tw（耗时 $(( $(date +%s) - TW_START ))s）"
        break
      fi
      if [ "$(date +%s)" -ge "$TW_DEADLINE" ]; then
        echo "      超时（${TIME_WAIT_TIMEOUT_S}s）：TIME_WAIT=$tw > $TIME_WAIT_MAX"
        # 超时 = 排空判据未满足 ⇒ 同样不是合格的分离容量样本。
        fail_round "轮前 TIME_WAIT 排空超时（${TIME_WAIT_TIMEOUT_S}s，TIME_WAIT=$tw > $TIME_WAIT_MAX）：本轮不得作为合格的分离容量样本"
        break
      fi
      sleep 5
    done
  fi
fi

echo "=== [3/8] 取 prom-before 快照并启动持续采样（G5：应用侧 ${SAMPLE_INTERVAL_S}s / DB·MQ 侧 ${ENV_SAMPLE_INTERVAL_S}s） ==="
curl -s --noproxy '*' --max-time 10 "$APP_BASE_URL/actuator/prometheus" > "$PROM_BEFORE"
rm -f "$SAMPLE_CSV" "$MQ_CSV"
echo "ts,tomcat_busy,tomcat_current,tomcat_max,hikari_pending,hikari_active,hikari_max,proc_cpu,sys_cpu" > "$SAMPLE_CSV"
echo "ts,q_ready,q_unack,not_persisted,mysql_connected,mysql_running" > "$MQ_CSV"

# 应用侧：单次 /actuator/prometheus 抓取即可拿到 tomcat_threads_* / hikaricp_connections_* /
# process_cpu_usage / system_cpu_usage（实测：dynamic-datasource 下 Hikari 指标**有**注册，
# 但只注册了 pool="master"；从库池不在指标里 —— 这条要写进报告，不能默认它可观测）。
# 每次采样只花 1 个 curl + 1 个 awk（进程 spawn 是本机采样周期的头号成本）。
sample_app_once() {
  local ts prom
  ts="$(now_ms)"
  prom="$(curl -s --noproxy '*' --max-time 3 "$APP_BASE_URL/actuator/prometheus" 2>/dev/null || true)"
  if [ -z "$prom" ]; then
    echo "$ts,NA,NA,NA,NA,NA,NA,NA,NA" >> "$SAMPLE_CSV"
    return 0
  fi
  printf '%s\n' "$ts,$(printf '%s' "$prom" | parse_app_metrics)" >> "$SAMPLE_CSV"
}

# DB/MQ 侧：队列深度优先走 RabbitMQ 管理 API（实测 ~0.3s，`docker exec rabbitmqctl` 要 ~2.7s；
# 管理插件不可用时退回 docker exec，沿用原资产口径）。整段只起 1 个 curl + 1 个 awk。
queue_depth() {
  local body
  body="$(curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASS" --noproxy '*' --max-time 3 \
          "$RABBITMQ_MGMT_URL/api/queues/%2F/$SUBMIT_QUEUE" 2>/dev/null || true)"
  if [ -n "$body" ]; then
    read -r q_ready q_unack < <(printf '%s' "$body" | awk '
      { if (match($0, /"messages_ready":[0-9]+/))           r = substr($0, RSTART, RLENGTH)
        if (match($0, /"messages_unacknowledged":[0-9]+/)) u = substr($0, RSTART, RLENGTH)
        sub(/.*:/, "", r); sub(/.*:/, "", u)
        print r, u; exit }')
  fi
  if [ -z "${q_ready:-}" ] || [ -z "${q_unack:-}" ]; then
    read -r q_ready q_unack < <(docker exec "$RABBIT_CONTAINER" rabbitmqctl list_queues name messages_ready messages_unacknowledged 2>/dev/null \
        | awk -v q="$SUBMIT_QUEUE" '$1==q {printf "%s %s\n", $2, $3}') || true
  fi
}

# 三条 SQL 合成**一次** mysql 调用（本机每次 spawn ~0.4s：三次调用 = 1.2s 纯开销）
sample_env_once() {
  local ts out
  ts="$(now_ms)"
  q_ready=""; q_unack=""
  out="$(run_sql -e "
    SHOW GLOBAL STATUS LIKE 'Threads_connected';
    SHOW GLOBAL STATUS LIKE 'Threads_running';
    SELECT COUNT(*) FROM exam_submissions WHERE exam_id=$EXAM_ID AND status=2 AND answers IS NULL;
  " 2>/dev/null || true)"
  read -r dbc dbr miss < <(printf '%s\n' "$out" | awk -F'\t' '
    $1 ~ /Threads_connected/ { c = $2 }
    $1 ~ /Threads_running/   { r = $2 }
    $0 ~ /^[0-9]+$/          { m = $0 }
    END { printf "%s %s %s\n", (c==""?"NA":c), (r==""?"NA":r), (m==""?"NA":m) }')
  queue_depth
  echo "$ts,${q_ready:-NA},${q_unack:-NA},${miss:-NA},${dbc:-NA},${dbr:-NA}" >> "$MQ_CSV"
}

POLLER_PIDS=()
( while true; do sample_app_once || true; sleep "$SAMPLE_INTERVAL_S"; done ) &
POLLER_PIDS+=("$!")
( while true; do sample_env_once || true; sleep "$ENV_SAMPLE_INTERVAL_S"; done ) &
POLLER_PIDS+=("$!")
# 退出时务必杀掉采样器及其 docker exec 子进程，否则它会一直占着终端
stop_poller() {
  local p
  for p in "${POLLER_PIDS[@]}"; do
    pkill -P "$p" 2>/dev/null || true
    kill "$p" 2>/dev/null || true
  done
}
trap stop_poller EXIT

echo "=== [4/8] 运行 JMeter（非 GUI；目标 $SUT_HOST:$SUT_PORT） ==="
T0="$(now_ms)"
JM_EXIT=0
if [ -n "$JMETER_SSH" ]; then
  run_jmeter_remote || JM_EXIT=$?
else
  HEAP="$JMETER_HEAP" JAVA_HOME="$JAVA_HOME_FOR_JMETER" bash "$JMETER_HOME/bin/jmeter" -n \
    -t "$WT_WIN/loadtest/jmeter/submit-5000.jmx" \
    -Jhost="$SUT_HOST" -Jport="$SUT_PORT" \
    -Jexam.id="$EXAM_ID" \
    -Jsubmit.threads="$SUBMIT_THREADS" -Jsubmit.ramp="$SUBMIT_RAMP" \
    -Jlogin.threads="$SUBMIT_THREADS" -Jlogin.ramp="$LOGIN_RAMP" \
    -Jtokens.file="$WT_WIN/target/loadtest/tokens-$TAG.csv" \
    -Jresults.file="$WT_WIN/target/loadtest/submit-results-$TAG.csv" \
    -Jjmeter.save.saveservice.print_field_names=true \
    > "$JM_LOG" 2>&1 || JM_EXIT=$?
fi
# JMeter 非零退出 = 本轮不可信：必须让本轮以非零退出，不得被 run-arm.sh 的管道吞掉后进入臂级中位数。
[ "$JM_EXIT" -eq 0 ] || fail_round "JMeter 退出码 $JM_EXIT（见 $JM_LOG）"
T1="$(now_ms)"
echo "      提交阶段墙钟窗口: $T0 → $T1 （$(python -c "print(f'{($T1-$T0)/1000:.1f}')")s）"

echo "=== [5/8] 取 prom-after 快照（必须紧跟 JMeter 退出：TimeWindowMax 2 分钟衰减窗口） ==="
curl -s --noproxy '*' --max-time 10 "$APP_BASE_URL/actuator/prometheus" > "$PROM_AFTER"
echo "      已落盘 $PROM_AFTER"

echo "=== [6/8] 等积压归零（队列 ready+unacked=0 且 未落库计数=0） ==="
DEADLINE=$(( $(now_ms) + DRAIN_TIMEOUT_S * 1000 ))
while :; do
  q_ready=""; q_unack=""
  queue_depth
  miss="$(run_sql -e "SELECT COUNT(*) FROM exam_submissions WHERE exam_id=$EXAM_ID AND status=2 AND answers IS NULL;" | tr -d '\r\n')"
  [ "${q_ready:-1}" = "0" ] && [ "${q_unack:-1}" = "0" ] && [ "${miss:-1}" = "0" ] && break
  [ "$(now_ms)" -ge "$DEADLINE" ] && { fail_round "等积压归零超时（${DRAIN_TIMEOUT_S}s）：ready=$q_ready unack=$q_unack 未落库=$miss"; break; }
  sleep 1
done
# 排空失败（超时仍非零）= 落库时效被前一轮/本轮积压污染，本轮不得进入臂级中位数。
if [ "${q_ready:-1}" != "0" ] || [ "${q_unack:-1}" != "0" ] || [ "${miss:-1}" != "0" ]; then
  fail_round "积压未归零：ready=$q_ready unack=$q_unack 未落库=$miss"
fi
T2="$(now_ms)"
echo "      积压归零: $T2 （提交阶段结束后 $(python -c "print(f'{($T2-$T1)/1000:.1f}')")s）"
stop_poller
trap - EXIT

echo "=== [7/8] 采集 DB 指标 ==="
MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
  --default-character-set=utf8mb4 -t "$DB_NAME" < "$SCRIPT_DIR/db/02-metrics.sql" \
  | tee "$OUT/metrics-$TAG.txt"

echo "=== [8/8] 生成 JMeter HTML 仪表盘 ==="
# JMeter 要求输出目录为空/不存在，重跑同一 TAG 时先清掉上一次的仪表盘
[ -e "$OUT/html-$TAG" ] && rm -rf "$OUT/html-$TAG" || true
JAVA_HOME="$JAVA_HOME_FOR_JMETER" bash "$JMETER_HOME/bin/jmeter" -g "$OUT_WIN/submit-results-$TAG.csv" \
  -o "$OUT_WIN/html-$TAG" > "$OUT/jmeter-html-$TAG.log" 2>&1 || echo "警告：HTML 仪表盘生成失败（不影响数据）"

echo
echo "=== 逐笔统计 ==="
"$PYTHON_BIN" "$WT_WIN/loadtest/analyze-results.py" "$OUT_WIN/submit-results-$TAG.csv" || true
echo
echo "=== 本轮采样峰值（G5） ==="
"$PYTHON_BIN" "$WT_WIN/loadtest/summarize-samples.py" "$OUT_WIN/sample-$TAG.csv" "$OUT_WIN/mq-depth-$TAG.csv" || true
echo
echo "本轮原始产物（target/ 已 gitignored）："
echo "  $JM_LOG"
echo "  $RESULTS"
echo "  $MQ_CSV"
echo "  $SAMPLE_CSV"
echo "  $TW_CSV"
echo "  $PROM_BEFORE"
echo "  $PROM_AFTER"
echo "  $OUT/metrics-$TAG.txt"
echo "  $OUT/html-$TAG/index.html"

# 失败传播的最终出口：只要本轮有任何硬失败，就以非零退出——run-arm.sh 据此把该轮排除出臂级中位数，
# 「失败轮被报成成功」在脚本层面不再可能。
if [ "$ROUND_FAILED" != "0" ]; then
  echo
  echo "!!! 本轮判定：失败 —— 不得作为容量结论，也不得进入臂级中位数（原因见上文 !!! 行）"
  exit 1
fi
echo
echo "本轮判定：成功（exit 0）"
