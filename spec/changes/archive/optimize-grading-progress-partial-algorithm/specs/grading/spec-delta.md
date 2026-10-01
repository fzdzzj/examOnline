# 规范差异：grading（判分进度部分批改计数）

## ADDED Requirements

### Requirement: 判分进度部分批改计数

WHEN 教师查看判分进度总览,

系统 SHALL 以「存在未批简答（`subjective_grades.score IS NULL`）的已交卷答卷数」计算部分批改答卷数；该计数 SHALL 以「先归集存在未批简答的答卷 ID 集合、再单遍过滤已交卷答卷」的方式求出，复杂度 SHALL 为 O(答卷数 + 主观行数)，而不是逐答卷嵌套扫描主观行的 O(答卷数 × 主观行数)。

#### Scenario: 部分批改计数与朴素判定等价

GIVEN 一场考试的已交卷答卷列表与主观批改行

WHEN 计算部分批改答卷数

THEN 结果等于「存在至少一行 `submission_id` 匹配且 `score IS NULL` 的答卷数」

AND 空答卷列表、空主观行列表、score 全非空、score 全为 NULL、部分未批、同一答卷多道简答混合批改状态、行属于非本批答卷、答卷列表含重复项等形态下，均与逐答卷嵌套扫描的朴素判定逐点一致

AND 同一答卷多道简答中只要有一道未批即计 1 次（按答卷计，不按行计）

AND 主观行 `submission_id` 为 NULL 属越界输入、不在保证域内（库内不可达：`schema.sql` 对 `subjective_grades.submission_id` 声明 `BIGINT NOT NULL`）；该输入下朴素判定抛 NPE、集合判定返回计数值，差异如实登记而非冒称等价

> 合入注记（收口时补全）：实施门禁 revision 与四计数、等价性测试覆盖项与规模、已披露边界。