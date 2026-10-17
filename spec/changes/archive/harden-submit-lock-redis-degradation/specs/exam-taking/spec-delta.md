# spec-delta：exam-taking（交卷 SETNX 锁对 Redis 异常的降级放行）

## 变更类型

ADDED（新增 Requirement「交卷 SETNX 锁对 Redis 异常的降级放行」，五 Scenario；既有 Requirement 与 Scenario 零改动）

### Requirement: 交卷 SETNX 锁对 Redis 异常的降级放行

WHEN Redis 不可用时学生交卷（手动/前端归零）,

系统 SHALL 降级放行走防重表与状态机 CAS 链路并记录降级计数与 ERROR 日志, AND SHALL NOT 因锁基础设施异常使交卷全停摆。

#### Scenario: Redis 异常时放行（降级口只认不可用）

GIVEN SETNX 加锁调用因 Redis 连接异常失败

WHEN 学生提交交卷

THEN 交卷照常走防重表先查后插、状态机 CAS 与 MQ 削峰链路完成（不因锁不可用而失败）

AND 记录 ERROR 日志，降级计数器递增（exam.submit.lock.degraded，AlertAssetsTest 守护同笔登记）

AND 降级路径不记锁等待/锁竞争指标、不执行按 token 解锁（从未持锁）

#### Scenario: 锁竞争语义不变（降级口不认竞争）

GIVEN SETNX 返回 false（锁被并发路径真占）

WHEN 学生提交交卷

THEN 既有拒绝/幂等快速路径语义零变化（记录锁竞争并短轮询等待幂等返回）

AND 该路径不递增降级计数——竞争是正常业务形态，不是故障

#### Scenario: 正确性后盾不依赖锁

GIVEN 锁仅把三路竞态收敛为单飞、省 DB/MQ 压力（防抖器）

THEN 交卷只发生一次由三层 DB 幂等保证：防重表 uk_submit_dedup 唯一索引、状态机 CAS（casSubmitToSubmitted）、消费端 casFillAnswers（仅 answers IS NULL 时写入）

AND 锁降级只影响防抖半径（故障期并发交卷各自走完整幂等链，输家被 CAS/唯一索引裁决后幂等返回），不构成数据错误；与 reliability 域「幂等是正确性的唯一保证」同源推广

#### Scenario: 降级可观测

GIVEN 仓库已有 exam.ratelimit.degraded / exam.auth.degraded 降级计数先例

WHEN 交卷锁发生降级

THEN 打点与日志口径与先例一致（ERROR 日志 + 降级计数，命名对齐既有风格），不新增第二套降级哲学

#### Scenario: 变异击红

GIVEN 还原为「锁异常即失败」旧形态（Redis 异常直接上抛）

WHEN 运行交卷容错单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）
