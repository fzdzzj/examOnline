# 规范差异：performance / data-access（审计日志关键路径）

本文件包含对 `spec/specs/performance/spec.md` 的规范变更（新增，条件于评估结论=移出），以及对 `spec/specs/data-access/spec.md` 的引用说明（不重复定义清理规则）。

## ADDED Requirements

### Requirement: 审计写入不占请求关键路径

WHEN 请求关键路径上的业务操作需要落审计记录,

系统 SHALL 将审计写入移出业务事务的同步等待，且 SHALL NOT 静默丢弃审计事件。

#### Scenario: 业务不等待审计

GIVEN 关键路径上的写操作

WHEN 请求执行

THEN 业务事务不因审计落库而同步等待

AND 移出以实测时延对比为依据（不凭感觉优化）

#### Scenario: 审计不丢

GIVEN 异步审计写入失败

WHEN 失败发生

THEN 有补偿路径（重试 / 暂存 / 死信）

AND 该分支由自动化测试守住（撤掉补偿测试变红）

#### Scenario: 延迟窗口如实声明

GIVEN 审计已异步化

WHEN 描述审计可见性

THEN 声明为最终一致与延迟窗口

AND 不声称即时可查

#### Scenario: 评估结论可以是不移出

GIVEN 评估显示移出收益不成立

WHEN 记录结论

THEN 如实关闭该方向并保留数字依据

AND 不为了「做过」而硬改

## data-access 引用说明（非变更）

audit_log 的保留策略若需要清理规则，**引用** data-access 基线既有「数据保留策略」「清理的删除条件必须索引可达」Requirement，不在本变更重复定义；仅当 audit_log 维度未被覆盖且确需清理时，才在收尾时另拟 MODIFIED delta 交指导 agent 复核。
