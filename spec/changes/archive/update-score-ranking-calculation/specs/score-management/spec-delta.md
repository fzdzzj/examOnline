# 规范差异：score-management（排名计算等价提速）

> 目标基线：`spec/specs/score-management/spec.md`。本 delta 为**有条件拟合入**草案：只有本变更的同负载归因、语义验证和改后验收满足时才合入；仅有 O(n²) 静态证据或定向单测通过不能提前合入。性能归因通则已在 `spec/specs/performance/spec.md`，此处不复制第二份。

## MODIFIED Requirements

### Requirement: 成绩排名

WHEN 系统计算排名,

系统 SHALL 按总分排序，同分 SHALL 并列同名次。在同负载测量证明排名计算是该路径占比最高的可控因素且批准实施计算优化时，排名计算 SHALL 在维持原结果的前提下避免逐份答卷再次遍历全班的 O(n²) 比较，比较次数增长 SHALL 不超过 O(n log n)。仅凭静态复杂度 SHALL NOT 宣称端到端请求已经加速，改后须按同负载验收。

#### Scenario: 并列同名次

GIVEN 两名学生总分相同

WHEN 计算排名

THEN 两人并列同一名次

AND 后续名次跳空（1,2,2,4）

#### Scenario: 空值与数值等价不改变口径

GIVEN 成绩列表中包含未汇总的 null 分数、数值相等但 scale 不同的 BigDecimal 分数以及不同分数

WHEN 排名计算返回与原入参等长的结果

THEN null 项名次为 0 且不影响其他名次

AND 以 BigDecimal.compareTo 的数值比较决定并列，同分后的名次按人数跳空

AND 各项名次仍对应原入参位置；空输入返回空结果

#### Scenario: 归因成立才替换计算结构

GIVEN 固定考试规模和分数分布下已记录请求延迟、吞吐、资源与 SQL/排名计算分段耗时

AND 测量确认排名计算为该路径占比最高的可控因素

WHEN 对纯排名计算实施优化

THEN 旧二重比较实现在确定性比较次数护栏下失败，新实现通过

AND 同一数据与负载下分别报告计算指标与端到端指标，不把 SQL 取数或交卷路径的变化记作本次收益

#### Scenario: 缺少归因或改后无收益

GIVEN 测量不可用、SQL 等其他因素主导，或改后同口径指标未改善

WHEN 评估是否合入本变更

THEN 不宣称排名计算已解决请求瓶颈

AND 不为变绿顺手修改 SQL、缓存、JVM 或线程池；拟实施条款不作为已实现规范合入

---
