# spec-delta：add-alert-delivery

> 合入目标文件：`spec/specs/observability/spec.md` 末尾追加。ADDED 1 Requirement（五 Scenario）；observability 既有 Requirement 零改动。

## ADDED Requirement: 告警投递链路

WHEN 既有告警规则触发,

系统 SHALL 经 Alertmanager 投递告警至 webhook 通道, SHALL 按 severity 分层路由, AND SHALL 保持告警规则与阈值在既有护栏下零改动。

#### Scenario: alerting 接线

GIVEN observability 栈启动

WHEN Prometheus 配置加载

THEN alerting 段指向编排内 Alertmanager

AND 既有告警规则文件被规则加载链路挂载

#### Scenario: 触发真实投递

GIVEN observability 栈与告警接收器就绪且抓取目标不可达

WHEN up==0 持续超过规则 for 窗口

THEN 告警经 pending 转 firing

AND Alertmanager 向 webhook 接收器真实投递（POST 含 alertname 与 severity）

#### Scenario: severity 路由分层

GIVEN 同一投递链路承载 critical、warning、info 三级告警

WHEN 各级告警触发

THEN critical 立即投递

AND warning 按聚合窗口分组投递

AND info 不投递（仅留观）

#### Scenario: webhook 目标外置

GIVEN webhook receiver 配置存在

WHEN 生产对接真实平台

THEN webhook URL 经环境变量注入覆盖且不入库

AND 仓库文件不含任何真实平台 token 或凭证

#### Scenario: 告警规则与阈值零碰

GIVEN 既有 9 条告警规则与词法护栏测试

WHEN 本变更合入

THEN 规则文件内容与护栏测试内容均零改动

AND 后端门禁中护栏测试全绿即为执行层证据
