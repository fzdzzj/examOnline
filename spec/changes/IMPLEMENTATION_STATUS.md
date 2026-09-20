# 后端优化提案实施状态（已现场核实）

> 本文只记录**现场复跑验证过**的事实。提案原文（proposal.md）中的基线数字多处失真，
> 以本文件为准；未列出的提案尚未实施。
> 核实时间：2026-09-20　命令：`mvn -o test jacoco:report` + jacoco.csv 汇总

## 全量门禁

| 项 | 结果（第二轮收尾后） |
|---|---|
| Tests run | **272**（Failures 0 / Errors 0 / Skipped 1） |
| BUILD | **SUCCESS** |
| 行覆盖率 | **89.8%**（4274/4760） |
| 分支覆盖率 | **71.2%**（1140/1600） |
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

- 提案 1 的"整批回滚"这一 DB 事实**没有任何测试证明过**：`ScoreServiceTest` 是 Mockito 单测，
  mapper 全打桩，只证明了循环中止，未证明回滚。要证明需能在第 2 场注入故障的上下文级测试。
- `exam_publish_total` 无 exam_id 后，"具体哪场失败"只能看返回结果与审计表（有意取舍）。
- 项目里其实**早有**按端点限流的 `@RateLimit` + `RedisTokenBucket`（如 `exam:ratelimit:random-draw`），
  本轮新增的全局 `RateLimitConfig` 与它并存、职责重叠，尚未收敛为一套。
- 两个 inert 的 `db/migration/V*.sql` 未删（本项目无 Flyway）。
- 提案 4 的 Grafana Dashboard 与 P95 告警仍缺（需可访问的监控栈）。
- 提案 3 剩余部分、提案 7 的其余资源隔离项未做。

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

## 遗留缺口（未擅自扩大改动范围）

1. **审计日志只落日志、未落库**：`AuditLogService.logAuditEvent()` 内是
   `// 实际实现应该写入数据库，这里仅记录日志`，而 `V20260919__create_audit_log.sql` 建了表却没人写。
   该方法还用反射取 `SecurityUtil.getCurrentUser()`、自造 traceId 而不读 MDC。属提案 3 的未完成部分。
2. **提案 4 的 Grafana/告警**：需可访问的监控栈。
3. **提案 1/2/3/6/7 尚未实施**：其中提案 6（add-distributed-tracing）目前只有半成品——
   `pom.xml` 的 OTel 依赖集在 HEAD 里根本不存在，全是未提交内容，且 starter 2.1.0-alpha
   与核心 API 版本曾相差两个大版本（已通过 import instrumentation-bom-alpha 对齐到 1.35.0 修好）。
