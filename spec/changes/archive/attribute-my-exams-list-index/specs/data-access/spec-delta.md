# spec-delta：data-access「索引变更先行核查」MODIFIED（attribute-my-exams-list-index）

> 变更：`attribute-my-exams-list-index`。
> 裁决结果：**GO**（采纳 A1 臂 `idx_submissions_student (student_id)`；未采纳 C1 臂）。
> 实施提交：`02d933e`。

## MODIFIED Requirement: 索引变更先行核查

WHEN 为查询新增索引,

系统 SHALL 先核对该查询所需列组合是否已被既有索引覆盖，避免新增重复索引；且新增性能类索引 SHALL 在真实数据库引擎上以两形状多臂轮转测量进行实测归因，经确定性扫描行数比（收益倍数）与时间侧无回归、热写写放大检验后方可采纳，并 SHALL 遵循最小充分索引优先原则。

#### Scenario: 重复索引被拦下

GIVEN 拟新增的复合索引与既有索引列组合完全相同

WHEN 评审该变更

THEN 判定为重复索引，不新增

AND 只记录写放大成本而无读取收益

#### Scenario: 索引引入须先归因后裁决且最小充分优先

GIVEN 线上查询因结构上缺失打头索引而面临全表扫描风险（如 `exam_submissions` 按 `student_id` 单列查询）

WHEN 评估是否新增索引

THEN 必须先在真实引擎上测量基线代价（事实认定），且仅在基线证实为性能瓶颈（最快轮耗时超过冻结门槛）时才进入候选臂比对

AND 候选索引必须通过确定性扫描行数比（收益倍数）与时间侧无回归检验，并经热写批测量证实写放大在噪声带阈值内

AND 选臂遵循最小充分索引优先原则：单列索引已充分满足时，不得采用包含冗余列的复合索引

AND 若测量表明小表基线耗时未达瓶颈门槛，则该索引不予采用、不增加写放大开销
