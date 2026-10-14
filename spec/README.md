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

- 进行中变更（`spec/changes/`，**条数以 `find spec/changes -mindepth 1 -maxdepth 1 -type d -not -name archive | wc -l` 为准，本文件不写死计数**；**历史业务阶段 19–23 按阶段顺序串行；其他独立变更按下表「前置」列判断依赖**；**同一工作树不得并行跑两个子 agent**）：

| 变更 ID | 阶段 | 范围 | 目标能力域 | 前置 |
|---|---|---|---|---|
| （无进行中变更） | — | — | — | — |


**前端系列纪律**：技术栈对齐参考项目 `D:\code\crm\font\crm-front`（已核实其 package.json / vite.config.ts）；代码位于同仓库 `frontend/`；**阶段 19–23 一律不改后端**（`src/main`、`src/test`、`pom.xml` 零改动），发现接口缺口必须停下回报并单独立项，不得在前端拼凑绕过；前端 vitest/playwright 与后端 surefire **计数口径互不并入**，具体数值以当次执行记录为准（命令 + 该次原始输出 + 短 revision 写进该阶段 `tasks.json` 证据字段；验收按判据，不按常量——见 `update-agent-gate-single-source` 与 `AGENTS.md`）。

**`frontend` 能力域已建基线**：`spec/specs/frontend/spec.md` 由首个收尾的前端变更 `add-frontend-must-change-guard`（提案④，2026-09-21）创建；`add-frontend-student-taking`（阶段 22，2026-09-23）在「强制改密前端守卫」之外追加 6 个 Requirement（作答界面/服务端时间倒计时/草稿保存与断线恢复/交卷防重配合/切屏检测与上报/交卷结果如实呈现）；`add-frontend-exam-admin`（阶段 21，2026-09-23）追加 3 个 Requirement（班级管理界面/考试创建与发布界面/监考与行为日志界面）；`add-frontend-post-exam`（阶段 23，2026-09-23）追加 4 个 Requirement（主观题批改工作台/成绩发布撤回与导出界面/缺考与补考界面/成绩查询与复核界面）；`add-frontend-skeleton-auth`（阶段 19，2026-09-23）与 `add-frontend-teacher-authoring`（阶段 20，2026-09-23）经 `accept-frontend-19-23` 统一验收后追加 7 个 Requirement（前端工程底座/API 契约驱动集成/会话与令牌生命周期/角色路由守卫/题库管理界面/组卷界面/前端不复现判分口径）——**五阶段 Requirement 全量入基线**，已知缺陷（D1/D2）如实注记在基线合入注记与遗留 #17/#18。

- 已合入规范（`spec/specs/`，**能力域数以 `find spec/specs -mindepth 1 -maxdepth 1 -type d | wc -l` 为准，本文件不写死计数**；下表按合入顺序列出，若与目录不一致即为下表漏登）：

| # | 能力域 | 来源变更 | 阶段 |
|---|---|---|---|
| 1 | `authentication` | `add-project-skeleton`、`add-authentication`、`add-auth-must-change-password` | 1、2、18+ |
| 2 | `question-bank`（覆盖 paper-assembly 范围） | `add-question-bank`、`add-paper-batch-add-questions`（批量加题入卷端点与前端 onPick 批量化，2026-10-02 归档）、`add-question-batch-delete`（题库批量删除端点与前端 onBatchDelete 批量化，2026-10-02 归档）、`add-question-fill-blank`（填空题题型——type=5 单空填空、`|` 分隔多可接受答案与答案归一化，2026-10-13 归档）、`add-question-image`（题干图片——上传/下载、image_url 快照携带与五渲染点，2026-10-13 归档）、`add-question-import-export`（题库Excel批量导入导出——导入/导出/模板三端点与逐行容错，2026-10-14 归档） | 3 |
| 3 | `exam-management` | `add-exam-management`、`add-exam-list-filtering`（考试列表服务端筛选与状态过滤，2026-10-08 归档）、`add-exam-reservation`（考试创建需预约开关与开考后锁定，2026-10-12 归档） | 4、台账 U-3 |
| 4 | `exam-taking` | `add-exam-taking`、`add-mq-trace-and-capacity`、`fix-my-exams-list-scope`（学生考试列表取数范围与排序，2026-09-30 归档）、`enforce-exam-enter-class-affiliation`（进入考试班级归属准入强校验，2026-10-01 归档）、`add-exam-reservation`（考试预约与开考提醒——学生考试列表下发预约状态、开考提醒，2026-10-12 归档） | 5、10、列表口径 |
| 5 | `grading` | `add-grading-score`、`batch-grading-subjective-upserts`（2026-09-25 归档）、`add-subjective-grading-pagination`（主观题批改行分页与筛选下沉，2026-10-02 归档）、`update-grading-subjective-paging`（主观题批改分页护栏补强，2026-10-10 归档）、`add-question-fill-blank`（填空题自动判分策略经策略注册表接入——中文精确 / 英文忽略大小写、命中任一满分、不进主观批改工作台，2026-10-13 归档） | 6 |
| 6 | `score-management` | `add-grading-score`、`fix-grading-entry-and-summary-prerequisite`（前端 D2，2026-09-25 归档）、`batch-summary-subjective-reads`（2026-09-25 归档）、`update-score-ranking-calculation`（成绩排名先归因再 O(n log n) 等价提速，2026-09-26 归档）、`optimize-my-score-rank-fetch`（myScore 名次聚合取数，2026-09-29 归档）、`add-exam-analysis-report`（考试数据分析报告后端只读接口与共享聚合核心，创新点4，2026-10-08 归档）、`add-student-wrong-questions`（学生错题本列表查询与单场考试逐题回顾，创新点3 + §3.3，2026-10-09 归档）、`split-score-query-service`（ScoreService 读侧拆出 ScoreQueryService 读写分置，2026-10-11 归档）、`fix-analysis-report-pass-line`（分析报告及格线按试卷总分比例派生，2026-10-11 归档）、`update-wrong-question-list-skip-rank`（错题本列表读侧装配跳过消费路径未使用的重计算，2026-10-12 归档）、`project-analysis-report-graded-read`（分析报告全场取数仅投影消费列，2026-10-12 归档）、`add-exam-reservation`（分析报告应考口径随预约制分流，2026-10-12 归档）、`update-analysis-option-distribution`（题目选项分布统计——三处同源选项分布，2026-10-14 归档） | 6、前端 D2 |
| 7 | `anti-cheat` | `add-anti-cheat`、`update-monitor-overview-draft-batch-read`（监考总览草稿批读，2026-09-26 归档）、`reattribute-monitor-overview-projection`（监考总览答卷取数列投影 + 题数一次窄读，2026-09-29 归档） | 7 |
| 8 | `performance` | `add-performance-deepening`、`tune-submit-capacity`（交卷容量与压测方法学，2026-09-23 归档）、`gate-java-change-on-measured-factor`（Java 优化先归因门禁，2026-09-25 归档）、`update-question-stats-paging-resolve-attribution`（题目统计导出优化先拆分分页与解析，2026-09-27 归档） | 8 |
| 9 | `data-access` | `add-performance-deepening`、`add-data-retention`、`fix-schema-mysql-pk`、`project-scalar-only-submission-reads`（标量读取列投影，仅 GO 站点 s1/s2，2026-09-29 归档）、`attribute-my-exams-list-index`（学生考试列表答卷渠道索引，2026-09-30 归档）、`project-sweep-candidates-scalar-projection`（兜底扫描候选列投影，2026-10-01 归档）、`add-migration-applied-ledger`（存量库迁移执行台账与核验 + dev 现场盘点与 W40 补应用，2026-10-11 归档） | 8、15、17 |
| 10 | `observability` | `add-performance-deepening`、`add-slow-sql-and-rate-limit`、`add-mq-trace-and-capacity`、`add-alerting-and-dashboards`、`add-dlq-observability-and-replay`、`add-observability-runtime-evidence`、`add-submit-observability`（交卷时延直方图 + Tomcat 线程水位，2026-09-23 归档）、`add-alert-delivery`（告警投递链路——Alertmanager webhook 本地闭环，生产化 P6，2026-10-11 归档） | 8、8.1、10、11、14、16、P6 |
| 11 | `reliability` | `add-slow-sql-and-rate-limit`、`add-rate-limit-resilience`、`add-multi-instance-sweep-safety`、`add-dlq-observability-and-replay`、`fix-flaky-integration-baselines`（测试稳定性，2026-09-26 归档）、`add-backup-restore-drill`（数据库备份与恢复演练——docker/mysql/backup/ 脚本域 + BACKUPS.md 人工台账，生产化 P5，2026-10-12 收口） | 8.1、10、13、14、测试稳定性、生产化 P5 |
| 12 | `data-consistency` | `add-tx-rollback-consistency` | 8.2 |
| 13 | `class-management` | `add-class-and-post-exam-closure` | 9 |
| 14 | `absence-makeup` | `add-class-and-post-exam-closure`、`add-post-exam-closure-e2e`、`add-exam-reservation`（预约制考试应考名单构成，2026-10-12 归档） | 9、12 |
| 15 | `score-review` | `add-class-and-post-exam-closure` | 9 |
| 16 | `api-contract` | `add-backend-openapi` | 18 |
| 17 | `agent-harness` | `add-agent-context-routing`（工程性 E1，2026-09-21 归档）、`update-agent-gate-single-source`（工程性 E2，2026-09-25 归档）、`add-ci-gate-pipeline`（CI 门禁机检化——GitHub Actions 双 job 复跑双端门禁 + gate-baseline.json 三下限机检「只增不减」，生产化 P1，2026-10-11 归档）、`add-dependency-vuln-scan`（依赖漏洞扫描入 CI——dependency-scan 第三 job Trivy 非阻断报告，生产化 P4，2026-10-11 归档）、`add-mutation-check-tool`（变异校验工具化——scripts/mutation-check.sh 固化为可复现留证的五步原子流程，生产化 T2，2026-10-12 已收口，后续由 polish-mutation-and-ci-tooling 打磨判败留证/--out-dir/文档形态）、`polish-mutation-and-ci-tooling`（变异校验留证与失败传播完善——判败亦留证 + --out-dir 逃生口 + Windows Git-Bash 文档形态 + CI 三脚本噪音收敛，打磨分级①，进行中） | 工程性 |
| 18 | `frontend` | `add-frontend-must-change-guard`（提案④，2026-09-21 归档）、`add-frontend-student-taking`（阶段 22，2026-09-23 归档）、`add-frontend-exam-admin`（阶段 21，2026-09-23 归档）、`add-frontend-post-exam`（阶段 23，2026-09-23 归档）、`add-frontend-skeleton-auth`（阶段 19，2026-09-23 归档）、`add-frontend-teacher-authoring`（阶段 20，2026-09-23 归档）、`fix-frontend-paper-table-render`（前端 D1，2026-09-23 归档）、`fix-grading-entry-and-summary-prerequisite`（前端 D2，2026-09-25 归档）、`add-makeup-final-score-frontend`（前端 P3，2026-09-25 归档）、`gate-monitor-polling-by-tab`（监考总览轮询按页签门控，候选 1，2026-10-02 归档）、`narrow-server-countdown-watch`（倒计时监听依赖窄化并移除深层遍历，候选 6，2026-10-02 归档）、`fix-frontend-query-failure-states`（查询失败三态分离——学生成绩/教师复核/缺考/补考四处接线，UX 台账 U-1，2026-10-07 归档）、`add-frontend-exam-edit-delete`（考试编辑与删除入口——操作列乐观门控与创建页编辑模式，前端台账 F-1+F-2，2026-10-07 归档）、`add-frontend-invite-code-admin`（邀请码管理界面——列表/生成/作废最小闭环与 ADMIN 侧边栏入口，前端台账 F-4，2026-10-07 归档）、`fix-frontend-list-truncation-family`（下拉与列表取数不截断——通用逐页累加器 + 三下拉全量候选 + 试卷列表服务端分页，UX 台账 U-2，2026-10-07 归档）、`fix-frontend-makeup-time-and-landing`（补考时间控件规范化与落地页收官，UX 台账 U-4+U-5，2026-10-07 归档）、`add-frontend-submission-rejudge`（判分失败答卷逐卷重判——失败清单行内单卷重判入口，前端台账 F-3，2026-10-08 归档）、`add-frontend-manual-score`（判分失败答卷手动给分——失败清单行内「重判」旁的绕过引擎裁定入口，前端台账 T-1 澄清后立项，2026-10-08 归档）、`add-exam-list-filtering`（考试列表服务端筛选联动——标题 300ms 防抖 + 状态筛选 + 自动重置第 1 页，UX 台账 U-3，2026-10-08 归档）、`add-frontend-audit-logs-kick`（审计日志查询与强制下线——`auditLogs` 带 page/size/username/action 的服务端筛选页 + 无 total 信封的下界分页 + 仅 userId 行的危险确认下线，前端台账 F-4 第二阶段，2026-10-08 归档）、`fix-frontend-exam-page-error-states`（考试列表查询失败与编辑回填失败显性化——U-6 列表查询失败 Alert+Table 隐藏、U-7 编辑回填失败 Alert+保存禁用/提交拦截，UX 台账 U-6+U-7，2026-10-08 归档）、`add-exam-analysis-report`（教师端考试数据分析报告界面——「分析报告」页签于考试详情页渲染班级概览/分数段/逐题指标/知识点薄弱/关注名单五块，创新点4，2026-10-08 归档）、`add-frontend-paper-blueprint`（试卷抽题矩阵蓝图模式——双向细目表矩阵、InputNumber 题量输入与覆盖度合计、三态处理与抽题流程复用，创新点1，含修复笔 78a1785 修复 antd-vue ValueType 类型不兼容与 Number 归一，2026-10-09 归档）、`add-student-wrong-questions`（学生端我的错题本页面与查分页逐题回顾入口及解析弹层，创新点3 + §3.3，2026-10-09 归档）、`add-offline-exam-shell`（断网刷新重入离线外壳 + 断网显性保护 UI + 切屏事件离线兜底与去重补报，创新点5 Phase 1，2026-10-09 归档）、`update-monitor-polling-tab-gate`（监考总览轮询按浏览器页签可见性门控——非激活暂停、恢复可见立即拉取刷新与轮询恢复、词法护栏，2026-10-10 归档）、`add-offline-exam-reentry`（离线重入已领取考试——本地快照持久化、作答页离线降级与倒计时墙钟校正、SW 学生域化、列表离线降级与超窗风险显性告知，创新点5 Phase 2，2026-10-10 归档）、`fix-frontend-decorative-assertions`（测试断言有效性纪律——全仓装饰性断言普查与修复 + 词法护栏，2026-10-10 归档）、`add-exam-reservation`（学生预约入口、消息中心界面、教师建考预约开关，2026-10-12 归档）、`fix-frontend-offline-reservation-ux`（学生考试列表离线判定响应化 + 离线预约操作门控 + 消息中心查询失败显性化，UX 台账 U-8+U-9+U-10，2026-10-13 归档）、`add-question-fill-blank`（填空题录入可接受答案动态列表 / 作答单行文本输入 / 逐题回顾可接受答案列表，2026-10-13 归档）、`add-question-image`（题干图片——上传/下载、image_url 快照携带与五渲染点，2026-10-13 归档）、`add-question-import-export`（题库Excel批量导入导出——导入/导出/模板三端点与逐行容错，2026-10-14 归档）、`update-analysis-option-distribution`（题目选项分布统计——三处同源选项分布，2026-10-14 归档） | 前端系列 |
| 19 | `dev-config` | `parameterize-dev-credentials`（提案⑫，2026-09-21 归档） | 工程性 |
| 20 | `deployment` | `add-deployment-stack`（生产部署栈——应用镜像化 + Nginx TLS 终结 + 独立生产编排，生产化 P3，2026-10-11 归档；`spec/specs/deployment/spec.md` 新建，Requirement「生产部署栈」六 Scenario） | 生产化 P3 |

- 已归档（spec/changes/archive/）：完整历史变更明细台账已迁移至独立冷存储文件 [CHANGES_ARCHIVE.md](CHANGES_ARCHIVE.md)（历史追溯与审计专用）。近期最新归档速览见下方，条目数以 ind spec/changes/archive -mindepth 1 -maxdepth 1 -type d | wc -l 现场计数为准。

| 变更 ID | 阶段 | 内容 | 周期 |
|---|---|---|---|
| `add-question-import-export` | 题库Excel批量导入导出（成熟产品差距三案串行终案，**GO**） | 覆盖：三端点——POST /api/questions/import（multipart，@RequestPart + 逐行独立事务容错、错误行跳过记{行号,原因}、行数上限默认1000可配置整批拒绝）、GET /api/questions/export（全量对齐列表口径：软删过滤+非ADMIN仅个人/ADMIN全量，SXSSF复用ExcelSheetWriter，列口径与模板逐字一致 round-trip 可用）、GET /api/questions/import-template（表头+五题型示例+填写说明，与导入校验共用 QuestionImportColumn 列定义防漂移）；类级 question:manage 复用；标签仅已存在标签名；SDK multipart 护栏（OpenApiImportContractGuardTest）；门禁数字链 438→447、649→656、79→82；复核六项 PASS + 变异 m1–m4 击红-复原-回绿 + 证据链对账（执行侧文件数过计 18→17 已勘正）；低风险观察：导入答案长度无 app 层校验（DB 兜底、文案不对称，收口后小修）；零 DDL 无迁移脚本；实施笔 a06f9eb/7a7ad9a/e32b2aa/ce1df48、--no-ff 合并笔 1acfbab。 | 2026-10-14 |
| `update-analysis-option-distribution` | 题目选项分布统计（创新点 4 残差小案，**GO**） | 覆盖：analysis-report JSON（QuestionStatItem 增 optionDistribution）+ question-stats xlsx 第 10 列「选项分布」+ 前端分析报告页签逐题区展示，三处同源；题型过滤 SINGLE/MULTIPLE/JUDGE，填空/简答空数组；占比分母=作答人数，MULTIPLE 占比和可超 100%（实测 175%）；选项全集从试卷快照映射 A–F（判断固定 T/F），与判分同源；既有指标零漂移护栏锁值；零新端点（paths 82 维持）、零新依赖、零 DDL；门禁数字链 447→448、656→657、82 不变；复核六项 PASS + 变异 3 项击红-复原-回绿 + 证据链九项零失真；低风险观察：选项字母超 Z 为理论边界不防护；实施笔 9d53d44/8d19c72/46a3433、--no-ff 合并笔 fa9a16e。 | 2026-10-14 |
| `add-wrong-question-practice` | 错题本重练与消灭计划（创新点 3 深度，**GO**） | 错题本重练模式与消灭机制：后端基于 `practice_attempt` 明细记录推导连续答对次数与消灭状态（无冗余物理列），判分核心（`GradingStrategyRegistry`）同源复用，提供重练池端点 `GET /api/scores/my/wrong-questions/practice/pool`（本人已发布考试错题跨场去重、客观题过滤、已消灭排除）与逐题判分提交端点 `POST /api/scores/my/wrong-questions/practice/submit`（满分判对、即时反馈对错/正确答案/解析/连对进度、连对 3 次自动消灭、答错归零、仅限本人）；错题列表端点 `GET /api/scores/my/wrong-questions` 向后兼容扩展 `includeMastered` 参数（默认 false 隐藏已消灭）及 `consecutiveCorrect`/`mastered` 字段；前端错题本页提供重练入口、连对进度 Tag、刷题卡片即时判分反馈、消灭提示及已消灭条目筛选翻回；实施笔 35f2a78/a8953ae/9eb18f9，`--no-ff` 合并。 | 2026-10-14 |
| `add-user-import-export` | 用户/学生 Excel 批量导入导出（成熟产品差距 B 节立案，**GO**） | 覆盖：三端点——`POST /api/admin/users/import`（multipart，@RequestPart + 逐行独立事务容错、坏行跳过记 {行号,原因}、行数上限默认 1000 整批拒绝、初始密码 BCrypt 入库无默认常量、角色固定 STUDENT、班级名称按 classes 表解析挂接 user_class〔不存在/同名歧义行级跳过、不自动建班〕、库内与文件内学号双层查重跳过不覆盖既有账号）、`GET /api/admin/users/export`（参数化 scope=student\|teacher\|all + classId 过滤学生、SXSSF 复用 ExcelSheetWriter 流式、软删口径对齐 @TableLogic is_deleted=0、列口径与模板一致）、`GET /api/admin/users/import-template`（表头与导入解析、导出列三向一致，UserImportColumn 列定义枚举防漂移）；类级 @RequireRole(ADMIN) + 方法级 user:manage 复用；前端用户管理页导入上传 + 结果反馈（成功/失败计数与逐行错误）/模板下载/导出（scope 与班级过滤）入口 + (dashboard) 菜单与 NAVIGABLE_PATHS 挂接；SDK 再生成（paths 84→87）；门禁数字链 452→461、660→665、84→87（gate-baseline 三值同批上调）；复核六项 PASS + 变异 m1 查重与 m3 角色复现击红-复原-回绿 + 证据链对账（后端 461 全绿 Skipped 1 / 前端 665 全绿）；零 DDL 无迁移脚本、schema.sql 零 diff、pom 零新依赖；实施笔 1f4e233（后端端点与三件套）、a4c843b（前端页面与 SDK）、ca1dd8c（openapi 契约与 gate-baseline），feature/add-user-import-export 分支 `--no-ff` 合并入 main。 | 2026-10-14 |
| `add-paper-template-clone` | 试卷模板与克隆复用（B 节候选池高优先行立案，**GO**） | 覆盖：papers 加 `is_template` 列（默认 0，零新表）——`POST /api/papers/{id}/clone`（任意卷→新草稿卷、「（副本）」后缀、题目/顺序/分值覆盖全量复制、源卷与其快照零影响、非 owner 403、模板卷调用即「从模板一键建卷」）与 `POST /api/papers/{id}/save-as-template`（is_template=1、原卷状态与内容保留、模板不可再另存）；模板守卫三处均为追加校验行（不可选为考试卷 / 不可生成快照 / 不可再另存），既有行零改动、非模板卷行为零漂移；分页端点加 isTemplate 参数（默认 false）支撑前端「模板库」Tab 与克隆/另存入口（SDK 再生成）；schema.sql +1 列与实体同批、`docker/mysql/migrations/2026-W43-add-paper-template-clone.sql` 入库（硬约定 1/3/4，存量库应用由用户裁决）；门禁数字链 461→470、665→667、87→89（gate-baseline 三值同批上调）；复核 PASS + 变异 m1/m2 五步击红-复原-回绿（m1 模板考试卷守卫、m2 草稿态产物）；实施笔 8d61f37（后端）、0d37c45（前端与 SDK）、0ce8b47（契约与 gate-baseline），feature/add-paper-template-clone 分支 `--no-ff` 合并入 main。 | 2026-10-14 |
| `validate-question-import-answer-length` | 题库导入答案长度应用层前置校验（`add-question-import-export` 收口低风险观察的残差修复，S 级轻量，**GO**） | 覆盖：`QuestionImportExportService.parseRow` 归一化答案后新增 `ANSWER_MAX = 512` 应用层前置校验——超长行跳过并记「答案最长 512 字」（对齐单题建题/改题 `@Size` 文案），取代原先落到 DB `VARCHAR(512)` 溢出被捕获为「入库失败，请检查数据」的模糊文案；同批次合法题目行入库不受影响；零新端点 / 零新依赖 / 零 DDL（schema.sql 与 docker/mysql/migrations 零 diff）、零契约漂移；门禁数字链 470→471、667 不变、89 不变（gate-baseline 仅上调 backendTests）；先红后绿（红＝Tests run: 8, Failures: 1 复现兜底文案，绿＝8/0/0/0），全量后端 471/0/0/1 与前端 94 文件 667 例全绿（默认 5s 首跑 13 例环境性超时，`--testTimeout=60000` 后全绿）；实施笔 cf80300（服务层与集成用例）+ 收口笔（本笔：spec 基线合入、三件套归档、gate-baseline 上调）；feature/validate-question-import-answer-length 分支 `--no-ff` 合并入 main。 | 2026-10-14 |


👉 **查看全部历史归档变更明细台账**：请查阅 [spec/CHANGES_ARCHIVE.md](CHANGES_ARCHIVE.md)

## 遗留事项（已归档但未收口，勿当成已完成）

1. **交卷链路压测已做、P99 未达标**（2026-09-22 由 `add-submit-loadtest` 部分收口）——`loadtest/` 可复现资产与四轮真跑报告已入库（`docs/submit-loadtest-report.md`），**0 丢单与批量落库 < 30s 达标**；**提交 P99 四轮 2088–3700ms 未达标**，根因定位为 Tomcat 线程上限（Spring Boot 默认 200 未配置，容量 ≈455 req/s < 场景需求 ≈490 req/s），改并发运行参数超出该变更授权，已转为报告判据 G1–G6 待容量提案处置。**不得声称「交卷链路压测达标」**。**进度（2026-09-23）**：判据 G3（观测补齐）已由 `add-submit-observability` 收口；判据 G1/G2/G4/G5 已由 `tune-submit-capacity` 执行完毕（选型=线程 400 + DB_POOL_MAX=100、方法学固化进脚本、三臂×3 轮复验）——**P99 复验仍不达标（三臂全超，最好 2293ms）**，且新瓶颈位已跳出应用：MySQL 内部 SQL 排队（Threads_running 57–103、最差连接持有 6.9–7.7s）+ JMeter/MySQL 与 SUT 同机抢 20 核——**再调应用参数无进一步收益**，后续若要继续须分离压测机与 DB 排队治理（均超出当前提案授权，另立项）；G6 已由 `audit-log-off-critical-path` 评估关闭（2026-09-25 归档，**结论=不移出**：审计写仅登录链路、交卷不写 audit_log、失败已旁路、保留策略已由 data-access 基线覆盖，依据见归档 tasks.json 阶段 1）。**本条保持开放直至 P99 在合规口径下达标或需求方显式接受现状。****2026-09-28 复核注记**：`isolate-submit-load-generator` 已收口归档——阶段 1（分离跑法 + 入口门禁）入库，阶段 2（分离后按原口径复验一臂）判**不可执行**（无第二台物理宿主，本地 Docker 不算）。故本条的 P99 未达标结论**仍只以「未分离」的同机采样为据**，分离后的同口径剖面仍缺；不得据此宣称 P99 已复验，也不得把同机结果改写成已分离。
7. **磁盘空间回收不在任何提案范围内**（阶段 15 取证时发现）——MySQL InnoDB 的 `DELETE` 只把页标记为可复用，**文件大小不会变小**；真正回收需 `OPTIMIZE TABLE` 或 `ALTER TABLE ... ENGINE=InnoDB`（离线重写整表、期间锁表），在在线考试系统上属高风险窗口操作。`add-data-retention`（阶段 15）的目标是**控制行数与查询代价**（避免全表扫描与索引膨胀），**不是腾磁盘**。**不得声称"清理后磁盘释放"。**
8. **`exams` 表没有 `ended_time` 列**（阶段 15 取证时发现）——实际结束时刻无字段记录，`updated_time` 会被任意更新刷新（表达的不是结束时刻）。`add-data-retention` 因此改用 `end_time`（时间窗终点）作为"考试已终结"的代理，误差方向是**晚删而非早删**（`force-end` 提前结束的考试其 `end_time` 仍在未来），属安全选择。若要精确化需新增列（= 迁移），当前不值得。

14. **`DataRetentionIntegrationTest` 存在偶发失败**（2026-09-23 阶段 22 第 3 片开工基线首跑暴露）——`purgeRemovesOnlyTerminalOldExams` expected 4 was 8，单类复跑 6/6 绿、同命令全量复跑 290/0/0/1 绿。子 agent 疑点指向共享 H2（`mem:exam;DB_CLOSE_DELAY=-1`）下 `exam` 表跨测试类不清理导致的测试间干扰，机制未定位（返修片禁改后端，未排查）。全量门禁当前可复现为绿，**不阻塞验收**；在它再次变红前不值得动，再变红时按「先单类复跑定性、再查跨类污染」处理。**逐次复核（2026-09-26，`fix-flaky-integration-baselines`）**：在 41f8298 上 `./mvnw clean test -Dtest=DataRetentionIntegrationTest` 逐次独立执行 3 次，每次 Tests run: 6, Failures: 0, Errors: 0, Skipped: 0、BUILD SUCCESS、退出码 0——**未复现，保持开放观察**（未把重跑绿写成已修复）；同批归档的跨实例取号护栏返修与本条偶发失败无归因关系。**触发注记（2026-09-29）**：本条「在它再次变红前不值得动」的触发条件已触发——全量门禁在 revision 584fbb0 再现偶发红（本次红的是 AntiCheat/Auth 两类、非本条的 DataRetention 类，本条自身仍未复现），登记为 #19。

15. **`ExamTakingIntegrationTest.enterGateAndCountdown` 存在偶发失败**（2026-09-23 `add-submit-observability` 验收期间暴露，与观测改动无关——失败点在教师注册链路）——`registerTeacher` 期望 200 得 400，单类复跑 10/10 绿，另一轮全量复跑 293/0/0/1 绿；与遗留 #14 同属「全量偶发、单类绿」模式，疑点同指向共享 H2 下跨测试类状态干扰，机制未定位。**不阻塞验收**；再变红时与 #14 一并按「先单类复跑定性、再查跨类污染」处理，若两条同源可合并立项排查用例隔离。**逐次复核（2026-09-26，`fix-flaky-integration-baselines`）**：在 41f8298 上 `./mvnw clean test -Dtest=ExamTakingIntegrationTest`（含 enterGateAndCountdown）逐次独立执行 3 次，每次 Tests run: 10, Failures: 0, Errors: 0, Skipped: 0、BUILD SUCCESS、退出码 0——**未复现，保持开放观察**；与 #14 同批复核，护栏返修与本条无归因关系。**触发注记（2026-09-29）**：本条「再变红时与 #14 一并处理」的触发条件已触发——全量门禁在 revision 584fbb0 再现偶发红（本次红的是 AntiCheat/Auth 两类、非本条的 enterGateAndCountdown，本条自身仍未复现），登记为 #19。

19. **全量门禁再现「全量偶发、单类绿」——AntiCheatIntegrationTest 与 AuthIntegrationTest 偶发失败（#14/#15 家族第三例；编号取 19，#17/#18 已被已收口的 D1/D2 占用）**（2026-09-29 登记于 optimize-my-score-rank-fetch 归档返修，revision 584fbb0）——同一命令 mvnw.cmd clean test 两次执行：第 1 次 **323/3/0/1 BUILD FAILURE**（三条失败＝AntiCheatIntegrationTest.switchScreenCollectsWarnsAndNeverForceSubmits、AuthIntegrationTest.accountLockedAfterFiveFailures、AuthIntegrationTest.loginRateLimitReturns429），第 2 次 **323/0/0/1 BUILD SUCCESS、退出码 0**；定向复跑两次全绿（-Dtest=AuthIntegrationTest,AntiCheatIntegrationTest → 24/0/0/0；加 MyScoreRankEquivalenceTest → 33/0/0/0）⇒ 符合 #14/#15「全量偶发、单类绿」模式，且本变更新增的 MyScoreRankEquivalenceTest 不是触发器。现场核实事实：/api/auth/login 无 @RateLimit（限流拦截器只对打了 @RateLimit 的接口生效——全 src/main 仅 PaperController/ExamTakingController 三处注解；登录限流走 AuthService.loginGuard.checkRateLimit）；flushDb()（Redis FLUSHDB 清测试库 db 15，application-test.yml database: 15）测试侧共三处（IntegrationTestBase/AuthIntegrationTest/MustChangePasswordIntegrationTest 各一处 @BeforeEach）；主测试库 jdbc:h2:mem:exam;DB_CLOSE_DELAY=-1 主从双源同 URL、跨 Spring 上下文共库。**机制未定位，未修复，保持开放——不得写成已修**；处置沿用 #14/#15 口径（先单类复跑定性、再查跨类污染；同源可合并立项排查用例隔离）。

16. **压测复位脚本受 mysql 客户端默认字符集影响可静默假绿**（2026-09-23 `tune-submit-capacity` 真实踩中）——本机 `character_set_client=gbk`，漏 `--default-character-set=utf8mb4` 时：纯中文字符串报 1267（会被发现），但 **ASCII+中文混合字面量静默失真**（`= _utf8mb4'…'` 匹配 0 行、退出码 0）——`loadtest/db/03-cleanup.sql` 的 DELETE 与末尾自证用同一失真字面量，打印全 0 假绿而复位根本没执行；同源风险罩着 `02-metrics.sql` 的 `@exam_id`（会静默变 NULL ⇒「0 丢单」假绿）。**已处置**：`loadtest/README.md` 用法行补 `--default-character-set=utf8mb4` + 独立复核命令 + 坑条目改写（`259d4f4`）；脚本本体属既有共享资产未动。**收尾纪律**：任何用这些脚本的后续压测，复位结果以独立复核命令为准，不信脚本自证输出。**现场核对注记（2026-09-28，revision c8d27fa）**：`loadtest/` 下每条脚本内的 `mysql` 调用点都已带该参数——`prepare-data.sh:46`、`run-loadtest.sh:176`、`run-loadtest.sh:401`（自 `b1ff00c`，2026-09-22）、`run-arm.sh:82`（复位与自证路径，自 `df1871d`，2026-09-23）；`db/03-cleanup.sql` 在任何 `.sh` 里**没有调用点**，只按 `loadtest/README.md` 的用法行手工执行。⇒ 残留的静默假绿风险限于**手工/临时** `mysql` 调用（尤其手工跑 `03-cleanup.sql`），脚本路径已被参数覆盖；收尾纪律不变——复位结果以独立复核命令为准，不信脚本自证输出。

20. **新测试夹具可经共享 H2「已发布考试第 1 页 50 条」窗口挤红学生考试列表用例（确定性红，非偶发，根因＝列表取数窗口口径，已收口）**（2026-09-29 登记于 `reattribute-monitor-overview-projection` 收口；2026-09-30 由 `fix-my-exams-list-scope` 收口）——`ExamTakingIntegrationTest.examListGroups` 依赖 `ExamTakingService.myExams()` 的「已发布考试按 `start_time` 倒序第 1 页 50 条」窗口（`ExamTakingService.java:150`）；`jdbc:h2:mem:exam;DB_CLOSE_DELAY=-1` 跨测试类累积，任何新测试若把 `published=1` 的考试插进该排序窗口的上方（本次：新增护栏夹具 4 条 `start=now-10min`，计数复核后该窗口线上恰好 50 条 ⇒ 目标考试落第 2 页），该用例即红；两次原样重跑同点同文案 ⇒ **确定性红**（与 #14/#15/#19「全量偶发、单类绿」不同，本类单类复跑也会红）。**处置**：夹具按「只服务被测路径」改 `published=0`（`MonitorService.overview` 与 `requireOwnedExam` 不读发布态，断言对象不变）后转绿；两次红与修正依据见 `spec/changes/archive/reattribute-monitor-overview-projection/evidence/gate-runs.md`。**预防（写测试者自查）**：新建考试夹具前先确认被测路径是否消费「发布态／列表排序」语义——不消费就置 `published=0`；确需 `published=1` 的用例自清数据并避开这一固定 50 条窗口。**收口（2026-09-30，`fix-my-exams-list-scope`）**：根因确认为 `ExamTakingService.myExams` 既有口径以系统级全局 50 条窗口过滤，令无关考试挤掉学生考试。现已将取数范围修正为本人归属（答卷 ∪ 班级 ∪ 补考名单），排序按 §12.1 分组及近→远，50 上限作用于本人集合内。测试间已发布考试不再无界竞争全局 50 窗口，用例间干扰彻底消除。


> 说明：已收口历史问题已归档至 [CHANGES_ARCHIVE.md](CHANGES_ARCHIVE.md)「历史已收口遗留事项清单」。此处仅保留真正未达标或留观的开放项。

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
| 8 | performance | 缓存三防（穿透/击穿/雪崩）、热点只读缓存、「交卷容量可由运行参数调整且达标判定可复现」、「Java 优化先归因、一次只改一类」、「题目统计导出优化先拆分分页与解析」 |
| 9 | data-access | 读写分离、读己之写、数据保留与有界清理（三张辅助表 / 零 DDL / 默认双关）、新库建表 MySQL 8 兼容（AUTO_INCREMENT 必须有主键） |
| 10 | observability | 指标导出、自定义业务指标、慢 SQL 识别与请求关联、异步链路请求关联、指标驱动的告警、观测面板、死信队列指标与告警、观测栈动态可验证性 |
| 11 | reliability | 接口限流（Redis 令牌桶）、分布式一致性、限流粒度、限流器降级与可观测、定时扫描多实例幂等、交卷锁按持有者解锁、死信可见性与有界重投、集成测试偶发失败的可复现处置、跨实例取号确定性护栏 |
| 12 | data-consistency | 事务显式回滚、受检异常转换 |
| 13 | class-management | 班级 CRUD、学生入班/转班、班级学生列表 |
| 14 | absence-makeup | 缺考标记（含自然到点与 force-end 两条结束路径）、补考独立记录、补考成绩规则合并、考后闭环端到端一致性 |
| 15 | score-review | 复核申请限次限时、复核中隐藏成绩、复核处理 |
| 16 | api-contract | OpenAPI 契约暴露、鉴权语义标注、契约导出复现、契约冒烟护栏 |
| 17 | agent-harness | 根入口路由（`README.md` 只指路不承载易变事实）、硬约定的 agent 必经索引（`AGENTS.md`）、无自动执行者的 SQL 目录必须自述触发方式、文档判据优先于文档常量。**基线已合入 `spec/specs/agent-harness/spec.md`**（E1，2026-09-21；E2 四条，2026-09-25） |
| 18 | frontend | 五阶段全量：前端工程底座（可复现启动/门禁独立可跑/不并入后端计数）、API 契约驱动集成（gen:api 生成/代理保留前缀/统一解包）、会话与令牌生命周期（续期单飞/刷新失败登出/登出黑名单）、角色路由守卫（安全边界在后端）、强制改密前置守卫；题库管理界面（题型差异化录入/软删以后端为准）、组卷界面（手动+随机抽题，随机算法在后端）、前端不复现判分口径；学生端在线考试（作答界面/服务端时间倒计时/草稿保存与断线恢复/交卷防重配合/切屏检测与上报/交卷结果如实呈现）；教师端考务（班级管理/考试创建与发布/监考与行为日志）；考后闭环（主观题批改工作台/成绩发布撤回与导出/缺考与补考/成绩查询与复核）。已知缺陷 D1/D2 的历史注记见基线合入注记，两项均已收口（原遗留 #17/#18，见「已收口」清单） |
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

9. **收口笔同步上调 `.github/ci/gate-baseline.json`（只增不减机检载体）**：该文件登记后端 surefire 用例数、前端 vitest 用例数与仓库根 `openapi.yaml` paths 数三下限，GitHub Actions 在每次 push/PR 上机检比对。**每个变更的收口笔若新增用例或契约路径，必须同笔上调对应下限（数值以该收口笔当次实测为准并绑定 revision），不得下调**——下调使数字吻合按「用例总数下降」同罪处理；不上调则该笔 push 的 CI 直接红。由 `add-ci-gate-pipeline`（2026-10-11 归档）确立；判据见 `spec/specs/agent-harness/spec.md`「CI 门禁机检化」。

10. **变异验证优先以工具驱动（`scripts/mutation-check.sh`）**：凡需按 AGENTS.md「变异验证纪律」注入护栏（注入违规→击红→复原→归零→回绿）自证或独立复现的，执行侧与复核侧**优先**以 `scripts/mutation-check.sh` 驱动（注入点仍按各变更定制），手工五步仅作兜底。工具将五步固化为可复现的原子流程并统一留证形态（全落 `target/mutation-check/<label>/`），杜绝 CRLF 模式串静默失配、装饰性断言假绿等历史坑。判据见 `spec/specs/agent-harness/spec.md`「变异校验工具化」。

## 参考资料（非 openspec 资产）

- `docs/examOnline需求规格说明书.md` — 完整产品愿景
- `docs/需求决策记录.md` — 80+ 项场景决策（开发逐条对照）
- `docs/面试版实施方案.md` — v3 大厂面试级实施方案（开发蓝本）
- `docs/指导Agent交接文档.md` — 交接说明（注意：其中「阶段 8.1/8.2/9 已完成归档」的描述直到 2026-09-14 才真正成立）

