# NOTES — add-exam-operation-audit-logs 实施备忘

## 设计裁决

1. **IP 解析复用 `ClientIpResolver`（anticheat/ip）**：提案写「RequestContextHolder 自动提取客户端 IP」，
   提取落在 `AuditLogService.currentClientIp()`——RequestContextHolder 只负责拿请求现场，
   解析本身复用防作弊域已有的 X-Real-IP 优先逻辑，不另起一套会漂移的副本。
2. **打点位置一律在操作成功之后、事务之内**：五处打点全部位于状态写成功路径；
   审计经既有 `record()` 的 try-catch 旁路，持久化异常只打 ERROR，绝不回滚主业务事务
   （单元侧 `persistenceFailureNeverPropagatesOutOfAnyOperation` + 集成侧真实链路共同守护）。
3. **幂等跳过不打点**：`ScoreService.doPublishOne` 的「已发布（幂等跳过）」分支不产生
   SCORE_PUBLISH 审计——审计记的是真实发生的状态迁移，不是请求。
4. **details 机器可读**：统一 `键=值` 逗号分隔（examId=/title=/count=/reason=/targetUserId=），
   集成测试按 details LIKE 精确定位本用例的审计行（H2 数据跨用例保留，不数全表）。

## 实施中发现的坑（供后续案例引用）

- **Mockito + 字段初始化器构造被测服务 = 整体假红**：`AuditLogServiceExamOperationTest`
  最初用 `private final AuditLogService svc = new AuditLogService(auditLogMapper)` 在字段初始化器里
  构造，此时 MockitoExtension 尚未注入 `@Mock`，服务拿到 null mapper；其 NPE 又被
  `record()` 的旁路 try-catch 吞掉（旁路语义反而被意外实证了一次），全部用例报
  「Wanted but not invoked / zero interactions」。改为 `@BeforeEach` 构造后消失。
  结论：被测服务必须在 `@BeforeEach` 里构造，不能依赖字段初始化顺序。

## 验证事实（2026-10-18，分支 feature/add-exam-operation-audit-logs）

- 实施笔 `341d51f`（8 文件 449+/6-，源 5 + 测试 3）。
- TDD：41 例 5 断言级红（接线缺失期）→ 接线后 41/41 绿。
- 变异：m1/m2/m3 各自 1 断言级红、还原回绿、`git checkout HEAD --` 还原后零残留。
- 后端门禁：`mvnw clean test` 733/0/0/1（722→733，+11 = 单测 7 + 集成 3 + ScoreServiceTest 1）。
- 前端门禁：lint:check / type-check:check 零错、vitest 939/939（前端零改动持平）。
- gate-baseline.json：backendTests 722→733（同笔上调）。
