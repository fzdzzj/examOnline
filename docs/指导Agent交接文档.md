# examOnline 项目交接文档（指导 Agent 视角）

> **交接对象**：下一位「项目指导 Agent」
> **文档时点**：2026-09-16。**阶段 1–11 已归档；阶段 12 代码已完成待收口；阶段 13/14/15 已立项待实施**（提案 + 子 agent 提示词均已落盘）。
> **本文档的作用**：让接手者不必重读全部历史对话，就能继续以相同方式推进项目，并且知道**下一步该深化哪里、为什么、代价是什么**。

---

## 一、项目定位（先对齐目标，否则会走偏）

这是**个人面试作品（大厂面试级）**，不是商业项目。

- **目标不是"功能多"，而是"技术深度 + 被追问三层能答上"**：
  - **L1 功能正确**：正常流程跑通；
  - **L2 并发边界正确**：并发、重复、超时、崩溃、网络分区下行为正确；
  - **L3 可观测、可恢复、可证明**：有指标、有日志、有对账自愈、有压测数据。
- **策略**：收窄广度、加深深度。宁可 9 个模块讲透，不要 20 个模块讲浅。
- **开发模式**：**AI 写码 + 人消化**。人是面试主体，必须能回答三层追问——所以代码注释写"为什么"，不写"做了什么"。
- **当前形态**：**纯后端单体**（本仓库无前端）。Vue3 前端不在本仓库。
- **判断一切"要不要做"的标准**：这件事能不能让某条关键路径**从"我说它有"变成"我能证明它有"**。做不到就别做。

---

## 二、技术栈与仓库事实

| 类别 | 选型 |
|---|---|
| 语言/框架 | Java 17 + Spring Boot 3.5.5（单体） |
| 持久层 | MyBatis-Plus 3.5.9（+ jsqlparser 分页插件） |
| 数据库 | MySQL 8.0 **主从**（GTID 复制，master:3306 / slave:3307） |
| 缓存 | Redis 7（缓存 / 草稿 / 计数器 / 令牌桶 / 黑名单 / 分布式锁） |
| 消息 | RabbitMQ 3.13（生产者 confirm + 消费者手动 ack + 批量落库 + 死信） |
| 多数据源 | dynamic-datasource 4.3.1（`@DS` 注解 + 读己之写切面） |
| 可观测 | Micrometer + prometheus registry + 慢 SQL 拦截器 + 7 条告警规则 + Grafana 面板 |
| 认证 | jjwt 0.11.5（双 Token）+ jbcrypt |
| 构建 | Maven（`maven-settings.xml` + `.mvn/maven.config`，本地仓库 `.m2-repo/`） |

**仓库事实（2026-09-16 实测）**：主代码 **237** 个 Java 文件、**34** 个测试类、**81** 个端点映射、**13** 个 Controller、**25** 张表、**15** 个能力域、**14** 个已归档变更、**76** 次提交。工作分支 `feature/add-performance-deepening-readwrite`。

**测试基线**：全量 **190 → 191**（本轮新增 1 条「教师复核清单」用例），**Failures 0 / Errors 0**。规模不大但每条都对应一个真实不变量或一条真实链路。

---

## 三、阶段全景

| 阶段 | 变更 ID | 能力域 | 状态 |
|---|---|---|---|
| 1 | `add-project-skeleton` | authentication（工程基础） | ✅ 已归档 |
| 2 | `add-authentication` | authentication | ✅ 已归档 |
| 3 | `add-question-bank` | question-bank（含 paper-assembly） | ✅ 已归档 |
| 4 | `add-exam-management` | exam-management | ✅ 已归档 |
| 5 | `add-exam-taking` | exam-taking | ✅ 已归档（压测遗留） |
| 6 | `add-grading-score` | grading + score-management | ✅ 已归档 |
| 7 | `add-anti-cheat` | anti-cheat | ✅ 已归档 |
| 8 | `add-performance-deepening` | performance + data-access + observability | ✅ 已归档 |
| 8.1 | `add-slow-sql-and-rate-limit` | reliability + observability（慢 SQL） | ✅ 已归档 |
| 8.2 | `add-tx-rollback-consistency` | data-consistency | ✅ 已归档 |
| 9 | `add-class-and-post-exam-closure` | class-management + absence-makeup + score-review | ✅ 已归档 |
| 10 | `add-mq-trace-and-capacity` | observability + exam-taking（trace 透传 + 容量模型） | ✅ 已归档 |
| 10 | `add-rate-limit-resilience` | reliability（限流器 fail-open 降级） | ✅ 已归档 |
| 11 | `add-alerting-and-dashboards` | observability（7 条告警 + Grafana 面板 + 静态校验） | ✅ 已归档（动态验证遗留） |
| 12 | `add-post-exam-closure-e2e` | absence-makeup（闭环端到端 + 两个真缺陷） | 🟡 **代码完成，待归档** |
| 13 | `add-multi-instance-sweep-safety` | reliability（定时扫描多实例安全 + 锁解锁修正） | ⬜ 已立项待实施 |
| 14 | `add-dlq-observability-and-replay` | reliability + observability（死信可见性与重投） | ⬜ 已立项待实施 |
| 15 | `add-data-retention` | data-access（数据保留与清理） | ⬜ 已立项待实施 |

> 阶段 12 的产出远超原计划：它本来只是"补端到端用例"，结果**用例第一次执行就翻出两个从未被发现的真缺陷**（见 §9.1）。这是本项目最有说服力的一次"补覆盖 ≠ 形式主义"的证明。

---

## 四、资产地图（接手第一件事）

```
D:\code\examOnline\
├── docs/                                    ← 产品与决策文档（人读）
│   ├── examOnline需求规格说明书.md          ← 完整产品愿景（原始需求）
│   ├── 需求决策记录.md                      ← ★ 最重要：80+ 项场景决策，改动代码前必查
│   ├── 面试版实施方案.md                    ← v3 蓝本（四大深水区 + 里程碑）
│   ├── newLab后端经验参考.md                ← 同类项目经验参考
│   └── 指导Agent交接文档.md                 ← ★ 本文档
├── spec/                                    ← ★ OpenSpec 规范资产（spec 驱动开发的核心）
│   ├── README.md                            ← 能力地图 + 阶段映射 + 工作流 + 遗留事项（权威台账）
│   ├── specs/{capability}/spec.md           ← 已实现的规范（累积真相，15 个能力域）
│   └── changes/
│       ├── {change-id}/                     ← 进行中提案（proposal + tasks.json + specs/*/spec-delta.md
│       │                                       + agent-prompt*.md ← 子 agent 提示词，直接复制可用）
│       └── archive/{change-id}/             ← 已归档提案（14 个）
├── src/main/java/com/exam/
│   ├── auth/        ← 认证：注册/登录/双Token/黑名单/RBAC/越权/锁定限流
│   ├── user/        ← 用户/角色/权限五表
│   ├── question/    ← 题库：题目/标签/答案归一化（AnswerNormalizer）
│   ├── paper/       ← 组卷：试卷/题目/快照/随机抽题
│   ├── exam/        ← 考试管理：状态机 CAS/考试快照/缺考补考入口（AbsenceService）
│   ├── taking/      ← 在线答题：进入考试/个人快照/草稿/兜底扫描/MQ 拓扑（RabbitMqConfig）
│   ├── submission/  ← 答卷：交卷幂等 / MQ 消费（批量落库+重试+死信）/ 防重表 / 行为日志
│   ├── grading/     ← 判分：策略模式（单选/多选/判断/简答）+ 人工批改
│   ├── score/       ← 成绩：汇总/发布/撤回/导出（SXSSF 流式）/审计/复核
│   ├── anticheat/   ← 防作弊：行为事件策略模式/严重度/切屏
│   ├── monitoring/  ← BusinessMetrics（自定义指标）+ 监考大屏
│   ├── clazz/       ← 班级体系（注意：class 是关键字，故包名 clazz）
│   ├── common/      ← ApiResponse/异常/RequestIdFilter/ResponseCode
│   │   ├── cache/       ← CacheMutexLoader（防击穿互斥重建，含正确的 Lua 解锁）
│   │   ├── slow_sql/    ← SlowSqlInterceptor
│   │   └── ratelimit/   ← @RateLimit / RateLimitInterceptor / RedisTokenBucket
│   └── config/      ← 多数据源/缓存/读己之写/MyBatis-Plus/初始化器
├── src/main/resources/
│   ├── application.yml          ← ★ 全部配置集中在此（含 exam.* 业务配置、jackson non_null）
│   ├── application-dev.yml      ← 本地数据源/Redis/Mail
│   └── schema.sql               ← ★ 新库幂等建全（25 张表，唯一建表来源）
├── docker/
│   ├── mysql/master|slave/init/ ← 主从复制初始化
│   ├── mysql/migrations/        ← ★ 存量库迁移（5 个脚本，含 W15 补列）
│   └── observability/           ← Prometheus rules + Grafana provisioning/dashboards（独立编排片段）
├── docker-compose.yml           ← MySQL 主从 + Redis + RabbitMQ 一键编排
└── pom.xml
```

---

## 五、核心业务链路（面试主线，必须能顺下来）

```
出题 → 组卷(随机抽题/快照锁定) → 建考试(绑班级/时间窗/时长) → 发布(生成考试快照)
   → 学生进入(个人快照锁定抽题与乱序 + 个人倒计时) → 答题(自动保存/断线恢复/切屏埋点)
   → 交卷(三重幂等 + MQ 削峰 + 超时三路兜底) → 判分(客观自动/简答人工批改)
   → 成绩汇总 → 发布(可撤回) → 导出
   → 【考后闭环】考试结束(自然到点 或 教师提前结束)自动标记缺考 → 教师筛选指定补考
   → 补考(独立考试 + 成绩规则) → 学生成绩复核申请(限1次/7天, 复核中隐藏成绩) → 教师处理 → 更新显示
```

**四大深水区**（面试问得最深的地方）：

1. **交卷链路可靠性**（最核心）
   - 三重幂等：防重表 + `uk_exam_student` 唯一索引 + Redis SETNX 一次性锁；
   - 三路竞态（手动 / 前端倒计时归零 / 后端定时兜底）共享锁与状态机 CAS，**只提交一次**；
   - MQ 削峰：生产者 confirm + 消费者手动 ack + 批量落库（`rewriteBatchedStatements`）+ 重试 + 死信；
   - **答案补发对账**：已交卷但 `answers` 未落库的答卷重新投递（MQ 丢消息自愈）；
   - 兜底扫描：`ExamSweepService`（超时强制交卷 + 补发对账），与状态机扫表同构；
   - `doSubmit` **刻意不加 `@Transactional`**：流程长且含 MQ confirm，各步各自原子，且 **MQ 发送在 CAS 之后** → 不会出现"消息先于 DB 状态到达"。**这是可讲的正面设计，别误当缺陷。**
2. **认证与安全**：双 Token（Access 30min / Refresh 7d）+ rotation + **复用检测**；Redis 黑名单；登录锁定（5 次/15min）+ 接口限流；RBAC 五表 + `OwnershipGuard`（ADMIN 层级 3 越级放行）；统一错误提示防账号枚举。
3. **数据一致性**：乐观锁 `version` + 状态机 CAS；试卷/考试**双快照**；逻辑删除；唯一索引兜底幂等；`@Transactional(rollbackFor = Exception.class)` 全项目统一。
4. **性能工程**：缓存三防（穿透=空值缓存 / 击穿=**Redis SETNX 全局互斥**重建 / 雪崩=TTL 抖动）；读写分离（`@DS("slave")` + 读己之写短窗口路由）；Redis 令牌桶限流（Lua 原子）+ **Redis 故障时 fail-open 降级**；Prometheus 指标 + 慢 SQL 拦截器（带 requestId）；死信队列。

---

## 六、关键设计决策速查（面试必答）

完整清单在 `docs/需求决策记录.md`（**80+ 条，改动代码前务必对照**）。高频条目：

| 主题 | 决策 | 出处 |
|---|---|---|
| 时间基准 | **服务端时间为准**，前端倒计时仅展示 | §1.1 |
| 个人倒计时 | **点击"开始考试"才计时** | §7.9 |
| 交卷 | 交卷即终稿**不可反悔**；重复提交返回首次结果 | §1.x |
| 教师提前结束 | 按**最后自动保存**强制交卷；**同样必须标记缺考**（阶段 12 修） | §1.5 |
| 切屏检测 | **只警告 + 记录，绝不强制交卷**；教师事后依日志判定 | §3.1 |
| 抽题/乱序 | **进入考试时锁定一次**，刷新不换题 | §3.4 |
| 试卷锁定 | **考试开始后锁定试卷**（禁改题/删题/改分值） | §4.1 |
| 状态机 | 未开始→进行中→已结束→已批改→已发布，**乐观锁 CAS** | §4.3 |
| 考试快照 | **发布时生成**，之后答题/判分/回看一律读快照 | §10.10 |
| 多选判分 | 漏选给部分分、错选/多选 0 分；**首版系数全局 1.0** | §8.9 |
| 缺考 | 时间窗结束仍未点开始 = 缺考；**两条结束路径都触发**（阶段 12 明确） | §8.10 |
| 补考 | **独立考试记录**（独立时间窗/时长/规则），成绩规则取最高/最近/平均 | §5.1 §12.5 |
| 转班 | **成绩随人**（答卷绑 student_id，成绩不依赖班级） | §12.6 |
| 成绩撤回 | 撤回需**管理员权限 + 审计日志**；撤回后学生端隐藏 | §5.3 |
| 成绩复核 | 每场**限 1 次**、发布后 **7 天内**；复核中**隐藏成绩**（字段直接不出现在响应里） | §10.7 §5.4 |
| 防作弊裁剪 | 不做人脸/设备指纹硬拦截、不做浏览器锁定 | §10.x |
| 观测栈边界 | 告警规则**只引用能从 `BusinessMetrics` 常量确定性推导的指标名**；刻意不写 `hikaricp_*` | 阶段 11 |

---

## 七、指导 Agent 的工作方法（★ 本节是交接重点）

这是本项目行之有效的协作方式，接手者请**沿用**：

### 7.1 决策必须先问、不擅自定
- 遇到有取舍的设计点（范围切分、技术选型、业务规则、**同一缺陷的两种修法**），用 `AskUserQuestion` **让用户拍板**。
- **一次问 1–2 个关键问题**，每个选项**附代价说明**（"更一致但要跑迁移" vs "零迁移但偏离约定"）。
- 典型案例：① 阶段 9 原以为可以不做班级体系，核实后发现 `User` 无班级字段、`Exam.classId` 是**悬空 ID**，"应考名单"无从推导——地基问题必须先问；② 阶段 12 缺陷三，子 agent 选了"删实体字段"而提示词要求"补列"，两者都成立 → 交给用户定，不替用户选。

### 7.2 一个阶段 = 一个提案（OpenSpec）
- 提案四件套：
  ```
  spec/changes/{change-id}/
  ├── proposal.md              ← Why / 已核实事实 / 期望状态 / What Changes / Impact / 时间线 / 风险
  ├── tasks.json               ← [{number, category:"阶段N：xxx（Wx）", task, steps[], passes}]
  ├── specs/{capability}/spec-delta.md   ← ## ADDED / MODIFIED Requirements（EARS 格式）
  └── agent-prompt.md          ← ★ 子 agent 提示词，自包含可直接复制执行
  ```
- **规范格式**：`### Requirement: xxx` + `系统 SHALL ...`，每条下配 `#### Scenario:` 用 `GIVEN / WHEN / THEN`。
- **tasks.json 的 `category` 带周标记**（如 `"阶段 2：状态机（W4）"`），沿用参考项目 `D:\code\sports\spec` 的风格。
- 一个 change 可跨多个能力域（阶段 9 跨 3 个、阶段 14 跨 2 个）。

### 7.3 证据优先：写提案前先核实
- **不要凭印象写提案**。先 `grep`/`read` 核实代码现状，否则会写出"已经实现的东西"。
- 已发生过的误判（教训）：以为缓存三防没做（实际已完整）；以为事务回滚是 bug（实际是防御性规范）；提案里写 `ResponseCode.RATE_LIMITED`（实际是 `TOO_MANY_REQUESTS`，照抄会编译失败）；**本轮又三次**：以为分页 `size` 无上限（实际 `Math.min(size,100)`）、以为缓存击穿用本地锁（实际 Redis SETNX）、以为兜底扫描缺索引（实际有 `idx_submissions_sweep`）。
- **反证纪律**：提优化建议前**先证伪自己的假设**；**负结论必须写下来**（否则下次会重复发现同一件事，本项目已这样排除 3 条）。已证伪清单见 §9.6。
- **发现自己的判断有误要当场更正**，并在提案里写明"本提案性质是 X 不是 Y"。

### 7.4 子 Agent 委派四要素（硬要求）
每份子 agent 提示词必须写死：
1. **写入边界**：只允许写哪些文件 + 明确"绝对不要触碰"清单；
2. **最多修复尝试次数**：默认 **1 次**，不过就如实上报停止；
3. **回报上限**：≤300 字（实际执行时允许放宽，但必须结构化：基线 / 改动清单 / 结果行 / 逐条对照 / 意外发现）；
4. **细节留文件**：细节写进代码注释与 commit message。

补充规则：
- **广度受控**：一次任务默认 ≤4 个子 agent；
- **一个路径只有一个写入者**；范围大就拆块 + 串并行编排；
- **共享文件要保护**：`schema.sql`、`pom.xml`、`application.yml`、`config/` 是冲突高发区。

### 7.5 收尾五步（每个阶段完成时）
1. `spec-delta.md` 的需求**合入** `spec/specs/{capability}/spec.md`；
2. 变更目录移入 `spec/changes/archive/{change-id}/`；
3. 更新 `spec/README.md`（能力地图 + 阶段映射 + 遗留事项）；
4. `tasks.json` 回勾（**按代码实际完成度回查**，做不完留 `false` 并写明原因，**不勾满**）；
5. git commit（**两段式**：`feat(<域>): <资产>` + `docs(spec): 归档 <id> 并合入 <域> 规范`）。

### 7.6 Commit 规范
- **Conventional Commit + 中文描述**：`feat(x):` / `test(x):` / `docs(spec):` / `chore(spec):` / `perf(x):`
- 粒度：**一个 task 一个 commit**；中文说明"做了什么 + 为什么"。
- **注释写"为什么"，不写"做了什么"**。

### 7.7 与用户的协作契约（观察所得）
- 用户会**亲自审提案**、**自己把提示词发给子 agent**、**自己提交/核对代码** → 提示词必须**自包含、可直接复制粘贴**（现固化为 `agent-prompt.md` 文件）。
- 用户会**纠正方向**（"一个阶段一个提案，不要拆成 4 个碎片"、"我要的是像 `D:\code\sports\spec` 那样的"、"应该是三个"）。**被纠正后立即对齐，不辩解。**
- 用户重视：中文注释、"为什么"而非"做了什么"、写入边界隔离、commit 规范、**不为了数字写假测试**。
- 用户会问"还有没有可以进化/更符合现实的"——此时从**业务完整性 + 面试追问深度**两个角度回答，不是技术洁癖式重构。

### 7.8 ★ 子 agent 的交付必须独立核实（本轮新增，重要）
- **回报 ≠ 事实**。阶段 12 第 1 轮，子 agent 回报"已完成"，实测**只交付了测试文件，两处必需源码修改一处都没做**。
- 接手后**第一件事**是 `git status --short` + `git diff --stat` + 关键点 `grep`，逐个确认"它到底改了哪些文件、改成什么样"。
- 第 2 轮它又**走了一条与提示词相反的路线**（删实体字段而非补列）。**不看 diff 就看不出来**。
- 结论：**每轮交付都必须重新核实**，并把核实结论写进下一轮提示词的"现状"段（让子 agent 不用重复劳动，也让用户的每一步都有据可依）。

### 7.9 ★ 提示词落盘（本轮新增）
- 提示词不再只在对话里给，而是写成 `spec/changes/<change-id>/agent-prompt.md`（多轮则 `agent-prompt-continue[-2].md`）。
- 收益：可复用、可追溯、归档时随提案一起进 archive，成为"这个阶段是怎么被实施的"的记录。

### 7.10 ★ 两条最有效的质量判据（本轮再次验证）
- **红先绿后**：新增用例必须先跑出**预期的红**（证明它不是空转），修缺陷后转绿；**不接受放宽断言式的转绿**。阶段 12 的 4 个红 → 两个真缺陷，是这个判据最有力的证据。
- **零覆盖区优先怀疑缺陷**：JaCoCo 覆盖率极低的能力域（`MakeupService` 11.8%、`ScoreReviewController` 22.2%），**优先怀疑那里有真缺陷，而不是先补测试**。本项目两个真缺陷（`force-end` 漏标记缺考、`score_review` 缺列）全部出自"有实现、无端到端用例"的地方。

---

## 八、已知坑与易错点（踩过的）

| 坑 | 正确做法 |
|---|---|
| 限流错误码 | `ResponseCode.TOO_MANY_REQUESTS`（1008/429），**没有 `RATE_LIMITED`** |
| 慢 SQL 阈值配置 key | `exam.monitor.slow-sql-threshold-ms`（默认 1000） |
| `class` 是 Java 关键字 | 包名 `com.exam.clazz`、实体 `ClassEntity`、表 `classes` |
| 建表方式 | 新库走 `schema.sql`（幂等建全，**25 张表**）；存量库必须手工执行 `docker/mysql/migrations/*.sql` |
| **`CREATE TABLE IF NOT EXISTS` 不会为已存在的表补列** | 加列必须**同时**改 `schema.sql` 与写迁移脚本；否则新库能跑、存量库继续报错（或反之） |
| **集成测试禁止用 `@Sql` 自建表** | 建表只以 `schema.sql` 为唯一来源。自建表会让"新库建不起来"被测试掩盖（`classes`/`user_class` 曾缺表而 CI 全绿）。目标 `grep -rn '@Sql' src/test` 保持为空 |
| **实体字段与建表定义必须双向一致** | MyBatis-Plus 按实体生成 INSERT，"实体有、表里没有的列"**在任何环境都会失败**。`ScoreReview.createdTime` vs `score_review` 缺列就是这么藏了很久（复核申请接口从未成功执行过一次）。核对手法：比对每个含 `createdTime` 的实体的 `@TableName` 与 `schema.sql` |
| **`application.yml` 的 `spring.jackson.default-property-inclusion: non_null`** | 响应里 **null 字段整个消失**（不是 `"f": null`）。读 JSON 做断言时 `node.get("f")` 对缺失 key 返回 **Java null**，`.isNull()` 会 NPE。用 `assertNull(node.get("f"))` 或 `node.path("f").isNull()`——**`path()` 优于 `get()`** |
| **`@PathVariable` 与路径模板必须匹配** | `ScoreReviewController.listByExam` 路径写 `{examId}` 却用 `@RequestParam Long examId` → 按 REST 语义调用必然 400，端点从未跑通。审计脚本（正则扫全部 Controller）应作为收尾自查项 |
| 多选部分分系数 | 首版**全局 1.0**；"按考试粒度配置"需 `exams` 加字段，留待独立变更 |
| 慢 SQL 拦截点 | 拦截 `StatementHandler.query/update`（能直接拿 `BoundSql`），不是 `Executor` |
| 读己之写 | 强一致读（答卷详情/成绩）**不加 `@DS("slave")`**，默认走主库 |
| 缓存范围 | **只缓存只读快照**；有更新路径的用短 TTL + 写路径 `@CacheEvict` |
| 子 agent 并发 | `schema.sql`、`pom.xml`、`application.yml`、`config/` 是冲突高发区，必须划定唯一写入者 |
| tasks.json 漏勾 | 代码提交了但勾选没更新（阶段 7 发生过），收尾要回查 |
| **Git 斜杠分支的 ref 会莫名消失** | 提交后 `HEAD` 变 unborn。**提交没丢**，sha 在 `.git/logs/HEAD`。`git update-ref` 不可信 → 用 `mkdir -p .git/refs/heads/feature && printf '%s\n' <sha> > .git/refs/heads/feature/<branch>`，再 `git rev-parse HEAD` 验证。**每次提交后都要复查** |
| **`target/surefire-reports/` 里可能有陈旧 XML** | 旧类名残留（如 `ReaddWriteSoSeperationIntegrationTest`）会让"XML 合计"虚高（实测虚高 3 个）。**计数前先核对每个 XML 对应当前测试类** |
| **Maven 全量重编会清空 `target/classes` 里的已拷贝资源** | 表现为 `No schema scripts found at location 'classpath:schema.sql'` → 一堆 `Failed to load ApplicationContext`（实测一次 8 Errors），**不是 schema 或断言问题**。补跑 `process-resources` 即可；`mvn clean test` 同风险。增量编译不受影响 |
| H2 方言 | `MODE=MySQL` 实测支持 `INSERT IGNORE`、`AUTO_INCREMENT`、`TINYINT`、`DATETIME`、具名 `CONSTRAINT ... UNIQUE`、`KEY idx_x (col)`；重复 `INSERT IGNORE` 影响 0 行。故缺考/补考幂等链可在 H2 真跑 |
| RabbitMQ 未启动 | `/actuator/prometheus` 无法实测（应用 boot 失败）；用 `PrometheusMeterRegistry.scrape()` 单测兜底验证导出格式 |

---

## 九、深化优化路线图（★ 接手后按此推进）

### 9.1 第一步（阻塞项）：收口阶段 12 `add-post-exam-closure-e2e`

**代码已完成、测试已绿，只差归档**：

| 项 | 状态 |
|---|---|
| `ExamService.forceEnd` 在 CAS 后调 `absenceService.markAbsence(id)` | ✅ |
| `schema.sql` 的 `score_review` 补 `created_time` + `2026-W15-add-score-review-created-time.sql` | ✅ |
| `ClassManagementIntegrationTest` 删 `@Sql` + 改 javadoc（`@Sql` 全仓归零） | ✅ |
| `PostExamClosureIntegrationTest`（9 用例，含本轮新增的教师复核清单） | ✅ 9/9 |
| `ScoreReviewController.listByExam` 的 `@RequestParam` → `@PathVariable` | ✅（本轮由指导 agent 修） |
| 覆盖率目标（阶段 4） | ✅ `MakeupService` 81.74% / `AbsenceService` 94.24% / `ScoreReviewController` **100%**（修 `listByExam` 前是 77.78%，未达标） |
| 全量回归 | ✅ **191 / 0 / 0**（全项目指令 89.35% / 行 89.53%） |

**收口五步**：delta 合入 `spec/specs/absence-makeup/spec.md` → 目录移入 `spec/changes/archive/` → `tasks.json` 回勾（阶段 1/2/3/4/5，**注意阶段 4 的覆盖率目标现在已达成**）→ 更新 `spec/README.md` 遗留 #2/#3 收口 → 两段式 commit。

### 9.2 已立项待实施（提案 + 提示词已就绪，直接可用）

| 阶段 | 变更 | 要解决的问题（已取证） | 关键取舍 |
|---|---|---|---|
| 13 | `add-multi-instance-sweep-safety` | 两个 `@Scheduled` 扫描无任何协调，而"多实例会不会重复执行"**只有注释声称、无测试证明**；且 `ExamSubmitService` 的 SETNX 锁**解锁不校验持有者**（token 生成了从未使用） | **刻意不加调度锁**：正确性靠下游幂等，换来定时兜底不依赖 Redis 可用性；若将来要加锁**必须 fail-open** |
| 14 | `add-dlq-observability-and-replay` | 死信队列**无消费者、无指标、无告警、无重投**；`MqSubmitQueueBacklog` 只看主队列 → 消息进死信后主队列指标反而"变好看"（**指标方向性错误**） | `receive()` 是 autoAck，"取出→落档"有毫秒级崩溃窗口 → 论证其可接受（重投是加速手段，答卷仍被对账扫描兜底）；**拒绝**引入常驻 DLQ 消费者（会 CPU 空转死循环）；**不给 DLQ 设 TTL**（等于把兜底改成延迟丢弃） |
| 15 | `add-data-retention` | 三张诊断/幂等辅助表**只增不减**；且它们的 `created_time` **都无索引**，最"简单"的 `DELETE WHERE created_time < ?` 是**全表扫描** | 改为**按考试生命周期**（`exams.end_time`）逐考试删 `exam_id` → 命中既有索引最左前缀，**零 DDL 零迁移**；默认 `enabled=false` + `dry-run=true` |

### 9.3 缺陷台账（已查实、尚未修复）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | **`MakeupScoreService.finalScore(examId, studentId)` 全仓零调用**；`mergeFinalScore` 只被纯函数单测调用；无"补考最终成绩"接口 → `spec/specs/absence-makeup` 的 `Requirement: 补考成绩规则`（取最高/最近/平均）**已验收但从未接线** | grep 零命中 | 立项 `add-makeup-final-score`（阶段 16）：想清"最终成绩在哪暴露"（学生查成绩？导出？），属**功能变更** |
| 2 | `/actuator/**` 从认证拦截器排除（Prometheus 抓取需要，阶段 11 依赖它，属**有意取舍**），但没有独立端口/IP 白名单隔离 → 生产暴露 `/actuator/metrics`、`health` 组件详情 | `AuthenticationInterceptor` 排除清单 | 小改：`management.server.port` 或 IP 白名单。**别当成漏洞**，要讲成"有意识的取舍 + 待补的隔离" |
| 3 | **死信链路无出口** | 见阶段 14 | 已立项 |
| 4 | **锁解锁未校验持有者** | 见阶段 13 | 已立项 |
| 5 | 分支覆盖率 67.8% | JaCoCo | 见 §9.5-①，**不做数字游戏** |

### 9.4 唯一遗留硬指标（面试最想看的数据）

**JMeter 5000 并发交卷压测**（`add-exam-taking` 任务 8）：
- 验收硬指标：**P99 < 2s、0 丢单、批量落库 < 30s**；
- 前置瓶颈**已消除**：消费并发原先实际为 **1**（手工构造的 `batchContainerFactory` 从未设并发，`RABBIT_CONCURRENCY:2` 对它不生效），阶段 10 已显式化并可经 `exam.taking.mq.concurrency` 调整；容量模型已给公式（吞吐 ≈ 并发 × batchSize / 单批落库耗时）；
- **数值必须真跑，不替代实测**。产出应包含 `.jmx` 资产 + 报告。

### 9.5 深化候选（按"能否让某条路径从'我说它有'变成'我能证明它有'"排序）

| 优先 | 候选 | 为什么值得做 | 前置/代价 |
|---|---|---|---|
| ★★★ | **观测栈动态验证**：把 `docker/observability/` 真跑起来，让 7 条规则至少 **firing 过一次**、面板真出图 | 目前只有**静态正确性**（`AlertAssetsTest` 守住文件可解析 + 指标名可推导 + 已做变异验证），L3 的成色差这一步。**"我配了告警"到"我见过它报警"是面试里的两个层级** | Docker 运行；约半天 |
| ★★★ | **一条 requestId 贯穿 HTTP → MQ → 落库的可展示证据** | trace 透传已实现（阶段 10），但缺一份"按同一 requestId 把三个环节的日志串起来"的实录。这是 L3"可观测"最直观的证明 | 无代码改动，只需跑一次并留证 |
| ★★☆ | **优雅停机期间在途消息的行为证据** | `shutdown: graceful` 已配，但"消息是否真的不丢/不重复"**无证据**。可做：停机时投一批消息，验证未 ack 消息回队列并被重投、幂等生效 | 无代码改动（若发现缺陷则需改） |
| ★★☆ | **多实例的"真两个进程"演示** | 阶段 13 只做同构两线程（同进程），真起两个 JVM 的证据更强。可先用同一 jar 不同端口跑两次 | 依赖阶段 13 先完成 |
| ★★☆ | **分支覆盖率定向收口** | 不看总数，而是看"**没被走到的分支里，哪些是真边界**"（如判分策略的错选分支、限流降级的 fail-close 分支、复核窗口过期的分支）。找到一条补一条 | 需要先有一份"未覆盖分支清单" |
| ★☆☆ | **压测脚本资产化** | 见 §9.4 | 依赖环境 |
| ★☆☆ | **业务补全**：通知中心 / 错题本 / Excel 导入 / 题目查重 / 选做题计分 / A-B 卷 | `docs/需求决策记录.md` 已确认但未实现（清单见 §9.7）。**按面试主线取舍**，这些是"广度"，不是"深度" | 每项 1–3 天 |

### 9.6 已证伪 / 勿重复建设（**负结论，别再"发现"一遍**）

- 分页 `size` **已 clamp**（`Math.min(size, 100)`；行为日志 `Math.min(Math.max(size,1),200)`，`page>=1`）——不是漏洞；
- 缓存击穿保护用 **Redis SETNX 全局互斥**（`CacheMutexLoader` + Lua compare-and-delete），**不是本地锁** → 多实例下正确；
- 兜底扫描**有专用索引** `idx_submissions_sweep (status, deadline_time)`；
- 消费端幂等**已做**（`casFillAnswers` 仅在 `answers IS NULL` 时写）；
- 优雅停机**已配**（`shutdown: graceful`）；
- 对账扫描**已做**（`ExamSweepService` 10s / 批 500）；
- publisher confirm + 「暂存草稿 + 对账补发」= **outbox 的对账式等价实现，勿再加本地消息表**；
- 观测栈配置资产（阶段 11）**勿重新设计**，缺的是动态验证（§9.5）；
- 限流器 Redis 异常兜底、有指标无告警 —— **均已收口**（阶段 10 / 11）。

### 9.7 不建议做（附理由）

- **缓存二级（Caffeine + Redis）**：本项目无前端、单体现状下收益有限；
- **为整洁而拆 God class**（`PaperService` 431 行、`ScoreService` 380+ 行）：讲得通即可，不为整洁而重写；
- **引入调度中间件（ShedLock/Quartz/Redisson）**：与本项目"定时兜底不依赖 Redis"的取舍相冲突（见阶段 13）；
- **给死信队列设 TTL / max-length**：等于把"最终兜底"改造成"延迟丢弃"；
- **`OPTIMIZE TABLE` 在线回收磁盘**：离线重写整表、期间锁表，风险远大于收益（阶段 15 明确 out of scope）。

### 9.8 业务补全候选（决策已确认、代码未做）

| 功能 | 决策出处 | 价值 |
|---|---|---|
| 通知中心（站内信 + 微信模板消息，四类通知） | §8.8 | 考前提醒/成绩发布/缺考异常 |
| 设备/IP 指纹记录 + 报警（**记录不阻止**） | §9.2 | 补防作弊的设备维度 |
| 考前人脸核验（**仅考前核验、考中零抽拍**） | §10.x | 防替考，隐私优先 |
| 错题本归集（全部考试类型，可过滤） | §8.5 | 学习闭环 |
| 学生账号 Excel 导入 + 初始密码 | §8.6 | 教务对接 |
| 题目查重（相似度标记"疑似重复"，人工确认） | §10.9 | 题库质量 |
| 选做题计分（答对加分，超满分封顶） | §8.3 | 组卷灵活性 |
| 客观题答案修正重判（撤回→修正→重算→重发） | §5.2 | 容错 |
| 批量导入容错（逐行校验 + 错误报告） | §12.7 | 导入健壮性 |
| A/B 卷分配 | §10.8（**V2 延后**） | 防抄袭 |
| 多选部分分**场次粒度**配置 | §8.9 注记 | 独立变更 `add-partial-score-config` |

---

## 十、验证与运行

### 10.1 本机构建的坑（**必须先看**）

- `JAVA_HOME` **未设置**；PATH 里 `java` 是 **1.8** 而 `javac` 是 **21**；`D:\develop\jdk17` 是**空目录勿用**，真 JDK 在 `D:\develop\jdk177`；Maven 在 `D:\develop\Maven\apache-maven-3.9.4`。
- **Git Bash 的 `mvn` 脚本跑不起来**（Plexus classworlds Launcher 路径错），`cmd.exe` 被禁 → **必须直调 launcher**：

```bash
cd /d/code/examOnline
'D:\develop\jdk177\bin\java.exe' -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

- 单类跑：结尾换成 `-o test -Dtest=PostExamClosureIntegrationTest -DfailIfNoTests=false`。
- `-o`（离线）必须带；`.mvn/maven.config` 会自动附加 `-s maven-settings.xml`（本地仓库 `.m2-repo/`）。
- 质量门禁（**不阻断**）：JaCoCo 0.8.12 / SpotBugs 4.10.4.1 / checkstyle 3.6.0，**版本须显式锁定**。基线：Checkstyle 0 违规、SpotBugs 0 缺陷。跑法：launcher 命令后追加 `checkstyle:check spotbugs:check`。
- 覆盖率报告：`target/site/jacoco/jacoco.csv`。
- ⚠️ **全量重编会清空 `target/classes` 里的已拷贝资源** → 出现 `classpath:schema.sql` 找不到的假故障，补跑 `process-resources` 即可。

### 10.2 运行

```powershell
# 1. 中间件（MySQL 主从 + Redis + RabbitMQ）
docker compose up -d

# 2. 启动（dev profile 默认，schema.sql 幂等建库）
mvn spring-boot:run

# 3. 指标端点（需 RabbitMQ 已启动，否则 boot 失败）
#    GET http://localhost:8080/actuator/prometheus
#    GET http://localhost:8080/actuator/health

# 4. RabbitMQ 管理台
#    http://localhost:15672  (exam / exam123)

# 5. 观测栈（独立编排片段，不并入主 compose）
#    docker compose -f docker/observability/docker-compose.observability.yml up -d

# 6. 存量库迁移（已建库的环境必须手工执行）
#    docker exec -i exam-mysql-master mysql -uroot -proot123 exam_online < docker/mysql/migrations/xxx.sql
```

**默认账号**（首次启动 `AdminInitializer` 自动创建）：`admin` / `admin123`
**账号规则**：学生 = **学号**，教师 = **工号**（教师注册需邀请码，管理员生成）

---

## 十一、接手检查清单

- [ ] 读 `docs/需求决策记录.md`（80+ 决策）——**改动代码前的必读**；
- [ ] 读 `spec/README.md`（能力地图 + 阶段映射 + **遗留事项台账**）与 `spec/specs/` 现状，确认与代码一致；
- [ ] 跑一次全量测试确认基线（当前 **191 / 0 / 0**），用直调 launcher 命令；
- [ ] `git rev-parse HEAD` 确认 ref 未消失（**每次 commit 后都要查**）；
- [ ] 先做 §9.1 阶段 12 收口（阻塞项），再动阶段 13；
- [ ] 决策下一阶段前，先 `grep`/`read` 核实代码现状，**不要凭印象**；
- [ ] 有取舍的设计点先 `AskUserQuestion`，**不擅自决定**；
- [ ] 委派子 agent 时写全四要素，并把提示词落盘为 `agent-prompt.md`；
- [ ] **每轮子 agent 交付后，独立 `git status` + `git diff` 核实它到底改了什么**（§7.8）；
- [ ] 新增/修改用例时坚持"**红先绿后**"，**不接受放宽断言式的转绿**（§7.10）。

---

**一句话总结**：这个项目的价值不在功能数量，而在**"每个关键路径都能被追问三层且答得上"**——交卷链路、认证安全、数据一致性、性能工程四大深水区是面试主战场。接手时请优先保证这四块的**深度与可证明性**：**把"我实现了"变成"我有证据"**。阶段 12 最大的收获正是这个——一条端到端用例，翻出了两个埋了很久的真缺陷。
