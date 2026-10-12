# add-exam-reservation 提案（考试预约与开考提醒）

## 背景与需求（2026-10-12 用户发起，阶段 0 查证完成）

用户需求：为考试增加「定时开放 + 学生预约 + 到点消息提醒」能力。经两问收敛（2026-10-12 用户答复）：

- **Q1 预约形态**：「教师设置什么时候开放，学生可以得到消息提醒」——教师侧定时开放**已存在零新开发**（`exams.start_time`/`end_time` 时间窗 + `ExamStateMachineService.autoAdvance` 定时推进 + `ExamTakingService.requireEnterableExam`「已发布且进行中」准入，`schema.sql:185-186`），本案增量是**学生预约、开考提醒投递**。
- **Q2 缺席口径**：「预约即应考」——缺席标记与应考统计按**有效预约名单**计，而非班级全体。

阶段 0 现场核证（HEAD 实读，非台账推断）：

1. **准入两层**（`ExamTakingService.assertAdmission:126`）：主考强制班级归属（`class_id` ∈ 学生当前班级）；补考走 `exam_candidates` 白名单（`makeupService.assertCanEnter`）。预约校验是**叠加在既有两层之上的过滤层**，不替代。
2. **缺席口径**（`AbsenceService.markAbsence:70`）：应考 = `ClassService.listStudentIds(classId)` − 有答卷者，`INSERT IGNORE` 幂等，锚定状态机「进行中→已结束」迁移。联动点即此处的 `expectedIds` 来源分流。
3. **分析报告应考口径**（`ExamAnalysisReportService.buildClassOverview`）：应考/缺席沿用缺考域口径现算。预约制考试需同源分流，否则与 `exam_absence` 两套缺席数。
4. **监考域无「应考名单」概念**（Grep `应考|roster|expected|名单` 在 `monitoring` 包仅命中 retention）——监考大屏只呈现在线/答卷状态，**无需联动**（修正立项讨论时「监考应考名单按预约者计」的预期，如实登记）。
5. **系统无站内消息基础设施**（Grep `notification|站内|notice|UserMessage|inbox` 在 `src/main/java` 零命中）——提醒投递层（消息表 + 未读机制 + 端点）需**从零新建**，是本案工作量主体之一。
6. **扫描基础设施先例**：`ExamSweepService` `@Scheduled(fixedDelayString = "${exam.taking.sweep.fixed-delay-ms:10000}")` + 启动补偿——提醒投递扫描同构复用此模式。
7. **schema 双轨纪律**：新表唯一入口 `schema.sql`（CREATE TABLE IF NOT EXISTS + PRIMARY KEY(id)）；存量库改表走 `docker/mysql/migrations/`（无自动执行者，人工应用后登记 `APPLIED.md`）。`exams` 加列两轨都要写。

## 形态定版

### 考试级开关（非全局强制）

`exams` 新增 `reservation_required TINYINT NOT NULL DEFAULT 0`。教师建考/编辑考时设定；默认 0 = 维持现状（班级制即时进入），既有考试与既有测试**零影响**。补考（`parent_exam_id != null`）不适用预约（白名单制与本功能正交），建考链仅主考暴露此开关。

### 「未预约者不可进入」——自洽性推论（显式声明，供终局裁决）

「预约即应考」的数学自洽推论：**预约制考试中，未预约者不可进入**。若未预约者仍可进入，会出现「可进入但不计入应考名单」的割裂——监考侧看不见其在线状态的口径解释、缺席统计遗漏、分析报告口径漂移。故准入校验顺序定为：既有两层（班级/补考白名单）**先**、预约校验**后**（预约是必要条件而非充分条件：预约后学生被移出班级仍被班级校验拦截，属正确行为）。

**预约资格与准入口径同源**：学生预约时即做与进入准入同源的班级归属校验——防止「约了进不去」的死约污染应考名单（缺席统计会把外班预约者算作应考）。不另造第二套资格口径。

### 预约窗口与名单锁定

- **可约窗口**：已发布且未开始（`published=1 AND status=0`）。开考瞬间（状态机 → 进行中）预约名单即锁定：**开考后不可预约、不可取消**。
- **取消预约**：仅开考前，物理删除预约行（重约 = 重新插入，`uk_reservation_exam_student` 唯一索引天然幂等）。
- **开关修改窗口**：`reservation_required` 仅 `status=0` 可改（若教师编辑链现状无状态守卫，则为该字段加守卫；不改变其他字段的编辑语义）。
- **名单即终局**：缺席判定锚定考试结束时点，此时读到的预约行集 = 开考时锁定的名单（开考后不可增删行）。

### 开考提醒投递（行级幂等）

- `exam_reservations.reminded_time` 行级幂等标记：扫描投递 `UPDATE ... WHERE reminded_time IS NULL` 影响行数=1 才写消息，多实例/重启/重复扫描不重发（对齐 `add-multi-instance-sweep-safety` 竞态安全先例）。
- 提醒提前量经配置项（如 `exam.reservation.remind-ahead-minutes`，默认 30 分钟），扫描间隔沿用 sweep 模式 `fixedDelayString` 配置；服务端不承诺秒级实时（10s 级扫描间隔）。
- 扫描范围：`reservation_required=1 AND published=1 AND status=0 AND start_time <= now + 提前量` 的考试的未提醒预约行。
- 预约成功**不发消息**（列表状态可见），全功能仅一条「开考提醒」消息。

### 学生消息中心（新基础设施，本期唯一投递方为预约提醒）

- `user_messages` 表按 `user_id` 通用建模（`exam_id` 可空关联），端点：本人消息列表（倒序）、未读数、单条标记已读。鉴权只要求登录，不特判角色（教师天然可拥有空消息箱，为后续功能留口径）。

## 变更内容

1. **数据层（双轨）**：`schema.sql` 新增 `exam_reservations`（`exam_id`/`student_id`/`reminded_time`/`created_time`，`uk_reservation_exam_student` 唯一索引命名避让 H2 全局冲突先例，`idx_reservations_exam` 普通索引）与 `user_messages`（`user_id`/`exam_id` 可空/`title`/`content`/`read_flag`/`created_time`，`idx_user_messages_user` 按用户倒序列表）两表；`exams` 加列 `reservation_required`。存量库另写 `docker/mysql/migrations/` 脚本（头注释含预期错误码语义：加列 1060 重复、建表 1050 语义），应用后登记 `APPLIED.md`。
2. **预约域后端**：`Exam` 实体加字段；`ExamCreateRequest`/`ExamUpdateRequest`/`ExamResponse`/`ExamDetailResponse` 加开关（补考请求链不加）；新增 `ExamReservationService`（独立 service 先例）承担预约/取消/状态与资格校验；`ExamTakingService.assertAdmission` 叠加预约校验（既有两层零改动，仅追加分支）；`myExams` 的 `ExamListItem` 增加 `reservationRequired`/`reserved` 下发（canEnter 语义联动：预约制考试未预约且进行中 → canEnter=false）。
3. **提醒投递**：新增 `UserMessage` 实体/Mapper 与消息端点（学生消息列表/未读数/已读）；新增 `ReservationRemindService`（@Scheduled 同构 ExamSweepService 模式）行级幂等投递。
4. **口径联动**（两处，均已核证消费点）：`AbsenceService.markAbsence` 的 `expectedIds` 按 `reservation_required` 分流（预约制 → 有效预约名单）；`ExamAnalysisReportService.buildClassOverview` 的应考/缺席维度同源分流。**clazz 包零触碰**（`ClassService.listStudentIds` 仍是唯一班级名单入口）。
5. **契约**：`openapi.yaml` 重导出（离线路径 A，`getContentAsByteArray` UTF-8 纪律，与真实 dev 实例字节级比对；环境不可达时如实登记降级形态）——新增 paths（预约/取消/消息三组）与既有 schema 加字段，`paths` 数必增。
6. **前端**：学生考试列表（`student/exams/index.page.vue`）待考项预约/已约/取消入口与状态展示；导航壳（`(dashboard).page.vue`）消息铃铛（未读数）与消息中心（列表/已读）；教师建考表单（`teacher/exams/create.page.vue`）加「需预约」开关。前端类型经生成链同步。
7. **测试**：后端集成用例（预约/取消窗口边界、未预约拦截进入、预约资格同源校验、缺席名单分流、分析报告应考分流、提醒幂等与提前量、消息端点鉴权）+ 纯 JUnit 护栏（名单分流不可绕过）；前端直断形态用例（禁 if/exists 守卫与 || 双通）。变异自证以 `scripts/mutation-check.sh` 驱动（Git-Bash 形态；WSL 不可挂盘时同工具 Git-Bash 驱动，非手工兜底）。

## 红线与验收边界

- **状态机零语义改动**：时间窗推进、autoAdvance 触发缺席标记的时机**不变**，只改名单来源；不碰 `exam_candidates`/补考语义；不碰 `RabbitMQ` 交卷链路。
- **schema 纪律**：`schema.sql` 唯一建表入口 + `docker/mysql/migrations/` 存量库双轨；AUTO_INCREMENT 表同批 `PRIMARY KEY (id)`；实体字段与建表定义双向一致（AGENTS 硬约定 3 自查）；新唯一索引命名避让 H2 全局约束名碰撞。
- **契约纪律**：`openapi.yaml` 只经重导出更新（不手改）；`servers.url` 非降级、无 U+FFFD、完整中文描述断言维持；paths 数只增不减。
- **测试纪律**：双端用例总数只增不减；gate-baseline.json 三下限收口笔同笔上调（backendTests 增新用例数、frontendTests 增新用例数、openapiPaths 增新端点数）；CI run 引用只登记终态 success run（conclusion 核验）。
- **前端纪律**：断言直断形态（两禁形态禁用）；不本地持久化任何预约/消息状态；预约与消息 API 请求 network-only。
- **零改动域**：`auth/security`、`config`、`pom.xml`、`application*.yml`（新增配置项除外——允许在本案新增 `exam.reservation.*` 配置键，既有键零改动）、`clazz` 包、补考域、判分域、导出域、监考域。
- **实现位置**：预约逻辑独立 `ExamReservationService`（不改 `MakeupService`/`ScoreService` 等既有 service 方法体；`assertAdmission`/`markAbsence`/`buildClassOverview` 允许追加分支，既有分支行为逐字不变）。

## 停止条件（触发即停，如实上报）

1. 发现预约与班级/补考准入存在提案未列出的隐藏耦合（如某路径绕过 `assertAdmission` 直达 `createSubmission`）。
2. 发现「应考名单」还有第三处消费点（`markAbsence`、`buildClassOverview` 之外），口径联动范围超出本提案两处。
3. `schema.sql` 与 migrations 脚本出现同对象不同形态（列类型/默认值/索引名不一致）。
4. 开考提醒投递无法以行级幂等实现（如 H2 与 MySQL 的 UPDATE 影响行数语义差异导致幂等判据失效）。
5. 前端生成链（openapi-ts）无法承载新端点类型同步，需要改 `vite.config.ts`/生成配置——单独上报裁决，不得擅动。

## 复核分级判定（2026-10-12 定版规则，显式声明）

本变更含**业务代码 + DB 迁移（schema.sql 加列加表 + migrations 脚本）**，非零代码编排类 → 分级②：**全量六项本地独立复跑**保留，派发轮次采用**先复核后收口**形态——执行侧停在实施笔本地提交（不 push、不合入、不归档、不勾收口任务），复核子 agent PASS 后另行派收口（收口含 spec-delta 合入、--no-ff 合入、归档、README 登记与 gate-baseline 上调）。独立复现主线（名单分流真实变异击红 + 提醒幂等构造性实证 + 契约 diff 复核）任何环节不可砍。复核提示词注明「接手盘点结论在案」，复核侧不再重复盘 git 拓扑。

## 验收判据

- 后端仓库根 `./mvnw.cmd clean test` 全绿，Tests run 恰增新增用例数（与 gate-baseline backendTests 上调幅度一致）。
- 分级②核心实证：①预约制考试未预约者进入被拦截（FORBIDDEN）且既约者正常进入；②缺席标记名单分流（预约制考试按预约名单、普通考试按班级名单，既有用例零回归）；③提醒投递行级幂等（重复扫描不重发，构造性实证）；④`ExamListItem` 新字段下发且 canEnter 联动正确。
- `openapi.yaml` diff 仅含新增 paths 与既有 schema 新增字段，`paths` 数恰增新端点数；契约与真实 dev 实例字节级一致（或如实登记降级）。
- 前端 `npm.cmd run lint:check / type-check:check / test` 三门禁串行全绿（npm.cmd 形态 + 输出非空核验），用例数恰增。
- 实施笔 diff 全部落在白名单文件清单内（红线域全空）；schema.sql 与 migrations 脚本同对象逐字一致自证。
- 收口笔（复核 PASS 后）：CI 三 job 终态 success、gate-baseline 三下限同笔上调、五份 spec-delta 逐字合入对应能力域基线、spec/README.md 常规登记、归档形态与三件套入库形态如实对齐（随实施笔入库则 R100，否则新增形态）。
