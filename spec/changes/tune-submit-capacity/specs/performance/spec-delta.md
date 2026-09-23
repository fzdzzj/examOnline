# 规范差异：performance（交卷容量调整与压测复验）

本文件包含对 `spec/specs/performance/spec.md` 的规范变更（新增）。

## ADDED Requirements

### Requirement: 交卷容量可由运行参数调整且达标判定可复现

WHEN 交卷链路的容量参数（如 Tomcat 线程上限）被调整,

系统 SHALL 以可复现的压测方法学验证调整效果，且 SHALL NOT 以单轮或未预热的采样作为容量结论。

#### Scenario: 容量调整前有数字裁决

GIVEN 压测暴露容量瓶颈

WHEN 决定调整并发运行参数

THEN 方案与代价以量化对比为依据（如服务时长与请求速率的容量估算）

AND 不做无数字依据的拍脑袋调参

#### Scenario: 注入不改代码

GIVEN 运行参数可由环境变量注入

WHEN 应用参数调整

THEN 通过框架的宽松绑定机制注入（零代码改动）

AND 改后给出新的容量估算与下一个瓶颈位

#### Scenario: 压测方法学可复现

GIVEN 调整后的配置需复验

WHEN 执行压测

THEN 每臂至少三轮取中位数、每轮前预热、轮间等连接排空

AND 连接池水位在压测期间持续采样（而非突发后快照）

#### Scenario: 达标判定不因未达标而放宽

GIVEN 复验结果

WHEN 判定硬指标

THEN 按既有指标定义判定（P99 / 丢单 / 落库时效）

AND 未达标时如实登记与下一步瓶颈位，不修改指标定义凑数
