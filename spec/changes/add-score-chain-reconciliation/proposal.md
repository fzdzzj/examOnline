# 提案：成绩链全链路对账引擎（add-score-chain-reconciliation）

> **变更 ID**：`add-score-chain-reconciliation`
> **能力域**：`reliability`（既有域追加 Requirement）
> **级别**：S~M 级可靠性纵深特性（交卷→判分→成绩三段对账 + 差异票闭环 + 幂等补齐 + 定时兜底）
> **前置依赖**：无硬前置（成绩链三段与定时任务多实例安全护栏均已合入 main）
> **影响面**：`schema.sql` 新表、`docker/mysql/migrations/`、对账服务与四端点、`openapi.yaml`、前端管理面板

---

## 1. 为什么做（Why）

- **业务痛点**：
  1. 成绩链三段（交卷 → 判分 → 成绩）目前**无系统性对账**：若某答卷漏判、判分明细与总分不一致、成绩与报告聚合不符，只能靠考生申诉或人工翻库发现；
  2. 既有可靠性资产覆盖的是**消息层与存储层**（MQ 启动期答案补发对账、Redis 降级演练台账、数据库备份恢复演练），业务数据链的最终一致守护是空白；
  3. 大考级系统的「成绩可信」叙事需要工程证据，对账批处理 + 差异票闭环是标准答案。
- **治理目标**：补齐业务数据链对账缺口，与既有消息层/存储层资产拼成完整可靠性矩阵。

---

## 2. 改什么（What）

### 2.1 对账数据模型（新表两张，走建表全套红线）

- `reconciliation_runs`：对账运行记录（范围 examId、触发来源 MANUAL/SCHEDULED、起止时间、三类差异数、状态）；
- `reconciliation_diffs`：差异票（类型 SUBMISSION_UNGRADED / DETAIL_TOTAL_MISMATCH、考试/答卷/题目定位、期望值/实际值、状态 OPEN / AUTO_FIXED / MANUAL_CLOSED、关联 run）；
- `schema.sql` + `docker/mysql/migrations/` 同笔 + `APPLIED.md` 如实登记；实体过 `comm -23`。

### 2.2 对账引擎（ReconciliationService）

- **维度一：交卷 vs 判分**——已交卷答卷是否存在判分缺失/主观题未批完（考试已达已判分状态为前提）；
- **维度二：判分 vs 总分**——判分明细汇总与最终成绩记录逐答卷比对（期望值/实际值/偏差入票）；
- **确定性缺失幂等补齐**：仅「判分完成但总分未回填」类执行自动补齐，复用既有回填/重算服务方法（同源纪律，不另起口径），补齐动作记 `audit_log`；数据矛盾类**只出票不自动改分**；
- **定时兜底**：`@Scheduled` 增量扫描，严格遵守 reliability 域既有「定时任务的并发与多实例安全」口径（取号护栏/幂等，不引入新调度中间件）。

### 2.3 端点与前端

- `POST /api/admin/reconciliation/run`（手动触发，可带 examId 范围，`exam:manage`）；
- `GET /api/admin/reconciliation/runs`（运行历史分页）；
- `GET /api/admin/reconciliation/diffs`（差异票列表，按 run/类型/状态筛选，分页）；
- `POST /api/admin/reconciliation/diffs/{id}/close`（人工关闭，记审计）；
- `openapi.yaml` 110→114 paths + schemas，SDK 重生；
- 前端：管理端对账页（运行历史、差异票列表与筛选、触发按钮、人工关闭），接入管理导航。

---

## 3. 负范围（Out of Scope，防与既有资产重复）

- **不做通用故障注入框架**：Redis 降级演练（runbook + 台账）与备份恢复演练均已固化，不重复建设；
- **不做 MQ 消息层对账**：启动期答案补发对账已在 publisher-confirm 链路固化；
- **不自动改分**：任何涉分差异只出票，补齐仅限确定性缺失的回填动作。

---

## 4. 约束与硬红线（Guardrails）

- AGENTS.md 四条硬约定全套适用（`schema.sql` 唯一建表入口 + `AUTO_INCREMENT` 块内显式 `PRIMARY KEY (id)`、migrations + `APPLIED.md` 台账、集成测试禁 `@Sql`、实体/表双向一致）；
- 对账判分口径**同源复用**既有服务方法（判分状态判定、总分回填），禁止第二套公式；
- 零新 Redis 键族（对账状态全部落 DB；若实施中必须引入，先登记 runbook 矩阵再写代码）；
- 双端门禁 + TDD 先红后绿 + 三变异断言级击红回绿零残留 + `gate-baseline.json` 同笔上调（110→114 paths 等）。

---

## 5. 变异计划

- **m1**：抽除维度一「未判分答卷检测」（漏报）→ 集成断言击红；
- **m2**：抽除确定性缺失的幂等补齐（或其幂等保护，致重复补齐）→ 断言击红；
- **m3**：抽除差异票分类计数/汇总（runs 表三差异数恒 0）→ 断言击红。

---

## 6. 时间线

阶段 1 立案（本笔）→ 阶段 2/3 实施 + TDD + 三变异 + 双端门禁（执行 Agent）→ 阶段 4 独立复核 + 基线合入 + 归档合流（主 Agent）。
