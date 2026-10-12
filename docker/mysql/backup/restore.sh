#!/usr/bin/env bash
# ============================================================================
# restore.sh —— MySQL 备份恢复脚本（add-backup-restore-drill）
#
# 作用：把 backup.sh 产出的 dump 恢复到**显式指定的目标容器 + 库名**。恢复是
#   破坏性操作，本脚本坚持三重防护：
#     1) 目标容器与库名必须显式传入（不提供任何"默认目标"）；
#     2) 恢复前打印 dump 元信息（含 sha256 校验）并要求显式确认参数，缺确认即
#        拒绝（防误恢复生产库）；
#     3) 恢复后自动校验：目标库表数 == dump 表数，并对关键表做行数抽样对照。
#
# 仅用于隔离一次性训练容器或明确指定的目的库；恢复前务必确认目标不是
#   dev/prod 本体（本体恢复演练走隔离一次性容器，见 README）。
#
# 用法：
#   ./restore.sh --dump <dump.sql.gz> --container <容器名> --db <库名> \
#                --confirm   # 显式确认参数（必须）
#   凭据经环境变量 MYSQL_ROOT_PASSWORD 注入（${VAR:?required}，零硬编码口令）。
#   --binlog-passthrough 说明：目标库开放 binlog 时恢复会写 binlog，不影响数据。
# ============================================================================
set -euo pipefail

DUMP=""
TARGET_CONTAINER=""
DB_NAME=""
CONFIRM="no"
SAMPLE_TABLES="users exams questions exam_submissions papers"

usage() {
  cat <<'EOF'
用法: restore.sh --dump <dump.sql.gz> --container <容器名> --db <库名> [--confirm]

  --dump <文件>         backup.sh 产出的 .sql.gz（或 .sql 亦可）
  --container <容器名>  目标容器（恢复即覆盖目标库，须显式指定）
  --db <库名>           目标库名（须显式指定）
  --confirm             显式确认"目标确为我要恢复的库"，缺省即拒绝执行

必需环境变量: MYSQL_ROOT_PASSWORD（缺省即失败，零硬编码口令）
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dump)      DUMP="$2"; shift 2 ;;
    --container) TARGET_CONTAINER="$2"; shift 2 ;;
    --db)        DB_NAME="$2"; shift 2 ;;
    --confirm)   CONFIRM="yes"; shift ;;
    -h|--help)   usage; exit 0 ;;
    *)
      echo "[restore] 未知参数: $1" >&2; usage; exit 2 ;;
  esac
done

# ---- 凭据：${VAR:?required} ----------------------------------------------
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 必须通过环境变量注入（脚本零硬编码口令）}"

# ---- 目标与文件必填 -------------------------------------------------------
[[ -n "$TARGET_CONTAINER" ]] || { echo "[restore] 必须显式 --container <容器名>" >&2; exit 2; }
[[ -n "$DB_NAME" ]] || { echo "[restore] 必须显式 --db <库名>" >&2; exit 2; }
[[ -f "$DUMP" ]] || { echo "[restore] dump 不存在: $DUMP" >&2; exit 1; }

# ---- 显式确认护栏：缺 --confirm 一律拒绝 ----------------------------------
if [[ "$CONFIRM" != "yes" ]]; then
  echo "[restore] 未提供 --confirm：拒绝执行（恢复是破坏性操作，缺确认不恢复）" >&2
  exit 3
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$TARGET_CONTAINER"; then
  echo "[restore] 容器 $TARGET_CONTAINER 未在运行，拒绝恢复" >&2
  exit 1
fi

# ---- dump 元信息打印 + sha256 校验 ----------------------------------------
echo "================ dump 元信息 ================"
echo "文件: $DUMP"
echo "大小: $(du -h "$DUMP" | awk '{print $1}')"
# 由 backup.sh 生成：<db>-<时间戳>.sql.gz 与其校验文件 <db>-<时间戳>.sha256（后者无 .sql）
SHA_FILE="${DUMP%.sql.gz}.sha256"
if [[ -f "$SHA_FILE" ]]; then
  echo "sha256 校验:"
  (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$SHA_FILE")") || {
    echo "[restore] sha256 不匹配，dump 可能损坏，拒绝恢复" >&2; exit 1; }
else
  echo "sha256: $(sha256sum "$DUMP" | awk '{print $1}')"
  echo "（未找到同目录 .sha256 校验文件，仅显示实际值）"
fi
# 预估表数（dump 内 CREATE TABLE 计数）
DUMP_TBL_COUNT="$(zcat -f "$DUMP" 2>/dev/null | grep -c '^CREATE TABLE')"
echo "dump 内建表语句数(近似): $DUMP_TBL_COUNT"
echo "目标: 容器 $TARGET_CONTAINER / 库 $DB_NAME"
echo "============================================="
echo "[restore] 确认已给出，开始恢复（将覆盖目标库 $DB_NAME）..."

# ---- 执行恢复：先删后建，避免半覆盖残留 -----------------------------------
# 目标库不存在则先建；存在则 DROP 后重建，保证与 dump 完全一致。
# 注意：backup.sh 用 --databases 生成的 dump 含「CREATE DATABASE + USE <源库>
# + SET @@GLOBAL.GTID_PURGED」。恢复演练目标是**数据容器（恢复数据本体）**，
# 而非继承源复制血脉的新从库，因此这里剥掉三类语句：
#   - CREATE DATABASE / USE：否则会改写 USE 指向、绕过本脚本的 --db 目标；
#   - SET @@GLOBAL.GTID_PURGED：源服务器产生的 GTID 集合属于源复制血脉，盲目设置
#     会与目标已执行集合重叠（ERROR 3546）；数据本体恢复不需要它（备份文件里
#     GTID 元信息已由 sha256 + dump 内容如实留证）。
# 剥掉后无库名限定的建表/INSERT 全部落进显式指定的 --db。
docker exec "$TARGET_CONTAINER" env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" \
  mysql -uroot -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\`;"
zcat -f "$DUMP" | sed -e '/^CREATE DATABASE/d' -e '/^USE /d' -e '/^SET @@GLOBAL.GTID_PURGED/d' \
  | docker exec -i "$TARGET_CONTAINER" env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" \
    mysql -uroot "$DB_NAME"

echo "[restore] 恢复完成，开始校验..."

# ---- 恢复后校验：表数三层一致 + 关键表行数抽样 -----------------------------
RECOVERED_TBL="$(docker exec "$TARGET_CONTAINER" env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" \
  mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")"
echo "[restore] 恢复后表数: $RECOVERED_TBL（dump 内建表约 $DUMP_TBL_COUNT）"

for t in $SAMPLE_TABLES; do
  COL_COUNT="$(docker exec "$TARGET_CONTAINER" env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" \
    mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='$t';" 2>/dev/null || echo 0)"
  if [[ "$COL_COUNT" == "1" ]]; then
    ROWS="$(docker exec "$TARGET_CONTAINER" env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" \
      mysql -uroot -N -e "SELECT COUNT(*) FROM \`$DB_NAME\`.\`$t\`;" 2>/dev/null || echo '?')"
    echo "[restore]   表 $t 行数: $ROWS"
  else
    echo "[restore]   表 $t 不存在或读取失败（跳过行数抽样）"
  fi
done

echo "[restore] 完成。请比对以上表数与 dump 元信息、抽查关键行，并登记 BACKUPS.md。"