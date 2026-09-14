# examOnline — OpenSpec 规范驱动开发

> 组织方式对齐参考项目 `D:\code\sports\spec`：**一个变更（change）= 一个开发阶段**，阶段实现并验收后合入 `specs/`，变更目录移入 `archive/`。

## 目录结构

```
spec/
├── specs/{capability}/spec.md          # 已实现的规范基线
├── changes/{change-id}/                # 进行中的变更提案
│   ├── proposal.md                     # 为什么/改什么/影响/时间线/风险
│   ├── tasks.json                      # 任务清单（category 按阶段分组 + 周标记）
│   └── specs/{capability}/spec-delta.md # 规范差异（ADDED/MODIFIED/REMOVED）
└── changes/archive/{change-id}/        # 已归档的变更
```

## 当前状态

- 进行中变更（`spec/changes/`）：**无**（阶段 10 的两个提案均已归档）。

- 已合入规范（`spec/specs/`，共 15 个能力域）：

| # | 能力域 | 来源变更 | 阶段 |
|---|---|---|---|
| 1 | `authentication` | `add-project-skeleton`、`add-authentication` | 1、2 |
| 2 | `question-bank`（覆盖 paper-assembly 范围） | `add-question-bank` | 3 |
| 3 | `exam-management` | `add-exam-management` | 4 |
| 4 | `exam-taking` | `add-exam-taking`、`add-mq-trace-and-capacity` | 5、10 |
| 5 | `grading` | `add-grading-score` | 6 |
| 6 | `score-management` | `add-grading-score` | 6 |
| 7 | `anti-cheat` | `add-anti-cheat` | 7 |
| 8 | `performance` | `add-performance-deepening` | 8 |
| 9 | `data-access` | `add-performance-deepening` | 8 |
| 10 | `observability` | `add-performance-deepening`、`add-slow-sql-and-rate-limit`、`add-mq-trace-and-capacity` | 8、8.1、10 |
| 11 | `reliability` | `add-slow-sql-and-rate-limit`、`add-rate-limit-resilience` | 8.1、10 |
| 12 | `data-consistency` | `add-tx-rollback-consistency` | 8.2 |
| 13 | `class-management` | `add-class-and-post-exam-closure` | 9 |
| 14 | `absence-makeup` | `add-class-and-post-exam-closure` | 9 |
| 15 | `score-review` | `add-class-and-post-exam-closure` | 9 |

- 已归档变更（`spec/changes/archive/`，共 13 个，阶段 1–9 已全部收尾，阶段 10 起陆续归档）：

| 变更 ID | 阶段 | 内容 | 周期 |
|---|---|---|---|
| `add-project-skeleton` | 1 | 工程骨架（单体工程/统一响应/数据模型/中间件编排） | W1 |
| `add-authentication` | 2 | 用户认证与鉴权（注册/登录/双 Token/黑名单/RBAC/越权/锁定限流/找回/初始化） | W1-W2 |
| `add-question-bank` | 3 | 题库、标签、组卷与试卷快照 | W2 |
| `add-exam-management` | 4 | 考试创建/发布、状态机、考试快照 | W3 |
| `add-exam-taking` | 5 | 进入考试、答题、交卷三重幂等、MQ 削峰、超时兜底、自动保存 | W4-W6 |
| `add-grading-score` | 6 | 客观题判分、简答批改、成绩汇总/发布/导出 | W7 |
| `add-anti-cheat` | 7 | 切屏检测、行为日志、随机抽题/选项乱序 | W8 |
| `add-performance-deepening` | 8 | 缓存三防、读写分离 + 读己之写、Prometheus 指标 | W9-W10 |
| `add-slow-sql-and-rate-limit` | 8.1 | 慢 SQL 拦截器、Redis 令牌桶接口限流 | W10 |
| `add-tx-rollback-consistency` | 8.2 | 统一 26 处裸 `@Transactional` 为显式 `rollbackFor` | W10 |
| `add-class-and-post-exam-closure` | 9 | 班级体系、缺考标记、补考、成绩复核 | W10-W11 |
| `add-mq-trace-and-capacity` | 10 | 交卷 MQ 链路 traceId 透传 + 消费并发显式化 + 容量模型 | W12 |
| `add-rate-limit-resilience` | 10 | 限流器 Redis 异常降级（fail-open）+ 降级可观测 | W12 |

## 遗留事项（已归档但未收口，勿当成已完成）

1. **交卷链路压测未做**（`add-exam-taking` task 8）——JMeter 5000 并发交卷压测与硬指标验收（P99 < 2s / 0 丢单 / 批量落库 < 30s）**未执行**，仓库内无任何 `.jmx` 或压测报告。前置瓶颈已由 `add-mq-trace-and-capacity` 消除：消费并发原先实际为 **1**（手工构造的 `batchContainerFactory` 从未设并发，`RABBIT_CONCURRENCY:2` 对它不生效），现已显式化并可经 `exam.taking.mq.concurrency` 调整；**具体数值仍须真跑压测定稿**，该变更只给可复算的容量模型，不替代实测。
2. **考后闭环缺端到端串联验收**（`add-class-and-post-exam-closure` task 6 step 5）——各环节有独立测试，但没有一条「建班→入班→考试结束→缺考标记→指定补考→复核申请→处理」的串联用例。
3. **缺考/补考的真实链路只有 Mockito 单测覆盖**——`AbsenceServiceTest`、`MakeupServiceCandidateLimitTest` 都 mock 掉了 `ClassService`，`user_class → listStudentIds` 的真实接入在集成层无用例（这正是 `schema.sql` 缺 `classes`/`user_class` 两表而测试全绿的原因）。
4. **有指标无告警**——`BusinessMetrics` 指标齐备，但没有 alert rules / Grafana 面板。限流降级指标 `exam.ratelimit.degraded` 同理：埋点有了，但**无人值守**（无告警规则），Redis 故障期间 fail-open 放行会长期潜伏。

**已收口（从遗留清单移出）**：**限流器对 Redis 异常的兜底**（原遗留 #4）已由 `add-rate-limit-resilience`（阶段 10）实现——默认 fail-open 放行以保核心链路可用，同时打 ERROR 日志并递增 `exam.ratelimit.degraded` 计数（可按接口维度区分）；另留 `exam.ratelimit.fail-open=false` 切回 fail-close。

## 能力地图（15 个能力域，作为规范组织单位）

| # | 能力（capability） | 范围 |
|---|---|---|
| 1 | authentication | 注册/登录/双 Token/黑名单/RBAC/越权/锁定限流/找回/初始化 |
| 2 | question-bank | 题目 CRUD、标签、软删除、手动组卷、标签随机抽题、快照锁定（覆盖 paper-assembly） |
| 3 | exam-management | 考试创建/发布、状态机、试卷快照 |
| 4 | exam-taking | 进入考试、答题导航、交卷幂等与削峰、超时兜底、自动保存、落库容量与时延 |
| 5 | grading | 客观题判分、简答批改、策略模式 |
| 6 | score-management | 成绩汇总/发布/撤回/导出 |
| 7 | anti-cheat | 切屏检测、行为日志、随机抽题/选项乱序 |
| 8 | performance | 缓存三防（穿透/击穿/雪崩）、热点只读缓存 |
| 9 | data-access | 读写分离、读己之写 |
| 10 | observability | 指标导出、自定义业务指标、慢 SQL 识别与请求关联、异步链路请求关联 |
| 11 | reliability | 接口限流（Redis 令牌桶）、分布式一致性、限流粒度、限流器降级与可观测 |
| 12 | data-consistency | 事务显式回滚、受检异常转换 |
| 13 | class-management | 班级 CRUD、学生入班/转班、班级学生列表 |
| 14 | absence-makeup | 缺考标记、补考独立记录、补考成绩规则合并 |
| 15 | score-review | 复核申请限次限时、复核中隐藏成绩、复核处理 |

## 开发阶段 → 变更映射

| 阶段 | 变更 ID | 能力域 | 对应周 | 状态 |
|---|---|---|---|---|
| 1 | add-project-skeleton | authentication（工程基础） | W1 | 已归档 |
| 2 | add-authentication | authentication | W1-W2 | 已归档 |
| 3 | add-question-bank | question-bank + paper-assembly | W2 | 已归档 |
| 4 | add-exam-management | exam-management | W3 | 已归档 |
| 5 | add-exam-taking | exam-taking | W4-W6 | 已归档（压测遗留） |
| 6 | add-grading-score | grading + score-management | W7 | 已归档 |
| 7 | add-anti-cheat | anti-cheat | W8 | 已归档 |
| 8 | add-performance-deepening | performance + data-access + observability | W9-W10 | 已归档 |
| 8.1 | add-slow-sql-and-rate-limit | reliability + observability（慢 SQL） | W10 | 已归档 |
| 8.2 | add-tx-rollback-consistency | data-consistency | W10 | 已归档 |
| 9 | add-class-and-post-exam-closure | class-management + absence-makeup + score-review | W10-W11 | 已归档（端到端验收遗留） |
| 10 | add-mq-trace-and-capacity | observability + exam-taking（落库容量与时延） | W12 | 已归档（压测仍遗留） |
| 10 | add-rate-limit-resilience | reliability（限流器降级与可观测） | W12 | 已归档 |

## 工作流

1. 一个开发阶段 = 一个变更提案（`changes/{change-id}/`）。
2. 提案审批后按 `tasks.json` 逐步实施（每次处理一个 step）。
3. 阶段完成并验收后，`spec-delta.md` 的需求合入 `specs/{capability}/spec.md`，变更目录移入 `changes/archive/`。
4. 收尾五步（本项目约定）：spec-delta 合入 specs → 变更目录移入 archive → 回勾 `tasks.json`（**按代码实际完成度回查，不得凭印象勾满**）→ 更新本 README → commit。

## 参考资料（非 openspec 资产）

- `docs/examOnline需求规格说明书.md` — 完整产品愿景
- `docs/需求决策记录.md` — 80+ 项场景决策（开发逐条对照）
- `docs/面试版实施方案.md` — v3 大厂面试级实施方案（开发蓝本）
- `docs/指导Agent交接文档.md` — 交接说明（注意：其中「阶段 8.1/8.2/9 已完成归档」的描述直到 2026-09-14 才真正成立）
