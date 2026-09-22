# 规范差异：exam-taking（交卷落库容量与时延·实测收口）

本文件包含对 `spec/specs/exam-taking/spec.md` 的规范变更（修改既有 Requirement，补真跑压测场景）。

## MODIFIED Requirements

### Requirement: 交卷落库容量与时延

WHEN 高并发交卷（如 5000 人同时交卷）,

系统 SHALL 使消费并发与批量参数**真实生效且可通过配置调整**，并 SHALL 提供可复算的容量模型以支撑落库时延目标，且 SHALL 有真跑压测证据支撑容量与时延结论。

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

#### Scenario: 压测硬指标真跑且如实判定

GIVEN 5000 并发交卷压测在真 dev 实例执行

WHEN 汇总指标

THEN 每项硬指标（提交 P99 < 2s、0 丢单、批量落库完成 < 30s）按该次真跑输出**如实判定并登记**

AND 不达标项 SHALL 输出瓶颈定位与后续判据（2026-09-22 首轮实测：0 丢单与落库 < 30s 达标，提交 P99 四轮 2088–3700ms 未达标，绑定约束为 Tomcat 线程上限 200，见 `docs/submit-loadtest-report.md`）

AND 不得为凑指标改口径、改测试或只报最好的一轮

AND 证据含实际执行的命令、该次原始输出与当时短 revision

#### Scenario: 压测资产可复现

GIVEN 需要复跑压测

WHEN 使用入库的压测资产

THEN 可在真 dev 环境重建同场景

AND 数据构造可重复执行

#### Scenario: 容量模型数值以实测定稿

GIVEN 容量模型参数与压测实测并存

WHEN 发布容量结论

THEN 数值以真跑结果定稿

AND 不得以模型估算冒充实测
