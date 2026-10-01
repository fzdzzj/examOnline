# 提案：进入考试的班级归属准入强校验（安全收口 F-B/F-C）

> 状态：**GO，已归档（2026-10-01）**。实施笔 `aa3636a`；门禁 @ 该 revision 仓库根 `mvnw.cmd clean test` → 349/0/0/1 BUILD SUCCESS 退出码 0。
> 开工锚：分支 `feature/update-monitor-overview-submission-projection`，HEAD = `37831c3`，基线四计数 345/0/0/1（Skipped 1 = `com.exam.support.OpenApiContractTest`）。

## Why

`ExamTakingService.enter(examId)` 的准入校验存在两个安全缺陷：

1. **F-C（普通考试无班级归属校验）**：`enter` 仅在补考时校验候选人名单；普通考试（`parent_exam_id == null`）**完全不校验班级归属**。即便考试绑定班级 A，班级 B 的学生只要拿到 `examId`，也能通过 `enter()` 成功开考并生成答卷——「列表看不见但接口进得去」的越权口子。
2. **F-B（无班级考试被无条件放行）**：`class_id == null` 的已发布普通考试同样被无条件放行，任何学生拿到 `examId` 即可进入。

两项均为读码复核的现行为事实（现 `enter` 对普通考试无任何归属判据），非制造缺陷。

## What Changes（只动「进入考试准入」这一类）

1. **首次进入才做准入校验**：`submission == null`（本人尚无本场答卷）时执行严格准入拦截；`submission != null`（在考中或已交卷）直接放行进入答题上下文——断线重连/刷新恢复必须放行，**事后转班不得把在考学生挡在门外**。
2. **普通考试班级归属强校验**：
   - `exam.getClassId() == null` → 抛 403 Forbidden（「该考试未指派班级，无法参加」）；
   - `exam.getClassId() != null` → 查 `user_class`，学生当前必须属于该 `classId`；否则抛 403 Forbidden（「您不属于该考试指定的班级，无法参加」）。
3. **补考维持既有名单限制**：`parent_exam_id != null` 时沿用 `makeupService.assertCanEnter(exam, studentId)`。
4. **保持不动**：考试实体/状态机/发布语义、`myExams` 列表取数、`current`/`saveDraft`/`reportBehavior`/交卷链路、鉴权与限流、`schema.sql` 与数据库表结构、缓存/JVM/线程池/MQ/前端零改动；openapi 契约形状不变。

## Impact

- **规范**：`spec/specs/exam-taking/spec.md` 的「进入考试」Requirement 合入准入条款（MODIFIED）+ 5 个新增 Scenario（本目录 `specs/exam-taking/spec-delta.md`）。
- **代码**：仅 `ExamTakingService.enter` 与其新增私有方法 `assertAdmission`；受影响既有集成测试按新口径补真实班级夹具（断言不削弱、语义不变）；新增专项准入用例。
- **用户/API**：无新增端点、响应形状不变；普通考试的未指派班级/非本班学生由「可进入」变为「403 拒绝」。
- **数据与部署**：不改 `schema.sql`、迁移、索引、JVM、线程池或 Redis/MQ；不写共享 dev、不 push、不建 PR。

## 验收与停止条件

- 门禁为仓库根 `mvnw.cmd clean test`；记录四计数、BUILD、退出码与 revision。四计数须满足 345 → 345+k、0 Failures、0 Errors、Skipped 恒为 1。
- 既有集成测试适配仅允许在夹具层补建班级/入班，**不得削弱任何原有断言**（防作弊、交卷幂等、状态机等语义不变）。
- 停手：前置核对不符、既有用例因夹具适配失败且根因不可在夹具层消解、需要改 schema 或前端才成立——停手保留现场并回报。