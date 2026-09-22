# 交卷链路 5000 并发压测资产（add-submit-loadtest）

本目录是可复现的交卷压测资产：**任何人按下面步骤都能在真 dev 环境重建同场景**。
它服务于 `spec/specs/exam-taking/spec.md` 的「交卷落库容量与时延」需求——
该需求原先只有参数生效与容量估算场景，本资产补上真跑证据。

对应提案：`spec/changes/add-submit-loadtest/proposal.md`；实测报告：`docs/submit-loadtest-report.md`。

---

## 1. 目录内容

| 文件 | 作用 |
| --- | --- |
| `jmeter/submit-5000.jmx` | JMeter 场景：阶段1 登录取 token（前置）→ 阶段2 5000 并发交卷 |
| `db/01-prepare.sql` | 造数：压测专用试卷/考试 + 5000 学生 + 5000 条「进行中」答卷 |
| `db/02-metrics.sql` | 指标采集：丢单计数、落库时效（两侧端点都取 DB 列） |
| `db/03-cleanup.sql` | 清理：只删压测命名空间，把 dev 库恢复原状 |
| `db/04-reset.sql` | 复跑复位：答卷退回「待交卷」+ 清防重表，同一批数据可重复压测 |
| `prepare-data.sh` | 取真实 BCrypt 哈希 → 渲染 → 执行 `01-prepare.sql`（幂等） |
| `run-loadtest.sh` | 跑 JMeter + 同步采样 MQ 深度 + 等积压归零 + 采集 DB 指标 |
| `analyze-results.py` | 从逐笔 CSV 算 P50/P90/P95/P99/失败明细（只取交卷样本，不混登录） |
| `compare-runs.py` | 多轮汇总：客户端逐笔 + 服务端 MVC 计时并置，算「隐含并发度」并与线程上限对照 |
| `probe/ProbeTomcatThreads.java` | 探针：从构建所用 jar 读出 Tomcat 线程/接受队列默认值（运行期读不到） |

产物全部落在 `target/loadtest/`（`target/` 本就 gitignored），不入库。

## 2. 环境要求

- dev 栈容器在跑：MySQL 主库 + 从库、RabbitMQ、Redis（本机复用既有共享容器，**不要重建**）。
- dev 应用实例在跑（默认 `127.0.0.1:8080`）。若用 Maven 的 fork 环境启动，**务必显式指定
  `--server.port=`**：该环境可能注入 `SERVER_PORT`，默认端口会被顶掉。
- JMeter 5.6.3（本机路径见 `run-loadtest.sh` 的 `JMETER_HOME` 默认值）。
- `mysql` 客户端与 `python` 在 PATH 中；`docker` 可用（脚本用 `docker exec ... rabbitmqctl`
  只读队列深度）。

## 3. 执行步骤

```bash
# 0) 准备（造数）——幂等，可反复跑；会打印 exam_id
bash loadtest/prepare-data.sh

# 1) 正式一轮：5000 并发，交卷 ramp-up 10s（TAG 决定产物文件名，默认 run）
TAG=run1 bash loadtest/run-loadtest.sh

# 2) 复核产物
#    target/loadtest/jmeter-stdout-run1.log       JMeter 原始标准输出
#    target/loadtest/submit-results-run1.csv      逐笔明细（P99 数据源）
#    target/loadtest/mq-depth-run1.csv            队列深度 + 未落库计数时间线
#    target/loadtest/metrics-run1.txt             DB 指标（丢单/落库时效）
#    target/loadtest/html-run1/index.html         JMeter 仪表盘

# 3) 恢复环境
mysql -h127.0.0.1 -P13316 -uroot -p exam_online < loadtest/db/03-cleanup.sql
```

**复跑（换容量参数对比，不重新造数）**——顺序不能颠倒：

```bash
# 1) 停掉应用实例，用新参数重启（容量参数都是启动期读取的环境变量）
#    例：RABBIT_BATCH_CONCURRENCY=8 DB_POOL_MAX=40 SLAVE_DB_POOL_MAX=20 java -jar target/exam-online.jar ...
# 2) 复位数据到「5000 待交卷」（幂等；自证须打印 5000 / 0 / 0 / 0）
mysql -h127.0.0.1 -P13316 -uroot -p exam_online < loadtest/db/04-reset.sql
# 3) 确认两个队列都归零（否则上一轮积压会算进这一轮的落库时效）
docker exec exam-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
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
| 队列深度 / 消费积压 | 1s 一次只读采样 `rabbitmqctl list_queues` | `mq-depth-*.csv` |

「被接受」的判据是交卷接口返回 2xx：此时状态机 CAS 已把 `status` 置 2 且 `submit_time` 已落库，
答案由 MQ 消费者异步批量落库——所以「丢单」只可能是「已接受但答案没落库」。
被限流拒绝（429）的请求**不产生答卷行，不构成丢单**，但会作为失败样本暴露在逐笔 CSV 里。

## 6. 测量纪律（不遵守会得到错误结论）

下面三条都已在真机上被咬过（证据见 `docs/submit-loadtest-report.md` §3.3 与 §5）：

1. **测量前必须预热 JVM**。同一场景、同一组参数，仅换「实例是否已预热」，
   交卷 P99 就从 2200ms 变到 3700ms（差 68%）。冷实例的首轮结果主要反映 JIT
   编译开销，而不是系统容量。做法：正式轮前先跑一轮小规模，或在同一实例上连跑两轮只取后者。
2. **轮间必须等客户端 TIME_WAIT 排空**。每轮约产生 1 万条连接残留；不等就复跑会出现
   「登录连不上 → 该线程拿不到 token → 交卷假 401」以及客户端 `BindException:
   Address already in use: connect`，把假失败和虚高的 P99 混进结果。
   实测 9531 条 TIME_WAIT 约需 2 分钟自然排空（Windows 默认 `TcpTimedWaitDelay=120s`）。
   查法：`netstat -an | grep -c TIME_WAIT`。
3. **每臂至少 3 轮取中位数**。单轮单点无法区分「参数效应」与「环境漂移」，
   报告里只能写区间、不能写归因。

## 7. 本机环境踩过的坑（改脚本前先读）

1. **`curl -o /dev/null` 会失败**：本机 `curl` 是 Windows 版，只认 `NUL`；写成 `/dev/null`
   会以 `CURLE_WRITE_ERROR(23)` 退出，脚本在 `set -e` 下表现为「静默退出 23」。
2. **`mysql` 客户端默认 `character_set_client=gbk`**：与 UTF-8 的 SQL 文件混用会报
   `Data too long for column`（乱码被当成长串）。所有调用都要带 `--default-character-set=utf8mb4`。
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
