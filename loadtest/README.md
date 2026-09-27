# 交卷链路 5000 并发压测资产（add-submit-loadtest；tune-submit-capacity 修订 G1/G2/G4/G5）

本目录是可复现的交卷压测资产：**任何人按下面步骤都能在真 dev 环境重建同场景**。
它服务于 `spec/specs/exam-taking/spec.md` 的「交卷落库容量与时延」需求——
该需求原先只有参数生效与容量估算场景，本资产补上真跑证据。

对应提案：`spec/changes/add-submit-loadtest/proposal.md`（原始资产）、
`spec/changes/tune-submit-capacity/proposal.md`（容量裁决与压测复验）；实测报告：`docs/submit-loadtest-report.md`。

---

## 1. 目录内容

| 文件 | 作用 |
| --- | --- |
| `jmeter/submit-5000.jmx` | JMeter 场景：阶段1 登录取 token（前置）→ 阶段2 5000 并发交卷 |
| `db/01-prepare.sql` | 造数：压测专用试卷/考试 + 5000 学生 + 5000 条「进行中」答卷 |
| `db/02-metrics.sql` | 指标采集：丢单计数、落库时效（两侧端点都取 DB 列） |
| `db/03-cleanup.sql` | 清理：只删压测专用命名空间，把 dev 库恢复原状 |
| `db/04-reset.sql` | 复跑复位：答卷退回「待交卷」+ 清防重表，同一批数据可重复压测 |
| `prepare-data.sh` | 取真实 BCrypt 哈希 → 渲染 → 执行 `01-prepare.sql`（幂等） |
| `start-app.sh` | **G2 注入点**：起 dev 实例并把容量参数经环境变量注入（relaxed binding，零文件改动），落 `app-env-*.txt` / `app-proof-*.txt` |
| `stop-app.sh` | 停 dev 实例（换臂 = 换实例；只杀确为 `exam-online.jar` 的 8080 监听进程） |
| `run-arm.sh` | **G4 编排**：一臂 N 轮，每轮 = 复位 → 预热轮 → 复位 → 正式轮；跑完打印臂级中位数 |
| `run-loadtest.sh` | 单轮：轮前等 TIME_WAIT 排空 → prom 前置快照 → 持续采样 → JMeter → prom 后置快照 → 等积压归零 → DB 指标 |
| `lib-loadtest.sh` | 共享前置：入口门禁（宿主/物理隔离/写入授权）、可移植 `now_ms`、TIME_WAIT 探测（真零 vs 失败） |
| `tests/guards.sh` | 回归护栏：用替身直接跑上面两个脚本，验证门禁零副作用、Git Bash 4.4 时间戳、探测失败传播、远端结果回收；不触真实 DB/应用/Docker/负载宿主 |
| `analyze-results.py` | 从逐笔 CSV 算 P50/P90/P95/P99/失败明细（只取交卷样本，不混登录） |
| `compare-runs.py` | 多轮/多臂汇总：客户端逐笔 + 服务端净增量 + 连接池净增量 + 臂级中位数 |
| `summarize-samples.py` | **G5**：把采样时间线压成峰值表（Tomcat 线程水位 / Hikari pending·active / CPU / 队列 / DB 连接） |
| `probe/ProbeTomcatThreads.java` | 探针：从构建所用 jar 读出 Tomcat 线程/接受队列默认值（运行期读不到） |


产物全部落在 `target/loadtest/`（`target/` 本就 gitignored），不入库。
**注意**：`target/` 同时是 Maven 构建目录，**跑 `mvn clean` 会把历轮原始产物一起删掉**；
需要长期留档就先复制出工作区。报告里的汇总数字可用上表脚本随时重算，不依赖这些文件存活。

## 2. 环境要求

- dev 栈容器在跑：MySQL 主库 + 从库、RabbitMQ、Redis（本机复用既有共享容器，**不要重建**）。
- dev 应用实例在跑（默认 `127.0.0.1:8080`）。若用 Maven 的 fork 环境启动，**务必显式指定
  `--server.port=`**：该环境可能注入 `SERVER_PORT`，默认端口会被顶掉。
- JMeter 5.6.3（本机路径见 `run-loadtest.sh` 的 `JMETER_HOME` 默认值）。
- `mysql` 客户端与 `python` 在 PATH 中；`docker` 可用（脚本用 `docker exec ... rabbitmqctl`
  只读队列深度）。

## 3. 执行步骤

### 3.1 推荐路径：按「臂」跑（G4 方法学，判定只认它）

```bash
# 0) 前置：docker 栈在跑（主库 13316 / 从库 13317 / RabbitMQ 5672+15672）、Redis 在跑、JMeter 就位
#    入口门禁（在停/启应用、造数、复位**之前**执行；不通过即非零退出且零副作用）：
#      LT_WRITE_AUTHORIZED=1   必填。显式写入授权——run-arm.sh 会停/启应用、造数与复位数据库；
#                              env 里声明一个变量**不是**用户授权。
#      LT_ALLOW_SAME_HOST=1    只有在「确实没有第二台宿主、且只要历史同机记录」时显式选择；
#                              该臂只能标注为「未分离」，不得当作分离后的容量结论。

# 1) 默认臂（不注入容量参数 = 框架默认线程上限 200 / master 池 20）
ARM=default ROUNDS=3 LT_WRITE_AUTHORIZED=1 LT_ALLOW_SAME_HOST=1 bash loadtest/run-arm.sh

# 2) 调参臂 A（G2 注入：经环境变量走 relaxed binding，零文件改动）
ARM=tuned-t400 ROUNDS=3 SERVER_TOMCAT_THREADS_MAX=400 \
  LT_WRITE_AUTHORIZED=1 LT_ALLOW_SAME_HOST=1 bash loadtest/run-arm.sh

# 3) 调参臂 B（把线程上限的下游一并打开：master 池上限）
#    §2.4 证明单独提高线程上限是负优化（约束被推给池），故追加此臂
ARM=tuned-t400-p100 ROUNDS=3 SERVER_TOMCAT_THREADS_MAX=400 DB_POOL_MAX=100 \
  LT_WRITE_AUTHORIZED=1 LT_ALLOW_SAME_HOST=1 bash loadtest/run-arm.sh

# 4) 三臂并置 + 中位数判定（判定只看臂级中位数，单轮不作为结论）
python loadtest/compare-runs.py default=default-r1,default-r2,default-r3 \
                               tuned-t400=tuned-t400-r1,tuned-t400-r2,tuned-t400-r3 \
                               tuned-t400-p100=tuned-t400-p100-r1,tuned-t400-p100-r2,tuned-t400-p100-r3

# 5) 恢复环境（只删压测命名空间）
#    --default-character-set=utf8mb4 不是可选项：本机客户端默认 character_set_client=gbk，
#    漏掉它会让脚本里的中文字面量**静默失真**，DELETE 匹配 0 行、末尾自证也报 0（假绿）。
#    实测复现与机理见报告 §8.7。
mysql -h127.0.0.1 -P13316 -uroot -p --default-character-set=utf8mb4 exam_online < loadtest/db/03-cleanup.sql
# 复位后必须独立复核（不能用同一字面量的自证代替）：
mysql -h127.0.0.1 -P13316 -uroot -p --default-character-set=utf8mb4 exam_online -t -e \
  "SELECT (SELECT COUNT(*) FROM users WHERE username LIKE 'lt5k\\_%') AS users, \
          (SELECT COUNT(*) FROM exams WHERE title='LOADTEST-5000-并发交卷') AS exams;"
```

`run-arm.sh` 每轮的顺序是固定的，不能颠倒：**复位数据 → 预热轮 → 复位数据 → 正式轮**
（`run-loadtest.sh` 会在正式轮前自己等 TIME_WAIT 排空）。预热轮用 `WARMUP_THREADS`（默认 500）
小规模跑一轮，只为把 JIT 打热——冷 JVM 的 P99 比热 JVM 高 68%（见报告 §5），不预热等于在量 JIT。

单轮/单臂也可以手工跑，用于排查资产本身（**不得作为容量结论**；同样有入口门禁）：

```bash
ARM=probe LT_WRITE_AUTHORIZED=1 LT_ALLOW_SAME_HOST=1 bash loadtest/start-app.sh   # 起实例（注入写在 ARM_LOG / app-proof-*.txt）
TAG=probe LT_WRITE_AUTHORIZED=1 LT_ALLOW_SAME_HOST=1 bash loadtest/run-loadtest.sh  # 5000 并发正式一轮
TAG=dry SUBMIT_THREADS=20 SUBMIT_RAMP=2 LOGIN_RAMP=2 \
  LT_WRITE_AUTHORIZED=1 LT_ALLOW_SAME_HOST=1 bash loadtest/run-loadtest.sh
python loadtest/analyze-results.py target/loadtest/submit-results-probe.csv   # P99
python loadtest/summarize-samples.py target/loadtest/sample-probe.csv target/loadtest/mq-depth-probe.csv
```

> `SKIP_TIME_WAIT_WAIT=1` 现在**会让该轮以非零退出**（跳过排空 = 无法证明临时端口已排空，
> 不得当成合格样本），只能用于调试脚本本身，不能用来「省时间跑一轮」。

### 3.2 逐轮产物与「判定的数据源」

| 产物 | 判什么 |
| --- | --- |
| `submit-results-<TAG>.csv` | 提交 P99（硬指标，`analyze-results.py` / `compare-runs.py`） |
| `metrics-<TAG>.txt` | 0 丢单、批量落库 < 30s（硬指标） |
| `prom-before/after-<TAG>.txt` | 服务端 sum/count 的**本轮净增量**（服务时长）、连接池累计量净增量 |
| `sample-<TAG>.csv` | G5 应用侧时间线（Tomcat 线程水位、Hikari pending/active、进程/系统 CPU）峰值 |
| `mq-depth-<TAG>.csv` | DB/MQ 侧时间线（队列深度、未落库计数、MySQL 连接数）峰值 |
| `timewait-<TAG>.csv` | 轮前 TIME_WAIT 排空过程（判「本轮是否在临时端口耗尽状态下跑的」） |
| `app-proof-<ARM>.txt` | G2 生效证据（运行期读到的 `tomcat_threads_config_max_threads`） |

**复跑（换参数对比，不重新造数）**——顺序不能颠倒：

```bash
# 1) 停实例 → 用新参数重启（容量参数都是启动期读取的环境变量）
bash loadtest/stop-app.sh
ARM=r2 SERVER_TOMCAT_THREADS_MAX=400 bash loadtest/start-app.sh
# 2) 复位数据到「5000 待交卷」（幂等；自证须打印 5000 / 0 / 0 / 0）
#    同样必须带 --default-character-set=utf8mb4：漏掉它 @exam_id 会静默变 NULL，
#    复位（和 02-metrics.sql 的取数）全部静默变成 0 —— 见报告 §8.7
mysql -h127.0.0.1 -P13316 -uroot -p --default-character-set=utf8mb4 exam_online < loadtest/db/04-reset.sql
# 3) 确认队列归零（否则上一轮积压会算进这一轮的落库时效）
curl -s -u exam:exam123 http://127.0.0.1:15672/api/queues/%2F/exam.submit.queue
# 4) 换 TAG 再跑，保留上一轮产物以便对比
TAG=run2 bash loadtest/run-loadtest.sh
```

复位脚本**不重建数据**（造数约 7s，复位不到 1s），且只动压测命名空间。它清空的三样东西
（`status` 回 1、`answers` 置 NULL、防重表删空）缺一不可，否则复跑会撞唯一索引或直接被
判为重复提交，压不到目标路径。

小规模试跑（验证资产本身，不产生正式结论）：

```bash
TAG=dryrun SUBMIT_THREADS=20 SUBMIT_RAMP=2 LOGIN_RAMP=2 bash loadtest/run-loadtest.sh
```

可覆盖的变量都在 `run-loadtest.sh` 顶部「可用环境变量」段落里。

### 3.3 双宿主：把压测进程与被测进程分开（isolate-submit-load-generator）

默认跑法把 JMeter 与被测应用放在同一台机器：**那只能得到「未分离」的历史记录**，不能当作分离后的
达标/未达标结论（同机时 JMeter 的 5000 线程与应用抢同一批 CPU/磁盘，报告 §7.2 证据 B 的
「整机 1.00、应用仅 0.43 核」就是这个成因）。

分离后的角色分工（**只有这一种分工**）：

| 角色 | 宿主 | 跑什么 |
| --- | --- | --- |
| 控制端（SUT） | 被测应用所在宿主 | 应用实例、Docker 栈（MySQL/RabbitMQ）、`run-arm.sh`、采样器（`/actuator/prometheus`、MySQL、RabbitMQ 队列）、复位/清理 |
| 负载端（loadgen） | **另一台物理宿主** | 只跑 JMeter（JMX 由控制端推送过去）；临时端口/TIME_WAIT 也在这一侧 |

`.jmx` 的请求目标由 `run-loadtest.sh` 的 `-Jhost/-Jport` 决定，默认从 `APP_BASE_URL` 推导
（历史版本曾硬编码 `127.0.0.1:8080`）；**只改 `APP_BASE_URL` 只是把目标地址改了，并没有完成分离**——
分离还要求 JMeter 进程确实跑在另一台宿主上，故必须设 `JMETER_SSH`。

```bash
# 在 SUT 宿主的仓库根执行；凭据只从安全环境变量或人工配置取得，不写进仓库/日志/回报。
export JMETER_SSH=loadgen-user@loadgen-host     # 触发负载端执行（缺 ssh/scp 即 fail-closed）
export JMETER_REMOTE_HOME=/opt/apache-jmeter    # 负载宿主上的 JMeter 目录（POSIX 路径）
export LOADGEN_DIR=/tmp/loadtest                # 负载宿主上的暂存目录（可选，默认 /tmp/loadtest-<TAG>）
export LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1    # 人工核实两台宿主不共享物理 CPU/磁盘后声明（必需）
export LT_WRITE_AUTHORIZED=1                    # 显式写入授权（必需；env 里给个变量不等于用户授权）
ARM=default ROUNDS=3 bash loadtest/run-arm.sh
```

**门禁的执行位置**（isolate-submit-load-generator 返修）：`run-arm.sh` 会在**任何** `stop-app.sh` /
`start-app.sh` / `prepare-data.sh` / `db/04-reset.sql` **之前**先跑这套判定（`loadtest/lib-loadtest.sh`
的 `lt_preflight_isolation`）。判定不通过 ⇒ 立刻非零退出，**停/启应用、造数、复位一次都不会被调用**，
也不会创建臂日志。`run-loadtest.sh` 被单独调用时同样会判一次（fail-closed，不默认放行）。
这样「缺配置的缺省调用」不会先产生副作用再在单轮入口才发现被拒。

**如何确认两边不在同一宿主**（不是只看主机名）：

1. `lt_preflight_isolation` 打印两端 `hostname` 并要求**不同**，相同即拒绝启动；
2. 但「不同 hostname / 容器名 / VM 名」**不足以**证明不抢同一物理宿主资源，故还要求
   `LOADGEN_PHYSICAL_ISOLATION_ATTESTED=1` 的人工声明；未声明即 fail-closed，不启动压测；
3. 运行时以证据核实：控制端 `sample-*.csv` 的 `sys_cpu` 与负载端 JMeter 的 CPU 分别记录，
   两者不应再把同一台机器的核打满；网络拓扑改变会影响延迟，**不得把前后差值全归因于应用变快**。

**结果回收是可验证的**：JMeter 的逐笔 CSV / token CSV / 日志由 `scp` 从负载端拉回控制端的
`target/loadtest/`，`run-loadtest.sh` 会核验逐笔 CSV 存在且非空；拉回失败/空文件即判本轮失败
（非零退出），不会退回同机、也不会静默沿用旧文件。

**失败传播**：JMeter 非零退出、轮前/轮后队列未归零、等积压归零超时、远端产物未回收、
**轮前 TIME_WAIT 探测失败**、**轮前排空超时**、**显式跳过轮前排空**——任一条都会让该轮以非零退出；
`run-arm.sh` 据此把该轮排除出臂级中位数，并在整臂有失败轮时以非零退出。
即：**失败轮不会再被报成成功**。

**TIME_WAIT 探测的两种结果必须可区分**（`loadtest/lib-loadtest.sh` 的 `lt_tw_probe` / `lt_tw_count`）：
「命令成功且确实为 0」= 真的排空了（放行本轮）；「ssh 非零退出 / 远端没有 `netstat` / 输出非法」
= **探测失败**，返回非零 ⇒ 该轮判失败，**不得把空值或非法输出改写成 0** 来伪装成已排空。
双宿主下查的是**负载宿主**的 TIME_WAIT（临时端口耗尽发生在压测机一侧）。

### 3.4 回归护栏（替身，不触真实服务）

改 `run-arm.sh` / `run-loadtest.sh` / `lib-loadtest.sh` 后，先在 Git Bash（本机 `D:\git\Git\bin\bash.exe`，
4.4.23；`C:\Windows\System32\bash.exe` 是坏的 WSL，别用）跑一遍护栏：

```bash
& 'D:\git\Git\bin\bash.exe' loadtest/tests/guards.sh     # 全部通过退出 0
```

它用 PATH 前缀的替身（`curl`/`mysql`/`docker`/`hostname`/`netstat`/`ssh`/`scp`/`jmeter`）直接驱动真实脚本，
**不连真实 DB、应用、Docker 或负载宿主**，覆盖：

- `R*`：入口门禁拒绝时**零副作用**（`stop-app`/`start-app`/`prepare-data`/`04-reset.sql` 均未被调用、
  臂日志未创建），并有「配置齐备时副作用才发生」的正向对照；
- `T*`：真实 Git Bash 4.4 下（**不注入** `EPOCHREALTIME`）`now_ms` 可用且随时间前进；排空超时按真实时钟推进
  （`>=4s`），不被固定假时钟掩盖；
- `W*`/`I*`：TIME_WAIT 探测「真零」与「SSH 非零 / 远端无 netstat / 非法输出」可区分，后者让本轮失败；
- `N*`：正常远端执行 + 逐笔 CSV 回收可验证，回收失败即本轮失败。

护栏写在 `loadtest/tests/`（入库），判据是**真的调用**这些脚本看行为，不是 grep 脚本文本。

## 4. 场景设计与「交卷限流」的关系（重要）

交卷接口带 `@RateLimit(qps = 500, capacity = 2000, key = "submit")`，而 `RateLimitInterceptor`
按**接口维度**（不是用户维度）取令牌，即全局只有一把桶 `exam:ratelimit:submit`。
N 笔请求在 t 秒内到达时的放行上限 = `capacity + qps × t` = `2000 + 500t`：

| 到达方式 | 放行上限 | 结果 |
| --- | --- | --- |
| 5000 笔全在 <1s 内到达 | ≈ 2000 + 500 | 约 3000 笔得 429（被限流器按设计拒绝） |
| 5000 笔在 10s 内到达（本场景） | ≈ 2000 + 5000 = 7000 > 5000 | 全部放行，429 ≈ 0 |

本资产选 **ramp-up = 10s（约 500 笔/s 到达）**，让 5000 笔落在令牌桶预算内——
这是**场景整形**（模拟「5000 人在几秒内陆续点交卷」），**没有修改任何运行期限流参数**。
理由与代价都写进了 `docs/submit-loadtest-report.md`。

若要观察限流器在真实配置下的拒绝行为，把 `SUBMIT_RAMP` 调小（如 1）即可复现 429 语义。

## 5. 硬指标的取样口径

| 指标 | 口径 | 数据源 |
| --- | --- | --- |
| 提交 P99 < 2s | 只取 `label = POST /submit` 且断言通过的样本；429/5xx 记失败，不混入成功延迟 | `analyze-results.py` |
| 0 丢单 | `status=2`（已被接受）但 `answers IS NULL` 的行数 = 0 | `db/02-metrics.sql` |
| 批量落库 < 30s | `MAX(updated_time where answers 非空) − MAX(submit_time)`，两侧端点都取自 DB 列 | `db/02-metrics.sql` |
| 队列深度 / 消费积压 | 应用侧 0.25s / DB·MQ 侧 1s 一次只读采样（管理 API，失败退 `rabbitmqctl`） | `sample-*.csv` / `mq-depth-*.csv` |

「被接受」的判据是交卷接口返回 2xx：此时状态机 CAS 已把 `status` 置 2 且 `submit_time` 已落库，
答案由 MQ 消费者异步批量落库——所以「丢单」只可能是「已接受但答案没落库」。
被限流拒绝（429）的请求**不产生答卷行，不构成丢单**，但会作为失败样本暴露在逐笔 CSV 里。

## 6. 测量纪律（不遵守会得到错误结论）

下面三条都已在真机上被咬过（证据见 `docs/submit-loadtest-report.md` §3.3 与 §5），
**tune-submit-capacity 起它们不再是「人工纪律」而是脚本动作**（括号里是执行者）：

1. **测量前必须预热 JVM**。同一场景、同一组参数，仅换「实例是否已预热」，
   交卷 P99 就从 2200ms 变到 3700ms（差 68%）。冷实例的首轮结果主要反映 JIT
   编译开销，而不是系统容量。（`run-arm.sh`：每轮正式轮前先跑一轮 `WARMUP_THREADS` 小规模）
2. **轮间必须等客户端 TIME_WAIT 排空**。每轮约产生 1 万条连接残留；不等就复跑会出现
   「登录连不上 → 该线程拿不到 token → 交卷假 401」以及客户端 `BindException:
   Address already in use: connect`，把假失败和虚高的 P99 混进结果。
   实测 9531 条 TIME_WAIT 约需 2 分钟自然排空（Windows 默认 `TcpTimedWaitDelay=120s`）。
   查法：`netstat -an | grep -c TIME_WAIT`。**探测失败（ssh 非零 / 远端无 netstat / 输出非法）
   不再被改写成 0**：该轮直接判失败，不进入臂级中位数；`SKIP_TIME_WAIT_WAIT=1` 与排空超时同理
   （即：跳过排空或没排空，都不算合格的分离容量样本）。（`run-loadtest.sh` 第 2 步：阈值
   `TIME_WAIT_MAX`，另有「连续 30s 不再下降且已到本机基线」的提前退出，过程写 `timewait-*.csv`）
3. **每臂至少 3 轮取中位数**。单轮单点无法区分「参数效应」与「环境漂移」，
   报告里只能写区间、不能写归因。（`ROUNDS=3` + `compare-runs.py` 的臂级中位数表）

## 6.1 容量参数注入（G2：不改代码、不改配置文件）

`SERVER_TOMCAT_THREADS_MAX` → `server.tomcat.threads.max`，靠 Spring Boot **relaxed binding**
由环境变量直接注入。dev 应用跑在**宿主机**（不在容器里），所以注入点是 `start-app.sh`，
**不是** `docker-compose.yml`（那个文件只编排 MySQL/Redis/RabbitMQ/Jaeger）。

生效证据不靠自述：`app-proof-<ARM>.txt` 里记录运行期从 `/actuator/prometheus` 读到的
`tomcat_threads_config_max_threads`（add-submit-observability 暴露），**没改 `src/main` 也能证明生效**。

## 6.2 连接池证据的两条线（G5）

| 证据线 | 数据源 | 强度与限制 |
| --- | --- | --- |
| 采样峰值 | `sample-*.csv` 的 `hikari_pending/active` | 受采样周期限制：本机每次进程 spawn ~0.43s，采样周期实测 ~3s，10s 突发窗口只能取到 3~5 点 ⇒ **峰值可能漏尖峰**，只能当下界 |
| 累计量净增量 | `prom-before/after-*.txt` 做差：`hikaricp_connections_acquire_seconds_{count,sum,max}`、`usage_seconds_max`、`timeout_total` | **不依赖采样**：Δ取连接次数/平均等待回答「池是否排队」，`timeout_total` 增量回答「有没有取不到连接」，`*_max` 是 2 分钟滑窗最差值（紧跟突发取即覆盖该轮） |


## 7. 本机环境踩过的坑（改脚本前先读）

1. **`curl -o /dev/null` 会失败**：本机 `curl` 是 Windows 版，只认 `NUL`；写成 `/dev/null`
   会以 `CURLE_WRITE_ERROR(23)` 退出，脚本在 `set -e` 下表现为「静默退出 23」。
2. **`mysql` 客户端默认 `character_set_client=gbk`，与 UTF-8 的 SQL 文件混用时行为分两种，第二种最危险**：
   所有调用都要带 `--default-character-set=utf8mb4`（脚本里已全部带上，漏的是手工命令）。
   - **纯中文字面量 → 报错**：`ERROR 1267 Illegal mix of collations (utf8mb4_0900_ai_ci,IMPLICIT) and (gbk_chinese_ci,COERCIBLE)`，批处理中止、退出码 1（**会**被发现）。
   - **ASCII+中文混合字面量 → 静默失真**：不报错、退出码 0，但字面量被解成别的字符，`=` 比较恒为假。
     实测（同一文件、同一字面量 `'LOADTEST-5000-并发交卷'`，仅换连接字符集）：
     ```text
     # 默认 gbk：'…' = _utf8mb4'…'  → 0（不相等）  CONVERT(… USING utf8mb4) → …2D E9AA9E E8B7BA …（乱码）
     # utf8mb4 ：'…' = _utf8mb4'…'  → 1（相等）    CONVERT(… USING utf8mb4) → …2D E5B9B6 E58F91 …（正确）
     ```
     ⇒ `03-cleanup.sql` 的 `DELETE … WHERE title='…'` 与它末尾的自证 `leftover_exams` **用同一个失真字面量**，
     于是「删了 0 行」和「残留 0 行」同时成立，**自证打印全 0 的绿色结果而复位根本没执行**（实测复现，见报告 §8.7）。
     ⇒ `04-reset.sql` / `02-metrics.sql` 的 `@exam_id` 同样依赖这个字面量：漏参数会静默变成 `NULL`，
     于是**所有硬指标静默变成 0**——「0 丢单」会变成假绿。
   - 因此：① 所有 `mysql` 调用显式带 `--default-character-set=utf8mb4`；
     ② **复位/取数之后必须用独立查询复核**，不得用脚本自带的自证代替复核。
3. **`git bash` 不做 POSIX→Windows 路径转换**：交给 `java` / `python.exe` / `jmeter` 的路径必须是
   `D:/...` 形态；写成 `/d/...` 会被解释成 `D:\d\...`（JMeter 报 `Unable to access jarfile`）。
   同样适用于 `javap` / `jar` / `javac`：`javap -classpath /d/code/... ` 会静默报
   「找不到类」，看起来像类不存在，其实只是路径没被认出来。
4. **JMX 结构**：元素与其子节点树是兄弟关系，**每个元素后面都必须紧跟一个 `<hashTree>`**，
   叶子元素也要写空树 `<hashTree/>`。漏写会让元素与树错位配对，JMeter 报
   `ClassCastException: HTTPSamplerProxy cannot be cast to HashTree`，行号还指向无关位置。
5. **`ctx.getThreadNum()` 是 0 基**（线程名 `1-1` 对应 threadNum 0）。场景里改用
   `props` 共享锁 + 自增计数器分配学号，与线程编号语义解耦。
6. **结果 CSV 的表头只在「文件不存在」时写**：用 `: > file` 截成空文件不会得到表头，
   会导致 `-g` 生成仪表盘报列数不匹配。脚本改用 `rm -f` 删除旧文件。
7. **JMeter 默认堆只有 1g**（`bin/jmeter` 的 `HEAP` 默认值），5000 线程不够；
   `run-loadtest.sh` 通过 `JMETER_HEAP` 抬到 2g（本机内存有限，不盲目抬高）。
8. **`rm` 在本环境被安全删除钩子接管**：删工作区内 `target/` 下的文件正常；
   删工作区外的临时目录路径会 fail-closed 报错，所以渲染产物落在 `target/` 而不是 `mktemp`。
9. **（环境级，非脚本问题）Docker Desktop 波动会让宿主端口代理失效**：容器本身
   `Up (healthy)`、数据完好，但宿主 `13316/13317/5672` 突然不通，且**容器状态与端口映射
   可能错位**（实测 `13317` 曾映射到 master 实例）。症状是脚本第 1 步 `actuator/health`
   探测失败或 MySQL 连接被拒。处理：`docker restart <容器名>`，重启后务必按
   README 第 2 节的端口逐一复核（主库/从库各连一次 + 队列可读）。这不是脚本缺陷，
   改脚本解决不了。
10. **本机 `javac` 默认按 GBK 解码源文件**：源码里有中文注释就会报一串
    「编码 GBK 的不可映射字符」。跑 `probe/` 里的探针时必须显式 `-encoding UTF-8`；
    且探针的 classpath 要**三个 jar**（`spring-boot-autoconfigure` + `spring-boot` +
    `spring-core`）——少 `spring-core` 会以 `NoClassDefFoundError: DataSize` 失败，
    看起来像类不存在。版本不要用 glob 猜：本机 `.m2-repo` 里躺着 6 代 spring-core。
    完整命令见 `probe/ProbeTomcatThreads.java` 文件头注释。
11. **本机进程 spawn ~0.43s/次，它是采样周期的头号成本**（实测：`date` 10 次 3.9s、
    `python -c` 10 次 5.3s、`awk` 10 次 5.4s；整份 `/actuator/prometheus` 才 32KB，
    curl 一次 0.51s 里大半是进程启动）。所以：
    - `now_ms` 优先用 bash5 内建 `EPOCHREALTIME`（0 次 fork）；**但编排宿主是 Git Bash 4.4.23，
      没有 `EPOCHREALTIME`**（`set -u` 下直接引用会 `unbound variable` 终止整轮），
      故 `loadtest/lib-loadtest.sh` 的 `lt_now_ms` 会回退 GNU `date +%s%3N`；
      **别改成 `python -c`**（更慢）。回退路径每个时间戳多一次进程 spawn ⇒ **采样周期会变长，
      这是口径变化、不是等价替换**：本机实测 `date +%s%3N` ≈ **62ms/次**（与 `date +%s` 基本相同，
      成本几乎全在 spawn）。以应用侧为例，`sample_*_once` 每次约 1 个 curl(~0.5s) + 1 个 awk(~0.5s)
      + 1 个时间戳(~0.06s)，即回退相对 EPOCHREALTIME 只多约 6%，0.25s 的 `SAMPLE_INTERVAL_S`
      本来就受进程成本支配，故**采样密度不因该回退发生量级变化**；但结论要按实测密度复核，不能默认；
    - 应用侧 8 个指标用**一个** awk 扫完（`parse_app_metrics`），不要一个指标一次 awk；
    - DB/MQ 侧三条 SQL 合成**一次** mysql 调用；
    - 改完必须复核采样密度：`wc -l target/loadtest/sample-<TAG>.csv`，
      10s 突发窗口至少要有 3 个点，否则 G5 的峰值只是摆设。
12. **Hikari 指标在 dynamic-datasource 下只注册了主库池**（实测
    `/actuator/prometheus` 里只有 `pool="master"`，没有从库池）——写报告时不能声称
    「整个连接池都被观测到了」；从库池（`SLAVE_DB_POOL_MAX`）目前**不可观测**。
13. **RabbitMQ 管理 API 的凭据不是 guest**：容器用 `RABBITMQ_DEFAULT_USER/PASS`
    （默认 `exam/exam123`，见 `docker-compose.yml`），`guest:guest` 会 401。
    队列深度优先走 `http://127.0.0.1:15672/api/queues/%2F/exam.submit.queue`（~0.3s），
    比 `docker exec rabbitmqctl`（~2.7s）快一个量级；脚本在 API 不可用时自动退回后者。

## 8. 可覆盖的环境变量（速查）

| 脚本 | 变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `start-app.sh` | `ARM` / `SERVER_TOMCAT_THREADS_MAX` / `DB_POOL_MAX` / `SLAVE_DB_POOL_MAX` / `RABBIT_BATCH_CONCURRENCY` / `JAVA_BIN` / `JVM_OPTS` | 见脚本 | 容量参数经环境变量注入；产物 `app-env-*.txt`、`app-proof-*.txt` |
| `run-arm.sh` | `ARM`（必填）/ `ROUNDS` / `WARMUP_THREADS` / `WARMUP_SUBMIT_RAMP` / `WARMUP_LOGIN_RAMP` | `3` / `500` / `3` / `5` | 一臂 N 轮，每轮前预热 |
| `run-loadtest.sh` | `TAG` / `SUBMIT_THREADS` / `SUBMIT_RAMP` / `LOGIN_RAMP` / `SAMPLE_INTERVAL_S` / `ENV_SAMPLE_INTERVAL_S` / `TIME_WAIT_MAX` / `TIME_WAIT_FLOOR_ACCEPT` / `TIME_WAIT_TIMEOUT_S` / `SKIP_TIME_WAIT_WAIT` / `DRAIN_TIMEOUT_S` / `RABBITMQ_USER` / `RABBITMQ_PASS` | `run` / `5000` / `10` / `30` / `0.25` / `1` / `1500` / `3000` / `300` / `0` / `180` / `exam` / `exam123` | 单轮采集；`SKIP_TIME_WAIT_WAIT=1` 只许用于调试，且**该轮会以非零退出**（不算合格样本） |
| `run-arm.sh` / `run-loadtest.sh`（入口门禁） | `LT_WRITE_AUTHORIZED` / `LT_ALLOW_SAME_HOST` | `0` / `0` | `LT_WRITE_AUTHORIZED=1` **必填**（显式写入授权，非「声明」）；未设 `JMETER_SSH` 时须显式 `LT_ALLOW_SAME_HOST=1` 才走同机历史跑法（该臂标为「未分离」，不得当分离容量结论） |
| 全部 | `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` / `MYSQL_BIN` / `JMETER_HOME` / `JMETER_HEAP` / `PYTHON_BIN` / `APP_BASE_URL` | 见脚本 | 环境适配 |
| 全部（目标地址） | `SUT_HOST` / `SUT_PORT` | 从 `APP_BASE_URL` 推导 | JMeter 的**实际请求目标**（不再硬编码 `127.0.0.1:8080`），见 §3.3 |
| 双宿主（见 §3.3） | `JMETER_SSH` / `JMETER_REMOTE_HOME` / `JAVA_HOME_FOR_JMETER_REMOTE` / `JMETER_REMOTE_HEAP` / `LOADGEN_DIR` / `LOADGEN_PHYSICAL_ISOLATION_ATTESTED` | 空 / 空 / 空 / 同 `JMETER_HEAP` / `/tmp/loadtest-<TAG>` / `0` | 设 `JMETER_SSH` 才启用负载端执行；分离校验或物理隔离声明不通过即 fail-closed |

