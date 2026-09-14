# 规范差异：observability（死信队列的指标与告警覆盖）

本文件包含对 `spec/specs/observability/spec.md` 的规范变更（死信队列的指标与告警覆盖，新增）。

## ADDED Requirements

### Requirement: 死信队列的指标与告警覆盖

WHEN 系统为异步链路配置指标与告警,

系统 SHALL 覆盖消息的**最终去处**（死信队列），而不仅覆盖其入队处，且 SHALL 保证新增告警所用的指标名可从代码常量确定性推导。

#### Scenario: 最终去处有指标

GIVEN 交卷消息经主队列进入死信队列

WHEN 指标采集端抓取

THEN 主队列与死信队列各有独立的深度指标

AND 消息离开主队列进入死信后，死信深度指标反映该事实

#### Scenario: 规则引用的指标名必须可推导

GIVEN 新增一条告警规则

WHEN 校验其表达式引用的指标名

THEN 每个自定义业务指标名都能在指标常量定义处找到

AND 该一致性由自动化测试守住

#### Scenario: 指标不可用时告警不误报

GIVEN 死信队列深度指标在队列不可查询时返回哨兵值

WHEN 告警规则求值

THEN 规则排除该哨兵值

AND 不因指标不可用而产生误报

#### Scenario: 观测面板覆盖死信

GIVEN 观测面板展示交卷异步链路

WHEN 查看面板

THEN 死信队列深度有独立展示格

AND 面板引用的数据源与数据源配置一致
