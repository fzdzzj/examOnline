# spec-delta：data-access 域（harden-slave-read-failover）

## ADDED Requirements

### Requirement: 从库读故障降级回主库 (harden-slave-read-failover)

WHEN @DS("slave") 非强一致读点（考试快照拉卷/试卷快照/判分进度）遭遇从库数据访问异常（连接失败/超时）,

系统 SHALL 降级回主库重试一次（压栈 master 覆盖路由，与读己之写路由同机制）并记录降级计数与 ERROR 日志, AND SHALL NOT 使拉卷主链路因从库故障 500。

#### Scenario: 从库故障主库兜底 (harden-slave-read-failover)

GIVEN 从库连接故障且读点无缓存可用

WHEN 读点执行从库读发生 DataAccessException

THEN 降级压栈 master 重试一次并返回正确数据（重试期路由栈顶=master）

AND 记录 ERROR 日志，降级计数器按读点 tag 递增（endpoint=exam_snapshot/paper_snapshot/grading_progress）

#### Scenario: 主库也失败诚实上抛 (harden-slave-read-failover)

GIVEN 从库与主库均不可用是基础设施级故障

THEN 重试后仍失败则异常原样上抛（不吞不计入降级成功）

AND 降级计数在该过程中按触点如实记录

#### Scenario: 降级口只认不可用 (harden-slave-read-failover)

GIVEN 业务异常（NOT_FOUND/越权拒绝）不是基础设施故障

THEN 业务异常原样传播，不重试不计数

AND 从库恢复后读点自然回到原路由，零人工干预

#### Scenario: 正常路径零回归 (harden-slave-read-failover)

GIVEN 从库健康时读点应零额外开销

THEN 正常路径不压栈不计数，既有窄投影/缓存互斥/归属校验语义零变化

AND 本执行器不用于事务内写路径（push/poll 只影响线程路由栈顶）

#### Scenario: 降级计数与告警映射同源 (harden-slave-read-failover)

GIVEN 仓库已有 exam.ratelimit/auth/submit.lock/cache.degraded 降级指标族与对应告警

THEN 新指标 exam.datasource.degraded（tag endpoint 代码常量有界）+ 告警规则 DatasourceDegraded 照抄同源形态，AlertAssetsTest known 集合同笔守护

AND 不新增第二套降级哲学

#### Scenario: 变异击红 (harden-slave-read-failover)

GIVEN 摘除执行器的压栈重试（catch 内直接上抛）

WHEN 运行从库降级单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）
