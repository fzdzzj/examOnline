# 规范差异：observability（告警与观测面板）

本文件包含对 `spec/specs/observability/spec.md` 的规范变更（指标驱动的告警、观测面板，新增）。

## ADDED Requirements

### Requirement: 指标驱动的告警

WHEN 核心链路指标越过健康阈值,

系统 SHALL 以代码可维护的方式声明告警规则（规则纳入版本库、可随代码评审），使故障无需人工翻看指标端点即可被发现。

#### Scenario: 交卷失败率越阈值告警

GIVEN 交卷失败计数与成功计数持续上报

WHEN 失败占比超过配置阈值并持续一段时间

THEN 触发告警

AND 告警标注严重级别与可读摘要

#### Scenario: 队列积压告警

GIVEN 交卷 MQ 队列深度被持续采集

WHEN 积压超过阈值并持续一段时间

THEN 触发告警

AND 采集不可用（深度为哨兵负值）时不得误报

#### Scenario: 限流降级告警

GIVEN 限流器因 Redis 故障降级放行

WHEN 降级计数出现增量

THEN 触发告警

AND 告警可按接口维度定位

#### Scenario: 实例不可达告警

GIVEN 抓取目标为应用实例

WHEN 实例无法被抓取

THEN 触发告警

---

### Requirement: 观测面板

WHEN 需要了解系统整体健康,

系统 SHALL 以代码可维护的方式（随版本库分发）提供观测面板，使指标以可读视图呈现，而非要求人工拼查询。

#### Scenario: 面板随栈自动就绪

GIVEN 观测栈按仓库内配置启动

WHEN 打开观测面板

THEN 面板已自动加载、无需手工导入

AND 所引用的数据源可直接取数（面板与数据源标识一致）

#### Scenario: 覆盖核心链路

GIVEN 面板已加载

WHEN 查看总览

THEN 可看到交卷量与成功率、交卷时延分位、MQ 队列积压、限流降级、HTTP 5xx 与 JVM 资源

AND 可按应用维度筛选
