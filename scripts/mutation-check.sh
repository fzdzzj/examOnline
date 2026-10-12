#!/usr/bin/env bash
# mutation-check.sh —— 变异校验驱动脚本（生产化 T2，add-mutation-check-tool）
#
# 用途：把 AGENTS.md「变异验证纪律」（注入违规→确认变红→复原→归零→回绿）固化为
#       一个可复现的原子五步流程，使执行侧自证与复核侧独立复现共用同一工具与留证形态。
#       手工五步仅留作兜底。
#
# 用法：
#   scripts/mutation-check.sh \
#       --target <file> \
#       --inject-sed '<sed 替换表达式 s/OLD/NEW/ 或 s|OLD|NEW|>' \
#          （或 --inject-file <file> 整文件替换形态，二选一） \
#       --verify '<command>' \
#       --label <name> \
#       [--out-dir <dir>]
#   scripts/mutation-check.sh --help        # 自述用法
#
# 参数契约：
#   --target      注入目标文件（相对仓库根，可含目录）
#   --inject-sed  sed 替换表达式；替换侧 NEW 即注入标志，注入后会被 grep 核验在场
#   --inject-file 整文件替换形态；目标将整体覆盖为该文件内容（与 --inject-sed 互斥）
#   --verify      验证命令串；预期注入后非零退出（击红）、复原后零退出（回绿）
#   --label       变异标识，用于留证目录命名（<out-dir>/<label>/）
#   --out-dir     留证根目录（可选）；缺省为 target/mutation-check/（行为与既有形态逐字一致），
#                 指定时为留证跨 mvnw clean 存续的逃生口——留证落 <out-dir>/<label>/
#   --help        打印用法并退出 0
#
# Windows 下驱动形态：WSL 不可挂盘时以 Git-Bash 驱动同工具可行（两案复核实证）——
#   在 Git-Bash 中执行 scripts/mutation-check.sh 即可（bash 4.x 兼容，无 bash5 专属变量）。
#
# 五步原子流程（任一步失败 → 先基准备份带回复原，再以非零退出并指明失败步骤）：
#   ① 备份      ：目标字节级复制到 target/mutation-check/<label>/backup/original
#   ② 注入+命中 ：应用注入；必须验证注入生效——--inject-sed 以「注入标志（替换侧 NEW）在场」为
#                  判据阻断静默失配（CRLF 失配/模式不匹配即标志不在场→判「注入未生效」失败）；
#                  --inject-file 以「源文件整份在场 + 与备份字节不同」判在场
#   ③ 击红      ：跑 --verify，输出 tee 留证；断言非零退出——仍零退出即判「假绿/装饰性断言」失败
#   ④ 复原+归零 ：从备份字节级复制回目标，cmp 与备份字节一致；grep 断言注入标志不在场
#   ⑤ 回绿      ：重跑 --verify，断言零退出；输出五步汇总报告（PASS/FAIL + 留证路径）
#
# 环境与历史坑防护：
#   - 全程只写 target/（gitignored，target/mutation-check/<label>/）；不依赖 bash5 专属变量
#     （bash 4.4 无 EPOCHREALTIME，本案 verprobe 实测 4.4.23）。
#   - 工作树文件可能是 CRLF 而 git 管道/表达式侧为 LF——sed/grep 模式串可能静默失配。
#     本工具以「cp 字节级备份 + cmp 复原比对」做复原底座；注入是否生效以「注入标志在场」
#     为判据（不依赖模式串命中为唯一依据），杜绝「以为注入了其实没注入」的假结论。
#   - verify 命令的退出码用显式捕获（set +e 包裹），不靠 $() 子壳 exit 传播。
set -euo pipefail

usage() {
  cat <<'EOF'
mutation-check.sh —— 变异校验驱动脚本（生产化 T2）

用法：
  scripts/mutation-check.sh \
      --target <file> \
      --inject-sed '<sed 替换表达式 s/OLD/NEW/ 或 s|OLD|NEW|>' \
         （或 --inject-file <file> 整文件替换形态，二选一） \
      --verify '<command>' \
      --label <name> [--out-dir <dir>]
  scripts/mutation-check.sh --help

参数：
  --target      注入目标文件  --inject-sed  sed 替换表达式
  --inject-file 整文件替换源   --verify    验证命令串（注入后非零、复原后零）
  --label       变异标识（留证 <out-dir>/<label>/）  --out-dir 留证根目录（缺省 target/mutation-check/）
  --help        本用法

五步：①备份 ②注入+命中核验 ③击红非零断言 ④复原 cmp 字节一致+归零 ⑤回绿零退出。
任一步失败非零退出并指明步骤；判败同样写 report.txt 留证；全部留证落 out-dir 之下；零 out-dir 之外写入。
Windows：WSL 不可挂盘时以 Git-Bash 驱动同工具可行。
EOF
}

fail() {
  # 判败亦留证（红线 ①）：任一步骤判败退出前补写 report.txt——判败轮次与判胜轮次同规格留证。
  # 尽力而为：参数/环境态缺失（REPORT 未定义，如参数校验期）时按缺省/指定 out-dir 构造；
  # 留证写入即使失败也不掩盖判败本体（`|| true`）。
  local fstep="$1" fmsg="${2:-}" fexit="${3:-1}" rpt ri
  rpt="${REPORT:-}"
  if [ -n "$rpt" ]; then
    ri="$rpt"
  else
    ri="${OUT_DIR:+$OUT_DIR/}${LABEL:-}/report.txt"
  fi
  mkdir -p "$(dirname "$ri")" 2>/dev/null || true
  {
    echo "mutation-check 五步汇总 —— label: ${LABEL:-}"
    echo "target: ${TARGET:-}"
    echo "verify: ${VERIFY:-}"
    echo "FAIL $fstep: $fmsg"
  } > "$ri" 2>/dev/null || true
  echo "mutation-check FAIL [$fstep]: $fmsg" >&2
  exit "$fexit"
}

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET=""; INJECT_SED=""; INJECT_FILE=""; VERIFY=""; LABEL=""; OUT_DIR=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --target) TARGET="${2:-}"; shift 2 ;;
    --inject-sed) INJECT_SED="${2:-}"; shift 2 ;;
    --inject-file) INJECT_FILE="${2:-}"; shift 2 ;;
    --verify) VERIFY="${2:-}"; shift 2 ;;
    --label) LABEL="${2:-}"; shift 2 ;;
    --out-dir) OUT_DIR="${2:-}"; shift 2 ;;
    *) echo "mutation-check: 未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

# ---- 留证根目录：--out-dir 缺省时行为与既有形态逐字一致（仍落 target/mutation-check/）----
OUT_DIR="${OUT_DIR:-$ROOT/target/mutation-check}"

# ---- 参数完整性 ----
[ -n "$TARGET" ] || fail "参数" "--target 必填" 2
[ -n "$LABEL" ] || fail "参数" "--label 必填" 2
[ -n "$VERIFY" ] || fail "参数" "--verify 必填" 2
if [ -n "$INJECT_SED" ] && [ -n "$INJECT_FILE" ]; then
  fail "参数" "--inject-sed 与 --inject-file 互斥，二选一" 2
fi
if [ -z "$INJECT_SED" ] && [ -z "$INJECT_FILE" ]; then
  fail "参数" "必须提供 --inject-sed 或 --inject-file" 2
fi
[ -f "$TARGET" ] || fail "参数" "--target 不是普通文件：$TARGET" 2
if [ -n "$INJECT_FILE" ]; then
  [ -f "$INJECT_FILE" ] || fail "参数" "--inject-file 不是普通文件：$INJECT_FILE" 2
fi

# ---- 留证路径（可 --out-dir 指定逃生目录；缺省只写 target/，gitignored）----
EVID="$OUT_DIR/$LABEL"
BACKUP_DIR="$EVID/backup"
REPORT="$EVID/report.txt"
mkdir -p "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/original"

# ---- 注入标志：--inject-sed 取替换侧 NEW；--inject-file 取源文件本身 ----
MARKER=""
if [ -n "$INJECT_SED" ]; then
  MARKER="$(printf '%s' "$INJECT_SED" | sed -nE 's/^s(.)(.*)\1(.*)\1.*$/\3/p')"
  [ -n "$MARKER" ] || fail "参数" "--inject-sed 须为 s|OLD|NEW| 简单替换形态（无法解析替换侧标志）" 2
fi

REPORT_LINES=()
pass() { REPORT_LINES+=("PASS  $1"); echo "mutation-check: PASS  $1"; }
add_report_line() { REPORT_LINES+=("$1"); }

restore_target() {
  [ -f "$BACKUP" ] && cp "$BACKUP" "$TARGET" 2>/dev/null || true
}
write_report_exit() {
  {
    echo "mutation-check 五步汇总 —— label: $LABEL"
    echo "target: $TARGET"
    echo "verify: $VERIFY"
    for l in "${REPORT_LINES[@]}"; do echo "$l"; done
  } > "$REPORT"
  # 任一 FAIL 行同时回显到 stderr，方便交互式调用直接看到失败步骤
  for l in "${REPORT_LINES[@]}"; do
    case "$l" in FAIL*) echo "mutation-check: $l" >&2 ;; esac
  done
  exit "$1"
}

# ================= ① 备份 =================
cp "$TARGET" "$BACKUP"
pass "① 备份：$TARGET -> $BACKUP"

# ================= ② 注入 + 命中核验 =================
if [ -n "$INJECT_SED" ]; then
  TMPINJ="$EVID/.injected.tmp"
  SED_OUT="$EVID/.injected.out"
  sed -e "$INJECT_SED" "$TARGET" > "$SED_OUT" 2>/dev/null || {
    restore_target
    fail "② 注入+命中" "sed 执行失败（表达式：$INJECT_SED）" 1
  }
  cp "$SED_OUT" "$TARGET"
  rm -f "$TMPINJ" "$SED_OUT"
  # 命中核验（authoritative）：注入标志（替换侧 NEW）必须在场；不在场即 sed 未命中
  # （CRLF 失配/模式不匹配）→ 判「注入未生效」失败，杜绝以为注入了其实没注入。
  if ! grep -qF -- "$MARKER" "$TARGET"; then
    restore_target
    fail "② 注入+命中" "注入未生效（标志 '$MARKER' 不在场，sed 静默未命中；CRLF 失配或模式不匹配）" 1
  fi
  # 兜底：确实产生过字节变化（沿用历史坑防护，但判定以标志在场为准）
  if cmp -s "$TARGET" "$BACKUP"; then
    restore_target
    fail "② 注入+命中" "注入未生效（目标与备份字节一致，无任何变更）" 1
  fi
  pass "② 注入+命中：注入标志在场（$MARKER）"
else
  cp "$INJECT_FILE" "$TARGET"
  if ! cmp -s "$INJECT_FILE" "$TARGET"; then
    restore_target
    fail "② 注入+命中" "注入未生效（目标未整文件替换为 --inject-file 内容）" 1
  fi
  if cmp -s "$TARGET" "$BACKUP"; then
    restore_target
    fail "② 注入+命中" "注入未生效（目标与备份字节一致，整文件替换与原文件相同）" 1
  fi
  pass "② 注入+命中：--inject-file 整份在场"
fi

# ================= ③ 击红（断言非零） =================
RED_LOG="$EVID/step3-verify-red.log"
set +e
( cd "$ROOT" && eval "$VERIFY" ) >"$RED_LOG" 2>&1
VRED=$?
set -e
if [ "$VRED" -eq 0 ]; then
  restore_target
  add_report_line "FAIL ③ 击红（注入后 verify 仍零退出 → 假绿/装饰性断言）"
  write_report_exit 1
fi
pass "③ 击红：--verify 非零退出（exit=$VRED），留证 $RED_LOG"

# ================= ④ 复原 + 归零 =================
cp "$BACKUP" "$TARGET"
if ! cmp -s "$TARGET" "$BACKUP"; then
  add_report_line "FAIL ④ 复原（cmp 字节不一致）"
  write_report_exit 1
fi
if [ -n "$INJECT_SED" ]; then
  if grep -qF -- "$MARKER" "$TARGET"; then
    add_report_line "FAIL ④ 复原（归零 grep 注入标志仍在场）"
    write_report_exit 1
  fi
fi
pass "④ 复原+归零：cmp 字节一致，注入标志不在场"

# ================= ⑤ 回绿（断言零退出） =================
GREEN_LOG="$EVID/step5-verify-green.log"
set +e
( cd "$ROOT" && eval "$VERIFY" ) >"$GREEN_LOG" 2>&1
VGREEN=$?
set -e
if [ "$VGREEN" -ne 0 ]; then
  add_report_line "FAIL ⑤ 回绿（复原后 verify 退出码 $VGREEN ≠ 0）"
  write_report_exit 1
fi
pass "⑤ 回绿：--verify 零退出（exit=$VGREEN），留证 $GREEN_LOG"

# ================= 汇总 =================
add_report_line "PASS  留证目录：$EVID"
write_report_exit 0