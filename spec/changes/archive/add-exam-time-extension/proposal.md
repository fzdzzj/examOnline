# 提案：考中应急顺延与个性化补时（add-exam-time-extension）

> **变更 ID**：`add-exam-time-extension`  
> **能力域**：`exam-management`  
> **级别**：S 级考务特性加固（纯后端 + API 契约）  
> **前置依赖**：`harden-register-rate-limit`（已合入 main）  
> **影响面**：`ExamController`、`ExamService`、`ExamSubmissionMapper`、`AuditLog`、`AuditLogService`、`ExamLifecycleNotificationService`、`openapi.yaml`、`gate-baseline.json`

---

## 1. 为什么做（Why）

- **业务痛点**：在真实考务场景中，突发事件屡见不鲜（例如机房突发断电 5~10 分钟、校园网大面积抖动、偶发考生个人电脑死机重启或设备故障）。
- **现状缺陷**：
  1. 当前系统考试一旦开始（`status = 1`），`exams.end_time` 和 `duration_minutes` 被锁定，且学生进入考试时答卷 `deadline_time` 即被固定；
  2. 教师端没有任何接口可以为进行中的考试延长全场结束时间，也无法为因突发设备故障而丢失答题时间的指定考生单独补时；
  3. 若出现机房断电或网络波动，教师只能眼看着学生因倒计时归零被兜底任务强制交卷，造成重大考务教学事故。
- **治理目标**：新增考中应急顺延与个性化补时端点 `POST /api/exams/{id}/extend-time`，支持教师在考试进行中灵活应对突发故障（全场顺延 vs 个人精准补时），联动安全审计与站内消息旁路通知，保障考务稳定性。

---

## 2. 改什么（What）

1. **接口与 DTO 设计**：
   - 新增请求模型 `ExamTimeExtensionRequest`：
     - `extensionMinutes`：`@NotNull`, `@Min(1)`, `@Max(240)`（单次补时 1~240 分钟）；
     - `studentIds`：可选 `List<Long>`（为 null 或空集合表示「全场顺延」；指定时表示「个人补时」）；
     - `reason`：`@Size(max = 255)`，顺延/补时原因（如“机房突发断电应急顺延”）。
   - 新增响应模型 `ExamTimeExtensionResponse`：返回 `examId`、`extensionMinutes`、`isGlobal`、`affectedSubmissionsCount`（实际受影响在途答卷数）、`newEndTime`、`newDurationMinutes`。
   - `ExamController` 暴露端点 `POST /api/exams/{id}/extend-time`（权限 `@RequirePermission("exam:manage")`，受教师归属权校验保护）。

2. **双模式服务层逻辑（ExamService & ExamSubmissionMapper）**：
   - **状态前置门控**：仅允许对处于「进行中」（`status = 1`）的考试执行顺延或补时，其余状态（未开始、已结束等）一律拦截并返回 400 `BAD_REQUEST`。
   - **模式 A：全场顺延（Global Extension，`studentIds == null || studentIds.isEmpty()`）**：
     - 更新 `exams` 主表：`end_time = end_time.plusMinutes(minutes)`，`duration_minutes = duration_minutes + minutes`；
     - 清除考试详情缓存：`@CacheEvict(cacheNames = CacheConfig.CACHE_EXAM_DETAIL, key = "#id")`；
     - 批量顺延在途答卷：`ExamSubmissionMapper` 批量更新该考试下所有 `status = 1`（进行中）答卷的 `deadline_time = TIMESTAMPADD(MINUTE, #{minutes}, deadline_time)`；
     - 后续未入场考生保障：未进入考试的学生后续进入时，自然基于新的 `end_time` 与 `duration_minutes` 算出延后的截止时间。
   - **模式 B：个人补时（Individual Compensation，`studentIds != null && !studentIds.isEmpty()`）**：
     - `exams` 主表保持不变（不影响全场时间窗）；
     - 仅对指定考生的在途答卷更新：批量更新 `exam_id = #{examId} AND status = 1 AND student_id IN (...)` 的答卷 `deadline_time = TIMESTAMPADD(MINUTE, #{minutes}, deadline_time)`。

3. **数据库兼容性与原子更新**：
   - 使用 SQL 标准函数 `TIMESTAMPADD(MINUTE, #{minutes}, deadline_time)`，在 MySQL 8 与 H2 2.x（测试库）下完全原生兼容且可参数化执行。

4. **审计日志与站内通知联动**：
   - `AuditLog` 扩充动作常量 `EXAM_EXTEND_TIME`（16 字符，兼容 `VARCHAR(32)`，零 DDL）；
   - `AuditLogService` 增加 `logExamExtendTime`，包含考试 ID、标题、顺延时长、模式、影响人次与原因，旁路容错绝不阻断主业务；
   - `ExamLifecycleNotificationService` 增加 `notifyExamTimeExtended`，向受影响考生投递消息中心站内通知，统一走既有旁路降级（try-catch 吞异常）。

5. **API 契约同笔更新**：
   - [`openapi.yaml`](file:///d:/code/examOnline/openapi.yaml) 登记 `POST /api/exams/{id}/extend-time` 接口定义，路径总数 101 → 102；
   - [`.github/ci/gate-baseline.json`](file:///d:/code/examOnline/.github/ci/gate-baseline.json) 同笔上调 `openapiPaths`（101 → 102）。

---

## 3. 约束与红线（Guardrails）

- **零 DDL**：`audit_log.action` 原生为 `VARCHAR(32)`，本次扩充 16 字符常量；`exams` 与 `exam_submissions` 字段完备，严禁修改 `schema.sql`，严禁新增数据库迁移脚本；
- **H2 / MySQL 双兼容**：时间累加使用跨库兼容的标准 SQL 函数 `TIMESTAMPADD`，严禁使用 H2 不支持的非标语法；
- **旁路降级一致性**：审计日志与站内通知均作为旁路服务，其失败绝不得导致考试加时主事务回滚；
- **双端门禁与 TDD 纪律**：新增单测与集成测试必须先红后绿，变异验证破坏核心逻辑（如抽除状态校验、去答卷批量更新等）必须断言级击红并回绿留证。
