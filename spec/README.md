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

- 进行中变更（`spec/changes/`，阶段 13、14、15，**均待实施**）：

| 变更 ID | 阶段 | 内容 | 目标能力域 |
|---|---|---|---|
| `add-multi-instance-sweep-safety` | 13 | 修复 SETNX 锁解锁未校验持有者 + 并发扫描只生效一次的证据 + 多实例策略显式化 + 重复扫描可观测 | reliability |
| `add-dlq-observability-and-replay` | 14 | 死信队列深度指标 + 重试/进死信计数 + 2 条告警规则与面板 + 有界可审计的死信重投入口（先留档再重投） | reliability + observability |
| `add-data-retention` | 15 | 按考试生命周期的数据保留策略（默认关闭 + 默认只试算 + 分批有界删除 + 只用既有索引） | data-access |

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
| 10 | `observability` | `add-performance-deepening`、`add-slow-sql-and-rate-limit`、`add-mq-trace-and-capacity`、`add-alerting-and-dashboards` | 8、8.1、10、11 |
| 11 | `reliability` | `add-slow-sql-and-rate-limit`、`add-rate-limit-resilience` | 8.1、10 |
| 12 | `data-consistency` | `add-tx-rollback-consistency` | 8.2 |
| 13 | `class-management` | `add-class-and-post-exam-closure` | 9 |
| 14 | `absence-makeup` | `add-class-and-post-exam-closure`、`add-post-exam-closure-e2e` | 9、12 |
| 15 | `score-review` | `add-class-and-post-exam-closure` | 9 |

- 已归档变更（`spec/changes/archive/`，共 15 个，阶段 1–9、12 已收尾，阶段 10–11 已归档；13–15 仍进行中）：

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
| `add-alerting-and-dashboards` | 11 | Prometheus 抓取与告警规则 + Grafana provisioning 与面板 + 配置资产静态校验 | W13 |
| `add-post-exam-closure-e2e` | 12 | 考后闭环端到端验收 + force-end 漏标缺考修复 + `score_review.created_time` 补列 + 删除 `@Sql` 自建表 + `listByExam` 的 `@PathVariable` 修正 | W13-W14 |

## 遗留事项（已归档但未收口，勿当成已完成）

1. **交卷链路压测未做**（`add-exam-taking` task 8）——JMeter 5000 并发交卷压测与硬指标验收（P99 < 2s / 0 丢单 / 批量落库 < 30s）**未执行**，仓库内无任何 `.jmx` 或压测报告。前置瓶颈已由 `add-mq-trace-and-capacity` 消除：消费并发原先实际为 **1**（手工构造的 `batchContainerFactory` 从未设并发，`RABBIT_CONCURRENCY:2` 对它不生效），现已显式化并可经 `exam.taking.mq.concurrency` 调整；**具体数值仍须真跑压测定稿**，该变更只给可复算的容量模型，不替代实测。
4. **观测栈动态行为未验证**（`add-alerting-and-dashboards` task 4）——`docker/observability/` 的**配置资产静态正确性**已由 `AlertAssetsTest` 守住（文件可解析、规则结构完整、指标名与 `BusinessMetrics` 常量对齐、面板 uid 与数据源一致，并已做变异验证证明断言非空转），但 **Docker 未运行**，「抓取是否 `UP`、告警是否真的 `firing`、面板是否真的出图」**未在本机验证**，需按 `docker/observability/README.md` 的人工验收步骤确认。**不得声称「已验证告警可用」。**
5. **补考成绩规则已合入规范但从未接线**（阶段 12 取证时发现）——`MakeupScoreService.finalScore(examId, studentId)` **全仓库零调用**，`mergeFinalScore(...)` 只被纯函数单测调用，也没有任何暴露"补考最终成绩"的接口。而 `spec/specs/absence-makeup/spec.md` 的 `Requirement: 补考成绩规则`（取最高分/取最近一次/取平均分）**已合入并验收**——属"已验收但未接线"的需求。修复需新增接口/查询路径（功能变更），建议单独立项 `add-makeup-final-score`，**不要在本清单里当成已完成**。

6. **死信队列的"真 broker 往返"未验证**（阶段 14 取证时发现）——`exam.submit.dead.queue` 已声明且绑定正确、`basicNack(requeue=false)` 投递路径已存在，但**无消费者、无指标、无告警、无重投**（P3 的 7 条规则一条都没覆盖它）。已立项 `add-dlq-observability-and-replay`（阶段 14）；但**本机 Docker 未运行、集成测试用 `@MockitoBean RabbitTemplate`**，该变更能证明的只是「重投逻辑正确 + 留档表写入正确 + 规则/面板资产静态正确」，**「真发一条坏消息 → 真进 DLQ → 真重投回来」仍无法在本机验证**。**不得声称死信链路端到端已验证。**
7. **磁盘空间回收不在任何提案范围内**（阶段 15 取证时发现）——MySQL InnoDB 的 `DELETE` 只把页标记为可复用，**文件大小不会变小**；真正回收需 `OPTIMIZE TABLE` 或 `ALTER TABLE ... ENGINE=InnoDB`（离线重写整表、期间锁表），在在线考试系统上属高风险窗口操作。`add-data-retention`（阶段 15）的目标是**控制行数与查询代价**（避免全表扫描与索引膨胀），**不是腾磁盘**。**不得声称"清理后磁盘释放"。**
8. **`exams` 表没有 `ended_time` 列**（阶段 15 取证时发现）——实际结束时刻无字段记录，`updated_time` 会被任意更新刷新（表达的不是结束时刻）。`add-data-retention` 因此改用 `end_time`（时间窗终点）作为"考试已终结"的代理，误差方向是**晚删而非早删**（`force-end` 提前结束的考试其 `end_time` 仍在未来），属安全选择。若要精确化需新增列（= 迁移），当前不值得。

**已收口（从遗留清单移出）**：

1. **限流器对 Redis 异常的兜底** 已由 `add-rate-limit-resilience`（阶段 10）实现——默认 fail-open 放行以保核心链路可用，同时打 ERROR 日志并递增 `exam.ratelimit.degraded` 计数（可按接口维度区分）；另留 `exam.ratelimit.fail-open=false` 切回 fail-close。
2. **有指标无告警、无面板** 已由 `add-alerting-and-dashboards`（阶段 11）实现——7 条告警规则（含 `RateLimitDegraded`，使 `exam.ratelimit.degraded` 从「埋了没人看」变为「有告警值守」）+ Grafana 数据源与总览面板；规则只使用能从 `BusinessMetrics` 常量确定性推导的指标名，刻意不写 `hikaricp_connections_*`（dynamic-datasource 下未实测，写错会让规则因 `no data` 永久静默）。其**动态验证**转为上述遗留第 4 条。

3. **考后闭环缺端到端串联验收** 已由 `add-post-exam-closure-e2e`（阶段 12）收口——曾是真问题：阶段 9 各环节有独立测试，但没有「建班→结束→缺考→补考→批改发布→复核」整链；且 `force-end` 曾漏标缺考（状态已 ENDED 后定时扫描无法自愈）。现由 `PostExamClosureIntegrationTest` 9 条用例覆盖，两条结束路径均 `markAbsence`。
4. **缺考/补考真实链路仅 Mockito、以及 `@Sql` 掩盖缺表 / `score_review` 缺列** 已由阶段 12 收口——曾是真问题：`ClassManagementIntegrationTest` 的 `@Sql` 自建表掩盖过缺表回归；`score_review` 缺 `created_time` 曾使复核申请 INSERT 在任何环境必失败。现 `src/test` 无 `@Sql`，schema/migration 已补列，`listByExam` 同步改为 `@PathVariable`。

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
| 9 | data-access | 读写分离、读己之写、数据生命周期（保留与清理） |
| 10 | observability | 指标导出、自定义业务指标、慢 SQL 识别与请求关联、异步链路请求关联、指标驱动的告警、观测面板 |
| 11 | reliability | 接口限流（Redis 令牌桶）、分布式一致性、限流粒度、限流器降级与可观测 |
| 12 | data-consistency | 事务显式回滚、受检异常转换 |
| 13 | class-management | 班级 CRUD、学生入班/转班、班级学生列表 |
| 14 | absence-makeup | 缺考标记（含自然到点与 force-end 两条结束路径）、补考独立记录、补考成绩规则合并、考后闭环端到端一致性 |
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
| 9 | add-class-and-post-exam-closure | class-management + absence-makeup + score-review | W10-W11 | 已归档（端到端已由阶段 12 收口） |
| 10 | add-mq-trace-and-capacity | observability + exam-taking（落库容量与时延） | W12 | 已归档（压测仍遗留） |
| 10 | add-rate-limit-resilience | reliability（限流器降级与可观测） | W12 | 已归档 |
| 11 | add-alerting-and-dashboards | observability（告警与面板） | W13 | 已归档（动态验收遗留） |
| 12 | add-post-exam-closure-e2e | absence-makeup（闭环端到端验收 + 缺考路径修复） | W13-W14 | 已归档 |
| 13 | add-multi-instance-sweep-safety | reliability（定时扫描多实例安全 + 锁解锁修正） | W14 | 已提案，待实施 |
| 14 | add-dlq-observability-and-replay | reliability + observability（死信可见性、告警与重投） | W14-W15 | 已提案，待实施 |
| 15 | add-data-retention | data-access（数据保留与清理） | W15 | 已提案，待实施 |

## 工作流

1. 一个开发阶段 = 一个变更提案（`changes/{change-id}/`）。
2. 提案审批后按 `tasks.json` 逐步实施（每次处理一个 step）。
3. 阶段完成并验收后，`spec-delta.md` 的需求合入 `specs/{capability}/spec.md`，变更目录移入 `changes/archive/`。
4. 收尾五步（本项目约定）：spec-delta 合入 specs → 变更目录移入 archive → 回勾 `tasks.json`（**按代码实际完成度回查，不得凭印象勾满**）→ 更新本 README → commit。
5. **集成测试不得用 `@Sql` 自建表**（本项目硬约定）：测试库建表只以 `src/main/resources/schema.sql` 为唯一来源（`application-test.yml` 已配 `mode: always` + `continue-on-error: false`）。自建表会让「新库/新环境建不起来」被测试掩盖——`schema.sql` 曾缺 `classes`/`user_class` 两表而 CI 全绿，就是这么发生的。目标：`grep -rn '@Sql' src/test` 保持为空。
6. **实体字段与建表定义必须双向一致**（本项目硬约定）：MyBatis-Plus 按实体字段生成 INSERT，**实体有、表里没有的列会让该写入在任何环境都失败**——`score_review` 缺 `created_time` 而 `ScoreReview` 实体有 `@TableField(fill = INSERT) createdTime`（全局 `MetaObjectHandler` 会填充），导致复核申请接口从未成功执行过一次（阶段 12 挖出）。核对手法：用脚本比对每个含 `createdTime` 的实体的 `@TableName` 与 `schema.sql` 中对应建表语句，双方都必须齐。**新增表/实体时必须双向核对**，且优先靠「走真实链路的集成用例」暴露，而不是靠人肉比对。

## 参考资料（非 openspec 资产）

- `docs/examOnline需求规格说明书.md` — 完整产品愿景
- `docs/需求决策记录.md` — 80+ 项场景决策（开发逐条对照）
- `docs/面试版实施方案.md` — v3 大厂面试级实施方案（开发蓝本）
- `docs/指导Agent交接文档.md` — 交接说明（注意：其中「阶段 8.1/8.2/9 已完成归档」的描述直到 2026-09-14 才真正成立）
