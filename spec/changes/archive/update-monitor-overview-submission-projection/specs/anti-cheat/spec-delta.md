# anti-cheat 规范差异：监考大屏答卷取数边界

> 待审批、仅在隔离同负载归因 GO 且实施/复测通过后合入；NO-GO 时本 delta 为未采用草案。其余 Requirement 不变。以下 MODIFIED 包含原有 Requirement 的完整语句与两个既有场景。

## MODIFIED Requirements

### Requirement: 监考大屏

WHEN 教师进入监考大屏,

系统 SHALL 展示在线/离线/已交卷人数、答题进度，并 SHALL 高亮异常行为。WHEN 同负载归因确认答卷长字段读取是总览请求占比最高的可控因素且投影有净收益时，系统 SHALL 只为逐人状态读取所需的答卷短字段，并 SHALL 至多独立读取一份个人快照计算题目总数，而 SHALL NOT 为每份答卷载入 `paper_json` 和 `answers`；所有原有权限、近似进度与异常高亮口径 SHALL 保持不变。

#### Scenario: 实时状态展示

GIVEN 考试进行中有学生在线/离线/已交卷

WHEN 教师打开监考大屏

THEN 系统展示各类人数与进度

AND 异常行为学生被高亮

---

#### Scenario: 查看异常详情

GIVEN 监考大屏高亮某学生异常

WHEN 教师点击该学生

THEN 系统展示该学生行为时间线

---

#### Scenario: 只在需要时读取个人快照

GIVEN 同负载测量确认答卷长字段读取是主要可控成本，且本轮只优化该数据库取数

WHEN 有权限的教师轮询考试总览

THEN 逐学生列表的答卷主查询不取所有学生的 `paper_json` 和 `answers`

AND 题目总数仍基于个人快照，读取个人快照的额外查询至多一次，不按学生回查

AND 没有非空快照或所选快照损坏时，题目总数与进度的既有降级语义不变

AND 在线/离线/已交卷计数、草稿近似进度、异常高亮及排序、教师归属校验和响应形状不变
