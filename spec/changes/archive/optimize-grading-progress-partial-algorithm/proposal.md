# 提案：progress 部分批改计数单遍哈希优化（C3 候选）

> 状态：执行中。静态核对基于 `1b00673`（分支 `feature/update-monitor-overview-submission-projection`，2026-10-01）。
> 一次只改一类：本卡只改 `GradingQueryService.progress()` 中「部分批改计数」的流计算逻辑；SQL 查询、Mapper、`schema.sql`、DTO 字段、控制器、前端及其他业务类零改动。

## Why

`GradingQueryService.progress`（判分进度总览，`@DS("slave")` 只读端点）当前对「部分批改答卷数」用嵌套流计算：

```java
long partial = submissions.stream().filter(submission ->
        subjectiveRows.stream().anyMatch(row -> row.getSubmissionId().equals(submission.getId())
                && row.getScore() == null)).count();
```

n=答卷数、m=主观行数，最坏 O(n*m)。卡面量级锚 n=3000、m=6000 → 最坏约 18,000,000 次嵌套匹配，每行还伴随一次 `Long` 装箱相等比较，构成纯 CPU 热点；不随数据规模亚线性下降。

## What Changes

1. 把上述嵌套匹配改为单遍哈希：先对 `subjectiveRows` 过滤 `score == null` 收集「存在未批主观题的 `submissionId` 集合」，再单遍 `filter(submissions)` 用 `Set.contains` 判定；复杂度由 O(n*m) 降为 O(n+m)。仅动这一段流计算，其余字段统计与查询语句不变。
2. 新增等价性测试 `GradingProgressAlgorithmTest`：以「改动前原文」为参考实现（oracle），对随机规模与边界形状逐点断言新旧两种流计算对 partial 计数完全一致；另驱动真实 `progress()` 端点，断言其 `partialGradedCount` 等于参考实现。

## 语义与边界

覆盖并断言一致的形态：空答卷列表、空主观行列表、score 全非空、score 全为 null、部分未批、同一答卷多道简答混合批改状态（按答卷计 1 次而非按行）、行属于非本批答卷、答卷列表含重复项、答卷项 `id == null`。另含随机规模（400 组固定种子）与卡面规模 n=3000/m=6000。

**已披露的口径修正（前提更正）**：卡面要求「包含 null 键 100% 等价」，实测不成立。主观行 `submission_id == null` 时，改动前写法 `row.getSubmissionId().equals(...)` 会抛 NPE（`&&` 左操作数先求值，故与 `score` 是否为空无关），而单遍哈希对 null 键是容忍的、返回计数值——两者在越界输入上不等价。该输入在库内**不可达**：`src/main/resources/schema.sql` 对 `subjective_grades.submission_id` 声明 `BIGINT NOT NULL`。故结论是：**两者在可达域上逐点等价；越界输入（主观行 null 键）上旧抛 NPE、新返回值**。该差异已固化为测试用例 `nullRowKeyIsOutOfDomainDivergence`（断言旧抛 NPE、新返回 0、真实端点同新），不隐瞒、不冒称等价。

## Impact

- **代码**：仅 `src/main/java/com/exam/grading/service/GradingQueryService.java` 的 import（新增 `java.util.Set`、`java.util.stream.Collectors`）与该计数块；`submittedCount`/`gradedCount`/`failedCount`/`pendingCount`/`subjectiveTotal`/`subjectiveGraded` 与两条 `selectList` 语句一律不改。
- **规范**：`spec/specs/grading/spec.md` 追加「判分进度部分批改计数」Requirement（含等价口径与已披露边界），delta 见本目录 `specs/grading/spec-delta.md`。
- **用户/API**：不新增端点、不改响应契约或用户可见口径。
- **数据与部署**：不改 `schema.sql`、迁移、索引、JVM/线程池/连接池/MQ；零远程操作（不 push / 不 fetch / 不建 PR）。

## 验收与停止条件

- 门禁＝仓库根 `mvnw.cmd clean test`（`JAVA_HOME=D:\develop1\jdk21`）；基线锚 330/0/0/1（Skipped 1 = `com.exam.support.OpenApiContractTest`），新增 15 例后精确账目 330→345、Skipped 恒为 1。
- 两笔提交严格划分：提交 1＝实施（`GradingQueryService.java` + `GradingProgressAlgorithmTest.java`）；提交 2＝收口（`spec/specs/grading/spec.md` + `spec/README.md` + 变更目录归入 `archive/`）。收口笔零 `src` 改动，以 `git diff --name-only` 空输出为豁免凭据。
- 任何需要动 SQL / 索引 / 缓存 / 写语句 / 其他业务类的改动即超出本卡授权，停手。