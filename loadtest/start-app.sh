#!/usr/bin/env bash
# =============================================================
# 启动 dev 应用实例（交卷容量压测的「参数注入点」）——tune-submit-capacity
#
# 为什么单独成脚本：
#   本仓库的 dev 应用跑在**宿主机**（`java -jar`），不在容器里，所以「注入并发运行参数」
#   的落点只能是这里，而不是 docker-compose.yml（compose 只编排 MySQL/Redis/RabbitMQ）。
#   把注入点固化成一条可复现命令，才能让「G2 注入生效」有可复核的现场证据。
#
# 注入机制：Spring Boot relaxed binding —— 环境变量 `SERVER_TOMCAT_THREADS_MAX`
#   直接映射到 `server.tomcat.threads.max`，**零代码改动、零配置文件改动**。
#
# 用法：
#   bash loadtest/start-app.sh                      # 默认臂（不注入 = 框架默认 200）
#   ARM=tuned SERVER_TOMCAT_THREADS_MAX=400 bash loadtest/start-app.sh
#   ARM=tuned SERVER_TOMCAT_THREADS_MAX=400 DB_POOL_MAX=40 bash loadtest/start-app.sh
#
# 产物（落在 target/loadtest/）：
#   app-<ARM>.log               实例标准输出
#   app-env-<ARM>.txt           本次实际注入的环境变量清单（含未注入项的显式记录）
#   app-proof-<ARM>.txt         生效证据：运行时读到的 Tomcat 线程上限 / 指标可用性 / revision
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT="$(cd "$SCRIPT_DIR/.." && pwd)"
WT_WIN="$(cd "$WT" && pwd -W)"
OUT="$WT/target/loadtest"
OUT_WIN="$WT_WIN/target/loadtest"
mkdir -p "$OUT"

APP_BASE_URL="${APP_BASE_URL:-http://127.0.0.1:8080}"
ARM="${ARM:-default}"
JAR="${JAR:-$WT_WIN/target/exam-online.jar}"
JAVA_BIN="${JAVA_BIN:-D:\\develop\\jdk177\\bin\\java.exe}"
JVM_OPTS="${JVM_OPTS:-}"
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-120}"

# ---- 注入的运行参数（不设即为「框架默认」，不写 application*.yml）----
# SERVER_TOMCAT_THREADS_MAX → server.tomcat.threads.max（本变更的 G2 主角）
# DB_POOL_MAX / SLAVE_DB_POOL_MAX / RABBIT_BATCH_CONCURRENCY → 既有 dev 容量参数（仅在需要时一并注入）
INJECT_KEYS=(SERVER_TOMCAT_THREADS_MAX DB_POOL_MAX SLAVE_DB_POOL_MAX RABBIT_BATCH_CONCURRENCY APP_PROFILE)

LOG="$OUT/app-$ARM.log"
ENV_TXT="$OUT/app-env-$ARM.txt"
PROOF="$OUT/app-proof-$ARM.txt"

[ -f "$JAR" ] || { echo "出错：找不到 $JAR（先跑 mvn -o package -DskipTests）"; exit 1; }

echo "=== 启动 dev 实例（ARM=$ARM） ==="
{
  echo "# 注入清单（未列出的项 = 未注入，取框架/配置文件默认值）"
  echo "# 采集时间 $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "# 短 revision $(git -C "$WT_WIN" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "# 完整命令 java $JVM_OPTS -jar $JAR --server.port=8080 --spring.profiles.active=dev"
  for k in "${INJECT_KEYS[@]}"; do
    if [ -n "${!k:-}" ]; then echo "$k=${!k}"; else echo "# $k=(未设置)"; fi
  done
} > "$ENV_TXT"

# 逐条 export：只把显式给值的键传下去，未给值的键保持不存在（否则空串会被 relaxed binding 当值）
ENV_ARGS=()
for k in "${INJECT_KEYS[@]}"; do
  if [ -n "${!k:-}" ]; then
    export "$k"
    ENV_ARGS+=("$k=${!k}")
  fi
done
PROFILE="${APP_PROFILE:-dev}"

# 启动期已知异常（不要误判为失败）：答案补发对账在真 broker 下抛
# IllegalStateException: This operation is only available within the scope of an invoke operation（遗留 #10，非致命）
nohup "$JAVA_BIN" $JVM_OPTS -jar "$JAR" --server.port=8080 --spring.profiles.active="$PROFILE" \
  > "$LOG" 2>&1 &
APP_PID=$!
echo "      PID=$APP_PID  日志=$LOG"
[ ${#ENV_ARGS[@]} -gt 0 ] && echo "      注入：${ENV_ARGS[*]}"

echo "=== 等 /actuator/health ==="
DEADLINE=$(( $(date +%s) + HEALTH_TIMEOUT_S ))
code=000
while :; do
  code="$(curl -s -o NUL -w '%{http_code}' --noproxy '*' --max-time 3 "$APP_BASE_URL/actuator/health" 2>/dev/null || echo 000)"
  [ "$code" = "200" ] && break
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "出错：${HEALTH_TIMEOUT_S}s 内未就绪（最后 HTTP $code），见 $LOG"
    exit 1
  fi
  sleep 2
done
echo "      health=200"

# ---- 生效证据（不凭「应该生效」）----
{
  echo "### ARM=$ARM"
  echo "### 短 revision $(git -C "$WT_WIN" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "### 采集时间 $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo
  echo "--- [1] 进程命令行（证明用的是哪份 jar / 哪套参数）---"
  echo "启动脚本 shell PID=$APP_PID（实际 java 进程见下方 netstat 反查）"
  echo
  echo "--- [2] /actuator/prometheus 里的 Tomcat 线程上限与水位（add-submit-observability 暴露）---"
  curl -s --noproxy '*' --max-time 5 "$APP_BASE_URL/actuator/prometheus" \
    | grep -E '^tomcat_threads_(config_max|busy|current)_threads' || echo "（未取到 tomcat_threads_*，观测补齐未生效？）"
  echo
  echo "--- [3] /actuator/metrics 可用指标名里与线程/连接池相关的项 ---"
  curl -s --noproxy '*' --max-time 5 "$APP_BASE_URL/actuator/metrics" \
    | tr ',' '\n' | grep -oE '"(tomcat|hikaricp|jdbc|process\.cpu|system\.cpu)[^"]*"' | sort -u \
    || echo "（指标清单获取失败）"
  echo
  echo "--- [4] 连接池指标直查（G5 目标端点；不存在则如实记录 HTTP 码）---"
  for m in hikaricp.connections.pending hikaricp.connections.active hikaricp.connections.max; do
    body="$(curl -s -w '|HTTP:%{http_code}' --noproxy '*' --max-time 5 "$APP_BASE_URL/actuator/metrics/$m" || true)"
    echo "$m -> ${body}"
  done
  echo
  echo "--- [5] 服务端 MVC 计时是否有直方图桶（G3 补齐项）---"
  curl -s --noproxy '*' --max-time 5 "$APP_BASE_URL/actuator/prometheus" \
    | grep -cE '^exam_submit_duration_seconds_bucket' | sed 's/^/exam_submit_duration_seconds_bucket 行数=/'
} > "$PROOF" 2>&1

echo "      生效证据已写入 $PROOF"
cat "$PROOF"
