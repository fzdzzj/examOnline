# 提案：补考最终成绩前端展示（add-makeup-final-score-frontend，P3）

> **状态：已归档（2026-09-25）。** 验收边界=隔离页面测 + 前端四门禁（vitest / eslint / vue-tsc / tsc）全绿；**未跑真实 Chromium、未写共享 dev、未 docker**，不冒称真机已验。规范已合入 frontend 基线、目录已归档；生成层 diff 除 makeup-final 两方法外含既有欠账 `auditLogs`（`GET /api/admin/audit-logs`）——该端点早已在 `openapi.yaml` 与后端实现中，经裁决随本次重生成一并消化，未新开变更、未做审计页面、业务代码零调用。

## Why

提案⑨（补考成绩接线）的**后端部分已完成**：`ScoreController` 已有两个端点——`GET /api/exams/{examId}/scores/makeup-final/{studentId}`（教师侧，`exam:manage`，沿主考家族按考试配置规则合并、历史成绩保留不覆盖）与 `GET /api/scores/makeup-final?examId=`（学生查本人，口径同 `myScore`：未发布统一「成绩待发布」、进行中复核隐藏分数防「看了分数再申请」）；`openapi.yaml` 已含两条路径。

前端却是**滞后状态**：`frontend/src/api` 生成层对 makeup-final 零命中（未随契约重生成），`teacher/makeups` 页面仍挂着「后端尚未接线、只做创建与准入管理」的诚实边界 Alert（当时守遗留 #5 的正确行为）。现在接口已存在，该缺口可以前端收口——把「不展示合并最终成绩」的边界**替换为真实展示**。

**属前端变更**：零后端改动（`src/main`、`src/test`、`pom.xml` 不动），与前端系列纪律天然一致。

## What Changes

1. **契约重生成**：`pnpm gen:api`（`openapi.yaml` 已含两条路径，生成层补齐 makeup-final 方法；重生成后 `git diff` 核对无契约漂移——这是既有工程惯例）；
2. **教师侧展示**：`teacher/makeups`（或成绩页，按领域归属裁决）对候选/已建补考学生展示补考最终成绩（`exam:manage` 端点，合并规则在后端、前端只渲染返回值）；
3. **学生侧展示**：`student/scores` 对补考关联考试按 `GET /api/scores/makeup-final` 展示最终成绩，`reviewing` 语义与 `myScore` 同构（复核中隐藏分数，不本地推断）；
4. **诚实边界改写**：makeups 页的「后端尚未接线」Alert 更新为如实描述（合并规则在后端、历史成绩保留）；不得残留「最终成绩后端尚未接线」的过期表述；
5. vitest 用例：教师侧渲染、学生侧三态（正常 / 待发布 / 复核中）、不本地推算合并规则；
6. spec-delta：`frontend` 基线「缺考与补考界面」Requirement 的「不声称最终成绩合并可用」场景**随本变更改写**（前提条件已变：后端已接线）。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — MODIFIED：「缺考与补考界面」Requirement（「不声称最终成绩合并可用」场景 → 展示真实返回并保持「不本地推算合并规则」）。

### 受影响的文件
- `frontend/src/api/**`（gen:api 产物）
- `frontend/src/pages/(dashboard)/teacher/makeups/**`、`frontend/src/pages/(dashboard)/student/scores/**`
- `frontend/src/**` 相关测试
- `frontend/docs/**`（演示脚本更新，可选）

### 需要迁移
- [ ] 数据库迁移（无，零后端改动）

## 时间线评估

小–中：约 0.5–1 天。

## 风险

- gen:api 重生成可能带出**其它未消化的契约漂移**——若有，停下回报单独处理，不顺手改（既有惯例：重导出后比对）；
- 学生端 `reviewing` 语义必须与 `myScore` 完全同构（防「看了分数再申请」是后端设计意图），前端只读字段不推断；
- 补考家族关系（主考/多场补考）展示口径以后端返回为准，前端不自行组织家族树。
