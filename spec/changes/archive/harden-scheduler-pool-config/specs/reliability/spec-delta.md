# spec-delta：reliability 域（harden-scheduler-pool-config）

## ADDED Requirements

### Requirement: 定时任务调度池容量可配置且不小于 2 (harden-scheduler-pool-config)

WHEN 应用运行 @Scheduled 定时任务（状态机扫表/开考提醒/交卷兜底 sweep/数据保留）,

系统 SHALL 以可配置的调度线程池（spring.task.scheduling.pool.size，env 可覆盖）承载, AND 池容量缺省不小于 2, AND SHALL NOT 依赖 Spring 默认单线程调度器使慢任务阻塞其余任务的定时触发。

#### Scenario: 单任务阻塞不再扩散 (harden-scheduler-pool-config)

GIVEN sweep 单轮最多 10 轮 × 500 批、每批等待 MQ confirm 至 5s，broker 故障时单轮可拖分钟级

WHEN 任一定时任务长时间执行

THEN 其余定时任务（状态机流转/开考提醒）照常按各自的 fixed-delay 触发，不排队等待慢任务

#### Scenario: 同任务语义与多实例幂等不变 (harden-scheduler-pool-config)

GIVEN fixed-delay 按任务自互斥（同任务上一次未结束不触发下一次）

THEN 池扩容不产生同任务自我重叠

AND 多实例 sweep 幂等安全性（既有护栏）不受线程池影响

#### Scenario: 配置声明受护栏钉住 (harden-scheduler-pool-config)

GIVEN base application.yml 是该键的唯一声明点（dev/prod 零覆盖即不可能漂移）

THEN base 必须声明 spring.task.scheduling.pool.size 且缺省值 ≥2（词法护栏测试）

AND dev/prod 若未来出现同名覆盖，其值同样必须 ≥2

#### Scenario: 变异击红 (harden-scheduler-pool-config)

GIVEN 把声明值改回 1 或删除声明

WHEN 运行调度池护栏测试

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）
