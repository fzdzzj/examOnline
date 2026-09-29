# score-management 规范差异：myScore 名次索引候选先在真 MySQL 引擎归因

> 拟合入；仅当本笔归因裁决 GO 且独立实施提案获批实施后随其收口合入。NO-GO 或未实施则保留为未采用草案，不把测量结论写成生产收益，不把候选索引写成已存在。

## ADDED Requirements

### Requirement: myScore 名次索引候选先在真 MySQL 引擎归因

WHEN 针对 myScore 名次聚合计数（`GradingSubmissionMapper.selectCount` 打在答卷表上）提出索引类候选,

系统 SHALL 先从 MyBatis 侧捕获该语句的原文与绑定参数（不手写近似 SQL），并在与生产同主版本 MySQL 引擎的隔离环境上按可复现数据形状逐轮测量选中索引、访问方式、是否覆盖（Using index）与实际扫描行数/耗时；覆盖索引类候选 SHALL 以「回表是否消除、收益倍数是否超过同代码顺序漂移判定带、写路径代价是否有界」三项同时成立为准。H2 上的计时或计划 SHALL NOT 作为回表类候选的证据；静态索引推演 SHALL NOT 宣称收益已证实；隔离结果 SHALL NOT 外推生产 P99 与生产负载。同代码顺序漂移吞没收益时 SHALL 写「稳定性不达标」而不是「收益不存在」；收益不可辨时 SHALL 写「无可见收益」而不是宣称存在收益。

#### Scenario: 基线无回表可消除

GIVEN 基线臂 EXPLAIN 显示该 COUNT 已是覆盖扫描（Extra 含 Using index）

WHEN 评估是否需要新增覆盖索引

THEN 判定为 NO-GO，结论记录「无回表可消除」

AND 不新增索引，不为了 GO 改写判据或换用别的语句形态

#### Scenario: 收益被波动吞没

GIVEN 候选臂与基线臂各自多轮测量的收益倍数下界不大于基线自身的轮间漂移判定带

WHEN 评估是否新增覆盖索引

THEN 判定为稳定性不达标（波动吞没收益），保留逐轮原始数据

AND 不宣称收益不存在，也不实施索引

#### Scenario: 写代价超界

GIVEN 候选索引使答卷 INSERT、汇总 CAS 或交卷 CAS 的同口径墙钟中位放大超过预登记界值

WHEN 评估读端收益是否可实施

THEN 判定为 NO-GO 并如实记录写路径代价

AND 不以读端收益倍数豁免写端代价

#### Scenario: 归因通过后实施走独立提案

GIVEN 回表消除、收益超过漂移判定带（门禁形状）、写代价有界三项全部成立且测量有效

WHEN 推进实施

THEN 另写独立实施提案承载 schema.sql 变更、存量库迁移脚本与应用验证责任

AND 归因变更自身不携带实施，等价性以既有 MyScoreRankEquivalenceTest 全绿佐证
