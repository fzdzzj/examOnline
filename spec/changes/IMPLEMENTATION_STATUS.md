# 后端优化提案实施状态（已现场核实）

> 本文只记录**现场复跑验证过**的事实。提案原文（proposal.md）中的基线数字多处失真，
> 以本文件为准；未列出的提案尚未实施。
> 核实时间：2026-09-20　命令：`mvn -o test jacoco:report` + jacoco.csv 汇总

## 全量门禁

| 项 | 结果 |
|---|---|
| Tests run | **262**（Failures 0 / Errors 0 / Skipped 1） |
| BUILD | **SUCCESS** |
| Skipped 说明 | 唯一 1 个 skip 是 `OpenApiContractTest.exportOpenApiContract`，由 `exportContract` 系统属性按需开启，非回归 |
| 行覆盖率 | **89.5%**（4305/4811） |
| 分支覆盖率 | **71.0%**（1141/1606） |

对比：本轮开始前工作区是**红的**（257 tests / 8 failures + 5 errors），
且在这之前 `@SpringBootTest` 全线 101 errors（应用根本无法启动）。

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
