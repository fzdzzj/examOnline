# 规范差异：observability（交卷链路观测补齐）

本文件包含对 `spec/specs/observability/spec.md` 的规范变更（新增）。

## ADDED Requirements

### Requirement: 交卷时延直方图

WHEN 观测交提交链路的服务端时延,

系统 SHALL 提供带直方图桶的提交时长指标，且 SHALL 使服务端分位数可从指标端点计算。

#### Scenario: 桶计数可读

GIVEN 服务处理过交卷请求

WHEN 读取 exam_submit_duration_seconds 指标

THEN 直方图桶计数出现且桶边界覆盖亚秒到数秒量级

AND 服务端 P99 可由桶计算，不依赖客户端侧采样

### Requirement: Tomcat 线程水位可观测

WHEN 交卷链路接近容量上限,

系统 SHALL 暴露 Tomcat 线程 busy 与 max 指标，且 SHALL NOT 引入无界标签。

#### Scenario: 线程指标存在

GIVEN 应用启动

WHEN 读取指标端点

THEN tomcat.threads.busy 与 tomcat.threads.config.max 可读

#### Scenario: 基数有界

GIVEN 新增线程与时延指标

WHEN 检查其标签维度

THEN 不含业务键（考试 ID / 用户 ID 等）维度

#### Scenario: 配置回退可检测

GIVEN 直方图或线程指标配置被移除

WHEN 运行指标存在性测试

THEN 测试失败（防静默失效）
