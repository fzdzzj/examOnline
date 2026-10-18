# Proposal: add-exam-operation-audit-logs（考务核心操作安全审计覆盖增强，S 级）

## 1. 为什么做（背景与动机）

依据 [`docs/功能待办清单.md`](../../docs/功能待办清单.md) §B.2 巡查备忘中登记的系统观察项（L-04 / 观察 12）：
- **现状**：系统目前建有 `audit_log` 审计表与管理员前端「安全审计日志」查询页面（`GET /api/admin/audit-logs`，带用户名、动作筛选与分页），但后端写入链路**仅覆盖了登录成功/失败与账户锁定**（`LOGIN` / `ACCOUNT_LOCKED`）；
- **痛点**：教师与管理员的关键高危考务写操作（**考试发布、考试删除、成绩发布、成绩撤回、管理员强制踢人下线**）在系统内未持久化审计记录。当发生考试误发布/误删除、成绩撤回纠纷或安全会话被踢等争议事件时，无法通过审计日志追溯操作人身份、操作时间、关联业务 ID 与终端 IP，存在安全管理与合规追溯盲区；
- **目标**：在既有 `AuditLogService` 旁路持久化机制之上，扩充考务核心写操作的审计动作，打通发布/删除/撤回/踢人各写路径的审计留痕，彻底消除巡查观察项中的安全盲区。

## 2. 改动范围与设计细节

### 2.1 零 DDL 与契约红线
- **零 DDL**：`src/main/resources/schema.sql` 中 `audit_log` 表的 `action VARCHAR(32)` 列宽完全容纳新增动作常量，**0 字节改动**；
- **契约保持不变**：`openapi.yaml` 100 paths 保持不变；
- **前端零改动**：`/admin/audit-logs` 动作筛选框为通用文本精确匹配，天然兼容新动作类型，前端 **0 字节改动**；
- **门禁单调递增**：后端门禁新增覆盖测试，`gate-baseline.json` 的 `backendTests` 递增。

### 2.2 涉及代码白名单
1. `src/main/java/com/exam/audit/entity/AuditLog.java`：
   - 扩充考务核心操作动作常量：
     - `ACTION_EXAM_PUBLISH = "EXAM_PUBLISH"`
     - `ACTION_EXAM_DELETE = "EXAM_DELETE"`
     - `ACTION_SCORE_PUBLISH = "SCORE_PUBLISH"`
     - `ACTION_SCORE_REVOKE = "SCORE_REVOKE"`
     - `ACTION_USER_KICK = "USER_KICK"`
2. `src/main/java/com/exam/service/AuditLogService.java`：
   - 增加基于 `RequestContextHolder` 自动提取客户端 IP 与 `SecurityUtil` 获取当前登录人的专用记录方法：
     - `logExamPublish(Long examId, String examTitle)`
     - `logExamDelete(Long examId, String examTitle)`
     - `logScorePublish(Long examId, String examTitle, int count)`
     - `logScoreRevoke(Long examId, String examTitle, String reason)`
     - `logUserKick(Long targetUserId)`
3. 业务调用点接线：
   - `ExamService.publish`：成功后调用 `auditLogService.logExamPublish`；
   - `ExamService.delete`：成功后调用 `auditLogService.logExamDelete`；
   - `ScoreService.doPublishOne`：成功后调用 `auditLogService.logScorePublish`；
   - `ScoreService.revokeOne`：成功后调用 `auditLogService.logScoreRevoke`；
   - `AdminController.kickUser`：成功后调用 `auditLogService.logUserKick`。
4. 旁路安全原则：
   - `AuditLogService.record` 既有 `try-catch` 捕获全部持久化异常并打 `log.error`，审计失败绝不阻断或回滚主业务事务。

## 3. 变异验证预设（三项必检闭环）

1. **变异 m1（移除考试发布审计）**：
   - 注入点：移除 `ExamService.publish` 中的 `auditLogService.logExamPublish` 调用；
   - 预期击红：测试断言「发布考试后产生一条 action=EXAM_PUBLISH 的 audit_log 记录」断言级失败。
2. **变异 m2（移除成绩撤回审计）**：
   - 注入点：移除 `ScoreService.revokeOne` 中的 `auditLogService.logScoreRevoke` 调用；
   - 预期击红：测试断言「撤回成绩后产生一条 action=SCORE_REVOKE 的 audit_log 记录且包含撤回原因」断言级失败。
3. **变异 m3（移除用户强制下线审计）**：
   - 注入点：移除 `AdminController.kickUser` 中的 `auditLogService.logUserKick` 调用；
   - 预期击红：测试断言「管理员踢人后产生一条 action=USER_KICK 的 audit_log 记录且目标为该 userId」断言级失败。

## 4. 门禁与交付基线
- 后端门禁：`./mvnw.cmd clean test` 全绿，上调 `gate-baseline.json`；
- 前端门禁：`pnpm test` 939 例全绿；
- 契约：100 paths 保持不变。
