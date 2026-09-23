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

  > **2026-09-21 实况重同步**（下表的状态列由指导 agent 逐条复算：`git ls-files` 页面归属、`git grep` 调用点、`it(`/`test(` 用例计数、五个 `tasks.json` 的 `completed`/`passes` 真值；不采信任何自述产物）：
  > ① **串行纪律已被打破**——阶段 23 在阶段 22 零交付的前提下先行入库（`39c7fbf` 11:52 → `ddaabdb` 12:18，间隔 26 分钟）；
  > ② 19–23 的 22 个 task / 83 个 step 的 `completed` 与 `passes` **全为 false**，故按"验收后才登记合入"的字面规则，**19/20/21/23 均未正式合入**——本表的"进行中"应读作"已提交未验收"；22 此前连提交都没有，现入库第 1 片（同样未验收），第 2、3 片待做；
  > ③ 全量实测口径：`frontend/` 下 16 个 vitest 文件 **126 用例**（阶段 19=36、20=47、23=43、21=0、22=0）+ 1 个 playwright 文件 3 用例。

| 变更 ID | 阶段 | 内容 | 目标能力域 | 前置 |
|---|---|---|---|---|
| `add-frontend-skeleton-auth` | 19 | 前端方向①：`frontend/` 工程骨架（Vue3.5+TS+Vite7+AntD4+Tailwind4+文件路由+vue-query+pnpm）、生成式 API 层、令牌续期单飞、角色路由守卫、认证四页、playwright 冒烟。**代码已入库**（`ecf6f5d`→`3f81b2a`→`a6e487f`）：36 个 vitest 用例（apiClient 14 / sessionRefresh 6 / access 16）+ 3 个 playwright 冒烟用例。**但验收从未在"已提交状态"上发生**——`agent-prompt-round2.md` 要求补的那笔 commit 未产生，返修遗留一份不可达 stash；`tasks.json` 4 任务 / 16 step 全未回勾（判据与后果见遗留 #13）。**原「强制改密前置」Requirement 已移出**，待后端契约就绪后由独立前端小变更接回（遗留 #11） | frontend（新） | 18（已合入） |
| `add-frontend-teacher-authoring` | 20 | 前端方向②：题库列表与题型驱动编辑表单、标签管理、手动组卷与标签随机抽题、试卷预览。**代码已入库**（`c18c339`→`63194d2`→`060b012`→`8a47644`）：47 个用例（questionTypeConfig 20 / paperMath 11 / useRandomDraw 7 / questionFilters 5 / drawRules 4）。注意一处渲染缺陷潜伏到阶段 23 真机联调才由 `4861847` 修掉（列定义只写 `key` 不写 `dataIndex`）；`tasks.json` 3 任务 / 12 step 全未回勾 | frontend | 19 |
| `update-agent-gate-single-source` | 工程性 E2（**不在 19–23 业务串行链内**） | 门禁命令与验收判据的单一来源。**B-1 已完成**：6 份进行中前端提示词里的"必须仍是 210"常量与"一律读作 218"式人肉更正段全部清除，改为编号判据（开工记录 + 收尾比较）；`docs/指导Agent交接文档.md` 首屏常量降级为带 revision 的历史记录，§6.1 定性为特定 shell 的历史绕行办法、§6.2 加互斥注记。**B-2 部分完成**：任务 6 已落地——`type-check` / `type-check:check` 的单个 `&` 改 `&&`、删除无任何调用者的 `precommit:check`，并因此暴露且修掉两处既存类型红（见遗留 #13）。**仍未开始**：Maven wrapper、`.mvn`/`maven-settings.json` 去机器绝对路径、唯一门禁命令回填——需联网与磁盘授权，且排在业务阶段收尾之后 | `agent-harness` | 与 `add-frontend-*` 无文件冲突（B-1 只改提示词与文档）；`add-agent-context-routing`（E1）已归档 |
| `tune-submit-capacity` | 后端独立立项（P2） | 交卷链路容量调整与压测复验（遗留 #1 收口载体，`docs/submit-loadtest-report.md` §8.3 判据 **G1/G2/G4/G5** 的执行立项）：G1 容量方案有数字地裁决（均值语义 vs 提高线程上限）、G2 `SERVER_TOMCAT_THREADS_MAX` relaxed binding 注入并给新瓶颈位、G4 压测方法学修订（≥3 轮中位数/预热/TIME_WAIT 排空）、G5 Hikari pending/active 持续采样，复验 P99 < 2s 按原指标定义判定（不达标如实登记）。预期零业务代码改动 | `performance` | `add-submit-observability`（G3）先行或同批——否则达标判定退回客户端侧弱证据口径 |
| `add-submit-observability` | 后端独立立项（P2） | 交卷链路观测补齐（判据 **G3**）：暴露 Tomcat 线程 busy/max 指标（当前 MBean registry 未开）、`exam_submit_duration_seconds` 补直方图桶（服务端 P99 可计算，不再依赖 JMeter 客户端侧）；含防回退测试与基数有界判据。**是 `tune-submit-capacity` 的前置** | `observability` | 无 |
| `audit-log-off-critical-path` | 后端独立立项（P2，**门控提案**） | 审计日志移出请求关键路径（判据 **G6**）：先评估（audit_log 同步写占关键路径的时延数字 + 失败语义盘点），裁决=移出才实施异步化（**审计不丢**：失败补偿 + 变异可红的测试）；保留策略部分与 `add-data-retention` 的 data-access 基线**查重引用、不重复定义**。评估结论=不移出则如实关闭 | `performance`（data-access 引用） | 无 |
| `add-makeup-final-score-frontend` | 前端独立立项（P3） | 补考最终成绩前端展示（提案⑨后端已接线、前端滞后）：后端 `ScoreController` 两条 makeup-final 端点与 `openapi.yaml` 均已存在，前端生成层零命中、makeups 页仍挂「后端尚未接线」诚实边界——gen:api 重生成（无漂移核对）、教师侧展示最终成绩（合并规则在后端）、学生侧三态与 `myScore` reviewing 口径同构、诚实边界 Alert 改写。**零后端改动** | `frontend` | 无（接口已在） |
| `accept-frontend-19-23` | 验收类（P2） | 前端阶段 19–23 统一验收：①阶段 19 round-2 空缺补验收记录（四项门禁在已提交状态复跑 + 16 step 回勾，不可达 stash 按「无功能丢失」登记不恢复）；②阶段 20 逐条判定回勾 12 step；③两阶段 spec-delta 合入 frontend 基线（五阶段全量）；④统一真机走查（起 dev 栈错峰，走 19/20/21/22/23 全页面族与已知余项，含切屏脚本四与四个修复弹窗的浏览器侧确认）；⑤缺陷零静默——逻辑/契约级停下回报另立项。**预期零业务代码改动**（演示级小修除外） | `frontend` | 阶段 21/22/23 已归档（成立）；需 dev 栈可用 |

> **工程性变更（E 系列）与验证类变更（如 ⑥ `verify-mysql8-init`）不进入 19–23 的业务串行链**：前者改的是文档与门禁表述、后者只产证据文档，都不碰 `src/main`，与"阶段 19–23 一律不改后端"的纪律一致；
> 但**仍受"同一工作树不得并行跑两个子 agent"约束**，所以它们要么排在当前返修之后单独跑一轮，要么在独立工作树里完成后由指导 agent 合并。
> 上表的行数与 `spec/changes/` 目录数**当前相等**（均为 8，2026-09-23 复核，五份后续提案立项后同步加行：`tune-submit-capacity` / `add-submit-observability` / `audit-log-off-critical-path` / `add-makeup-final-score-frontend` / `accept-frontend-19-23`；`find spec/changes -mindepth 1 -maxdepth 1 -type d -not -name archive`）；若日后再不相等，以 `find` 的计数为准并把漏登的提案补进本表。

**前端系列纪律**：技术栈对齐参考项目 `D:\code\crm\font\crm-front`（已核实其 package.json / vite.config.ts）；代码位于同仓库 `frontend/`；**阶段 19–23 一律不改后端**（`src/main`、`src/test`、`pom.xml` 零改动），发现接口缺口必须停下回报并单独立项，不得在前端拼凑绕过；前端 vitest/playwright 与后端 surefire **计数口径互不并入**，具体数值以当次执行记录为准（命令 + 该次原始输出 + 短 revision 写进该阶段 `tasks.json` 证据字段；验收按判据，不按常量——见 `update-agent-gate-single-source` 与 `AGENTS.md`）。

**`frontend` 能力域已建基线**：`spec/specs/frontend/spec.md` 由首个收尾的前端变更 `add-frontend-must-change-guard`（提案④，2026-09-21）创建；`add-frontend-student-taking`（阶段 22，2026-09-23）在「强制改密前端守卫」之外追加 6 个 Requirement（作答界面/服务端时间倒计时/草稿保存与断线恢复/交卷防重配合/切屏检测与上报/交卷结果如实呈现）；`add-frontend-exam-admin`（阶段 21，2026-09-23）追加 3 个 Requirement（班级管理界面/考试创建与发布界面/监考与行为日志界面）；`add-frontend-post-exam`（阶段 23，2026-09-23）追加 4 个 Requirement（主观题批改工作台/成绩发布撤回与导出界面/缺考与补考界面/成绩查询与复核界面）；阶段 19、20 的 spec-delta 待其各自验收收尾后按 Requirement 标题逐个追加。

- 已合入规范（`spec/specs/`，**能力域数以 `find spec/specs -mindepth 1 -maxdepth 1 -type d | wc -l` 为准，本文件不写死计数**；下表按合入顺序列出，若与目录不一致即为下表漏登）：

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
| 17 | `agent-harness` | `add-agent-context-routing`（工程性 E1，2026-09-21 归档） | 工程性 |
| 18 | `frontend` | `add-frontend-must-change-guard`（提案④，2026-09-21 归档）、`add-frontend-student-taking`（阶段 22，2026-09-23 归档）、`add-frontend-exam-admin`（阶段 21，2026-09-23 归档）、`add-frontend-post-exam`（阶段 23，2026-09-23 归档） | 前端系列 |
| 19 | `dev-config` | `parameterize-dev-credentials`（提案⑫，2026-09-21 归档） | 工程性 |

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
| `add-frontend-must-change-guard` | 提案④ | 强制改密前端守卫（遗留 #11 收口）：`decideNavigation` 单一入口接 `mustChangePassword`（`/api/auth/me` 恒有值字段，缺省按 false 不误拦），未改密仅放行改密与登出（`/login` 也重定向改密页），改密成功走既有会话刷新自动放开；不本地持久化标记、不改鉴权拦截器；access.spec.ts +5 用例；创建 frontend 能力域基线 | 2026-09-21 |
| `fix-contract-export-charset` | 提案⑧ | 契约导出编码修复（遗留 #12 收口）：`getContentAsByteArray()` 按字节落盘 + Mock 请求固定 8080 端口；护栏断言（无 U+FFFD / 中文描述完整 / servers.url 带端口）；重导 openapi.yaml（+79/-0 仅陈旧契约补齐）与真 dev 实例路 B 产物 SHA256 字节级一致，**路 A 恢复为推荐路径** | 2026-09-21 |
| `parameterize-dev-credentials` | 提案⑫ | docker-compose.yml 六处裸字面量凭据改 `${ENV:-default}`（默认值一字未改，开箱行为不变）；`application*.yml` 立项复核已达标记为证据基线；新建 `dev-config` 能力域（自查命令已做变异验证） | 2026-09-21 |
| `audit-concurrency-test-coverage` | 提案⑬ | 三处并发边界「分支×测试名」覆盖盘点（coverage-mapping.md 入库）+ 5 个确定性测试：CacheMutexLoader 败者等待超时兜底直源（轮数驱动）、RedisLockHelper 按 token 解锁三场景（易主由改写 Redis 值模拟）、交卷锁按 token 装配解锁；边界二（状态机 CAS 0 行）既有覆盖如实收窄不重复补；**只补测试，实现零改动** | 2026-09-21 |
| `add-makeup-final-score` | 提案⑨ | 补考最终成绩接口接线（遗留 #5 收口）：教师端 `GET /api/exams/{examId}/scores/makeup-final/{studentId}`（`exam:manage` + 归属校验）、学生端 `GET /api/scores/makeup-final`（仅本人 + 主考须已发布 + 复核中隐藏）；`MakeupScoreService.finalScore` 获真实调用者；2 个真实链路集成用例（takeAverage 合并 / 历史保留 / 越权 403 / 未发布 400 / 复核隐藏）；契约重导走路 A（纯增量 67 行）；absence-makeup 基线补两场景；前端展示位另立前端小变更（未立项） | 2026-09-22 |
| `fix-broker-confirm-and-dlq-roundtrip` | 提案⑦ | 发布确认作用域修复 + DLQ 真往返（遗留 #6/#10 收口）：「发送 + waitForConfirmsOrDie」整体移入 `RabbitTemplate.invoke()` 作用域；`PublisherConfirmScopeGuardTest` 词法护栏（含失靶与 fire-and-forget 退化保护，变异验证过）；真 dev 实例验证补发对账真实完成（待补=1 已补=1、`answers_missing` 1→0）与 DLQ 闭环（entered 0→1→2、replayed=1、留档），全程真 broker 无 mock，证据含时间戳（`docs/broker-confirm-dlq-roundtrip-evidence.md`）；+7 测试；6 个 mock 集成测试经 `RabbitTemplateInvokeStubs` 适配（既有断言零改动）；reliability 基线新增「发布确认作用域与死信真往返」Requirement | 2026-09-22 |
| `add-submit-loadtest` | 提案⑤ | 交卷链路 5000 并发压测（遗留 #1 压测缺失部分收口）：`loadtest/` 可复现资产（.jmx/造数/复位/比对脚本）+ 四轮真跑报告（`docs/submit-loadtest-report.md`，三要素齐全）；**硬指标如实判定：0 丢单与批量落库 < 30s 达标，提交 P99 四轮 2088–3700ms 未达标**，根因定位为 Tomcat 线程上限（Spring Boot 默认 200，容量 ≈455 req/s < 需求 ≈490 req/s），JIT 预热效应（冷热差 68%）被 A-B-A 对照证伪归因；报告给出后续判据 G1–G6（容量调整/观测补齐/audit_log 关键路径）；exam-taking 基线「交卷落库容量与时延」补真跑场景（如实判定措辞，非达标措辞）；src/main、src/test、pom.xml 零改动 | 2026-09-22 |
| `verify-mysql8-init` | 提案⑥ | MySQL 8 空库初始化与存量迁移真机验证（遗留 #9 收口）：一次性 mysql:8.0.46 容器上 `schema.sql` 零报错建出全部业务表（information_schema 现场计数，自增列与主键双向集合差为空）；存量迁移真跑首跑 25 条 ALTER 全部成功、重复执行的 1068/1146 与脚本头注逐条一致、业务数据 checksum 不变；**方法学修正**：「自增列 + 完全无索引」在 MySQL 8 上是不可存在状态（ERROR 1075 实测），旧库按「自增列挂二级索引、缺主键」构造，避免测试空转；**零缺陷**（schema.sql、迁移脚本、测试零改动），仅新增证据文档（`docs/mysql8-init-verification.md`，三要素齐全，开工/收尾门禁 290/0/0/1）；两项操作级发现（迁移重复执行须带 `--force`、冗余二级索引不被自动清除）已如实登记 | 2026-09-22 |
| `add-frontend-student-taking` | 阶段 22（前端方向④） | 学生端在线考试，**三片串行追补 + 一轮返修后全量收口**：作答界面（个人快照、题型控件沿用阶段 20 口径）、服务端时间倒计时与归零锁定（单调秒表、`deadlineTime − Date.now()` 零命中）、30s 自动保存 + IndexedDB 断线恢复 + 保守合并（防抖让路定时拍、jsdom 无 IndexedDB 用可注入存储端口分层）、交卷防重（`useSubmitExam` 唯一入口 + 在途守卫、手动/归零同路径、失败保留可重试）、切屏检测只警告（`reduceBehaviorSignal` 归并纯函数一次离开一条、**时长返修**：离开只暂存单调时钟、回归报一条 `eventData={durationMs}`、三路兜底 flush `{incomplete:true}` 不谎报、失败还原暂存）、结果卡如实「待批改」不猜分。真机联调全链路走通（enter→draft×2→behavior×2→submit×2 同一 submissionId→`answers_missing=0`→教师端日志→越权 403）。**门禁**（指导 agent 独立复算）：lint/type-check exit=0、vitest 35 文件 324 例、后端 290/0/0/1、后端零改动。四条演示脚本两条实跑、断网续答/归零锁定如实标注部分/未实跑；**返修后口径的切屏真机走查待补**（需起 dev 后端，脚本四）。frontend 基线合入 6 个新 Requirement | 2026-09-23 |
| `add-frontend-exam-admin` | 阶段 21（前端方向③） | 教师端考务，**补齐 + 验收后收口**：班级 CRUD 与学生入班/转班（`useClassRoster` 状态收敛工厂 + 生成式 `transfer` 真实调用者）、考试创建（全字段对齐 `ExamCreateRequest`，时间字符串带字面量 T）、列表状态/动作按后端返回渲染不推算状态机、发布/force-end 二次确认（**验收时由新增用例暴露并修复**：两处确认弹窗正文写在 antd4 `Alert` 默认插槽被静默丢弃 → 改走 `#message` 具名插槽，spec「知情确认」场景从此真实可见）、考试详情五 Tab（快照预览/考生名单与进度/监考/行为日志）、Grafana 只读入口（`VITE_GRAFANA_BASE_URL` 未配置降级、不重做图表）、监考 10s 轮询只声称「准实时」。**验收（指导 agent 独立复算，2026-09-23）**：`pnpm lint:check`/`type-check:check` exit=0、vitest **36 文件 329 例**（含验收补的 `examCreatePublishFlow.spec.ts` 5 例——创建→发布关键流程此前零用例，为任务 #4 判据缺口）、后端 `mvn -o clean test` 290/0/0/1（813da6d，全阶段零后端改动）；两份子 agent 自述产物已加失实注记（遗留 #13 子项）；`monitor.ts`/`severity.ts` 死代码已随补齐转为被引用。**余项**：阶段 21 页面族无真机走查（监考/行为时间线/Grafana 入口/两个确认弹窗的浏览器侧演示，dev 后端已停未走）——不阻塞收口，待真机演示时一并走。frontend 基线合入 3 个新 Requirement | 2026-09-23 |
| `add-frontend-post-exam` | 阶段 23（前端方向⑤） | 考后闭环，**复核验收后收口**：批改工作台（考试选择→主观题进度→逐行打分，乐观锁冲突只提示并拉最新行**绝不自动重试**——`useGradingFlow` 8 用例守住 1012/409 分支）、成绩汇总/发布前预览/批量发布/撤回（动作入口一律走 `scoreActions` fail-closed 映射，撤回仅 ADMIN）/流式导出（后端生成 xlsx/pdf、前端只触发与 blob 下载）、缺考名单（不编造标记来源）与补考创建（候选由后端按及格线判定；**不越界**：`MakeupScoreService.finalScore` 后端零调用，页面 Alert 明示不做合并展示、须后端立项）、学生成绩三态可见（`mapMyScoreToView` 只看后端 `reviewing`）与复核申请（不本地计数资格）。**复核（指导 agent 独立复算，2026-09-23）**：阶段 21 验收全仓审计出的两处 Alert 默认插槽残留（发布确认/复核申请弹窗正文不可见）已修复走 `#message` 具名插槽，补 `publishConfirmAlert` / `applyReviewAlert` 2 例做红绿取证（变异验证：改回默认插槽→该用例红）；`pnpm lint:check`/`type-check:check` exit=0、vitest **38 文件 331 例**、后端零改动（阶段提交全在 `frontend/` 与 `docs/`）。真机演示脚本 `post-exam-demo.md` 此前已实跑（含两次真实 409+1012 并发冲突）；「个人成绩单导出 PDF」是后端导出格式参数、非前端拼装报告，不违反「未夹带」判据。**余项**：与阶段 21/22 相同，无本轮新增。frontend 基线合入 4 个新 Requirement | 2026-09-23 |

## 遗留事项（已归档但未收口，勿当成已完成）

1. **交卷链路压测已做、P99 未达标**（2026-09-22 由 `add-submit-loadtest` 部分收口）——`loadtest/` 可复现资产与四轮真跑报告已入库（`docs/submit-loadtest-report.md`），**0 丢单与批量落库 < 30s 达标**；**提交 P99 四轮 2088–3700ms 未达标**，根因定位为 Tomcat 线程上限（Spring Boot 默认 200 未配置，容量 ≈455 req/s < 场景需求 ≈490 req/s），改并发运行参数超出该变更授权，已转为报告判据 G1–G6 待容量提案处置。**不得声称「交卷链路压测达标」**。
7. **磁盘空间回收不在任何提案范围内**（阶段 15 取证时发现）——MySQL InnoDB 的 `DELETE` 只把页标记为可复用，**文件大小不会变小**；真正回收需 `OPTIMIZE TABLE` 或 `ALTER TABLE ... ENGINE=InnoDB`（离线重写整表、期间锁表），在在线考试系统上属高风险窗口操作。`add-data-retention`（阶段 15）的目标是**控制行数与查询代价**（避免全表扫描与索引膨胀），**不是腾磁盘**。**不得声称"清理后磁盘释放"。**
8. **`exams` 表没有 `ended_time` 列**（阶段 15 取证时发现）——实际结束时刻无字段记录，`updated_time` 会被任意更新刷新（表达的不是结束时刻）。`add-data-retention` 因此改用 `end_time`（时间窗终点）作为"考试已终结"的代理，误差方向是**晚删而非早删**（`force-end` 提前结束的考试其 `end_time` 仍在未来），属安全选择。若要精确化需新增列（= 迁移），当前不值得。
9. **（已收口，移入下方「已收口」清单第 11 条）****已立项 `verify-mysql8-init`**（验证类独立变更，见进行中表；三件套 + `agent-prompt.md` 齐备，待派工，产物 `docs/mysql8-init-verification.md`）。**注**：本条的「25 张表」与迁移脚本头注里的同款数字是**过期副本**——2026-09-22 19:0x 现场计数：`src/main/resources/schema.sql` 有 **27** 个 `CREATE TABLE IF NOT EXISTS`、**26** 个 `AUTO_INCREMENT`、**26** 个 `PRIMARY KEY (id)`（1 张表天然无自增列）；验证时以现场 `SHOW TABLES` 为准，**不得为了对上「25」去改文档或加表**。
13. **前端 19–23 从未按"验收"登记，且阶段 22 被跳过**（2026-09-21 实况重同步时发现，四个开放子项 + 一条纪律）——

    - **阶段 22 零交付而阶段 23 已入库**：22 的 6 个 task / 24 step 全 false、`/student/` 下只有 `scores/`、`indexedDB` 与 `visibilitychange` 全仓 0 命中；23 却在 proposal 里声明"前置阶段 22 必须已合入"并已提交。**需显式裁决**：追补 22（面试主战场，倒计时/自动保存/断线恢复/交卷防重/切屏检测都在这块）作为 23 的返修前置，还是接受乱序并把 23 的前置声明改写为"接口级验证"并标注缺口。**（已收口：追补路线于 2026-09-23 执行完毕——阶段 22 三片全部入库、切屏时长返修补齐、24/24 step 验收回勾、变更已归档、真机联调全链路走通；23 的前置声明自本日起成立。余真机走查项：返修后口径的切屏演示（脚本四），登记于归档表该行，不阻塞收口。本子项关闭。）**
    - **阶段 21  incomplete**（2026-09-21 代码与单测已补齐，见进行中表该行的复核结论；**本条仍是开放项，因为验收未做**）：五项无代码（学生入班/转班、考试详情、监考视图、行为日志时间线、Grafana 只读入口）、**0 测试文件**（与其 `tasks.json` 任务 #4 直接冲突）、`src/constants/monitor.ts` 与 `severity.ts` 为零引用死代码。要么补齐，要么显式缩小其 proposal/tasks 范围并删掉死代码——**不得留成"看起来做过"**。**（已收口：2026-09-23 验收完成——五项缺口由 `33860ad` 补齐并逐条核验（`transfer`/`overview`/grafana 均有真实调用者）、验收补 `examCreatePublishFlow.spec.ts` 5 例使任务 #4 的「建考试→发布用例覆盖」判据成立、`monitor.ts`/`severity.ts` 已被 9 处引用不再是死代码、12/12 step 回勾、变更归档；验收中另暴露并修复两处确认弹窗 Alert 默认插槽正文不可见缺陷（见归档表该行）；余项为阶段 21 页面族真机走查，不阻塞。本子项关闭。）**
    - **阶段 19 的 round-2 验收从未发生**：`agent-prompt-round2.md` 要求补的第 4 笔 commit 在任何分支都不存在，返修内容只留在一份不可达 stash；逐文件剥离空白与逗号差异后与 HEAD 仅差 prettier 格式，**无功能丢失**，但"在已提交状态上重跑四项门禁"这一步的记录是空的。
    - **两份自述产物含失实声明**：`add-frontend-exam-admin/final-summary.md` 与 `progress-report-1.md` 的"✅ 班级管理与学生入班转班""界面标注每 10s 刷新""type-check:check 通过（0 errors）"三处与代码/提交不符（第三处可 `git show 39c7fbf` 复核：其正文记录 `2789f4b` 的三页有 19 处 vue-tsc 错误），`2789f4b` 的提交信息还重复了其中"入班/转班"一处。按本仓惯例**不改写已提交历史**，但这两份文件须加失实注记，且不得据它们判断交付状态。**（已收口：2026-09-23 阶段 21 验收时两份文件均已加失实声明注记（正文原样保留），验收结论以归档表该行与验收提交为准。本子项关闭。）**
    - **阶段 23 入库时前端门禁其实是红的**（2026-09-21 因 E2-B2 的 `&`→`&&` 修复才暴露）：旧 `type-check:check` 用单个后台符 `&` 串接两个子检查，app 侧 `vue-tsc` 的退出码被丢弃，于是 `type-check:app:check` 报 6 处 `TS18048`（`ScorePublishPreview.spec.ts`，由 `21c9482` 引入）而 `type-check:check` 仍返回 0；config 侧另有 `vitest.config.ts` 在 `test` 块内重复 `plugins` 导致 1 处 `TS2769`。**修前证据**：`npm run type-check:app:check` → exit=2 / 6 errors，`npm run type-check:config:check` → exit=1 类错误，`npm run type-check:check` → exit=2（因 config 侧本来就红，说明这道门禁在改前也不是"绿"，只是吞掉了 app 侧）。**修后**：`npm run type-check:check` → exit=0、`vitest run` → 16 文件全绿；变异验证 `type-check:check` → exit=2（TS2322）→ 撤除后 exit=0。含义：**阶段 23 的"门禁全绿"从未在可判定的意义上成立过**，其交付状态应据此复核。**（已收口：2026-09-23 阶段 23 复核验收时独立重跑门禁——`pnpm lint:check`/`type-check:check` exit=0、vitest 38 文件 331 例全绿——"门禁在已提交状态全绿"自此在可判定意义上成立；历史红仅存在于旧串接符吞码时期，且当时引入的 6 处 TS18048 已由 E2-B2 修复并变异验证过。复核同时发现并修复两处 Alert 默认插槽正文不可见缺陷（见归档表该行）。本子项关闭，余下仅阶段 19 round-2 验收空缺一个开放子项。）**
    - **纪律（与仓库根 `AGENTS.md` 同源）**：自述产物（`final-summary.md` / `progress-report-*.md` / 提交信息）**不是**交付证据。可采信的只有三样——代码归属于哪笔提交、可复算的用例计数、以及**在已提交状态上**跑出的门禁输出。本条与上表的状态列即按此口径重写；上面那条"门禁其实是红的"正是违反本纪律的实例——它能一直"绿"，只是因为串接符写错。

14. **`DataRetentionIntegrationTest` 存在偶发失败**（2026-09-23 阶段 22 第 3 片开工基线首跑暴露）——`purgeRemovesOnlyTerminalOldExams` expected 4 was 8，单类复跑 6/6 绿、同命令全量复跑 290/0/0/1 绿。子 agent 疑点指向共享 H2（`mem:exam;DB_CLOSE_DELAY=-1`）下 `exam` 表跨测试类不清理导致的测试间干扰，机制未定位（返修片禁改后端，未排查）。全量门禁当前可复现为绿，**不阻塞验收**；在它再次变红前不值得动，再变红时按「先单类复跑定性、再查跨类污染」处理。

**已收口（从遗留清单移出）**：

1. **限流器对 Redis 异常的兜底** 已由 `add-rate-limit-resilience`（阶段 10）实现——默认 fail-open 放行以保核心链路可用，同时打 ERROR 日志并递增 `exam.ratelimit.degraded` 计数（可按接口维度区分）；另留 `exam.ratelimit.fail-open=false` 切回 fail-close。
2. **有指标无告警、无面板** 已由 `add-alerting-and-dashboards`（阶段 11）实现——7 条告警规则（含 `RateLimitDegraded`，使 `exam.ratelimit.degraded` 从「埋了没人看」变为「有告警值守」）+ Grafana 数据源与总览面板；规则只使用能从 `BusinessMetrics` 常量确定性推导的指标名，刻意不写 `hikaricp_connections_*`（dynamic-datasource 下未实测，写错会让规则因 `no data` 永久静默）。其**动态验证**已由阶段 16 收口（见下条）。

3. **考后闭环缺端到端串联验收** 已由 `add-post-exam-closure-e2e`（阶段 12）收口——曾是真问题：阶段 9 各环节有独立测试，但没有「建班→结束→缺考→补考→批改发布→复核」整链；且 `force-end` 曾漏标缺考（状态已 ENDED 后定时扫描无法自愈）。现由 `PostExamClosureIntegrationTest` 9 条用例覆盖，两条结束路径均 `markAbsence`。
4. **缺考/补考真实链路仅 Mockito、以及 `@Sql` 掩盖缺表 / `score_review` 缺列** 已由阶段 12 收口——曾是真问题：`ClassManagementIntegrationTest` 的 `@Sql` 自建表掩盖过缺表回归；`score_review` 缺 `created_time` 曾使复核申请 INSERT 在任何环境必失败。现 `src/test` 无 `@Sql`，schema/migration 已补列，`listByExam` 同步改为 `@PathVariable`。
5. **观测栈动态行为** 已由 `add-observability-runtime-evidence`（阶段 16）收口——Targets `UP`、9 条规则 loaded、**5 条真实 firing**（ExamOnlineDown / RateLimitDegraded / MqSubmitRetryExhausted / MqDlqBacklog / AntiCheatEventSpike）、面板出图；另 **4 条流量/性能阈值未在本机点着**（Http5xxRatioHigh / SubmitFailureRatioHigh / MqSubmitQueueBacklog / SubmitLatencyP99High）且**未改规则凑绿**，已留 PromQL 反证。详见 `docs/observability-runtime-evidence.md`。**不得据此声称遗留 #6（DLQ 真 broker 端到端）已完成**（firing ≠ 重投闭环）。
6. **初始密码强制修改（前端守卫）** 已由 `add-frontend-must-change-guard`（提案④，2026-09-21）收口——`decideNavigation` 单一入口接 `mustChangePassword`（`/api/auth/me` 恒有值字段，缺省按 false 不误拦），未改密仅放行改密与登出（含 `/login` 重定向改密页），改密成功走既有会话刷新自动放开；不本地持久化标记、不改鉴权拦截器。原遗留 #11 的后端部分早由 `add-auth-must-change-password`（18+）完成，本条收口后「初始密码强制修改」用户可感知能力端到端成立。
7. **契约导出路 A 编码缺陷** 已由 `fix-contract-export-charset`（提案⑧，2026-09-21）收口——根因：`getContentAsString()` 未设 charset 按 ISO-8859-1 解码 + Mock 请求无端口致 `servers.url` 退化；修法：`getContentAsByteArray()` 按字节落盘 + 固定 8080 端口，护栏断言（无 U+FFFD、中文描述完整、servers.url 带端口）随导出测试入库；修复后路 A 与真 dev 实例路 B 产物 SHA256 字节级一致，**路 A 恢复为推荐路径**（表述已同步 api-contract 基线、交接文档与提示词）。原遗留 #12 关闭。
8. **补考成绩规则接线** 已由 `add-makeup-final-score`（提案⑨，2026-09-22）收口——`MakeupScoreService.finalScore` 自阶段 12 验收以来全仓库零调用，现获接口层真实调用者：教师端 `GET /api/exams/{examId}/scores/makeup-final/{studentId}`（`exam:manage` + 归属校验），学生端 `GET /api/scores/makeup-final?examId=`（仅本人 + 主考须已发布 + 进行中复核隐藏分数）；2 个真实链路集成用例断言 takeAverage 合并、历史成绩保留不覆盖、越权 403、未发布 400、复核隐藏；契约重导走路 A（纯增量）。absence-makeup 基线补「最终成绩经接口可查」「合并规则有真实调用者」两场景。**前端展示位（阶段 23 边界 Alert 的替换）另立前端小变更，尚未立项**。原遗留 #5 关闭。
9. **启动期「答案补发对账」在真 broker 下抛异常** 已由 `fix-broker-confirm-and-dlq-roundtrip`（提案⑦，2026-09-22）收口——根因：`ExamSubmitSender.send` 在 `RabbitTemplate.invoke()` 作用域**外**调 `waitForConfirmsOrDie`，真 broker 下抛 `IllegalStateException`，启动对账实际未补发成功。修法：「发送 + confirm 等待」整体移入 `invoke()` 作用域；`PublisherConfirmScopeGuardTest` 对 `src/main/java` 做词法扫描守住「confirm 调用必须在 invoke 区间内」（无真 broker 的 CI 也可检出，变异验证 2/2 如期变红）。真 dev 实例验证：补发对账 `待补=1 已补=1` 无异常、消息经真实 RabbitMQ 送达并被消费端真实落库（`answers_missing` 1→0）。详见 `docs/broker-confirm-dlq-roundtrip-evidence.md`。reliability 基线合入「发布确认作用域与死信真往返」Requirement（含「mock 证据不得冒充实测」场景）。原遗留 #10 关闭。
10. **死信队列的「真 broker 往返」未验证** 已由 `fix-broker-confirm-and-dlq-roundtrip`（提案⑦，2026-09-22）收口——真 dev 实例下完成「真发必死消息 → 真进 DLQ → 经 `DlqReplayService` 真重投」闭环：坏消息 `parse` 抛异常 → 重试 3 次 → `exam_mq_dlq_entered` 0→1、`exam.submit.dead.queue` 深度 +1 → ADMIN 端点重投返回 `replayed=1`、留档 `exam_dlq_messages` 新增 → 重投消息再走真实链路再进 DLQ（`entered` 1→2）。全程真 broker 无 mock。详见 `docs/broker-confirm-dlq-roundtrip-evidence.md`。reliability 基线合入「发布确认作用域与死信真往返」Requirement。原遗留 #6 关闭。
11. **MySQL 8 空库真机初始化未实测** 已由 `verify-mysql8-init`（提案⑥，2026-09-22）收口——一次性 mysql:8.0.46 容器上 `schema.sql` 零报错建出全部业务表（26 个自增列全部被主键覆盖，information_schema 现场计数、双向集合差为空）；`2026-W16-add-primary-keys.sql` 在按迁移前形态构造的真实存量库上首跑全部成功、重复执行的 1068/1146 与头注逐条一致且业务数据 checksum 不变；**零缺陷**，证据见 `docs/mysql8-init-verification.md`（三要素齐全，开工/收尾门禁 290/0/0/1）。两条操作级发现如实登记：迁移脚本重复执行须带 `--force`（否则客户端中断退出）；`ADD PRIMARY KEY` 不清除 `id` 上原有二级索引（真实旧库无此索引，不构成生产问题）。原遗留 #9 关闭。**数字口径**：旧文档里的「25 张表」是过期副本，以现场 `SHOW TABLES` / `information_schema` 计数为准，本条不写死表数。

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
| 18 | frontend | 前端路由守卫（强制改密前置：可导航范围限制、单一判定入口、恒有值字段直判、改密后自动恢复）；学生端在线考试（作答界面/服务端时间倒计时/草稿保存与断线恢复/交卷防重配合/切屏检测与上报/交卷结果如实呈现）；教师端考务（班级管理/考试创建与发布/监考与行为日志）；考后闭环（主观题批改工作台/成绩发布撤回与导出/缺考与补考/成绩查询与复核）。阶段 19、20 各 Requirement 待其验收收尾后追加 |
| 19 | dev-config | dev 环境凭据参数化（环境变量可覆盖、开箱默认不变、新增凭据不裸写、生产凭据不带默认值，含可机械执行的自查命令） |

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

8. **验收记录三要素（不得只写结论）**：凡 step 声称"全绿 / 通过 / 已回归"，其证据字段必须同时含 **产生结论的命令 + 该次真实输出（四计数或退出码）+ 当时的短 revision**；缺任一项即视为未验收，`completed` 不得置 true。**禁止为此另建一份"数字汇总"文件**——那正是本仓库要消灭的多副本失效，数值只存在于产生它的那条证据里。写结论时把"该数值描述的是哪一次执行"说清楚，跨阶段比较用判据（不减少、退出码为 0），不用裸数字。此条由 `update-agent-gate-single-source`（E2）确立，反例见 `da5b579` 记录的"`b0eb18e` 机制与数值都记错过一次、事后不改写历史只以状态文件为准"，正例见 `33860ad` 的提交信息。

## 参考资料（非 openspec 资产）

- `docs/examOnline需求规格说明书.md` — 完整产品愿景
- `docs/需求决策记录.md` — 80+ 项场景决策（开发逐条对照）
- `docs/面试版实施方案.md` — v3 大厂面试级实施方案（开发蓝本）
- `docs/指导Agent交接文档.md` — 交接说明（注意：其中「阶段 8.1/8.2/9 已完成归档」的描述直到 2026-09-14 才真正成立）

