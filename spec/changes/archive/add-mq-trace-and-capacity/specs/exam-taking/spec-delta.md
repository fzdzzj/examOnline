# 规范差异：exam-taking（交卷落库容量与时延）

本文件包含对 `spec/specs/exam-taking/spec.md` 的规范变更（交卷落库容量与时延，新增）。

## ADDED Requirements

### Requirement: 交卷落库容量与时延
WHEN 高并发交卷（如 5000 人同时交卷）,
系统 SHALL 使消费并发与批量参数**真实生效且可通过配置调整**，并 SHALL 提供可复算的容量模型以支撑落库时延目标。

#### Scenario: 消费参数真实生效
GIVEN 配置了消费并发与单批大小
WHEN 交卷监听容器启动
THEN 容器实际并发等于配置值
AND 不因手工构造容器工厂而退回框架默认值

#### Scenario: 容量可估算
GIVEN 已知单批落库耗时
WHEN 估算落库时延
THEN 按「吞吐 ≈ 并发 × 单批大小 / 单批耗时」推算积压与时效
AND 调参结论包含与数据库连接池上限的联动约束
