# 项目概览（接手 agent 用）

> 时点：2026-09-21。**这份文档只做三件事**：说明项目是什么、指出每类事实的 canonical 真源、给出现在怎么验证。
> 它**不承载易变事实**——版本、端口、凭据、用例数、覆盖率、阶段状态一律不写在这里，只写"去哪个文件读"。
> 若你在这里读到某个数值而它已在别处被改动，那是这份文档的缺陷，见仓库根 `AGENTS.md` 末节的自查命令。

## 一、这是什么

在线考试系统，**个人面试作品，不是商业生产系统**。判断"值不值得做"的标准写在交接文档里：
能否让一条关键路径从「我说它有」变成「我能证明它有」。

每一块能力要能回答三层（出处 `docs/指导Agent交接文档.md` 第 14–24 行）：

- **L1 功能正确**——正常流程可用；
- **L2 并发边界正确**——并发、重复、超时、崩溃、网络分区、断线下的行为可解释；
- **L3 可观测、可恢复、可证明**——指标、日志、对账、自愈、动态证据、压测数据。

形态是**单体**：后端 Spring Boot + Java + Maven（一个 `pom.xml`，非多模块），前端 `frontend/` 独立 SPA。
版本与依赖不在此列出——后端读 `pom.xml`（父 POM `dependencyManagement` 集中锁定），前端读 `frontend/package.json`。

## 二、真源地图（最重要的一节）

| 你想知道 | 唯一真源 | 说明 |
|---|---|---|
| 表、列、索引 | `src/main/resources/schema.sql` | dev 与测试共用，H2/MySQL 双兼容。**未接 Flyway**，`db/migration/V*.sql` 形态的脚本在本仓库从不执行 |
| dev 连哪个库/端口/口令、起了哪些中间件 | `docker-compose.yml`（映射与编排）+ `src/main/resources/application-dev.yml`（应用侧默认值） | 两处默认值已对齐；`docker-compose.yml` 顶部注释解释了宿主端口为何不取各组件的默认端口 |
| 存量库的改表脚本 | `docker/mysql/migrations/`（**无自动执行者**，见该目录 README） | 把脚本放进去 ≠ 变更已生效 |
| HTTP 接口契约 | `openapi.yaml`（由后端 springdoc 导出） | 前端客户端由此生成；发现它落后于后端要停下回报，不要手写补丁类型 |
| 某个功能"应当"是什么行为 | `spec/specs/<能力域>/spec.md` | EARS 格式的需求 + 场景，这才是验收口径 |
| 当前做到哪、还剩什么 | `spec/README.md`：「当前状态」表 + 「遗留事项」清单 | **不要相信任何变更目录里的 `final-summary.md` / `progress-report*.md` / 提交信息**（见第六节） |
| 后端实测状态（覆盖率、用例数、哪些结论被证伪过） | `spec/changes/IMPLEMENTATION_STATUS.md` | 该文件自称"只记录现场复跑验证过的事实" |
| 为什么这么设计（含被推翻的方案） | `docs/需求决策记录.md` | 场景决策集，逐条对照用（条数不写在这里） |
| 环境事实、禁忌、已知假阳性 | `docs/指导Agent交接文档.md` | 含"不要做"清单、dev 库实况、启动日志里哪些异常**不要顺手修** |
| 观测面板与告警 | `docker/observability/`（独立编排，不随根 `docker compose up` 启动） | 前端 Grafana 入口的基址是环境变量，不写端口 |

能力域清单（名字，不是数量，也不解释范围——范围读各自 spec）：`authentication`、`question-bank`、`exam-management`、`exam-taking`、`grading`、`score-management`、`anti-cheat`、`performance`、`data-access`、`observability`、`reliability`、`data-consistency`、`class-management`、`absence-makeup`、`score-review`、`api-contract`、`agent-harness`。

## 三、业务主线（一句话一条，细节读 spec）

- 认证与鉴权：双 Token + Redis 黑名单 + RBAC + 登录锁定限流 + 初始密码强制修改（后端已通，**前端守卫未接**，见遗留 #11）。
- 题库与组卷：题目 CRUD/软删除、标签、手动组卷与标签随机抽题、**试卷快照锁定**（抽题结果固化，考试用快照而非实时题集）。
- 考试生命周期：创建 → 发布（生成快照 + 考生名单）→ 状态机推进 → `force-end`；状态以后端为准。
- 作答与交卷：进入考试拉**个人快照**、服务端时间倒计时、自动保存、交卷三重幂等 + MQ 削峰 + 超时兜底扫描。
- 判分与成绩：客观题自动判分、主观题批改（**乐观锁防并发覆盖**）、汇总/发布/撤回/流式导出。
- 考后闭环：缺考标记（自然到点与 force-end 两条路径都要标）、补考独立记录、成绩复核（限次限时、复核中隐藏成绩）。
- 防作弊：切屏检测**只警告不强制交卷**、行为日志、选项乱序（后端已洗牌并重映射答案字母）。
- 工程侧：读写分离（强一致读不标 `@DS("slave")`）、缓存三防、限流与降级、指标/告警/面板、数据保留与有界清理。

## 四、怎么验证（接手第一天就该跑的）

**后端**：**仓库自有的唯一门禁命令尚未定稿**——它待工程性变更 `update-agent-gate-single-source` 的 B-2 段（加 Maven wrapper、把 `.mvn` / `maven-settings.xml` 里的机器绝对路径去掉）落地后回填到 `AGENTS.md`。在此之前，本仓库于 2026-09-21 实测可跑的验证方式是 `mvn -o clean test`——但它依赖所执行 shell 的 JDK/Maven 解析（当次是在 Git Bash、`JAVA_HOME` 指向项目所需 JDK 的前提下跑通的），换环境先自检再拼命令，**不要把它当成已定稿的标准命令**；交接文档 §6.1 那段更完整的启动器写法已被明确标注为"特定 shell / 本机的历史绕行办法"，同样不是推荐命令。
⚠️ 必须带 `clean`——残留的 surefire 报告会让用例总数虚高。判据是 `Failures=0`、`Errors=0`、`Skipped` 保持 1（那 1 个 skip 是契约导出方法受 `exportContract` 开关控制，属设计使然，**不要试图消除**）。
覆盖率可复算：`mvn -o test jacoco:report` 后读 `target/site/jacoco/jacoco.csv`（JaCoCo 只出报告不拦截，见第五节）。

**前端**（在 `frontend/`）：`npm run lint:check`、`npm run type-check:check`、`npm run test`（vitest）、`npm run test:e2e`（playwright，需后端起着）。
注意：`type-check` 的串接符在 2026-09-21 之前写成了 `&`（后台符），app 侧类型错误**不进退出码**——也就是说这道门禁当时不可能失败。修好后暴露出三处既存红。这条历史值得记住，因为它说明"绿"要建立在能红的判据上。

**后端与前端的计数口径互不并入**（vitest 的用例数不进 Maven 的基线）。

## 五、四条硬约定（完整表述与自查命令在 `AGENTS.md`）

1. 建表唯一入口是 `schema.sql`；不接 Flyway，不恢复 `db/migration/V*.sql`。
2. 集成测试**不得用 `@Sql` 自建表**——那会把"新库建不起来"藏进 CI 的绿勾里（真发生过）。
3. 实体字段与建表定义**必须双向一致**——实体有、表里没有的列，会让该写入在任何环境都失败（真发生过，且让一个接口从未成功过一次）。
4. 存量库改表走 `docker/mysql/migrations/`，而该目录**没有自动执行者**；声称"脚本已提交＝迁移已完成"按违规处理。

质量门禁是**刻意的渐进策略**：SpotBugs / Checkstyle / JaCoCo 只出报告不阻断构建（`pom.xml` 里有注释说明）。所以 `BUILD SUCCESS` 不含质量语义——别把它当验收。

## 六、这个仓库的两条特有纪律

- **自述产物不是交付证据。** `final-summary.md`、`progress-report-*.md`、提交信息里的"✅ 已完成 / 全绿"都不算证据——本仓库抓过多处与代码、提交不符的实例（`spec/README.md` 遗留 #13 记了三类）。可信的只有三样：**代码归属于哪笔提交**、**可复算的用例计数**、**在已提交状态上跑出的门禁输出**。
- **验收记录三要素**（`spec/README.md` 工作流第 8 条）：任何"通过/全绿"的结论必须同时写下**命令 + 该次真实输出 + 当时的短 revision**；缺任一项即视为未验收。比较跨阶段结果用判据（不减少、退出码为 0），不用裸数字。
- 补测试要做**变异验证**：注入违规 → 确认变红 → 撤销 → 确认变绿。跑不出红的判据是装饰性的。

## 七、别做的事（指针，正文以被指向处为准）

- 不要为了"架构优化"重写单体：不建议清单见 `docs/指导Agent交接文档.md` 第 26–34 行（拆微服务、二级缓存、为整洁拆 God class、引入 ShedLock/Quartz/Redisson、给 DLQ 加 TTL、在线 `OPTIMIZE TABLE`、为点亮告警降阈值）。
- dev 库里那张历史遗留表**不要删**（交接文档第 193 行）。
- 启动期"答案补发对账"在真 broker 下抛的那个异常**不要顺手修**（非致命，属遗留项；交接文档第 236 行 + `spec/README.md` 遗留 #10）。
- 未收口的三件事别当成已完成：交卷链路**压测从未做**（遗留 #1）、死信队列**真 broker 往返未验证**（遗留 #6）、**MySQL 8 空库真机初始化未实测**（遗留 #9）。
- 工作树约定：**同一工作树不得并行跑两个子 agent**；阶段 19–23 的串行前置已被破坏过一次（阶段 23 越过未做的阶段 22 先行入库），处置与现状见 `spec/README.md` 遗留 #13。

## 八、截至 2026-09-21 的一句话快照（带时点，不作为真源）

前端阶段 19–23 中：19/20/23 代码已入库、21 的五项缺口刚补齐、22 拆成三片追补中（第 1 片已入库）——**五个阶段没有一个做过验收**，`tasks.json` 全部未回勾。
后端阶段 1–18 已归档收口，工程性变更 E1（`add-agent-context-routing`）已归档，E2（`update-agent-gate-single-source`）B-1 与其 B-2 的任务 6、7 已落地，剩 Maven wrapper 与构建配置去机器绑定一段未做。
当前状态请回读 `spec/README.md`——这行快照明天就可能错。

## 九、接手后的前三步

1. 读 `AGENTS.md` → `spec/README.md`（当前状态 + 遗留清单）→ `docs/指导Agent交接文档.md`（环境、禁忌、假阳性）。
2. 跑一次第四节的门禁，**把命令、真实输出、当时短 sha 记进你当次变更的 `tasks.json` 证据字段**——这就是你的基线，不是任何文档里的数字。
3. 领一个遗留项或开放项，按 `spec/README.md` 的"一个阶段 = 一个变更"立项（`spec/changes/<change-id>/{proposal.md,tasks.json,specs/<能力域>/spec-delta.md}`）。
