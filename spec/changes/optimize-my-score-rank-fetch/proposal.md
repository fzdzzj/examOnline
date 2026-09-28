# 提案：myScore 名次取数由「取回全班」改为一条聚合查询

> 状态：待审批；静态证据基于 2026-09-28 的 `bfa8082`。本提案不声称 myScore 已是请求延迟的主要瓶颈，也不声称隔离测量结果可外推生产。执行前必须重新核对 HEAD、工作树和现行规范。

## Why

上一刀 `update-score-ranking-calculation`（2026-09-26 归档）已把 `RankCalculator.rank` 从 O(n²) 改为 O(n log n)，并实测：改后请求内 rank 分账占比从 **68.4–71.8%** 降至 **4.176/4.840/4.360%**（n=3000 并发 1，三轮；证据 revision `cde1e15`，隔离 H2 + MockMvc 同进程），剩余热点由 JFR 栈顶帧定为 **MyBatis 反射结果映射（`slowVerifyAccess`/`applyAutomaticMappings`）与 H2 取数（`JdbcResultSet`）**——归档结论明确「属『其他因素』，未顺手改」。

`ScoreService.myScore` 为算一个名次仍执行 `gradingSubmissionMapper.selectList` 取回**全班已汇总答卷行**（n 行、全列，含逐行反射映射），随后 `rankCalculator.rank(全班)`、再按下标找本人——这是一次**取数 + 结果映射**形态的成本，随班级人数线性增长，而名次本身只需「严格更高分的人数」一个标量。候选改法：名次 = `COUNT(exam_id=? AND status=GRADED AND total_score IS NOT NULL AND total_score > 本人总分) + 1`，一条聚合，不再取回全班。

**已证实**的是归档实测的剩余热点（映射与取数）与代码取数形态；**未知**的是在本仓库当前 revision、同口径基线下「取数+映射」是否仍是 myScore 请求内占比最高的一类。按 `spec/specs/performance/spec.md`「Java 优化先归因、一次只改一类」：本变更阶段 2 必须先以同口径基线重新确认，确认不了就停，不得以归档结论直接授权改码。

**边界声明**：本刀只改 myScore 这一条路径的取数形态；发布预览（`publishPreview`）与导出（`ScoreExportService`）两条仍取全班的路径**不在本刀范围**（它们需要全班明细，不是同形态）。隔离 H2 结果**不外推生产 MySQL/Tomcat、不构成交卷 P99 收益**。

## What Changes

1. **阶段 2（先量后改）**：扩展 `src/test/java/com/exam/score/measure/RankAttributionMeasureIT.java`，在既有 rank 分账之外新增 myScore 请求内分账臂：①「全班取数 + 结果映射」（测试侧 MyBatis 拦截器按语句 id 分账）②「rank 计算」（既有计时包装器）③「其余（权限/考试/本人行/复核判定）」（墙钟减前两项）；同数据同负载同预热，SIZES 50/200/1000/3000、逐轮至少 3 次、不取单次最好值；同时记录每请求 SQL 条数与返回行数、堆峰值。停止条件：S1「取数+映射」不是占比最高的一类；S2 其占比未稳定高于轮间波动；S3 探针开销不可忽略（单列、不并入生产段）——命中即停手回报，不改任何生产代码。
2. **阶段 3（只改这一类）**：`myScore` 名次改为一条聚合 COUNT（不再取全班）；404 条件由「本人行是否落在全班 GRADED 集合里」的隐式判定改为**显式**判 `status`，且与现状逐条等价（无行 / totalScore 为 null / status 不是 GRADED → 「暂无本人成绩记录」）；`GradingSubmissionMapper` 仅按需新增方法（或复用 `selectCount`），不动既有 SQL。不加 `@DS("slave")`（成绩是强一致读）、不改复核隐藏分支、不改发布状态门、不改 preview 与导出、不改 schema.sql。等价性测试 E1–E8 先写、必须能红（E6 差分对拍、E7 取数护栏在旧实现上红灯）。
3. **阶段 4（同口径复测）**：同数据同负载逐轮 3 次复测，报告新旧 best/mean/p99 比值、SQL 条数与返回行数变化、堆峰值变化、映射段份额变化；指标无变化就如实写「无收益」并回到度量，不叠加别的参数。在已提交实现 revision 上跑唯一门禁 `./mvnw clean test`，按三要素回勾证据。
4. **收口**：GO 且等价才把 delta 合入 `spec/specs/score-management/spec.md`；NO-GO 或不等价按未采用草案归档、`src/main` 零改动。

## Impact

### 受影响的规范

- `spec/specs/score-management/spec.md`：「成绩排名」MODIFIED——保留既有并列/归因场景，新增「名次取数为聚合计数（行级取数不随班级人数增长）」「404 条件显式且逐条等价」两个场景。`spec/specs/performance/spec.md` 的归因门禁已存在，不重复新增。

### 受影响的代码

- `src/main/java/com/exam/score/service/ScoreService.java` 的 `myScore` 方法体；`src/main/java/com/exam/grading/mapper/GradingSubmissionMapper.java` 仅新增所需方法（不动既有）。
- 测试：`src/test/**` 新增/扩展（等价性 E1–E8、测量工具）。
- **不改**：`RankCalculator`（上一笔已收口）、`publishPreview`/`ScoreExportService` 两条路径、`schema.sql`、Mapper 既有 SQL、JVM/堆/GC/线程池/连接池参数、前端、OpenAPI。

### 用户影响

- 目标为原样返回同一成绩与名次（并列、跳号、复核隐藏、404 口径逐条不变）；是否有可观察的响应改善须以阶段 4 同口径复测为准。

### API 变更

- 无新增/变更端点、字段或错误码。

### 需要迁移

- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（仅在验收满足后合入 delta/归档）

## 时间线评估

小到中。阶段 2 基线不达标即停（不改生产代码）；达标后实施与测试为一刀窄改。

## 风险与停止条件

- **误归因**：归档的剩余热点是旧工具口径下的结论；若本变更阶段 2 基线中「取数+映射」不是占比最高的一类（S1）、或占比未稳定高于轮间波动（S2）、或探针开销不可忽略（S3），一律停手回报，不改任何生产代码。
- **语义退化**：名次改聚合可能因 BigDecimal scale/数值比较差异拆散并列，或 404 判定不等价。用 E1（1,2,2,4）、E2（60 与 60.00 数值等价，按入库后实际 scale 形态断言）、E6（逐学生对拍旧 `rankCalculator.rank` oracle，n 覆盖 1/2/50/3000）与显式 status 判定测试守住；E6/E7 在旧实现上跑不出红灯则停手，不得声称等价。
- **微基准假收益**：沿用归档方法学（同数据/同负载/同预热、逐轮 3 次、不取单次最好值、探针开销单列）；隔离 H2 结果不外推生产 MySQL/Tomcat。
- **范围蔓延**：一次只改「取数+映射」这一类；不顺手改 SQL/索引/缓存/JVM/线程池/其他候选；本变更不能冒充交卷 P99 修复。不写共享 dev、不启 Docker、不跑压测。
