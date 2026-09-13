# examOnline 项目交接文档（指导 Agent 视角）

> **交接对象**：下一位「项目指导 Agent」
> **文档前提**：阶段 8.1（慢 SQL + 接口限流）、阶段 8.2（事务回滚统一）、阶段 9（班级体系 + 考后闭环）**三份提案已完成、验收并归档**。文中凡涉及这三块产物（`com.exam.clazz` 包、W10 迁移文件、对应 specs）均按"已完成"描述。
> **本文档的作用**：让接手者不必重读全部历史对话，就能继续以相同方式推进项目。

---

## 一、项目定位（先对齐目标，否则会走偏）

这是**个人面试作品（大厂面试级）**，不是商业项目。

- **目标不是"功能多"，而是"技术深度 + 被追问三层能答上"**：
  - **L1 功能正确**：正常流程跑通；
  - **L2 并发边界正确**：并发、重复、超时、崩溃、网络分区下行为正确；
  - **L3 可观测、可恢复、可证明**：有指标、有日志、有对账自愈、有压测数据。
- **策略**：收窄广度、加深深度。宁可 9 个模块讲透，不要 20 个模块讲浅。
- **开发模式**：**AI 写码 + 人消化**。人是面试主体，必须能回答三层追问——所以代码注释要写"为什么"，而不是"做了什么"。
- **当前形态**：**纯后端单体**（本仓库无前端目录）。Vue3 前端不在本仓库。

---

## 二、技术栈

| 类别 | 选型 |
|---|---|
| 语言/框架 | Java 17 + Spring Boot 3.5.5（单体） |
| 持久层 | MyBatis-Plus 3.5.9（+ jsqlparser 分页插件） |
| 数据库 | MySQL 8.0 **主从**（GTID 复制，master:3306 / slave:3307） |
| 缓存 | Redis 7（三级用途：缓存/草稿/计数器/令牌桶/黑名单） |
| 消息 | RabbitMQ 3.13（生产者 confirm + 消费者手动 ack + 死信） |
| 多数据源 | dynamic-datasource 4.3.1（`@DS` 注解 + 读己之写切面） |
| 可观测 | Micrometer + micrometer-registry-prometheus + 慢 SQL 拦截器 |
| 认证 | jjwt 0.11.5（双 Token）+ jbcrypt |
| 构建 | Maven（`maven-settings.xml` + `.mvn/maven.config`，本地仓库 `.m2-repo/`） |

**仓库事实（阶段 9 完成时点）**：约 **206+ 个主代码 Java 文件**、**27+ 个测试类**、**50+ 次提交**；当前工作分支 `feature/add-performance-deepening-readwrite`。

---

## 三、阶段全景（阶段 1-9 全部完成）

| 阶段 | 变更 ID | 能力域 | 周 | 状态 |
|---|---|---|---|---|
| 1 | `add-project-skeleton` | authentication（工程基础） | W1 | ✅ 已归档 |
| 2 | `add-authentication` | authentication | W1-W2 | ✅ 已归档 |
| 3 | `add-question-bank` | question-bank + paper-assembly | W2 | ✅ 已归档 |
| 4 | `add-exam-management` | exam-management | W3 | ✅ 已归档 |
| 5 | `add-exam-taking` | exam-taking | W4-W6 | ✅ 已归档 |
| 6 | `add-grading-score` | grading + score-management | W7 | ✅ 已归档 |
| 7 | `add-anti-cheat` | anti-cheat | W8 | ✅ 已归档 |
| 8 | `add-performance-deepening` | performance + data-access + observability | W9-W10 | ✅ 已归档 |
| 8.1 | `add-slow-sql-and-rate-limit` | observability + reliability | W10 | ✅ 已归档 |
| 8.2 | `add-tx-rollback-consistency` | data-consistency | W10 | ✅ 已归档 |
| 9 | `add-class-and-post-exam-closure` | class-management + absence-makeup + score-review | W10-W11 | ✅ 已归档 |

> ⚠️ **已知待办**：`add-exam-taking` 的 **JMeter 5000 并发交卷压测**（硬指标 P99<2s / 0 丢单 / 落库<30s）是唯一遗留项，见第九节。

**假设完成后 `spec/specs/` 应包含 15 个能力域**：
`authentication`、`question-bank`、`paper-assembly`、`exam-management`、`exam-taking`、`grading`、`score-management`、`anti-cheat`、`performance`、`data-access`、`observability`、`reliability`、`data-consistency`、`class-management`、`absence-makeup`、`score-review`。

---

## 四、资产地图（接手第一件事）

```
D:\code\examOnline\
├── docs/                                    ← 产品与决策文档（人读）
│   ├── examOnline需求规格说明书.md          ← 完整产品愿景（10 模块，原始需求）
│   ├── 需求决策记录.md                      ← ★ 最重要：80+ 项场景决策，开发逐条对照
│   ├── 面试版实施方案.md                    ← v3 面试蓝本（四大深水区 + 里程碑）
│   ├── newLab后端经验参考.md                ← 同类项目经验参考
│   └── W1-工程骨架开发总结.md
├── spec/                                    ← ★ OpenSpec 规范资产（spec 驱动开发的核心）
│   ├── README.md                            ← 能力地图 + 阶段→变更映射 + 工作流
│   ├── specs/{capability}/spec.md           ← 已实现的规范（累积真相）
│   └── changes/
│       ├── {change-id}/                     ← 进行中的提案（proposal + tasks + spec-delta）
│       └── archive/{change-id}/             ← 已完成的提案
├── src/main/java/com/exam/
│   ├── auth/        ← 认证：注册/登录/双Token/黑名单/RBAC/越权/锁定限流
│   ├── user/        ← 用户/角色/权限五表
│   ├── question/    ← 题库：题目/标签/归一化（AnswerNormalizer）
│   ├── paper/       ← 组卷：试卷/题目/快照/随机抽题
│   ├── exam/        ← 考试管理：状态机 CAS/考试快照/试卷锁定
│   ├── taking/      ← 在线答题：进入考试/个人快照/草稿/兜底扫描
│   ├── submission/  ← 答卷：交卷幂等/MQ 发送与消费/防重表/行为日志
│   ├── grading/     ← 判分：策略模式（单选/多选/判断/简答）+ 人工批改
│   ├── score/       ← 成绩：汇总/发布/撤回/导出（SXSSF 流式）/审计
│   ├── anticheat/   ← 防作弊：行为事件策略模式/严重度/切屏
│   ├── monitoring/  ← 监考大屏 + BusinessMetrics（自定义指标）
│   ├── clazz/       ← ★ 阶段9：班级体系（注意：class 是关键字，故包名 clazz）
│   ├── common/      ← ApiResponse/异常/RequestIdFilter/ResponseCode
│   │   ├── cache/       ← CacheMutexLoader（防击穿互斥重建）
│   │   ├── slow_sql/    ← SlowSqlInterceptor
│   │   └── ratelimit/   ← @RateLimit / RateLimitInterceptor / RedisTokenBucket
│   └── config/      ← 多数据源/缓存/读己之写/MyBatis-Plus/初始化器
├── src/main/resources/
│   ├── application.yml          ← ★ 全部配置集中在此（含 exam.* 业务配置）
│   ├── application-dev.yml      ← 本地数据源/Redis/Mail
│   └── schema.sql               ← 新库幂等建全（CREATE TABLE IF NOT EXISTS）
├── docker/
│   ├── mysql/master/init/       ← 主库复制账号
│   ├── mysql/slave/init/        ← 从库 GTID 复制配置
│   └── mysql/migrations/        ← ★ 存量库迁移（W7 判分字段 + W10 阶段9 三张新表）
├── docker-compose.yml           ← MySQL 主从 + Redis + RabbitMQ 一键编排
└── pom.xml
```

---

## 五、核心业务链路（面试主线，必须能顺下来）

```
出题 → 组卷(含随机抽题/快照锁定) → 建考试(绑班级/时间窗/时长) → 发布(生成考试快照)
   → 学生进入(个人快照锁定抽题与乱序 + 个人倒计时) → 答题(自动保存/断线恢复/切屏埋点)
   → 交卷(三重幂等 + MQ 削峰 + 超时三路兜底) → 判分(客观自动/简答人工批改)
   → 成绩汇总 → 发布(可撤回) → 导出
   → 【考后闭环】考试结束自动标记缺考 → 教师筛选指定补考 → 补考(独立考试+成绩规则)
   → 学生成绩复核申请(限1次/7天, 复核中隐藏) → 教师处理 → 更新显示
```

**四大深水区**（对应 `docs/面试版实施方案.md`，是面试问得最深的地方）：

1. **交卷链路可靠性**（最核心）
   - 三重幂等：防重表 + `uk_exam_student` 唯一索引 + Redis SETNX 一次性锁；
   - 三路竞态（手动 / 前端倒计时归零 / 后端定时兜底）共享锁与状态机 CAS，**只提交一次**；
   - MQ 削峰：生产者 confirm + 消费者手动 ack + 批量落库（`rewriteBatchedStatements`）+ 死信重试；
   - **答案补发对账**：已交卷但 `answers` 未落库的答卷重新投递（MQ 极端丢消息自愈）；
   - 兜底扫描：`ExamSweepService`（超时强制交卷 + 补发对账），与状态机扫表同构。
2. **认证与安全**：双 Token（Access 30min / Refresh 7d）+ rotation + **复用检测**；Redis 黑名单；登录锁定（5 次/15min）+ 接口限流；RBAC 五表 + 越权防护（`OwnershipGuard`）；统一错误提示防账号枚举。
3. **数据一致性**：乐观锁 `version` + 状态机 CAS（`UPDATE ... WHERE status=? AND version=?`）；试卷/考试**双快照**（发布时固化）；逻辑删除；唯一索引兜底幂等；`@Transactional(rollbackFor = Exception.class)` 统一。
4. **性能工程**：缓存三防（穿透=空值缓存 / 击穿=SETNX 互斥重建 / 雪崩=TTL 抖动）；读写分离（`@DS("slave")` + 读己之写短窗口路由）；核心接口 Redis 令牌桶限流（Lua 原子）；Prometheus 指标 + 慢 SQL 拦截器（带 requestId）。

---

## 六、关键设计决策速查（面试必答）

完整清单在 `docs/需求决策记录.md`（**80+ 条，改动代码前务必对照**）。高频条目：

| 主题 | 决策 | 出处 |
|---|---|---|
| 时间基准 | **服务端时间为准**，前端倒计时仅展示 | §1.1 |
| 个人倒计时 | **点击"开始考试"才计时** | §7.9 |
| 交卷 | 交卷即终稿**不可反悔**；重复提交返回首次结果 | §1.x |
| 教师提前结束 | 按**最后自动保存**强制交卷 | §1.5 |
| 切屏检测 | **只警告 + 记录，绝不强制交卷**；教师事后依日志判定 | §3.1 |
| 抽题/乱序 | **进入考试时锁定一次**，刷新不换题 | §3.4 |
| 试卷锁定 | **考试开始后锁定试卷**（禁改题/删题/改分值） | §4.1 |
| 状态机 | 未开始→进行中→已结束→已批改→已发布，**乐观锁 CAS** | §4.3 |
| 考试快照 | **发布时生成**，之后答题/判分/回看一律读快照 | §10.10 |
| 多选判分 | 漏选给部分分、错选/多选 0 分；**首版系数全局 1.0** | §8.9 |
| 缺考 | 时间窗结束仍未点开始 = 缺考；可被筛选指定补考 | §8.10 |
| 补考 | **独立考试记录**（独立时间窗/时长/规则），成绩规则取最高/最近/平均 | §5.1 §12.5 |
| 转班 | **成绩随人**（答卷绑 student_id，成绩不依赖班级） | §12.6 |
| 成绩撤回 | 撤回需**管理员权限 + 审计日志**；撤回后学生端隐藏 | §5.3 |
| 成绩复核 | 每场**限 1 次**、发布后 **7 天内**；复核中**隐藏成绩** | §10.7 §5.4 |
| 防作弊裁剪 | 不做人脸/设备指纹硬拦截、不做浏览器锁定 | §10.x |

---

## 七、指导 Agent 的工作方法（★ 本节是交接重点）

这是本项目行之有效的协作方式，接手者请**沿用**：

### 7.1 决策必须先问、不擅自定
- 遇到有取舍的设计点（范围切分、技术选型、业务规则），用 `ask_user_question` **让用户拍板**，不要自行假设。
- **一次问 1-2 个关键问题**，选项要给出代价说明（"最完整但范围大" vs "最聚焦"）。
- 典型案例：阶段 9 我原以为可以不做班级体系，核实后发现 `User` 无班级字段、`Exam.classId` 是**悬空 ID**，"应考名单"根本无从推导——**这是必须由用户决策的地基问题**，问完再动笔。

### 7.2 一个阶段 = 一个提案（OpenSpec）
- 提案三件套（缺一不可）：
  ```
  spec/changes/{change-id}/
  ├── proposal.md                              ← Why / 背景 / 当前状态 / 期望状态 / What Changes / Impact / 时间线 / 风险
  ├── tasks.json                               ← [{number, category:"阶段N：xxx（Wx）", task, steps[], passes}]
  └── specs/{capability}/spec-delta.md         ← ## ADDED Requirements（EARS 格式）
  ```
- **规范格式**：`### Requirement: xxx` + `系统 SHALL ...`，每条下配 `#### Scenario:` 用 `GIVEN / WHEN / THEN`。
- **tasks.json 的 `category` 带周标记**（如 `"阶段 2：状态机（W4）"`），这是参考项目 `D:\code\sports\spec` 的风格，用户在意图保持。
- 一个 change 可跨多个能力域（如阶段 9 跨 class-management / absence-makeup / score-review）。

### 7.3 证据优先：写提案前先核实
- **不要凭印象写提案**。先 `grep`/`read` 核实代码现状，否则会写出"已经实现的东西"。
- 本项目已发生过的误判（教训）：
  - 以为缓存三防没做 → 实际 `CacheConfig`/`CacheMutexLoader` 已完整；
  - 以为事务回滚是 bug → 实际所有受检异常都已转 `BusinessException`，**不是 bug 而是防御性规范**；
  - 提案里写 `ResponseCode.RATE_LIMITED` → 实际是 **`TOO_MANY_REQUESTS`**（照抄会编译失败）。
- **发现自己的判断有误要当场如实更正**，并在提案里写明"本提案性质是 X 不是 Y"（如阶段 8.2 明确写了"防御性规范统一，非 bug 修复"）。这是用户明确认可的做法。

### 7.4 子 Agent 委派四要素（硬要求）
每份子 agent 提示词必须写死：
1. **写入边界**：只允许写哪些文件，并声明"其余文件一律不碰 / 无其他写入者"；
2. **最多修复尝试次数**：默认 **1 次**，不过就如实上报停止（禁止"验证到满意"的自循环）；
3. **回报上限**：≤300 字，只报「产出路径 / 校验结果 / 未解决项 / 待决策项」；
4. **细节留文件**：细节写进代码注释与 commit message，**不得写进回报**。

补充规则：
- **广度受控**：一次任务默认 ≤4 个子 agent；
- **一个路径只有一个写入者**；范围大就**拆块 + 串并行编排**（例：阶段 9 拆 3 个 agent，第一轮 班级 + 复核 并行，第二轮 缺考+补考 依赖班级）；
- **共享文件要保护**：`schema.sql` 是共享文件 → 约定"所有 agent 都不改 schema.sql，各自写独立迁移文件"，收尾统一同步。

### 7.5 收尾流程（每个阶段完成时）
1. `spec-delta.md` 的需求**合入** `spec/specs/{capability}/spec.md`；
2. 变更目录移入 `spec/changes/archive/{change-id}/`；
3. 更新 `spec/README.md`（能力地图 + 阶段映射 + 状态）；
4. `tasks.json` 全部 `completed: true` / `passes: true`（**注意：常出现"代码已提交但 tasks.json 漏勾"**，需回查）；
5. git commit。

### 7.6 Commit 规范
- **Conventional Commit + 中文描述**：`feat(认证):` / `test(考试):` / `docs(spec):` / `chore(spec):` / `perf(x):`
- 粒度：**一个 task 一个 commit**（子 agent 各自提交）；
- 中文提交信息，说明"做了什么 + 为什么"。

### 7.7 与用户的协作契约（观察所得）
- 用户会**亲自审提案**、**自己把提示词发给子 agent**、**自己提交/核对代码**——所以**提示词要能"直接复制粘贴执行"**，自包含、无歧义。
- 用户会**纠正方向**（例："一个阶段一个提案，不要拆成 4 个碎片"、"我要的是像 `D:\code\sports\spec` 那样的"、"应该是三个"）。**被纠正后要立即对齐，不要辩解**。
- 用户重视：**中文注释、"为什么"而非"做了什么"、写入边界隔离、commit 规范**。
- 用户会问"还有没有可以进化/更符合现实的"——此时应从**业务完整性 + 面试追问深度**两个角度回答，而不是技术洁癖式的重构。

---

## 八、已知坑与易错点（踩过的）

| 坑 | 正确做法 |
|---|---|
| 限流错误码 | `ResponseCode.TOO_MANY_REQUESTS`（1008/429），**没有 `RATE_LIMITED`** |
| 慢 SQL 阈值配置 key | 实际是 `exam.monitor.slow-sql-threshold-ms`（默认 1000） |
| `class` 是 Java 关键字 | 包名用 `com.exam.clazz`，实体用 `ClassEntity`（表名 `classes`） |
| 建表方式 | 新库走 `schema.sql`（幂等建全）；**存量库**必须手工执行 `docker/mysql/migrations/*.sql`（MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，重复执行报 Duplicate 可忽略） |
| 多选部分分系数 | 首版**全局 1.0**；"按考试配置"需 `exams` 加字段，留待 `add-partial-score-config` |
| 慢 SQL 拦截点 | 拦截 `StatementHandler.query/update`（能直接拿 `BoundSql`），不是 `Executor` |
| 读己之写 | 强一致读（答卷详情/成绩）**不加 `@DS("slave")`**，默认走主库 |
| 缓存范围 | **只缓存只读快照**；有更新路径的（Exam 详情）用短 TTL + 写路径 `@CacheEvict` |
| 子 agent 并发 | `schema.sql`、`pom.xml`、`application.yml`、`config/` 是冲突高发区，必须划定唯一写入者 |
| tasks.json 漏勾 | 代码提交了但勾选没更新（阶段 7 anti-cheat 发生过），收尾要回查 |
| RabbitMQ 未启动 | `/actuator/prometheus` 无法实测（应用 boot 失败）；可用 `PrometheusMeterRegistry.scrape()` 单测兜底验证导出格式 |

---

## 九、剩余待办（Backlog）

### 9.1 唯一遗留硬指标（优先级最高）
- **JMeter 5000 并发交卷压测** — `add-exam-taking` 任务 8 剩余 2 步：
  - 产出压测报告；
  - 验收硬指标：**P99 < 2s、0 丢单、批量落库 < 30s**。
  - 前置：慢 SQL/指标/限流已就位（观测底座已具备）。

### 9.2 现实业务补全（决策已确认、代码未做）
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

### 9.3 技术深化候选（若要继续加深）
- 缓存二级（Caffeine 本地 + Redis）——本项目无前端，收益有限，**优先级低**；
- 幂等覆盖审计（发布/批改/成绩汇总是否补幂等）；
- 优雅停机（MQ 消费者先停）；
- 代码整洁度（`PaperService` 431 行、`ScoreService` 380+ 行有 God class 苗头，但**面试讲得通即可，不必为整洁而重写**）。

---

## 十、验证与运行

```powershell
# 1. 中间件（MySQL 主从 + Redis + RabbitMQ）
docker compose up -d

# 2. 全量测试（必须全绿；当前 27+ 测试类）
mvn clean test

# 3. 启动（dev profile 默认，schema.sql 幂等建库）
mvn spring-boot:run

# 4. 指标端点（需 RabbitMQ 已启动，否则 boot 失败）
#    GET http://localhost:8080/actuator/prometheus
#    GET http://localhost:8080/actuator/health

# 5. RabbitMQ 管理台
#    http://localhost:15672  (exam / exam123)

# 6. 存量库迁移（已建库的环境需手工执行）
#    docker exec -i exam-mysql-master mysql -uroot -proot123 exam_online < docker/mysql/migrations/xxx.sql
```

**默认账号**（首次启动 `AdminInitializer` 自动创建）：`admin` / `admin123`
**账号规则**：学生 = **学号**，教师 = **工号**（教师注册需邀请码，管理员生成）

---

## 十一、接手检查清单

- [ ] 读 `docs/需求决策记录.md`（80+ 决策）——**这是改动代码前的必读**；
- [ ] 读 `spec/README.md` 与 `spec/specs/` 现状，确认与代码一致；
- [ ] 跑一次 `mvn clean test` 确认基线全绿；
- [ ] 确认 `spec/changes/` 是否已清空（全部归档），`spec/specs/` 是否已含 15 个能力域；
- [ ] 确认 `spec/README.md` 的阶段映射已同步到阶段 9；
- [ ] 决策下一阶段前，先 `grep` 核实代码现状，**不要凭印象**；
- [ ] 有取舍的设计点先 `ask_user_question`，不要擅自决定；
- [ ] 委派子 agent 时写全四要素（写入边界 / 修复 1 次 / 回报 ≤300 字 / 细节留文件）。

---

**一句话总结**：这个项目的价值不在功能数量，而在**"每个关键路径都能被追问三层且答得上"**——交卷链路、认证安全、数据一致性、性能工程四大深水区是面试主战场，其余模块是支撑。接手时请优先保证这四块的**深度与可证明性**，而不是继续铺功能。
