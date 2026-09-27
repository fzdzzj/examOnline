# score-management 规范

> 能力域：成绩管理（阶段 6，W7）。
> 来源：`spec/changes/add-grading-score` 合入（成绩汇总、发布/撤回、排名、导出）。

## Requirements

### Requirement: 成绩汇总

WHEN 教师对已结束考试执行成绩汇总,

系统 SHALL 在本次纳入汇总的答卷均已完成判分且产生客观分（包括合法零分）时，汇总客观分与已批主观分得到总分；存在未判或判分失败答卷时 SHALL 在任何成绩与考试状态写入前拒绝；未批主观题仍按既有“部分批改”规则处理。主观分 SHALL 按本场答卷一次取出，而不是每份答卷各查一次。

#### Scenario: 加权汇总

GIVEN 已交卷答卷的判分状态成功且客观分已产生

WHEN 系统汇总

THEN 总分 = 客观分 + 已批主观分

#### Scenario: 判分未运行或失败则整场拒绝

GIVEN 本次待汇总答卷中至少一份未判分、判分失败或客观分未产生

WHEN 教师请求汇总

THEN 系统返回可观察的判分前置条件错误

AND 不写任何答卷总分或部分批改标记

AND 不推进考试至已批改状态

#### Scenario: 合法客观零分允许汇总

GIVEN 一份纯主观试卷已完成判分或教师已手动裁定客观零分

AND 答卷判分状态成功、客观分明确为零

WHEN 教师汇总

THEN 允许汇总并按实际已批主观分计算总分

AND 不将合法零分误判为“未判分”

#### Scenario: 主观未批完仍可部分汇总

GIVEN 客观判分已完成且试卷中仍有未批主观题

WHEN 教师汇总

THEN 未批主观题按零分计入

AND 整体标记部分批改，保持 `docs/需求决策记录.md` §7.5 的发布与复核语义

#### Scenario: 主观分一次取出

GIVEN 同一场有多份待汇总答卷且卷面含主观题

WHEN 系统汇总

THEN 这些答卷的主观分记录在进入逐份写入之前一次取出

AND 某份答卷没有主观分行时，仍按未批口径处理，不视为已批完

---

### Requirement: 成绩发布

WHEN 成绩已批改,

系统 SHALL 支持发布前预览与批量发布，状态由已批改迁至已发布。

#### Scenario: 发布前预览

GIVEN 教师完成批改

WHEN 教师查看发布预览

THEN 系统展示各学生成绩明细

#### Scenario: 批量发布

GIVEN 教师确认发布

WHEN 教师执行发布

THEN 状态迁至已发布

AND 学生可见成绩

---

### Requirement: 成绩撤回

WHEN 教师撤回已发布成绩,

系统 SHALL 需管理员权限，撤回后学生 SHALL 不再看到成绩，并 SHALL 记录审计日志。

#### Scenario: 撤回隐藏成绩

GIVEN 成绩已发布且学生已看到

WHEN 管理员执行撤回

THEN 学生端不再显示成绩

AND 记录撤回审计日志（谁/何时/原因）

---

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

### Requirement: 成绩导出

WHEN 教师导出成绩,

系统 SHALL 提供全班成绩单、逐题明细、题目统计、个人成绩单，且 SHALL 以流式方式防止大数据量内存溢出。

#### Scenario: 全班成绩单导出

GIVEN 教师请求导出

WHEN 导出完成

THEN 生成含每人分数与排名的 Excel

#### Scenario: 大数据量不溢出

GIVEN 数千条成绩记录

WHEN 系统导出

THEN 采用流式写入

AND 不因内存不足失败

> 评估结论注记（2026-09-27，`update-question-stats-export-memory` 归档）：本基线**未合入**该提案的
> 任何 Requirement——其 spec-delta（题目统计在保持统计口径下的逐题全量中间数据持有约束）按
> **未采用草案**随目录归档。要点：在隔离环境（H2 内存库 + 进程内直调 Service，不写共享 dev、不启
> Docker）对旧实现同负载三轮实测（被测旧业务基线 revision `8085c57`，测量工具提交 `91bf892`、
> `QuestionStatsExportAttributionMeasureIT`，类名以 IT 结尾不进全量门禁），三轮 XLSX 单元格与 zip
> 条目内容均与生产等价（oracleMatch=true）。**跨窗口量不写作请求内精确占比**：逐轮并列报告绝对 MB
> ——s3000-q60 分相位精确线程分配 pagingResolve（答卷分页+逐题解析）67.108/70.404/70.404MB、
> aggregatePairs（`pairs` 积累）8.472MB（三轮相同）、xlsxSerialize（SXSSF）9.025/9.021/9.024MB；
> s1000-q150 pagingResolve 46.951/49.698/49.698MB、aggregatePairs 6.787MB（三轮相同）、
> xlsxSerialize 14.121/14.142/14.133MB。若给比例，只用**同一 replica 内已观测相位分配之和**作分母
> （s3000-q60 相位和 89.922/93.222/93.217MB ⇒ pagingResolve 74.6–75.5%、aggregatePairs
> 9.1–9.4%、xlsxSerialize 9.7–10.0%；s1000-q150 相位和 83.442/86.228/85.987MB ⇒ pagingResolve
> 56.3–57.8%、aggregatePairs 7.9–8.1%、xlsxSerialize 16.4–16.9%），不冒充生产请求整体占比。
> `pairs` 的**持有**量 `pairsRetainedMB` 6.445MB@180000 与同轮 heapPeakMB
> 167.311/173.663/173.363MB 属不同测量窗口（GC 后差分 vs 采样上界），只并列绝对 MB、不写成
> 「占峰值 3.8%」。综合绝对量级，答卷分页与逐题解析（67.108–70.404MB）大于 `pairs` 积累（8.472MB）
> 与 SXSSF（9.025–9.024MB），`pairs` 不是占比最高的可控因素。若采用两遍扫描这一候选来消除 `pairs`，
> 会增加一次分页/解析、须计其 SQL 与 CPU 代价（分页 SQL 约 12→24、解析相位耗时约翻倍）；当前隔离
> 证据不支持 `pairs` 为占比最高的可控因素，故不因此另试新实现——**NO-GO**，`src/main` 零改动。
> 数字与依据见 `spec/changes/archive/update-question-stats-export-memory/tasks.json` 阶段 2 evidence；
> 隔离 H2 + 进程内直调不代表真实 MySQL/Tomcat 生产性能，不构成生产 P99 结论。
