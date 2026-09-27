#!/usr/bin/env bash
# =============================================================
# 压测编排共享前置（isolate-submit-load-generator 阶段1 返修）
#
# 为什么抽成文件：下面三段逻辑要**被多个入口共用**（run-arm.sh 的入口门禁、
# run-loadtest.sh 的单轮门禁与采样），而且必须能被替身测试**单独 source 后直接驱动**——
# 「控制流正确」不能靠 grep 脚本文本证明，只能靠真的调用这些函数看行为。
#
# 本文件只做只读判定与取值，自身不产生任何副作用（不停/启应用、不写库、不造数）。
# =============================================================

# -------------------------------------------------------------
# 1) 前置门禁：宿主配置 / 物理隔离声明 / 显式写入授权
#    语义（fail-closed，返回 1 即调用方必须非零退出且不得有任何副作用）：
#      * 任何模式都要求 LT_WRITE_AUTHORIZED=1 —— 明确的**实际写入授权**。
#        环境变量声明不是用户授权；本编排会停/启应用、造数与复位数据库。
#      * JMETER_SSH 为空 = 同机跑法（未分离）。必须显式 LT_ALLOW_SAME_HOST=1 才放行，
#        且明确标注「未分离、不能作为分离容量结论」；缺省调用不允许静默进入真实执行。
#      * JMETER_SSH 非空 = 分离模式：还要求两端 hostname 不同，且
#        LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1（不同 hostname/容器/VM 名本身
#        不足以证明不抢同一物理宿主 CPU/磁盘）。
# -------------------------------------------------------------
lt_preflight_isolation() {
  local local_host remote_host
  if [ "${LT_WRITE_AUTHORIZED:-0}" != "1" ]; then
    echo "      拒绝：缺少显式写入授权（LT_WRITE_AUTHORIZED=1）。本编排会停/启应用、造数与复位数据库。" >&2
    echo "            环境变量声明不是用户授权；未获授权时不得进入真实执行。" >&2
    return 1
  fi

  if [ -z "${JMETER_SSH:-}" ]; then
    if [ "${LT_ALLOW_SAME_HOST:-0}" != "1" ]; then
      echo "      拒绝：未设置 JMETER_SSH，且未显式选择同机历史跑法（LT_ALLOW_SAME_HOST=1）。" >&2
      echo "            缺省（JMETER_SSH 为空）不允许进入真实执行：压测进程会与被测进程抢同一宿主的 CPU/磁盘。" >&2
      return 1
    fi
    echo "      警示：同机历史跑法（未分离）——本臂只能作为历史记录，不能作为分离后的容量结论"
    return 0
  fi

  command -v ssh >/dev/null 2>&1 || { echo "      拒绝：JMETER_SSH 已设置但本机无 ssh" >&2; return 1; }
  local_host="$(hostname 2>/dev/null || echo unknown)"
  remote_host="$(ssh "$JMETER_SSH" 'hostname 2>/dev/null || echo unknown' 2>/dev/null | tr -d '\r\n')"
  if [ -z "$remote_host" ]; then
    echo "      拒绝：取不到负载宿主 hostname（ssh 不通？JMETER_SSH=$JMETER_SSH）" >&2
    return 1
  fi
  if [ "$local_host" = "$remote_host" ]; then
    echo "      拒绝：两端 hostname 相同（$local_host）——这仍是同机/同命名空间" >&2
    return 1
  fi
  if [ "${LOADGEN_PHYSICAL_ISOLATION_ATTESTED:-0}" != "1" ]; then
    echo "      拒绝：未声明物理隔离。不同 hostname/容器/VM 名不足以证明不抢同一物理宿主资源；" >&2
    echo "            人工核实两台宿主不共享物理 CPU/磁盘后，设 LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1 再跑。" >&2
    return 1
  fi
  echo "      前置通过：控制端 $local_host / 负载端 $remote_host 不同，物理隔离已人工声明，写入已授权"
  return 0
}

# -------------------------------------------------------------
# 2) 毫秒时间戳
#    * 优先 bash5 的 EPOCHREALTIME（0 次 fork；采样热路径，不要换成 `python -c`：
#      本机实测 python 每次 spawn ~0.4s，会把采样周期顶到 1s+）。
#    * 但编排宿主是 Git Bash 4.4.23，**没有** EPOCHREALTIME；`set -u` 下直接引用会报
#      unbound variable 并终止整轮，故必须回退 GNU coreutils 的 `date +%s%3N`。
#      回退路径每个时间戳多一次进程 spawn，**会拉长采样周期**——这是口径变化，
#      实测开销见 loadtest/README.md（不是等价替换）。
# -------------------------------------------------------------
lt_now_ms() {
  if [ -n "${EPOCHREALTIME:-}" ]; then
    local t="${EPOCHREALTIME/./}"
    printf '%s\n' "${t:0:13}"
    return 0
  fi
  # GNU date 才有 %3N；取不到合法 13 位毫秒时降级到秒精度（仍随真实时钟前进），
  # 绝不返回空值或非数字——那会让 `[ ... -ge ... ]` 静默判错。
  local t
  t="$(date +%s%3N 2>/dev/null || true)"
  case "$t" in
    ''|*[!0-9]*) t="$(( $(date +%s) * 1000 ))";;
  esac
  printf '%s\n' "$t"
}
# 兼容既有调用点：脚本正文一律用 now_ms
now_ms() { lt_now_ms; }

# -------------------------------------------------------------
# 3) TIME_WAIT 探测（G4 轮前排空）
#    关键不变式：**「探测到 0」与「探测失败」必须可区分**。
#    0 的含义是「确实排空了」，是放行本轮容量结论的依据；把 SSH 非零、远端无 netstat、
#    输出非法等情况改写成 0，会让一轮根本没排空的压测被误判为「已排空」。
# -------------------------------------------------------------
# lt_tw_probe：成功时把计数打到 stdout（可为 0）；失败时打 LT_PROBE_FAIL:<原因>。
# 设计成「永远退出 0、用哨兵表达失败」，是为了让 ssh 的退出码/输出解析在调用方一处收敛。
lt_tw_probe() {
  local out rc
  if [ -n "${JMETER_SSH:-}" ]; then
    command -v ssh >/dev/null 2>&1 || { echo "LT_PROBE_FAIL:no-local-ssh"; return 0; }
    # 远端命令自己区分「无 netstat」与「计数为 0」：`grep -c` 无匹配时打印 0 但退出 1，
    # 故用 `|| true` 保住 0 的输出，把「命令是否可用」单独用哨兵表达。
    out="$(ssh "$JMETER_SSH" 'if ! command -v netstat >/dev/null 2>&1; then echo LT_PROBE_FAIL:no-remote-netstat; exit 0; fi; netstat -an 2>/dev/null | grep -c TIME_WAIT || true' 2>/dev/null)"
    rc=$?
    if [ "$rc" -ne 0 ]; then echo "LT_PROBE_FAIL:ssh-rc=$rc"; return 0; fi
    printf '%s\n' "$out"
    return 0
  fi
  command -v netstat >/dev/null 2>&1 || { echo "LT_PROBE_FAIL:no-local-netstat"; return 0; }
  netstat -an 2>/dev/null | grep -c TIME_WAIT || true
}

# lt_tw_count：成功时打印计数并返回 0；失败时把原因打到 stderr 并返回非零。
# 调用方**必须**在返回非零时让本轮失败，不得 fallback 成 0。
lt_tw_count() {
  local raw
  raw="$(lt_tw_probe)"
  case "$raw" in
    LT_PROBE_FAIL:*)
      echo "      TIME_WAIT 探测失败（${raw#LT_PROBE_FAIL:}）" >&2
      return 1
      ;;
  esac
  case "$raw" in
    ''|*[!0-9]*)
      echo "      TIME_WAIT 探测失败（非法输出：'$raw'）" >&2
      return 1
      ;;
  esac
  printf '%s\n' "$raw"
  return 0
}
