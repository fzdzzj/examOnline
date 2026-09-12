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

系统 SHALL 以关键词初判提供提示分，最终分数 SHALL 由教师人工批改确定，且 SHALL 保留批改痕迹。

#### Scenario: 教师批改留痕

GIVEN 教师批改一道简答题

WHEN 教师提交分数与评语

THEN 系统保存分数、评语、批改人与批改时间

#### Scenario: 并发批改防覆盖

GIVEN 两教师同时批改同一答卷

WHEN 乐观锁执行

THEN 仅一个成功

AND 另一个冲突重载或报错

---

### Requirement: 判分失败处理

WHEN 判分失败,

系统 SHALL 标记判分失败，并 SHALL 支持重判或手动给分，不影响其他答卷。

#### Scenario: 标记失败可重判

GIVEN 判分过程异常

WHEN 系统检测到失败

THEN 标记该答卷判分失败

AND 教师可触发重判或手动给分
