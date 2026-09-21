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

- 进行中变更（`spec/changes/`，**条数以 `find spec/changes -mindepth 1 -maxdepth 1 -type d -not -name archive | wc -l` 为准，本文件不写死计数**；**必须按阶段顺序串行执行**，后一个以前一个已合入为前置；**同一工作树不得并行跑两个子 agent**）：

| 变更 ID | 阶段 | 内容 | 目标能力域 | 前置 |
|---|---|---|---|---|
| `add-frontend-skeleton-auth` | 19 | 前端方向①：`frontend/` 工程骨架（Vue3.5+TS+Vite7+AntD4+Tailwind4+文件路由+vue-query+pnpm）、生成式 API 层、令牌续期单飞、角色路由守卫、认证四页、playwright 冒烟。**首轮已交付但验收未通过**（11 个文件真实改动未提交，门禁跑在工作区而非 HEAD），返修提示词见 `agent-prompt-round2.md`。**原「强制改密前置」Requirement 已移出**，待后端契约就绪后由独立前端小变更接回 | frontend（新） | 18（已合入） |
| `add-frontend-teacher-authoring` | 20 | 前端方向②：题库列表与题型驱动编辑表单、标签管理、手动组卷与标签随机抽题、试卷预览 | frontend | 19 |
| `add-frontend-exam-admin` | 21 | 前端方向③：班级管理、考试创建/发布/force-end、状态机可视化（状态以后端为准）、监考进度与行为日志时间线、Grafana 只读入口 | frontend | 20 |
| `add-frontend-student-taking` | 22 | 前端方向④（**面试主战场**）：极简作答界面、服务端时间倒计时与归零锁定、30s 自动保存 + IndexedDB 断线恢复与保守合并、交卷防重配合、切屏检测只警告不强制交卷、结果如实呈现 + 四条可复现演示脚本 | frontend | 21 |
| `add-frontend-post-exam` | 23 | 前端方向⑤：批改工作台（乐观锁冲突可见）、成绩汇总/发布/撤回/流式导出、缺考名单与补考创建、学生成绩查询与复核闭环 | frontend | 22 |
| `update-agent-gate-single-source` | 工程性 E2（**不在 19–23 业务串行链内**） | 门禁命令与验收判据的单一来源。**B-1 已完成**：6 份进行中前端提示词里的"必须仍是 210"常量与"一律读作 218"式人肉更正段全部清除，改为编号判据（开工记录 + 收尾比较）；`docs/指导Agent交接文档.md` 首屏常量降级为带 revision 的历史记录，§6.1 定性为特定 shell 的历史绕行办法、§6.2 加互斥注记。**B-2 未开始**：Maven wrapper、`.mvn`/`maven-settings.xml` 去机器绝对路径、`frontend/package.json` 的 `&`→`&&`、唯一门禁命令回填——需联网与磁盘授权，且排在业务阶段收尾之后 | `agent-harness` | 与 `add-frontend-*` 无文件冲突（B-1 只改提示词与文档）；`add-agent-context-routing`（E1）已归档 |

> **工程性变更（E 系列）不进入 19–23 的业务串行链**：它们改的是文档与门禁表述，不碰 `src/**`、`pom.xml`，与"阶段 19–23 一律不改后端"的纪律一致；
> 但**仍受"同一工作树不得并行跑两个子 agent"约束**，所以 E1/E2 要么排在当前返修之后单独跑一轮，要么在独立工作树里完成后由指导 agent 合并。
> 上表的行数与 `spec/changes/` 目录数可以不相等：目录里有尚未登记的提案（如 E2）。

**前端系列纪律**：技术栈对齐参考项目 `D:\code\crm\font\crm-front`（已核实其 package.json / vite.config.ts）；代码位于同仓库 `frontend/`；**阶段 19–23 一律不改后端**（`src/main`、`src/test`、`pom.xml` 零改动），发现接口缺口必须停下回报并单独立项，不得在前端拼凑绕过；前端 vitest/playwright 与后端 surefire **计数口径互不并入**，具体数值以当次执行记录为准（命令 + 该次原始输出 + 短 revision 写进该阶段 `tasks.json` 证据字段；验收按判据，不按常量——见 `update-agent-gate-single-source` 与 `AGENTS.md`）。

- 已合入规范（`spec/specs/`，共 16 个能力域）：

| # | 能力域 | 来源变更 | 阶段 |
|---|---|---|---|
| 1 | `authentication` | `add-project-skeleton`、`add-authentication`、`add-auth-must-change-password` | 1、2、18+ |
| 2 | `question-bank`（覆盖 paper-assembly 范围） | `add-question-bank` | 3 |
| 3 | `exam-management` | `add-exam-management` | 4 |
| 4 | `exam-taking` | `add-exam-taking`、`add-mq-trace-and-capacity` | 5、10 |
| 5 | `grading` | `add-grading-score` | 6 |
| 6 | `score-management` | `add-grading-score` | 6 |
| 7 | `anti-cheat` | `add-anti-cheat` | 7 |
| 8 | `performance` | `add-performance-deepening` | 8 |
| 9 | `data-access` | `add-performance-deepening`、`add-data-retention`、`fix-schema-mysql-pk` | 8、15、17 |
| 10 | `observability` | `add-performance-deepening`、`add-slow-sql-and-rate-limit`、`add-mq-trace-and-capacity`、`add-alerting-and-dashboards`、`add-dlq-observability-and-replay`、`add-observability-runtime-evidence` | 8、8.1、10、11、14、16 |
| 11 | `reliability` | `add-slow-sql-and-rate-limit`、`add-rate-limit-resilience`、`add-multi-instance-sweep-safety`、`add-dlq-observability-and-replay` | 8.1、10、13、14 |
| 12 | `data-consistency` | `add-tx-rollback-consistency` | 8.2 |
| 13 | `class-management` | `add-class-and-post-exam-closure` | 9 |
| 14 | `absence-makeup` | `add-class-and-post-exam-closure`、`add-post-exam-closure-e2e` | 9、12 |
| 15 | `score-review` | `add-class-and-post-exam-closure` | 9 |
| 16 | `api-contract` | `add-backend-openapi` | 18 |

- 已归档变更（`spec/changes/archive/`，**数量以 `find spec/changes/archive -mindepth 1 -maxdepth 1 -type d | wc -l` 为准，本文件不写死计数**；阶段 1–9、12–18 及 18 后小阶段已收尾，阶段 10–11 已归档）：

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
| `add-multi-instance-sweep-safety` | 13 | 交卷锁按 token 解锁 + 两线程并发扫描只生效一次证据 + 刻意不加调度锁 + `exam.sweep.duplicate_detected`（含消费者 filled==0） | W14 |
| `add-dlq-observability-and-replay` | 14 | DLQ 深度/进死信/重试计数 + 2 条告警（`MqDlqBacklog`/`MqSubmitRetryExhausted`）+ 面板一格 + 有界留档重投（ADMIN）；**未做真 broker 端到端** | W14-W15 |
| `add-data-retention` | 15 | 按考试生命周期清理三张辅助表（默认关闭 + dry-run、零 DDL、按 exam_id 有界删除）；**不纳入** `exam_dlq_messages`；不声称磁盘释放 | W15 |
| `add-observability-runtime-evidence` | 16 | 观测栈动态验证：Targets UP、9 条 loaded、**5 firing / 4 未点着**、面板出图；证据 `docs/observability-runtime-evidence.md`；**未改阈值**；**未声称** DLQ 端到端 | W16 |
| `fix-schema-mysql-pk` | 17 | schema.sql 25 张表为 AUTO_INCREMENT 补 `PRIMARY KEY (id)`（MySQL 8 空库可建）；存量迁移 `2026-W16-add-primary-keys.sql`；约定测试 `SchemaSqlMysqlCompatibilityTest`；**MySQL 空库真机初始化仍未实测**（证据止于文本护栏 + H2 210 全绿） | W16 |
| `add-auth-must-change-password` | 18+ | 接通恒 0 的死标记 `must_change_password`：`CurrentUserResponse` 暴露 + `AdminInitializer` **仅首次创建**置 1（两道提前 return 在构造 `User` 之前）+ 改密成功置 0 + 重导契约（+2 行）；**刻意不入 JWT claim**、**不改鉴权拦截器**、零 DDL、不追溯存量 admin；`MustChangePasswordIntegrationTest` 5 例（含连续两次 `run()` 不打回已改密 admin）；基线 213 → **218**。**前端守卫未接**，见遗留 #11 | W18 |
| `add-backend-openapi` | 18 | springdoc 2.8.13 暴露 `/v3/api-docs`（65 paths、`openapi: 3.1.0`、覆盖 14 个 Controller）；导出 `openapi.yaml` 为前端客户端唯一契约来源；5 个公开端点由 `OpenApiCustomizer` 显式标 `security: []`（清单常量须与 `WebMvcConfig` 白名单同步）；契约冒烟测试 + 导出受 `exportContract` 开关控制（CI 基线 `Skipped: 1` 属设计使然）；prod 关 swagger-ui | W17 |
| `add-agent-context-routing` | 工程性 E1 | **工程性变更（不占业务阶段号）**：`README.md` 去事实化改为路由表（删除端口/口令/工程结构/里程碑四类副本）；新增仓库根 `AGENTS.md`——4 条硬约定按「规则 + canonical 出处 + 可机械执行的自查命令」承载，禁忌只给指针；新增 `docker/mysql/migrations/README.md` 自述该目录无自动执行者；本文件登记 `agent-harness` 能力域并把 3 处硬编码计数换成可复算语句；收尾五步补第 7 步。三条自查各做过变异验证（注入违规→变红→撤销→变绿）。零代码、零构建配置改动 | 2026-09-21 |

## 遗留事项（已归档但未收口，勿当成已完成）

1. **交卷链路压测未做**（`add-exam-taking` task 8）——JMeter 5000 并发交卷压测与硬指标验收（P99 < 2s / 0 丢单 / 批量落库 < 30s）**未执行**，仓库内无任何 `.jmx` 或压测报告。前置瓶颈已由 `add-mq-trace-and-capacity` 消除：消费并发原先实际为 **1**（手工构造的 `batchContainerFactory` 从未设并发，`RABBIT_CONCURRENCY:2` 对它不生效），现已显式化并可经 `exam.taking.mq.concurrency` 调整；**具体数值仍须真跑压测定稿**，该变更只给可复算的容量模型，不替代实测。
5. **补考成绩规则已合入规范但从未接线**（阶段 12 取证时发现）——`MakeupScoreService.finalScore(examId, studentId)` **全仓库零调用**，`mergeFinalScore(...)` 只被纯函数单测调用，也没有任何暴露"补考最终成绩"的接口。而 `spec/specs/absence-makeup/spec.md` 的 `Requirement: 补考成绩规则`（取最高分/取最近一次/取平均分）**已合入并验收**——属"已验收但未接线"的需求。修复需新增接口/查询路径（功能变更），建议单独立项 `add-makeup-final-score`，**不要在本清单里当成已完成**。

6. **死信队列的"真 broker 往返"未验证**（阶段 14 取证时发现）——阶段 14 已补指标/告警/mock 重投（`exam.mq.dlq.depth` / `exam.mq.retry` / `exam.mq.dlq.entered`、告警 `MqDlqBacklog`/`MqSubmitRetryExhausted`、面板「交卷死信队列深度」、`DlqReplayService` 先留档再重投），但**本机 Docker 未运行、集成测试用 mock `RabbitTemplate`**，**「真发一条坏消息 → 真进 DLQ → 真重投回来」仍未验证**。**不得声称死信链路端到端已验证。**
7. **磁盘空间回收不在任何提案范围内**（阶段 15 取证时发现）——MySQL InnoDB 的 `DELETE` 只把页标记为可复用，**文件大小不会变小**；真正回收需 `OPTIMIZE TABLE` 或 `ALTER TABLE ... ENGINE=InnoDB`（离线重写整表、期间锁表），在在线考试系统上属高风险窗口操作。`add-data-retention`（阶段 15）的目标是**控制行数与查询代价**（避免全表扫描与索引膨胀），**不是腾磁盘**。**不得声称"清理后磁盘释放"。**
8. **`exams` 表没有 `ended_time` 列**（阶段 15 取证时发现）——实际结束时刻无字段记录，`updated_time` 会被任意更新刷新（表达的不是结束时刻）。`add-data-retention` 因此改用 `end_time`（时间窗终点）作为"考试已终结"的代理，误差方向是**晚删而非早删**（`force-end` 提前结束的考试其 `end_time` 仍在未来），属安全选择。若要精确化需新增列（= 迁移），当前不值得。
9. **MySQL 8 空库真机初始化未实测**（阶段 17 验收时确认）——`fix-schema-mysql-pk` 的证据止于：文本约定测试（`SchemaSqlMysqlCompatibilityTest`，凡 AUTO_INCREMENT 必有 PRIMARY KEY）+ H2 全量 210 全绿。**尚未**在真实空 MySQL 8 实例上执行过 `schema.sql` 并建全 25 张表；`2026-W16-add-primary-keys.sql` 存量迁移也**未在真实存量库跑过**。**不得据此声称「MySQL 8 新环境可启动」已端到端验证**。
10. **启动期「答案补发对账」在真 broker 下抛异常**（阶段 18 验收时由指导 agent 实测发现）——用真 dev 实例（`exam-mysql-master` 13306 + `exam-rabbitmq` 5672 + 宿主 Redis 6379）启动时，`ExamSubmitSender.send` 调 `RabbitTemplate.waitForConfirmsOrDie` 抛 `IllegalStateException: This operation is only available within the scope of an invoke operation`，调用栈经 `SpringApplicationRunListeners.ready` → `ExamSweepService`；同批日志为 `答案补发对账: 待补=2 已补=0`。**根因**：`waitForConfirmsOrDie` 只能在 `RabbitTemplate.invoke()` 作用域内调用，而测试环境 RabbitMQ 是 mock 且 `auto-startup: false`，**这条路径从未在真 broker 下跑过**（与遗留 #6 同源）。应用仍能 `/actuator/health` = UP，**非致命**，但启动对账实际未补发成功。**未修**（不属阶段 18 范围，已明令子 agent 不得顺手修）。修复需改 `ExamSubmitSender` 的 confirm 用法，建议单独立项。
11. **初始密码强制修改：后端已接通，前端守卫仍未接**（阶段 19 开工时由子 agent 发现缺口、指导 agent 核实；后端已由 `0fb56b9` 实现并归档为 `add-auth-must-change-password`，**本条因前端未接而保留在遗留清单**）——原状态：`src/main` 中该字段仅 `schema.sql` L14 建列与 `User.java` L39 实体字段两处**声明**，**无读路径**（零 getter 调用、无 DTO 装载、`JwtUtil` claim 不含它，`CurrentUserResponse` 与 `openapi.yaml` 均无该字段）；写路径虽有但**恒写 0**（`AdminInitializer` 创建分支与 `AuthService` 各有一处 `setMustChangePassword(0)`）。后果：`AdminInitializer` 用配置的初始密码创建 admin，**该初始密码永远不被强制更换**，属真实安全缺口。
    > 指导 agent 取证更正：最初 grep 用小写 `mustChangePassword`，匹配不到 `setMustChangePassword`（大写 M），因此一度误判为「`AdminInitializer` 与 `AuthService` 根本没引用该字段」。正确表述是**有写路径但恒写 0**。「永不置 1、外部读不到」的结论不变。**教训：grep 实体字段时必须同时匹配 `setXxx` / `getXxx` 大小写变体，或用 `-i`。**
    现状态（`0fb56b9`）：`CurrentUserResponse` 暴露 `mustChangePassword`（`Boolean`，装载为原始 `boolean`，故 `/api/auth/me` 恒有值）；`AdminInitializer` 仅首次创建置 1（两道提前 return 在构造 `User` 之前，物理上不可能改写已存在账号）；`changePassword` 成功置 0；刻意**不入 JWT claim**（可变状态入无状态 token 会有「已改密但旧 token 仍说必须改密」窗口）；**未改鉴权拦截器**（不做后端强拦）；零 DDL、不追溯存量 admin。集成测试 `MustChangePasswordIntegrationTest` 5 例覆盖，含「连续两次 `adminInitializer.run()` 不打回已改密 admin」。基线 213 → **218**（`Skipped: 1` 不变）。
    **前端守卫尚未接（本条仍开放）**：阶段 19 已把「强制改密前置」Requirement 移出（当时契约无该字段）。契约现已就绪（`/api/auth/me` 返回 `mustChangePassword`，且**恒有值**、`non_null` 不会吞掉 `false`，前端可直接 `if (me.mustChangePassword)`），需另立前端小变更把守卫接回（守卫入口已收敛为 `frontend/src/router/access.ts` 的单一函数 `decideNavigation`）。**在前端守卫落地前，不得声称「初始密码强制修改」这项用户可感知的能力已完成**——后端标记可读可写，但没有任何东西阻止未改密的 admin 继续使用系统。
12. **契约导出「路 A」有编码缺陷，修好前必须走路 B**（`add-auth-must-change-password` 实施时由子 agent 发现）——`OpenApiContractTest.exportOpenApiContract()` 用 `MockHttpServletResponse.getContentAsString()` 取正文，**未设 charset 时按 ISO-8859-1 解码**，写出的 `openapi.yaml` 会让 `info.description` 与 `securitySchemes.Authorization.description` 的中文**全部乱码**，且 `servers.url` 从 `http://localhost:8080` 退化为 `http://localhost`。修法：改用 `getContentAsByteArray()`。该方法受 `exportContract` 开关控制、常规测试不触发，故未污染 CI，但**`-DexportContract=true` 一旦执行就会损坏仓库里的契约文件**（子 agent 已 `git checkout -- openapi.yaml` 回滚，未越界修改测试类）。**在修复前，重新导出 `openapi.yaml` 必须走路 B（真 dev 实例 `Invoke-WebRequest /v3/api-docs.yaml`）**；交接文档与相关提示词中「推荐路 A」的表述以本条为准。修复属独立小变更，尚未立项。

**已收口（从遗留清单移出）**：

1. **限流器对 Redis 异常的兜底** 已由 `add-rate-limit-resilience`（阶段 10）实现——默认 fail-open 放行以保核心链路可用，同时打 ERROR 日志并递增 `exam.ratelimit.degraded` 计数（可按接口维度区分）；另留 `exam.ratelimit.fail-open=false` 切回 fail-close。
2. **有指标无告警、无面板** 已由 `add-alerting-and-dashboards`（阶段 11）实现——7 条告警规则（含 `RateLimitDegraded`，使 `exam.ratelimit.degraded` 从「埋了没人看」变为「有告警值守」）+ Grafana 数据源与总览面板；规则只使用能从 `BusinessMetrics` 常量确定性推导的指标名，刻意不写 `hikaricp_connections_*`（dynamic-datasource 下未实测，写错会让规则因 `no data` 永久静默）。其**动态验证**已由阶段 16 收口（见下条）。

3. **考后闭环缺端到端串联验收** 已由 `add-post-exam-closure-e2e`（阶段 12）收口——曾是真问题：阶段 9 各环节有独立测试，但没有「建班→结束→缺考→补考→批改发布→复核」整链；且 `force-end` 曾漏标缺考（状态已 ENDED 后定时扫描无法自愈）。现由 `PostExamClosureIntegrationTest` 9 条用例覆盖，两条结束路径均 `markAbsence`。
4. **缺考/补考真实链路仅 Mockito、以及 `@Sql` 掩盖缺表 / `score_review` 缺列** 已由阶段 12 收口——曾是真问题：`ClassManagementIntegrationTest` 的 `@Sql` 自建表掩盖过缺表回归；`score_review` 缺 `created_time` 曾使复核申请 INSERT 在任何环境必失败。现 `src/test` 无 `@Sql`，schema/migration 已补列，`listByExam` 同步改为 `@PathVariable`。
5. **观测栈动态行为** 已由 `add-observability-runtime-evidence`（阶段 16）收口——Targets `UP`、9 条规则 loaded、**5 条真实 firing**（ExamOnlineDown / RateLimitDegraded / MqSubmitRetryExhausted / MqDlqBacklog / AntiCheatEventSpike）、面板出图；另 **4 条流量/性能阈值未在本机点着**（Http5xxRatioHigh / SubmitFailureRatioHigh / MqSubmitQueueBacklog / SubmitLatencyP99High）且**未改规则凑绿**，已留 PromQL 反证。详见 `docs/observability-runtime-evidence.md`。**不得据此声称遗留 #6（DLQ 真 broker 端到端）已完成**（firing ≠ 重投闭环）。

## 能力地图（规范组织单位；**已合入基线**的条数以 `find spec/specs -mindepth 1 -maxdepth 1 -type d | wc -l` 为准，本文件不写死计数；下表允许出现"已立项、基线待收尾时创建"的能力域，由该行自己注明）

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
| 9 | data-access | 读写分离、读己之写、数据保留与有界清理（三张辅助表 / 零 DDL / 默认双关）、新库建表 MySQL 8 兼容（AUTO_INCREMENT 必须有主键） |
| 10 | observability | 指标导出、自定义业务指标、慢 SQL 识别与请求关联、异步链路请求关联、指标驱动的告警、观测面板、死信队列指标与告警、观测栈动态可验证性 |
| 11 | reliability | 接口限流（Redis 令牌桶）、分布式一致性、限流粒度、限流器降级与可观测、定时扫描多实例幂等、交卷锁按持有者解锁、死信可见性与有界重投 |
| 12 | data-consistency | 事务显式回滚、受检异常转换 |
| 13 | class-management | 班级 CRUD、学生入班/转班、班级学生列表 |
| 14 | absence-makeup | 缺考标记（含自然到点与 force-end 两条结束路径）、补考独立记录、补考成绩规则合并、考后闭环端到端一致性 |
| 15 | score-review | 复核申请限次限时、复核中隐藏成绩、复核处理 |
| 16 | api-contract | OpenAPI 契约暴露、鉴权语义标注、契约导出复现、契约冒烟护栏 |
| 17 | agent-harness | 根入口路由（`README.md` 只指路不承载易变事实）、硬约定的 agent 必经索引（`AGENTS.md`）、无自动执行者的 SQL 目录必须自述触发方式、文档判据优先于文档常量。**基线已合入 `spec/specs/agent-harness/spec.md`**（E1，2026-09-21）；E2 的四条待其 B-2 收口后合入 |

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
| 13 | add-multi-instance-sweep-safety | reliability（定时扫描多实例安全 + 锁解锁修正） | W14 | 已归档 |
| 14 | add-dlq-observability-and-replay | reliability + observability（死信可见性、告警与重投） | W14-W15 | 已归档（真 broker 往返仍遗留） |
| 15 | add-data-retention | data-access（数据保留与清理） | W15 | 已归档 |
| 16 | add-observability-runtime-evidence | observability（观测栈动态可验证性） | W16 | 已归档（5 firing / 4 未点着；#6 仍遗留） |
| 17 | fix-schema-mysql-pk | data-access（新库建表 MySQL 8 兼容） | W16 | 已归档（MySQL 空库真机初始化未实测） |
| 18 | add-backend-openapi | api-contract（OpenAPI 契约暴露） | W17 | 已归档（返修 1 轮：pom 格式 / 测试副作用 / 免鉴权标注） |
| 18+ | add-auth-must-change-password | authentication（初始密码强制修改标记） | W18 | 已归档（前端守卫未接，遗留 #11） |

## 工作流

1. 一个开发阶段 = 一个变更提案（`changes/{change-id}/`）。
2. 提案审批后按 `tasks.json` 逐步实施（每次处理一个 step）。
3. 阶段完成并验收后，`spec-delta.md` 的需求合入 `specs/{capability}/spec.md`，变更目录移入 `changes/archive/`。
4. 收尾五步（本项目约定）：spec-delta 合入 specs → 变更目录移入 archive → 回勾 `tasks.json`（**按代码实际完成度回查，不得凭印象勾满**）→ 更新本 README → commit。
5. **集成测试不得用 `@Sql` 自建表**（本项目硬约定）：测试库建表只以 `src/main/resources/schema.sql` 为唯一来源（`application-test.yml` 已配 `mode: always` + `continue-on-error: false`）。自建表会让「新库/新环境建不起来」被测试掩盖——`schema.sql` 曾缺 `classes`/`user_class` 两表而 CI 全绿，就是这么发生的。目标：`grep -rn '@Sql' src/test` 保持为空。
6. **实体字段与建表定义必须双向一致**（本项目硬约定）：MyBatis-Plus 按实体字段生成 INSERT，**实体有、表里没有的列会让该写入在任何环境都失败**——`score_review` 缺 `created_time` 而 `ScoreReview` 实体有 `@TableField(fill = INSERT) createdTime`（全局 `MetaObjectHandler` 会填充），导致复核申请接口从未成功执行过一次（阶段 12 挖出）。核对手法：用脚本比对每个含 `createdTime` 的实体的 `@TableName` 与 `schema.sql` 中对应建表语句，双方都必须齐。**新增表/实体时必须双向核对**，且优先靠「走真实链路的集成用例」暴露，而不是靠人肉比对。
7. **收尾时同步 `AGENTS.md` 的指针（只加指针，不加正文）**：本次变更若新增了易变事实的载体（新的配置文件、新的 SQL 目录、新的门禁命令、新的能力域），检查仓库根 `AGENTS.md` 是否需要因此新增一条「去哪儿查」的指针或一条自查命令；需要就加指针，**不要把事实本身抄进去**（端口、口令、用例数、覆盖率、表清单、"共 N 个"一律不进 `AGENTS.md` 与 `README.md`）。判据与自查命令见 `AGENTS.md` 末尾与 `agent-harness` 能力域规格。

## 参考资料（非 openspec 资产）

- `docs/examOnline需求规格说明书.md` — 完整产品愿景
- `docs/需求决策记录.md` — 80+ 项场景决策（开发逐条对照）
- `docs/面试版实施方案.md` — v3 大厂面试级实施方案（开发蓝本）
- `docs/指导Agent交接文档.md` — 交接说明（注意：其中「阶段 8.1/8.2/9 已完成归档」的描述直到 2026-09-14 才真正成立）

