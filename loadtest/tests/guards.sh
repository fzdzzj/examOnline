#!/usr/bin/env bash
# =============================================================
# loadtest 回归护栏（isolate-submit-load-generator 阶段1 返修）
#
# 为什么入库：此前替身测试只放在 target/ 下，`mvn clean` 会连证据一起删掉——
#   回归护栏必须能在仓库里长期复跑，所以以最小形式放在 loadtest/tests/。
#
# 覆盖面（全部用替身，不连接真实 DB / 应用 / Docker / 负载宿主）：
#   R*  run-arm.sh 入口门禁：配置无效时零副作用（stop/start/prepare/reset 均未调用）
#   T*  真实 Git Bash 4.4 下的毫秒时间戳（不注入 EPOCHREALTIME）+ 排空超时不被假时钟掩盖
#   W*  TIME_WAIT 探测：「真零」与「探测失败」必须可区分（含 netstat 存在但执行非零退出）
#   I*  远端 TIME_WAIT 探测失败 → 本轮失败（不进入臂级中位数）
#   N*  正常远端执行 + 结果回收可验证
#
# 用法（在仓库根，用真实 Git Bash 4.4 运行）：
#   bash loadtest/tests/guards.sh
# 退出码：全部通过 0；任一失败非零。
# 只读服务：所有外部命令都被 PATH 前缀的替身顶掉；脚本只写仓库 target/（gitignored）。
# =============================================================
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$REPO/loadtest/lib-loadtest.sh"
RUNLOAD="$REPO/loadtest/run-loadtest.sh"
RUNARM="$REPO/loadtest/run-arm.sh"
OUT="$REPO/target/loadtest"

SBX="$(mktemp -d)"
trap 'rm -rf "$SBX"' EXIT
BIN="$SBX/bin"
mkdir -p "$BIN" "$SBX/log"

PASS=0
FAIL=0
check() { # check <名称> <命令...>
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "  PASS  $name"; PASS=$((PASS+1))
  else echo "  FAIL  $name"; FAIL=$((FAIL+1)); fi
}
rc_of() { cat "$1"; }
has() { grep -q -- "$1" "$2"; }
not_has() { ! grep -q -- "$1" "$2"; }

echo "BASH_VERSION=$BASH_VERSION   EPOCHREALTIME=${EPOCHREALTIME+present}${EPOCHREALTIME:-absent}"
echo "REPO=$REPO"
echo "SBX=$SBX"

# ---------------- 逐笔结果样例（替身 JMeter 的产物） ----------------
FAKE_RESULTS="$SBX/fake-results.csv"
{
  echo "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
  i=1
  while [ "$i" -le 20 ]; do
    echo "$((1700000000000 + i * 10)),$((100 + i)),POST /submit,200,OK,tg 1-1,text,true,,1,1,1,1,http://x/submit,90,0,5"
    i=$((i + 1))
  done
  echo "1700000000300,800,POST /submit,200,OK,tg 1-1,text,true,,1,1,1,1,http://x/submit,700,0,5"
} > "$FAKE_RESULTS"

# ---------------- 替身 ----------------
cat > "$BIN/curl" <<'STUB'
#!/usr/bin/env bash
a="$*"
case "$a" in
  *"/actuator/health"*)      echo 200; exit 0;;
  *"/actuator/prometheus"*)  [ -n "${STUB_PROM_FILE:-}" ] && cat "$STUB_PROM_FILE"; exit 0;;
  *"api/queues"*)            echo '{"messages_ready":0,"messages_unacknowledged":0}'; exit 0;;
esac
exit 0
STUB

cat > "$BIN/mysql" <<'STUB'
#!/usr/bin/env bash
a="$*"
if [ "${a#*-e}" != "$a" ]; then
  case "$a" in
    *"FROM exams"*)        echo 7;;
    *"status=1"*)          echo 5000;;
    *"Threads_connected"*) printf 'Threads_connected\t5\nThreads_running\t1\n0\n';;
    *"answers IS NULL"*)   echo 0;;
    *)                     echo 0;;
  esac
else
  printf '+----------------------+-------+\n| metric               | value |\n+----------------------+-------+\n'
fi
exit 0
STUB

cat > "$BIN/docker" <<'STUB'
#!/usr/bin/env bash
# 队列归零替身：assert_queues_zero 期望 'name ready unack'
if [ "${STUB_QUEUE_NONZERO:-0}" = "1" ]; then
  echo "exam.submit.queue 5 0"
else
  echo "exam.submit.queue 0 0"
fi
exit 0
STUB

cat > "$BIN/hostname" <<'STUB'
#!/usr/bin/env bash
echo "${STUB_LOCAL_HOSTNAME:-sut-host}"
STUB

cat > "$BIN/netstat" <<'STUB'
#!/usr/bin/env bash
# 默认不含 TIME_WAIT 行 ⇒ grep -c 得 0（真零）；STUB_NETSTAT_TW_COUNT 可造出非零。
# STUB_NETSTAT_EXEC_RC：模拟 netstat **存在但执行失败**——非零退出且无 stdout，
# 用来把「执行失败」与「执行成功且计数为 0」分开（二者在本替身下不能是同一现象）。
if [ -n "${STUB_NETSTAT_EXEC_RC:-}" ]; then exit "$STUB_NETSTAT_EXEC_RC"; fi
echo "  TCP    127.0.0.1:8080   127.0.0.1:5000   ESTABLISHED"
n="${STUB_NETSTAT_TW_COUNT:-0}"
i=0
while [ "$i" -lt "$n" ]; do
  echo "  TCP    127.0.0.1:$((20000 + i))   1.1.1.1:443   TIME_WAIT"
  i=$((i + 1))
done
exit 0
STUB

cat > "$BIN/ssh" <<'STUB'
#!/usr/bin/env bash
# 用法：ssh <target> "<远端命令>"。替身按命令内容分派，模拟远端行为。
[ "${1:-}" = "-o" ] && shift 2
target="${1:-}"; shift || true
cmd="${*:-}"
echo "SSH $target :: $cmd" >> "${STUB_SSH_LOG:-/dev/null}"
case "$cmd" in
  *hostname*) echo "${STUB_REMOTE_HOSTNAME:-loadgen-host}"; exit 0;;
esac
case "$cmd" in
  *netstat*)
    if [ -n "${STUB_SSH_NETSTAT_RC:-}" ]; then exit "$STUB_SSH_NETSTAT_RC"; fi
    if [ -n "${STUB_SSH_NETSTAT_OUT:-}" ]; then echo "$STUB_SSH_NETSTAT_OUT"; exit 0; fi
    # 默认**真正执行远端命令串**（PATH 前缀已把 netstat 换成替身）：这样「远端 netstat 执行失败」
    # 由命令串自身的语义产生，而不是替身预置的答案——旧实现把失败吞成 0 时新负例才会判红。
    bash -c "$cmd"; exit $?;;
esac
case "$cmd" in *mkdir*) exit 0;; esac
case "$cmd" in *jmeter*) exit "${STUB_SSH_JMETER_EXIT:-0}";; esac
exit 0
STUB

cat > "$BIN/scp" <<'STUB'
#!/usr/bin/env bash
args=("$@"); n=${#args[@]}
dest="${args[$((n-1))]}"; src="${args[$((n-2))]}"
echo "SCP $src -> $dest" >> "${STUB_SCP_LOG:-/dev/null}"
case "$src" in
  *submit-results*)
    if [ "${STUB_SCP_RESULTS_FAIL:-0}" = "1" ]; then echo "simulated scp failure" >&2; exit 1; fi
    cp "${STUB_FAKE_RESULTS:?}" "$dest"; exit 0;;
  *tokens*)        echo "lt5k_0001,tok" > "$dest"; exit 0;;
  *jmeter-stdout*) echo "stub remote jmeter log" > "$dest"; exit 0;;
esac
exit 0
STUB

mk_jmeter() {
  local d="$1"
  mkdir -p "$d/bin"
  cat > "$d/bin/jmeter" <<'STUB'
#!/usr/bin/env bash
echo "JMETER_ARGS $*" >> "${STUB_JMETER_LOG:-/dev/null}"
res=""
for a in "$@"; do case "$a" in -Jresults.file=*) res="${a#-Jresults.file=}";; esac; done
if [ -n "$res" ] && [ -n "${STUB_FAKE_RESULTS:-}" ]; then cp "$STUB_FAKE_RESULTS" "$res"; fi
exit "${STUB_JMETER_EXIT:-0}"
STUB
}
mk_jmeter "$SBX/jmeter-local"
mk_jmeter "$SBX/jmeter-remote"
chmod +x "$BIN"/* "$SBX/jmeter-local/bin/jmeter" "$SBX/jmeter-remote/bin/jmeter"

# ---------------- 驱动器：直接跑真实 run-loadtest.sh ----------------
# 关键：**不注入 EPOCHREALTIME**（`env -u`），让脚本走 Git Bash 4.4 的真实回退路径。
run_load() { # run_load <tag> [额外 env...]
  local tag="$1"; shift
  env -u EPOCHREALTIME PATH="$BIN:$PATH" \
      TAG="$tag" APP_BASE_URL="http://10.0.0.5:18080" \
      JMETER_HOME="$SBX/jmeter-local" JAVA_HOME_FOR_JMETER="" JMETER_HEAP="" \
      JMETER_REMOTE_HOME="$SBX/jmeter-remote" \
      STUB_LOCAL_HOSTNAME="sut-host" STUB_REMOTE_HOSTNAME="loadgen-host" \
      STUB_FAKE_RESULTS="$FAKE_RESULTS" \
      STUB_JMETER_LOG="$SBX/log/jmeter.log" STUB_SSH_LOG="$SBX/log/ssh.log" \
      STUB_SCP_LOG="$SBX/log/scp.log" \
      LT_WRITE_AUTHORIZED=1 \
      "$@" \
      bash "$RUNLOAD" > "$SBX/log/$tag.out" 2>&1
  echo $? > "$SBX/log/$tag.rc"
}

# ---------------- 沙箱里的 run-arm.sh（verbatim 复制 + 兄弟替身） ----------------
# run-arm.sh 用 $SCRIPT_DIR 调 stop-app.sh/start-app.sh/prepare-data.sh，无法用 PATH 顶掉，
# 故把**原文件原样复制**到沙箱，旁边放记录调用的兄弟替身——被执行的仍是真脚本内容。
ARMDIR="$SBX/armdir"
mkdir -p "$ARMDIR/db"
cp "$RUNARM" "$ARMDIR/run-arm.sh"
cp "$LIB" "$ARMDIR/lib-loadtest.sh"
: > "$ARMDIR/db/04-reset.sql"
for s in stop-app start-app prepare-data; do
  cat > "$ARMDIR/$s.sh" <<'EOS'
#!/usr/bin/env bash
echo "SIDE_EFFECT $0 $*" >> "${LT_STUB_LOG:-/dev/null}"
exit 0
EOS
done
cat > "$ARMDIR/run-loadtest.sh" <<'EOS'
#!/usr/bin/env bash
echo "SIDE_EFFECT run-loadtest.sh $*" >> "${LT_STUB_LOG:-/dev/null}"
exit 0
EOS
chmod +x "$ARMDIR"/*.sh

run_arm() { # run_arm <tag> [额外 env...]
  local tag="$1"; shift
  local stub="$SBX/log/arm-$tag.stub"
  : > "$stub"
  env -u EPOCHREALTIME PATH="$BIN:$PATH" \
      ARM="g-$tag" ROUNDS=1 LT_STUB_LOG="$stub" \
      MYSQL_BIN="$BIN/mysql" DB_HOST=127.0.0.1 DB_PORT=13316 DB_NAME=exam_online \
      STUB_LOCAL_HOSTNAME="sut-host" STUB_REMOTE_HOSTNAME="loadgen-host" \
      STUB_JMETER_LOG="$SBX/log/jmeter.log" STUB_SSH_LOG="$SBX/log/ssh.log" \
      STUB_SCP_LOG="$SBX/log/scp.log" \
      "$@" \
      bash "$ARMDIR/run-arm.sh" > "$SBX/log/$tag.out" 2>&1
  echo $? > "$SBX/log/$tag.rc"
}
arm_armlog() { echo "$(dirname "$ARMDIR")/target/loadtest/arm-g-$1.log"; }

# ---------------- S: 语法 ----------------
echo
echo "== S 语法检查（bash -n） =="
for f in "$REPO/loadtest/run-loadtest.sh" "$REPO/loadtest/run-arm.sh" "$REPO/loadtest/lib-loadtest.sh"; do
  check "bash -n $(basename "$f")" bash -n "$f"
done
check "bash -n guards.sh" bash -n "$REPO/loadtest/tests/guards.sh"

# ---------------- R: run-arm.sh 入口门禁零副作用 ----------------
echo
echo "== R run-arm.sh 入口门禁：拒绝时零副作用（stop/start/prepare/reset 均未调用） =="
run_arm r1                                   # 什么都没有：连写入授权都没有
check "R1 非零退出"            test "$(rc_of "$SBX/log/r1.rc")" != "0"
check "R1 零副作用"            test ! -s "$SBX/log/arm-r1.stub"
check "R1 未创建臂日志"        test ! -e "$(arm_armlog r1)"
check "R1 提示缺少写入授权"    has '缺少显式写入授权' "$SBX/log/r1.out"

run_arm r2 LT_WRITE_AUTHORIZED=1             # 有授权但没选同机、也没给 JMETER_SSH
check "R2 非零退出"            test "$(rc_of "$SBX/log/r2.rc")" != "0"
check "R2 零副作用"            test ! -s "$SBX/log/arm-r2.stub"
check "R2 提示未显式选择同机"  has 'LT_ALLOW_SAME_HOST=1' "$SBX/log/r2.out"

run_arm r3 LT_WRITE_AUTHORIZED=1 JMETER_SSH=user@loadgen   # 分离但未声明物理隔离
check "R3 非零退出"            test "$(rc_of "$SBX/log/r3.rc")" != "0"
check "R3 零副作用"            test ! -s "$SBX/log/arm-r3.stub"
check "R3 提示未声明物理隔离"  has '未声明物理隔离' "$SBX/log/r3.out"

run_arm r4 LT_WRITE_AUTHORIZED=1 JMETER_SSH=user@loadgen \
         LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1 STUB_REMOTE_HOSTNAME=sut-host
check "R4 非零退出（两端同名）" test "$(rc_of "$SBX/log/r4.rc")" != "0"
check "R4 零副作用"            test ! -s "$SBX/log/arm-r4.stub"
check "R4 提示 hostname 相同"  has '两端 hostname 相同' "$SBX/log/r4.out"

# 正向对照：配置齐备时门禁**放行**，副作用才开始发生（证明门禁不是无条件拒绝）
run_arm r5 LT_WRITE_AUTHORIZED=1 JMETER_SSH=user@loadgen \
         LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1
check "R5 配置齐备时副作用发生" has 'SIDE_EFFECT' "$SBX/log/arm-r5.stub"
check "R5 调用了 stop-app"       has 'stop-app.sh' "$SBX/log/arm-r5.stub"
check "R5 调用了 start-app"      has 'start-app.sh' "$SBX/log/arm-r5.stub"
check "R5 调用了 prepare-data"   has 'prepare-data.sh' "$SBX/log/arm-r5.stub"

# ---------------- T: 真实 Git Bash 4.4 时间戳 ----------------
echo
echo "== T 毫秒时间戳（真实 Git Bash 4.4，未注入 EPOCHREALTIME） =="
TS_PROBE="$SBX/ts-probe.out"
env -u EPOCHREALTIME LT_LIB="$LIB" bash -c '
  set -euo pipefail
  . "$LT_LIB"
  if [ -n "${EPOCHREALTIME:-}" ]; then echo "EPOCHREALTIME=present"; else echo "EPOCHREALTIME=absent"; fi
  a="$(now_ms)"
  case "$a" in ""|*[!0-9]*) echo "digits=FAIL($a)"; exit 1;; esac
  if [ "${#a}" -ne 13 ]; then echo "digits=FAIL(len=${#a})"; exit 1; fi
  echo "digits=OK(13, $a)"
  ok=0
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    b="$(now_ms)"
    if [ "$b" -gt "$a" ]; then ok=1; break; fi
    sleep 0.02
  done
  if [ "$ok" != 1 ]; then echo "advance=FAIL"; exit 1; fi
  echo "advance=OK($a -> $b)"
' > "$TS_PROBE" 2>&1
check "T0 now_ms 可用（13 位数字）" has 'digits=OK' "$TS_PROBE"
check "T0 now_ms 随时间前进"       has 'advance=OK' "$TS_PROBE"
check "T0 确认未注入 EPOCHREALTIME" has 'EPOCHREALTIME=absent' "$TS_PROBE"

# 回退路径成本（如实记录，不默称原口径不变）
N=200
c0="$(date +%s%3N)"; for _ in $(seq 1 $N); do _x="$(date +%s%3N)"; done; c1="$(date +%s%3N)"
d0="$(date +%s%3N)"; for _ in $(seq 1 $N); do _x="$(date +%s)"; done;    d1="$(date +%s%3N)"
echo "  [report] now_ms 回退路径 date +%s%3N: $(( (c1 - c0) * 1000 / N )) us/次（$((c1 - c0))ms / $N）"
echo "  [report] 对照 date +%s（秒）:          $(( (d1 - d0) * 1000 / N )) us/次"

# 跑真实脚本：不得因 unbound variable 终止；排空走真实时钟
run_load t_local LT_ALLOW_SAME_HOST=1
check "T1 本地轮退出码 0"        test "$(rc_of "$SBX/log/t_local.rc")" = "0"
check "T1 无 unbound variable"   not_has 'unbound variable' "$SBX/log/t_local.out"
check "T1 排空判定已打印"        has '已排空' "$SBX/log/t_local.out"
check "T1 时间线 ts 为 13 位"    awk -F, 'NR>1{ if ($1 !~ /^[0-9]+$/ || length($1) != 13) exit 1 }' "$OUT/timewait-t_local.csv"

# 排空超时：netstat 恒大于阈值 ⇒ 必须超时失败。
# TIME_WAIT_TIMEOUT_S=2（不是 1）：deadline=TW_START+2，首轮探测发生在 ~0.1s 后，即便跨秒
# 边界最多只到 TW_START+1 < deadline ⇒ 必然先 sleep 5 再判超时。这样「耗时 >= 4s」是确定值，
# 不会因秒边界让首轮即命中超时（>=4s 正是用来证明超时判据走真实时钟、未被固定假时钟掩盖）。
t_start="$(date +%s)"
run_load t_timeout LT_ALLOW_SAME_HOST=1 STUB_NETSTAT_TW_COUNT=2000 TIME_WAIT_TIMEOUT_S=2
t_end="$(date +%s)"
check "T2 超时轮非零退出"        test "$(rc_of "$SBX/log/t_timeout.rc")" != "0"
check "T2 报排空超时"            has '排空超时' "$SBX/log/t_timeout.out"
check "T2 声明不得作为样本"      has '不得作为合格的分离容量样本' "$SBX/log/t_timeout.out"
check "T2 轮前排空确实等待了真实时钟（>=4s）" test $(( t_end - t_start )) -ge 4
check "T2 排空过程至少探测 2 次"  awk -F, 'NR>1{ n++ } END{ exit (n >= 2 ? 0 : 1) }' "$OUT/timewait-t_timeout.csv"

# 显式跳过排空：不再被当成合格样本
run_load t_skip LT_ALLOW_SAME_HOST=1 SKIP_TIME_WAIT_WAIT=1
check "T3 跳过排空轮非零退出"    test "$(rc_of "$SBX/log/t_skip.rc")" != "0"
check "T3 声明跳过不得作样本"    has '跳过了轮前 TIME_WAIT 排空' "$SBX/log/t_skip.out"

# ---------------- W: TIME_WAIT 探测语义（直接驱动真实函数） ----------------
echo
echo "== W TIME_WAIT 探测：真零 vs 探测失败 =="
w_run() { # w_run <outfile> [env...]  -> 回显退出码
  local outf="$1"; shift
  env -u EPOCHREALTIME PATH="$BIN:$PATH" LT_LIB="$LIB" "$@" \
    bash -c '. "$LT_LIB"; lt_tw_count' > "$outf" 2>"$outf.err"
  echo $?
}

W="$SBX/log"
w_rc="$(w_run "$W/w_zero.out" STUB_NETSTAT_TW_COUNT=0)"
check "W1 无匹配 = 真零（rc=0）"   test "$w_rc" = "0"
check "W1 真零输出就是 0"          test "$(tr -d '\r\n' < "$W/w_zero.out")" = "0"

w_rc="$(w_run "$W/w_sshfail.out" JMETER_SSH=user@loadgen STUB_SSH_NETSTAT_RC=7)"
check "W2 SSH 非零 → 探测失败"     test "$w_rc" != "0"
check "W2 失败时不得输出 0"        test "$(tr -d '\r\n' < "$W/w_sshfail.out")" != "0"
check "W2 报错含 ssh-rc"           has 'ssh-rc=7' "$W/w_sshfail.out.err"

w_rc="$(w_run "$W/w_nonetstat.out" JMETER_SSH=user@loadgen STUB_SSH_NETSTAT_OUT='LT_PROBE_FAIL:no-remote-netstat')"
check "W3 远端无 netstat → 失败"   test "$w_rc" != "0"
check "W3 失败时不得输出 0"        test "$(tr -d '\r\n' < "$W/w_nonetstat.out")" != "0"
check "W3 报错含 no-remote-netstat" has 'no-remote-netstat' "$W/w_nonetstat.out.err"

w_rc="$(w_run "$W/w_illegal.out" JMETER_SSH=user@loadgen STUB_SSH_NETSTAT_OUT='abc not a number')"
check "W4 非法输出 → 探测失败"     test "$w_rc" != "0"
check "W4 失败时不得输出 0"        test "$(tr -d '\r\n' < "$W/w_illegal.out")" != "0"
check "W4 报错含非法输出"          has '非法输出' "$W/w_illegal.out.err"

# W5/W6：netstat **存在但执行非零退出** —— 最易被吞的一格。
# `command -v netstat` 只能证明命令存在；`netstat ... | grep -c TIME_WAIT || true` 在 netstat 失败时
# 会打印 0（grep 无匹配），把「没跑成」伪装成「真零」。负例断言：非零退出 + 不输出 0 + 报 netstat-rc。
# 远端负例走替身 ssh 真正执行命令串，故它检验的是「远端命令本身不再吞掉 netstat 失败」，而非预置答案。
w_rc="$(w_run "$W/w_localexecfail.out" STUB_NETSTAT_EXEC_RC=7)"
check "W5 本地 netstat 执行失败 → 探测失败" test "$w_rc" != "0"
check "W5 失败时不得输出 0"                 test "$(tr -d '\r\n' < "$W/w_localexecfail.out")" != "0"
check "W5 报错含 local-netstat-rc"          has 'local-netstat-rc' "$W/w_localexecfail.out.err"

w_rc="$(w_run "$W/w_remoteexecfail.out" JMETER_SSH=user@loadgen STUB_NETSTAT_EXEC_RC=7)"
check "W6 远端 netstat 执行失败 → 探测失败" test "$w_rc" != "0"
check "W6 失败时不得输出 0"                 test "$(tr -d '\r\n' < "$W/w_remoteexecfail.out")" != "0"
check "W6 报错含 remote-netstat-rc"         has 'remote-netstat-rc' "$W/w_remoteexecfail.out.err"

# ---------------- I: 远端探测失败 → 本轮失败 ----------------
echo
echo "== I 远端 TIME_WAIT 探测失败 → 本轮失败 =="
run_load t_twfail JMETER_SSH=user@loadgen LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1 STUB_SSH_NETSTAT_RC=7
check "I1 非零退出"              test "$(rc_of "$SBX/log/t_twfail.rc")" != "0"
check "I1 报 TIME_WAIT 探测失败" has 'TIME_WAIT 探测失败' "$SBX/log/t_twfail.out"
check "I1 声明不得作为样本"      has '不得作为合格的分离容量样本' "$SBX/log/t_twfail.out"

# ---------------- N: 正常远端执行 + 结果回收 ----------------
echo
echo "== N 正常远端执行 + 结果回收可验证 =="
run_load t_remote JMETER_SSH=user@loadgen LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1
check "N1 退出码 0"              test "$(rc_of "$SBX/log/t_remote.rc")" = "0"
check "N1 前置通过并声明隔离"    has '物理隔离已人工声明' "$SBX/log/t_remote.out"
check "N1 已回收获数行"          has '已回收' "$SBX/log/t_remote.out"
check "N1 远端 JMeter 目标正确"  has "-Jhost='10.0.0.5'" "$SBX/log/ssh.log"
check "N1 远端未落到 127.0.0.1"  not_has "-Jhost='127.0.0.1'" "$SBX/log/ssh.log"
check "N1 逐笔 CSV 已落盘非空"   test -s "$OUT/submit-results-t_remote.csv"

# 回收失败 → 本轮失败
run_load t_scpfail JMETER_SSH=user@loadgen LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1 STUB_SCP_RESULTS_FAIL=1
check "N2 回收失败轮非零退出"    test "$(rc_of "$SBX/log/t_scpfail.rc")" != "0"
check "N2 报未成功回收"          has '未成功回收' "$SBX/log/t_scpfail.out"

# 原始替身交互日志（ssh/scp/jmeter 的真实调用记录）留档到 target/：
# 沙箱会在退出时被 trap 清掉，而「远端到底收到什么命令」是这套护栏最原始的证据，
# 故先复制一份到 target/loadtest/guards-logs/（`mvn clean` 前需再复制出树）。
LOGDST="$REPO/target/loadtest/guards-logs"
rm -rf "$LOGDST" 2>/dev/null || true
mkdir -p "$LOGDST"
cp "$SBX"/log/jmeter.log "$SBX"/log/ssh.log "$SBX"/log/scp.log "$LOGDST"/ 2>/dev/null || true
cp "$SBX"/log/*.out "$SBX"/log/*.rc "$LOGDST"/ 2>/dev/null || true
echo "  [report] 原始替身交互日志（ssh/scp/jmeter）已留档：$LOGDST"

echo
echo "== 汇总：PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" = "0" ]
