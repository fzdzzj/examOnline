# 提案：兜底扫描候选行列投影（project-sweep-candidates-scalar-projection）

> 状态：执行中（护栏先红后绿、仅动读形态）。开工静态核对基于 `586a365`（分支 `feature/update-monitor-overview-submission-projection`，2026-10-01）。
> 一次只改一类：本卡只改 `selectForceSubmitCandidates` 的 SELECT 列集；谓词、JOIN_INDEX 提示、LIMIT、索引、schema、缓存、写语句一律零改动。

## Why

交卷链路定时兜底扫描（`ExamSweepService`，默认每 10 秒一轮、单轮分批 500 × 最多 10 轮）的候选行取数 `selectForceSubmitCandidates` 当前为 `s.*` 全列读取——整行含 `paper_json`（进入时洗牌锁定的个人快照长字段）与 `answers`。机械归因表明该语句**全仓唯一调用方** `ExamSweepService.forceSubmitOverdue`（`src/main/java/com/exam/taking/service/ExamSweepService.java` grep 锚 `selectForceSubmitCandidates`）对返回实体只消费两个 getter：`getExamId()` 与 `getStudentId()`（随后 `forceSubmitByBackend` 按 `(examId, studentId)` 重新定位答卷），长字段纯属无谓传输与实体反序列化。

收益集中在大场面收卷时刻（考试结束时大量学生同时超时、单轮命中成批候选），恰是系统压力峰值；空闲轮扫描零命中、不受影响。

**与三臂测量卡的边界（如实登记）**：本卡不设 OLD/PROJ/OLDrep 三臂容器测量。理由：本卡是已归档变更 `project-grading-score-scalar-projection` 确立的「列投影 + 常驻护栏」形态在 `exam_submissions` 上的直接应用；消费字段经机械归因唯一且确定（单调用方、两个 getter、下游经 `(examId, studentId)` 重定位），不存在需要测量裁决的 GO/NO-GO 不确定点；字节收益以判据表述（长字段退出 SELECT 列表＝每行必省 `paper_json` 全量，机械事实），不写死 KB 数值。时间侧该语句已有归档实测（grep 锚 `JOIN_INDEX`：提示优化 41–50ms → 1.7–2.7ms@忙轮），本卡不改变执行计划，仅减传输。

## What Changes

1. **冻结列集**：`s.exam_id, s.student_id`（机械归因产物，一字不差）。
2. **实施**：`ExamSubmissionMapper.selectForceSubmitCandidates` 的 `s.*` 改为冻结列集；JOIN_INDEX 提示、谓词（`s.status = 1 AND (s.deadline_time < ? OR e.status IN (2, 3))`）、JOIN、LIMIT 全部原样保留；方法 javadoc 同步登记投影与消费方。
3. **常驻护栏**：新增 `SweepCandidatesProjectionGuardTest`（`com.exam.scalar.guard`），先红后绿：Executor 层拦截目标语句，断言 ① 捕获 SELECT 列表 == 冻结列集（剥优化器注释后规范化）；② 返回实体的 `paperJson` 必为 null，而 seed 前置断言库内同批行该列非 null（排除「库本来就空」的伪绿）；③ 下游消费的 `examId`/`studentId` 照常载入。
4. **收口**：delta 合入 `spec/specs/data-access/spec.md` 既有 Requirement「标量读取的列投影」（追加 Scenario + 尾部合入注记）；`spec/README.md` 当前状态行 + 归档行；变更目录整体移入 `spec/changes/archive/`。

## Impact

- **规范**：验收通过时 `spec/specs/data-access/spec.md`「标量读取的列投影」Requirement 追加 Scenario（本目录 `specs/data-access-delta.md` 为候选）。
- **代码**：仅改 `ExamSubmissionMapper.java` 目标语句 SELECT 列集 + 新增护栏测试。既有端到端回归 `MultiInstanceSweepSafetyTest`（经 `sweepService.sweep()` 走真实扫描→强制交卷→MQ 桩全链路）作为回归基线，断言不放宽。
- **用户/API**：不新增端点、不改响应契约；兜底强制交卷行为与幂等语义不变（三路竞态 CAS、消费端 `casFillAnswers` 幂等不受影响）。
- **数据与部署**：不改 `schema.sql`、迁移、索引、JVM、线程池、Redis；不写共享 dev、不碰 sport-verify-* 容器。

## 验收与停止条件

- 护栏必须**先红后绿**：实施前跑一次留红色原文（`evidence/guard-red.log`），实施后转绿（`evidence/guard-green.log`）；红的原因必须是「实体 paperJson 非 null / SELECT 列表偏离冻结列集」，不得是装配错误。
- 冻结列集 `s.exam_id, s.student_id` 一字不差；实施若需动谓词/提示/LIMIT/索引/写语句即超出本卡授权，停手。
- 门禁为仓库根 `mvnw.cmd clean test`；基线锚 349/0/0/1（`aa3636a` 实测，交接文档 2026-10-01），新增常驻护栏 1 例后精确账目 349→350、Skipped 仍为 1；`MultiInstanceSweepSafetyTest` 两用例必须随全量门禁通过。
- 两笔提交严格划分：实施笔（mapper + 护栏）在已提交状态跑门禁；收口笔（spec/README/归档）须有 `git diff --name-only <实施笔> HEAD -- src pom.xml` 空输出的零 src 豁免凭据。
