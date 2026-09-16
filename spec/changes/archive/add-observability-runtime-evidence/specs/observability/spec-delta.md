# 规范差异：observability（观测栈动态可验证性）

本文件包含对 `spec/specs/observability/spec.md` 的规范变更（观测栈动态可验证性，新增）。

## ADDED Requirements

### Requirement: 观测栈动态可验证性

WHEN 观测栈与应用同时运行,

系统 SHALL 使告警规则与总览面板能在**真实抓取**下被观察，且 SHALL 将观察结果作为可复查证据保留，且 SHALL NOT 通过改低阈值或改 PromQL 来制造 firing。

#### Scenario: 抓取目标可达

GIVEN 应用在宿主暴露 Prometheus 指标端点

AND 观测栈已启动

WHEN 查看 Prometheus 抓取目标

THEN job=exam-online 状态为 UP

#### Scenario: 规则已加载

GIVEN 告警规则文件已被 Prometheus 加载

WHEN 查看规则组

THEN 现有全部业务告警规则（含死信两条）均 loaded

AND 规则健康而非 no data 永久静默

#### Scenario: 能触发的规则至少 firing 一次

GIVEN 一条告警的触发条件可用运维动作或真实请求达成（且不修改规则阈值）

WHEN 条件持续满足其 for 窗口

THEN Prometheus 将该告警置于 firing

AND 该次 firing 被记录进运行证据

#### Scenario: 点不着的规则保持诚实

GIVEN 一条告警的阈值在本机健康流量下无法达到（如交卷 P99 远低于 2s）

WHEN 无法在不破坏规则语义的前提下使其 firing

THEN 不得修改 expr 或 for 来凑绿

AND 证据中记录即时 PromQL 结果与未 firing 的原因

#### Scenario: 总览面板出图

GIVEN Grafana 已配置 Prometheus 数据源

WHEN 打开 Exam Online 总览面板

THEN 面板查询成功

AND 至少系统存活与运行时相关格展示时间序列（无业务流量时可以为 0，但不能整页 No data）

#### Scenario: 证据可复查

GIVEN 完成一次动态验证

WHEN 查阅仓库中的运行证据

THEN 能看到每条规则 firing 或未点着的结论

AND 不得据此声称死信链路真 broker 端到端已验收
