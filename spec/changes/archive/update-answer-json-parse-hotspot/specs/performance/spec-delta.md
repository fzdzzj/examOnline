# performance 规范差异：答案 JSON 解析热点的条件优化

> 仅 GO 且实施、同负载复测与唯一门禁通过时合入；NO-GO 原样归档为未采用草案。本 delta 不授权更改其他归因段。

## ADDED Requirements

### Requirement: 共享答案 JSON 解析在不改变判分语义下限制可测热点成本

WHEN 题目统计导出需要批量解析答卷答案,
系统 SHALL 在保留 `GradingPaperReader.parseAnswers` 的 JSON 输入、键和值、异常及坏卷隔离语义下处理答案；仅当同负载归因证明答案 JSON 解析本身稳定主导时，系统 SHALL 以同一基线验证该单因素改动的分配和耗时收益，并 SHALL NOT 以修改主观分映射、SQL、JVM 或放宽异常规则换取收益。

#### Scenario: 正常答卷和共享调用者保持同口径

GIVEN 客观判分、成绩解析与题目统计均调用共享答案解析器，且答案包含有效题 ID、null 与普通 JSON 值
WHEN 解析器作性能改动
THEN 旧实现与新实现对相同输入的映射、异常与下游题目得分一致
AND 相同数据形状的完整导出内容与资源复测可核对，改动收益大于轮间波动

#### Scenario: 损坏答案不能变零分卷

GIVEN 答案为空白、JSON 损坏、顶层非对象或题 ID 键非法
WHEN 解析器处理该答卷
THEN 保留既有失败/隔离行为，成绩导出不得将坏卷误作零分卷
AND 不通过改变判分、映射、数据库或 JVM 参数令资源护栏变绿

#### Scenario: 归因不成立则不实施

GIVEN 同一受控副本分账中答案 JSON 解析并非单独最大段、测量不可比或收益落在轮间噪声内
WHEN 评估该解析器优化
THEN 不实施该候选
AND 保留逐轮证据及未采用草案，不将复合 answerParse 段冒称解析器本身的占比
