#!/usr/bin/env bash
# ============================================================================
# backup.sh —— MySQL 全量备份脚本（add-backup-restore-drill）
#
# 作用：对目标 MySQL 容器执行 mysqldump 全量备份（--single-transaction 一致性
#   + --source-data GTID 元信息 + triggers/routines/events），gzip 压缩，落
#   sha256 校验文件，写入本目录 out/（gitignored，不入库），按 --keep 轮转。
#   幂等：可被宿主 crontab 反复调用。
#
# 用法：
#   ./backup.sh --env dev|prod [--container <容器名>] [--keep <N>] [--db <库名>]
#   或者把 MYSQL_ROOT_PASSWORD 环境变量注入后调用（${VAR:?required} 为零硬
#   编码口令；缺该变量脚本立即失败，不回退默认口令）。
#
# 映射事实（容器名是版本库事实，非机器绑定）：
#   --env dev  -> exam-mysql-master         （docker-compose.yml）
#   --env prod -> exam-online-prod-mysql-master （docker-compose.prod.yml）
#   --container 显式覆盖以上映射（用于一次性演练目标等）。
#   库名默认 exam_online（两环境一致），可用 --db 覆盖。
#
# 无自动执行者：本脚本入库 ≠ 备份已运行。何时调用由宿主 crontab 决定（示例见
#   本目录 README「宿主 crontab」），执行结果需按 README 流程登记 BACKUPS.md。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${SCRIPT_DIR}/out"

# ---- 参数解析 ------------------------------------------------------------
ENV_NAME=""
CONTAINER=""
KEEP=7
DB_NAME="exam_online"

usage() {
  cat <<'EOF'
用法: backup.sh --env dev|prod [--container <容器名>] [--keep <N>] [--db <库名>]

  --env dev|prod     目标环境：映射固定 container_name（dev=exam-mysql-master /
                     prod=exam-online-prod-mysql-master）
  --container <名>   显式覆盖目标容器名（演练/隔离目标用）
  --keep <N>         保留最近 N 份备份，默认 7
  --db <库名>        目标数据库名，默认 exam_online

必需环境变量: MYSQL_ROOT_PASSWORD（缺省即失败，零硬编码口令）
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      ENV_NAME="$2"; shift 2 ;;
    --container)
      CONTAINER="$2"; shift 2 ;;
    --keep)
      KEEP="$2"; shift 2 ;;
    --db)
      DB_NAME="$2"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "[backup] 未知参数: $1" >&2; usage; exit 2 ;;
  esac
done

# ---- 凭据：${VAR:?required} 零硬编码口令 ----------------------------------
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 必须通过环境变量注入（脚本零硬编码口令）}"

# ---- 目标容器映射 -------------------------------------------------------
if [[ -n "$CONTAINER" ]]; then
  TARGET_CONTAINER="$CONTAINER"
elif [[ "$ENV_NAME" == "dev" ]]; then
  TARGET_CONTAINER="exam-mysql-master"
elif [[ "$ENV_NAME" == "prod" ]]; then
  TARGET_CONTAINER="exam-online-prod-mysql-master"
else
  echo "[backup] 必须指定 --env dev|prod 或 --container <容器名>" >&2
  usage; exit 2
fi

# 数值合法性：KEEP 必须为正整数
if ! [[ "$KEEP" =~ ^[0-9]+$ ]] || [[ "$KEEP" -lt 1 ]]; then
  echo "[backup] --keep 必须为正整数（默认 7）" >&2
  exit 2
fi

# ---- 前置校验 -----------------------------------------------------------
if ! docker ps --format '{{.Names}}' | grep -qx "$TARGET_CONTAINER"; then
  echo "[backup] 容器 $TARGET_CONTAINER 未在运行（docker ps 不可见），拒绝备份" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

# ---- 执行备份 ------------------------------------------------------------
STAMP="$(date +%Y%m%d-%H%M%S)"
BASENAME="${DB_NAME}-${STAMP}"
OUT_SQL="${OUT_DIR}/${BASENAME}.sql"
OUT_GZ="${OUT_DIR}/${BASENAME}.sql.gz"
OUT_SHA="${OUT_DIR}/${BASENAME}.sha256"

# mysqldump --source-data（MySQL 8.0 取代 --master-data）写 GTID/二进制位点
# 元信息为注释输出；--single-transaction 在 InnoDB 上不锁表，对被备份库只读。
# --routines --events --triggers 保留存储过程/事件/触发器（8.0 后 triggers 默认含）。
echo "[backup] 开始备份: ${ENV_NAME:-直连 ${TARGET_CONTAINER}}/${DB_NAME} -> ${OUT_GZ}"

docker exec "$TARGET_CONTAINER" env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" \
  mysqldump -uroot \
    --single-transaction \
    --source-data=2 \
    --routines --events \
    --databases "$DB_NAME" \
  | gzip > "$OUT_GZ"

# 完整性：gzip -t 测良 + sha256 落校验文件
gzip -t "$OUT_GZ"
sha256sum "$OUT_GZ" > "$OUT_SHA"
echo "[backup] 完成: ${OUT_GZ}"
echo "[backup] sha256: $(awk '{print $1}' "$OUT_SHA")"
echo "[backup] 大小: $(du -h "$OUT_GZ" | awk '{print $1}')"

# ---- 轮转：仅保留最近 KEEP 份本库备份，过期即删 ---------------------------
# 匹配本库同名备份产物，按时间序保留最近 KEEP 份，其余删除（含对应 .sha256）。
mapfile -t OLD_FILES < <(find "$OUT_DIR" -maxdepth 1 -type f -name "${DB_NAME}-*.sql.gz" | sort -r)
COUNT="${#OLD_FILES[@]}"
if [[ "$COUNT" -gt "$KEEP" ]]; then
  echo "[backup] 轮转: 现存 ${COUNT} 份，保留最近 ${KEEP} 份"
  for ((i=KEEP; i<COUNT; i++)); do
    target="${OLD_FILES[$i]}"
    rm -f "$target" "${target%.gz}.sha256"
    echo "[backup]   删除过期: ${target##*/}"
  done
else
  echo "[backup] 轮转: 现存 ${COUNT} 份（≤ ${KEEP}），无需清理"
fi

echo "[backup] 完成"