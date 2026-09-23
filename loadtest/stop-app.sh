#!/usr/bin/env bash
# =============================================================
# 停掉 dev 应用实例（交卷容量压测的臂间切换动作）——tune-submit-capacity
#
# 为什么必须能停：「容量参数是启动期读取的环境变量」，换臂 = 换实例。
#
# 用法：bash loadtest/stop-app.sh
#
# 安全性：只杀「监听 8080 且镜像名为 java.exe」的进程，杀之前把 PID 与镜像打出来供人工复核。
#   **本机没有 wmic**（实测：`wmic: command not found`），拿不到完整命令行，
#   所以校验降级为「端口 + 镜像名」；若 8080 上跑的不是 java.exe 则拒绝，交人工处理。
#   （本仓库已踩过「残留 dev 实例占 8080 导致压测打到旧实例且静默成功」，宁可报错不误杀。）
# =============================================================
set -euo pipefail

PORT="${PORT:-8080}"

pids="$(netstat -ano 2>/dev/null | grep -E "[:.]$PORT[[:space:]]+.*LISTENING" | awk '{print $NF}' | sort -u || true)"
if [ -z "$pids" ]; then
  echo "$PORT 无监听进程（无需停止）"
  exit 0
fi

image_of_pid() {
  # tasklist 全量输出的第 1 列是映像名、第 2 列是 PID。
  # **不要用 `tasklist //FI "PID eq N"`，也不要用 `taskkill //PID N //F`**：
  # 本环境（Git Bash / MSYS 1.2.0 + Windows）把双斜杠原样传下去，报
  # 「无效参数/选项 - '//FI'」/「'//PID'」——看着像没有这个进程，其实只是参数没被转换。
  # 单斜杠形态实测可用（`tasklist /FI "PID eq N"` 能正常解析过滤器）。
  # 仓库文档《指导Agent交接文档》里的 `taskkill //PID` 写法在本机会失败，本脚本不再沿用。
  MSYS_NO_PATHCONV=1 tasklist 2>/dev/null | awk -v p="$1" '$2 == p { print tolower($1); exit }'
}

failed=0
for pid in $pids; do
  img="$(image_of_pid "$pid")"
  echo "--- PID $pid 镜像=${img:-未知} ---"
  if [ "$img" != "java.exe" ]; then
    echo "跳过：镜像不是 java.exe（避免误杀他人进程）。请人工复核后处理：taskkill /PID $pid /F"
    failed=1
    continue
  fi
  echo "（本机无 wmic，无法读出命令行确认 jar 名；已按「8080 监听 + java.exe」校验）"
  MSYS_NO_PATHCONV=1 taskkill /PID "$pid" /F
done

sleep 2
if netstat -ano 2>/dev/null | grep -qE "[:.]$PORT[[:space:]]+.*LISTENING"; then
  echo "警告：$PORT 仍在监听，请人工复核"
  exit 1
fi
[ "$failed" = "1" ] && { echo "警告：有进程因镜像不匹配被跳过，$PORT 已释放但仍请复核"; exit 0; }
echo "$PORT 已释放"
