# 提案：补考最终成绩接线（前后端优化组合·提案⑨，P3）

## Why

「补考成绩规则」Requirement（按考试配置取最高分/取最近一次/取平均分合并、历史成绩保留）**已合入并验收**，但 `MakeupScoreService.finalScore(examId, studentId)` **全仓库零调用**、`mergeFinalScore(...)` 只被纯函数单测调用、没有任何暴露「补考最终成绩」的接口（遗留 #5）——已验收的需求从未接到真实调用路径。阶段 23 前端已用页面 Alert 明示该边界，等的就是这个接口。

属后端独立立项（功能接线），不塞进前端阶段 19–23 串行链。

## What Changes

1. 新增暴露补考最终成绩的查询端点（预期挂 `com.exam.score.controller.ScoreController`（成绩查询域）或 `com.exam.exam.controller.ExamController`，实施时按能力域归属与既有路由风格定），内部调用 `MakeupScoreService.finalScore`；
2. **不改合并逻辑**：`MakeupScoreService` 既有方法体不动、`ScoreService` 既有方法体不动（工程约束：合并逻辑独立类），只做接口层接线；
3. 权限按既有 RBAC 与越权防护约束：教师侧（按考试查学生最终成绩）与学生侧（查本人）各自可见范围，不新造权限模型；
4. 集成测试：经接口查询主考+补考均有成绩的学生，验证按规则合并的结果与历史保留（已验收规则首次被真实链路执行）；
5. 契约重导出：受遗留 #12 约束——提案⑧合入前走路 B（真 dev 实例），⑧合入后走路 A；顺序在 tasks 里注明；
6. 前端把阶段 23 的边界 Alert 换成真实最终成绩**不属本变更**（前端侧改动另立前端小变更，契约就绪后随时可接）。

## Impact

### 受影响的规范
- `spec/specs/absence-makeup/spec.md` — MODIFIED：补考成绩规则（补「规则经接口可触达」场景）。

### 受影响的文件
- `src/main/java/com/exam/**` 新增端点与 DTO（score/exam 查询相关 Controller 层）
- `openapi.yaml`（重导出）
- `src/test/**`（新增集成测试）
- 不改 `MakeupScoreService` / `ScoreService` 既有方法体

### 需要迁移
- [ ] 数据库迁移（补考最终成绩为运行时计算合并，无新表）

## 时间线评估

小–中：约 1 天（端点 + 集成测试 + 契约重导出）。

## 风险

- 「不改既有方法体」是硬边界：接线需要的新逻辑放接口层或服务层新方法，不动已验收的合并实现；
- 学生侧可见性：补考最终成绩涉及主考成绩，学生查本人时按既有成绩可见性规则（如复核中隐藏）处理，不绕过既有约束；
- 契约重导出的路 A/B 顺序依赖提案⑧，先到先约束（tasks 已注明）；
- 端点命名与路由风格对齐既有 Controller 惯例，不为一个端点引入新风格。
