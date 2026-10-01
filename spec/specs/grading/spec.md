# grading 规范

> 能力域：判分（阶段 6，W7）。
> 来源：`spec/changes/add-grading-score` 合入（判分策略、客观题判分、简答批改、判分失败处理）。

## Requirements

### Requirement: 判分策略

WHEN 系统判分,

系统 SHALL 按题型选择对应判分策略；单选、多选、判断、简答 SHALL 各有独立实现，新增题型 SHALL 不改变既有核心。

#### Scenario: 按题型分派

GIVEN 一道单选题

WHEN 系统判分

THEN 调用单选判分策略

AND 与标准答案精确比对

---

### Requirement: 客观题判分

WHEN 系统判客观题,

系统 SHALL 单选/判断精确匹配，多选漏选 SHALL 给部分分、错选或多选 SHALL 给 0 分。

#### Scenario: 单选正确

GIVEN 学生答案与标准答案一致

WHEN 判分

THEN 判满分

#### Scenario: 多选漏选部分分

GIVEN 多选标准答案为 A、B、C

AND 学生只选 A、B

WHEN 判分

THEN 按配置比例给部分分

#### Scenario: 多选错选零分

GIVEN 学生答案含非标准选项

WHEN 判分

THEN 判 0 分

---

### Requirement: 简答批改

WHEN 系统判简答题,

系统 SHALL 以关键词初判提供提示分，最终分数 SHALL 由教师人工批改确定，且 SHALL 保留批改痕迹。整场判分时，已有主观批改行 SHALL 按本场答卷一次取出，而不是每份答卷每道简答各查一次；重判 SHALL NOT 覆盖教师终分、评语与 version。

#### Scenario: 教师批改留痕

GIVEN 教师批改一道简答题

WHEN 教师提交分数与评语

THEN 系统保存分数、评语、批改人与批改时间

#### Scenario: 并发批改防覆盖

GIVEN 两教师同时批改同一答卷

WHEN 乐观锁执行

THEN 仅一个成功

AND 另一个冲突重载或报错

#### Scenario: 整场判分主观行一次取出

GIVEN 同一场有多份已交卷答卷且卷面含多道简答题

WHEN 教师触发整场判分

THEN 这些答卷的已有主观批改行在进入逐份写入之前一次取出

AND 刷新初判提示分时不覆盖已有终分、评语与 version

AND 一份答卷判分失败仍只标记该份，不影响其他答卷

---

### Requirement: 判分失败处理

WHEN 判分失败,

系统 SHALL 标记判分失败，并 SHALL 支持重判或手动给分，不影响其他答卷。

#### Scenario: 标记失败可重判

GIVEN 判分过程异常

WHEN 系统检测到失败

THEN 标记该答卷判分失败

AND 教师可触发重判或手动给分

---

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

> 合入注记（2026-10-01，`optimize-grading-progress-partial-algorithm`，整卡 **GO**）：判分进度 `GradingQueryService.progress` 的「部分批改答卷数」由逐答卷嵌套扫描主观行的 O(n·m) 改为「先归集存在未批简答的 `submissionId` 集合、再单遍过滤已交卷答卷」的 O(n+m)，仅动该计数块与其 import（两条 `selectList` 语句、其余计数与其他业务类零改动）。等价性由新增常驻测试 `GradingProgressAlgorithmTest`（15 例：随机 400 组固定种子 + 卡面规模 n=3000/m=6000 + 空答卷/空行/全批/全未批/部分/同卷多题混合/行属他卷/答卷列表重复/答卷项 id 为 null 等边界，以改动前原文为 oracle 逐点比对，并驱动真实端点断言 `partialGradedCount` 等于 oracle）承担。**口径修正（如实登记）**：卡面「包含 null 键 100% 等价」不成立——主观行 `submission_id` 为 null 时旧写法抛 NPE、新写法返回值；该输入库内不可达（`subjective_grades.submission_id BIGINT NOT NULL`），故两者在可达域上逐点等价、在越界输入上不等价，用例 `nullRowKeyIsOutOfDomainDivergence` 已固化该差异。**验收边界**＝仓库根 `mvnw.cmd clean test`（`JAVA_HOME=D:\develop1\jdk21`）@ 实施笔 `5d089c9` → 345/0/0/1 BUILD SUCCESS 退出码 0（基线 330→345，Skipped 1 恒为 `com.exam.support.OpenApiContractTest`）；H2 测试上下文 + Mockito 隔离单测，**未做真机性能测量**，不构成 P99 结论。