# absence-makeup 规范差异：补考候选名单低分过滤下推（NO-GO，未采用草案）

> **本差异未被采用（NO-GO，2026-09-27 归档）。** 它是条件式草案：旧实现先归因，归因未达门槛即原样归档、不得合入基线。
> 按 `evidence/PREREGISTRATION.md` 判据测量后 D1、D2 在主族上不成立（`s200` 波动吞没收益且低分取回非最大相位、`a20k` 判定带被顺序漂移上界抬高），
> 故以下 Requirement 与全部 Scenario **均未合入** `spec/specs/absence-makeup/spec.md`（基线仅追加评估结论注记），
> `src/main` 零改动。隔离测量不证明生产请求频度或 MySQL/Tomcat 收益。

## ADDED Requirements

### Requirement: 补考候选低分取数须归因并保持等价

WHEN 有权限的教师携带 `passLine` 查询一场考试的可补考候选名单,

系统 SHALL 仅在同负载证据确认低分以外答卷行的取回和 Java 过滤为占比最高的可控因素、单独下推的请求级收益超出轮间波动且现有结果语义可保持时，才将 `total_score < passLine` 的判断下推至数据库；系统 SHALL 保持缺考与低分并集、候选原因及姓名的既有口径，不因优化附加考试状态或发布条件。系统 SHALL NOT 将隔离 H2 结果称为生产请求频度、MySQL/Tomcat 或 P99 结论。

#### Scenario: 满足归因门槛的低分条件

GIVEN 多轮隔离同负载归因确认低分以外行取回及 Java 过滤稳定主导可控成本

AND 单独的分数谓词下推臂在请求级稳定改善且结果 oracle 一致

WHEN 教师携带非 null 的 `passLine` 查询候选

THEN 低分答卷筛选在数据库按总分严格小于及格线执行

AND 总分为 null、等于或高于及格线的答卷不以低分理由纳入

AND 缺考并集、同 ID 原因覆盖、姓名缺失或软删的显示、权限与响应形状仍与原行为一致

#### Scenario: 未证实主导时不得实施

GIVEN 测量显示长字段投影、其他查询或其他因素主导，或下推收益落在波动内

WHEN 裁决本变更

THEN 不修改生产低分查询，也不把本条拟实施要求合入基线

AND 仅记录隔离测量的事实、反例及未知，不顺手实施投影或索引

#### Scenario: 不携带及格线

GIVEN `passLine` 为 null

WHEN 教师查询候选

THEN 只返回缺考名单

AND 候选为空时不发起姓名批量查询