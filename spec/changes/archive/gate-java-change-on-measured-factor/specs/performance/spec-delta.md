# 规范差异：performance（Java 优化门禁）

## ADDED Requirements

### Requirement: Java 优化先归因、一次只改一类

WHEN 对 Java 路径做性能优化,

系统 SHALL 先在同一负载下量出延迟、吞吐和资源占用，并确认时间主要耗在业务规则、数据库、远程调用、锁、CPU 或 GC 中的哪一类；每次 SHALL 只改占比最高的一类，并用同一基线验收。调用次数和事务范围没有下降之前，系统 SHALL NOT 先调整 JVM 参数。

#### Scenario: 没有剖面不得改参数

GIVEN 还没有同一负载下的延迟、吞吐和资源剖面

WHEN 准备优化

THEN 先补测量

AND 不修改 JVM、堆、GC 或容器内存参数

#### Scenario: 一次只改一类

GIVEN 剖面显示某一类占比最高

WHEN 实施优化

THEN 该次只改这一类因素

AND 分数、名额、权限和交卷幂等语义保持不变

AND 用同一基线复测；指标没有变化就回到度量，不继续叠加别的参数

#### Scenario: 交卷入口的已知结构不是延迟占比

GIVEN 交卷入口包含 Redis 锁、幂等读写、状态迁移和消息发送，答案落库使用 JDBC batch

WHEN 引用代码结构

THEN 只把它当作调用次数和事务范围的清单

AND 不把它当成已经测得的延迟占比

AND 同机压测的历史结果不能单独授权 JVM 或热点改写
