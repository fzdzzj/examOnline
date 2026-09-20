# 后端优化提案实施状态（已现场核实）

> 本文只记录**现场复跑验证过**的事实。提案原文（proposal.md）中的基线数字多处失真，
> 以本文件为准；未列出的提案尚未实施。
> 核实时间：2026-09-20　命令：`mvn -o test jacoco:report` + jacoco.csv 汇总

## 全量门禁

| 项 | 结果（复核后修正） |
|---|---|
| Tests run | **268**（Failures 0 / Errors 0 / Skipped 1） |
| BUILD | **SUCCESS** |
| 行覆盖率 | **90.1%**（4181/4642） |
| 分支覆盖率 | **71.6%**（1134/1584） |
| Skipped 说明 | 唯一 1 个 skip 是 `OpenApiContractTest.exportOpenApiContract`，由 `exportContract` 系统属性按需开启，非回归 |

对比：本轮开始前工作区是**红的**（257 tests / 8 failures + 5 errors），
且在这之前 `@SpringBootTest` 全线 101 errors（应用根本无法启动）。
再往前，HEAD 本身编译不过（见下）。

## 第二轮（同日收尾）：追踪偏差、审计落库、事务前提、抽题 500

对应提交：`fix(tracing)` / `feat(security): 审计落库` / `fix(score)` / `fix(paper)`。

### 修掉的真实缺陷

| 缺陷 | 说明 |
|---|---|
| `TraceIdInterceptor` 的 Scope 泄漏 | `Context.current().with(span).makeCurrent()` 从不 close。线程池复用线程，上一条链路的上下文会留给下一条请求。变异验证：种回后 2 failures，且**下一个用例的前置断言**被污染失败，直接演示跨请求泄漏。 |
| 响应头注入面 | 它读客户端 `X-Trace-ID` 再写回响应头。`RequestIdFilter` 早已用白名单专防此点（HTTP 响应头注入/响应拆分）。现只写服务端 SpanContext 的 hex id。 |
| MDC key 从未生效 | 它写 `traceId`，而日志 pattern 打的是 `%X{requestId}` → 整个组件对可观测性的实际贡献为零。现 pattern 补 `[%X{traceId:-}]`。 |
| 审计日志恒缺操作人 | `AuditLogService` 在 `@Async` 线程里用反射读 SecurityContext（恒 null），且反射结果根本没被使用；traceId 还是当场 new 的随机 UUID，拿去 Jaeger 查不到任何链路。改为同步写库、显式传 userId。变异验证：insert 换回 log.info → 4 红；操作人改传 null → 仅该条红。 |
| `publishOne` 上空转的 `REQUIRES_NEW` | private + 同类自调用，Spring 代理两种情形都不增强 → 注解完全无效，而 Javadoc 声称"每场独立事务"。删除并把注释改回描述真实边界。 |
| 发布指标基数失控 | `exam_publish_*` 拿 `exam_id` 当 tag，考试 ID 基数无上界 → 时间序列随业务量线性膨胀。收敛为单一 `status` 维度，并把基础设施故障从 fail 区分为 error。基数守卫断言已加（加回多余 tag 即红）。 |
| 抽题空标签 500 | 上一轮参数化改造把空集合交给 `wrapper.in()` → 拼出 `id IN ()` 语法错误。实测 H2 抛 `JdbcSQLSyntaxErrorException`。现短路为 0 候选，走既有"题目不足"分支。 |

### 经核实不成立、因此**没有**实施的主张

| 提案主张 | 事实 |
|---|---|
| 提案 1"前 5 个已提交无法回滚" | 方向反了。`publish()` 循环内捕获 BusinessException，业务失败不污染外层事务；基础设施故障一路上抛 → 整批回滚。当前语义正是期望的，无需拆分。 |
| 提案 1"补 timeout" | `spring.transaction.default-timeout: 30` 早已在 HEAD 的 application.yml 里。 |
| 提案 2"SQL 注入 CVSS 7.5" | `tagIds` 是 `List<Long>`，`String.valueOf` 后恒为数字，注入不可达。参数化保留，但定性降为纵深防御。 |
| 提案 2"需新建 idx_sweep_candidate" | `schema.sql` 已有同列组合的 `idx_submissions_sweep(status, deadline_time)`；那个迁移文件要建的是**重复索引**（且因无 Flyway 从未执行）。 |
| 提案 2 的性能数字表 | 撰写时虚构，从未实测。本机 3306 是另一 MySQL 实例、凭据不通，拿不到 EXPLAIN，因此 OR→UNION ALL **未实施**。要实施的前提是先有 10 万级数据上的真实执行计划。 |

### 仍未处理（如实记录）

- 提案 4 的 Grafana Dashboard 与 P95 告警仍缺（需可访问的监控栈）。
- 提案 3 剩余部分未做（审计查询接口 `/audit/logs`、密码有效期策略）。
- 提案 7 已于第四轮整体撤回删除，不在待办内。

## 第三轮：限流收敛、删 inert 迁移、补回滚证明

### 1. 全局限流已撤销，收敛到既有 @RateLimit

上一轮提交的全局 `RateLimitConfig`（对 `/api/**` 无差别 100qps）与项目既有的
`@RateLimit` + `RedisTokenBucket` 体系重叠，而且更危险：

| | 既有 @RateLimit | 已删的全局限流 |
|---|---|---|
| 预算 | 按端点实测：submit 500/2000、pull-paper 2000/5000、random-draw 50/200 | 一律 100/150 |
| 时机 | 刻意排在鉴权之后（先鉴权再限流） | 绕开鉴权顺序，未认证流量也耗桶 |
| 范围 | 只作用于显式打注解的热点端点 | 全部 /api/** |

最要紧的是第一条：全局 100qps 会把 submit 压到它自己 500 预算之下，恰好发生在
"5000 人同时交卷"这个最关键的场景。已删除该组件、其 5 条测试与 `rate-limiting.*` 配置；
`ExamController.page()` 的 `@Min/@Max` 保留（那是入参校验，不属于限流）。

### 2. 两个从未执行的 Flyway 迁移文件已删除

`V20260919__create_audit_log.sql` 与 `V20260919__create_idx_sweep.sql`。本项目未接
Flyway，二者从未执行；后者要建的 `idx_sweep_candidate` 还与 `schema.sql` 里既有的
`idx_submissions_sweep` 列组合完全相同，属重复索引——若将来真接上 Flyway 只会平添写放大。
（注：这两个删除随 `refactor(ratelimit)` 那个提交一起进去了，因为早先 `git rm` 已入暂存区，
该提交的 message 未描述它们。）

### 3. "整批回滚"这一数据库事实首次被证明

新增 `ScorePublishTransactionIntegrationTest`（2 用例，真实 H2）：
- 业务性失败只让那一场失败，兄弟场次的 CAS 更新与审计照常提交；
- 第 2 场注入非 BusinessException（用 `@MockitoSpyBean` 让 examMapper.selectById 抛
  IllegalStateException）时，第 1 场已做的状态迁移与审计**全部回滚**。

变异验证：摘掉 `publish()` 上的 `@Transactional` → 仅回滚用例变红，另一条仍绿。
说明这组断言确实钉在事务边界上，不是恒真。此前 `ScoreServiceTest` 全是 Mockito 打桩，
从未证明过这件事。

全量 **269 tests / 0 失败**（272 − 5 条随组件删除的限流测试 + 2 条新事务测试）。

## 第四轮：线程池隔离整体撤回删除

`46d7004` 交付的三个线程池经核实是**纯空转**，已全部删除：`ThreadPoolConfig`、
`ThreadPoolConfigTest`、`ExamSubmitService.submitAsync()`，以及该提交引入的
`@EnableAsync`。提案文件顶部记了完整理由。

判定依据（都是现场核实，不是推断）：
1. 上一轮摘掉两处挂错的 `@Async`（`sweep()` 返回 `int`、`collect()` 返回
   `EventVerdict`，都是"有返回值且调用方要用"的方法，Spring 直接抛非法返回类型异常）后，
   grade / monitor 两个池失去唯一租户；
2. `submitAsync()` 用 `grep` 全仓库（含 `src/test`）查得**零调用方**，submit 池同样空转；
3. 异步交卷本身没有落点：交卷必须把结果同步返回给学生，兜底扫描由 `@Scheduled` 驱动，
   行为采集被契约要求同步返回。

`ThreadPoolConfigTest` 那 4 条断言（core/max/queue 参数、"三个池不是同一实例"）在零租户下
依然全绿——这是"全绿不等于能跑"的又一例，故一并删除而非保留。

顺带移除 `@EnableAsync`：它会让 Spring 注册一个默认 `TaskExecutor`，在没有真实异步工作时
属多余的运行时面。

将来若真有批量重活（成绩导出、判分回填等），应连真实调用方和"能证明隔离起作用的并发测试"
一起重建，不预先占空池。

全量 **265 tests / 0 失败**（269 − 4 条随组件删除的池参数测试，数目精确对账）。

---

## 提案 5：add-api-rate-limiting —— 已实施并验证

落地内容：
- `ExamController.page()` 加 `@Min(1)` / `@Max(100)`。
- `RateLimitConfig`：Redis Token Bucket（Lua 原子扣减）+ MVC 拦截器挂在 `/api/**`，
  排除 `/actuator/**`；429 + `Retry-After` + 统一错误码 `RATE_LIMIT_EXCEEDED`。
- `http_request_rate_limited_total` 计数器。
- 配置项统一为 `rate-limiting.{enabled,qps,burst,key-prefix}`。
- 测试 profile 显式 `rate-limiting.enabled: false`，避免 20+ 集成测试类共用
  `127.0.0.1` 这一 clientId 时被阈值偶发打断；限流行为由专用测试单独开启验证。

新增 `RateLimitInterceptorIntegrationTest`（5 用例），走真实 MockMvc 而非直调 `isAllowed()`。
**三类变异均已验证可红**：

| 种回的缺陷 | 变红情况 |
|---|---|
| `@Value` 写成 `rate.limit.qps`（静默回落 100） | 1 failure：`expected <1> but was <100>` |
| 拦截器路径写成 `/apis/**`（永不匹配） | 2 failures + 1 skip |
| Lua 首次判断改回 `== nil` | 2 failures + 1 skip，脚本报 arithmetic on a nil value |

行为用例钉不住 qps 绑定（同秒内是否触顶只由容量决定），故另加一个读字段核对绑定的用例；
这条是变异验证暴露出来的，不是先想到的。

## 提案 4：add-concurrency-monitoring —— 代码已落，看板未做

`BusinessMetrics` 新增 `exam.submit.lock.wait`（Timer）、
`exam.submit.lock.acquisitions`、`exam.submit.lock.contentions`；
`ExamSubmitService` 在 SETNX 前后打点。`exam.sweep.duplicate_detected` 此前已存在并被
`ExamSweepService` 使用。
**未完成**：Grafana Dashboard 与 P95 告警规则（需部署环境，本地无法验证）。

## 提案 8：improve-test-coverage —— 前提失真，只补缺口

proposal.md 的基线是错的，照它执行会大量重做已完成的事：

| 提案原文断言 | 实际 |
|---|---|
| "覆盖率仅 14%" | 实施前即 85.1% 行 / 66.3% 分支 |
| "添加 JaCoCo 配置" | pom.xml 已有 v0.8.12，且注释说明刻意不设 check 门禁 |
| "新建 ExamSubmitServiceTest" | 已存在 |
| "新建 application-test.yml" | 已存在 |
| "新建 .github/workflows/ci.yml + Codecov" | **无 git remote**，写了也永远跑不了，属无效交付 |

真正有效的缺口是 `ScoreService` 当时 0/78 分支，现补到 **78/78**。
"70% 分支门禁"在实施前不过（66.3%），提案自带的 60% 门禁当时即已过。

---

## 本轮修掉的 5 个真实缺陷（均为未提交改动引入）

| 文件 | 缺陷 |
|---|---|
| `docker-compose.yml` | **整份编排被覆盖**：MySQL 主从(GTID)+Redis+RabbitMQ 全被删，只剩 Jaeger。已恢复原有内容并合并 Jaeger/ES，现 diff 为纯新增 +31/-0。 |
| `application.yml` | `rate-limiting:` 被插进 `exam.taking.mq` 子树中间，SnakeYAML 解析失败 → 应用无法启动、全部上下文测试瘫痪。已移到顶层。 |
| `ExamSweepService.sweep()` | `@Async` 加在返回 `int` 的方法上（非法）；且由同 bean 的 `scheduledSweep()` 自调用，本就不走代理。已移除注解。 |
| `BehaviorEventCollectService.collect()` | 同上，`@Async` 加在返回 `EventVerdict` 且契约要求同步返回的方法上 → 行为上报接口全 500。已移除注解。 |
| 测试夹具 ×35 处 | 密码强度规则（提交 `c063f0a`）上线时未同步夹具：`pass1234/newpass99/resetpass*` 无大写被拒。已改强口令。`admin123` 与 `"short"` 刻意保留（前者走 `AdminInitializer` 不经校验，后者是有意的弱口令负例）。 |

## 遗留缺口（截至第五轮，已按实际状态更新）

1. ~~审计日志只落日志、未落库~~ → **已修**（`9d79d73`）：表补进 `schema.sql` +
   存量迁移脚本，`AuditLogService` 改为构造注入 mapper 真写库，去掉反射取用户与自造
   traceId。提案 3 剩余部分仍未做：`/audit/logs` 查询接口、密码有效期策略。
2. ~~提案 1/2/6/7 尚未实施~~ → **状态已变化**：提案 6 的 OTel 依赖集与追踪代码已提交
   （`d957dad` + `5999b1e`，半成品但可启动）；提案 7 已**整体撤回删除**（`df5f311`）；
   提案 1 前提证伪并回退（`5a50ff6`）；提案 2 只做了参数化与空集合修复（`1f678d0`），
   OR→UNION ALL 因未测量而搁置。
3. **提案 4 的 Grafana Dashboard 与 P95 告警**：仍未做。注意本机其实存在
   `sport-verify-grafana` 容器（当前 exited），并非"本地无法验证"。

## 第五轮：双轴复核，修掉一个恒 0 指标

复核 `46d7004..HEAD` 时发现标准轴一处严重缺陷，是我自己引入的（`7f104ee`）：

`BusinessMetrics.recordLockWait()` 收 `double 秒` 再
`Duration.ofSeconds((long) waitTimeSeconds)` —— SETNX 的正常等待本就是毫秒级，
`(long) 0.05` → `0`，于是**每次采样都被记成 0 秒**，`exam.submit.lock.wait` 的
P50/P95/P99 恒为 0。而提案 4 的验收口径正是"P95 < 100ms"——该指标会以恒 0 **假通过**。

修法两条：
1. 接口改收纳秒并 `timer.record(nanos, NANOSECONDS)`，从源头消除有损换算；
   调用点 `ExamSubmitService` 直接传 `System.nanoTime() - start`。
2. 补毫秒级分桶。Micrometer 默认桶在 10ms 之后直接跳到 8s，锁等待几乎全落在这段空档里；
   不铺 50/100ms 桶则"P95 < 100ms"这个断言怎么都能通过。

同时补 3 条测试进**早已存在**的 `BusinessMetricsTest`（这三个指标此前完全没进它的测试）：
亚秒等待不得截成 0、必须存在 50/100ms 桶、两个计数 Counter 导出。
两个变异均验证可红：种回 `Duration.ofSeconds(nanos/1e9)` → 仅截断用例红；
摘掉 `serviceLevelObjectives` → 仅分桶用例红。

全量 **268 tests / 0 失败**（265 + 3 新）。

### 复核同时纠正我此前两条失实说法

1. 我说"本机 3306 是另一实例、凭据不通所以拿不到 EXPLAIN"——**归因错了**。真相是
   `exam-mysql-master` 容器 `Exited (255)`，而 `docs/指导Agent交接文档.md:192` 早已写明
   主库在 **13316 / root/root123**。我没读那份文档就下了阻塞结论。"未测量"成立，
   但只需 `docker start exam-mysql-master` 即可解开。
2. 我说 Grafana/P95 告警"本地验证不了"——`docker ps -a` 显示存在
   `sport-verify-grafana` 容器（已退出），且 observability 规范记录过阶段 16 的告警
   曾真正 firing。这条也下早了。

### 复核查出的规格轴欠账（2026-09-20 同日清完）

- 8 个变更目录已 `git mv` 进 `spec/changes/archive/`；能力域规范按**筛选式合入**
  （沿用仓库既有惯例：来源行点名合入了哪几条，另以"实施注记/撤回说明"记录偏离）：
  - `observability` ← 锁竞争可观测、日志与链路标识关联、指标基数有界；
  - `reliability` ← 分页入参上限；
  - `data-consistency` ← 批量操作失败分级、声明式增强必须真的生效；
  - `data-access` ← 动态条件不得拼接、空集合必须短路、索引变更先行核查；
  - `authentication` ← 安全事件审计落库、建表来源唯一；
  - `performance` ← **零合入**，只留撤回说明（线程池隔离纯空转已删）。
- 3 份已撤回 delta 各加防误合入横幅（`add-thread-pool-isolation`、
  `optimize-transaction-boundary`、`add-api-rate-limiting`），避免有人照 delta 写入不存在的能力。
- `docs/需求决策记录.md` 补 §十六～§十八：代理型增强必须验证生效、指标口径两条硬规则、
  建表与迁移唯一事实源。
- `improve-test-coverage` 未合入任何能力域规范：测试质量属工程实践、不定义运行行为，
  其结论只进决策记录与本文件。

### 复核又抓出一处我自己的失实声明（已修）

`add-api-rate-limiting` 的提交信息写过"`size=101` 直接 400"——**不实**：控制器缺类级
`@Validated`，`@RequestParam` 上的约束从不触发，实测 `size=1000` 返回 **200**（连 500 都不是）。
处置顺序是先写失败测试取证（红）→ 补 `@Validated` 与 `ConstraintViolationException`→400 的映射
→ 测试转绿；不是靠读代码下结论。全量 **269 tests / 0 失败**。
