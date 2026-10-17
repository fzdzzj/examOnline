# reliability 规范

> 能力域：可靠性（阶段 8，W10；限流器降级阶段 10；定时扫描多实例安全阶段 13；死信可见性与有界重投阶段 14；2026-09-20 补分页入参上限；2026-09-26 补偶发失败处置与跨实例取号护栏确定性）。
> 来源：`spec/changes/archive/add-slow-sql-and-rate-limit` 合入（核心接口限流、分布式一致性、限流粒度）+ `spec/changes/archive/add-rate-limit-resilience` 合入（限流器降级、降级可观测）+ `spec/changes/archive/add-multi-instance-sweep-safety` 合入（定时扫描多实例安全、不引入调度锁的取舍）+ `spec/changes/archive/add-dlq-observability-and-replay` 合入（死信消息的可见性、有界重投、死信队列不设过期与容量上限）+ `spec/changes/archive/add-api-rate-limiting` 合入（分页入参上限；其全局限流器已撤回，见文末注记）+ `spec/changes/archive/fix-broker-confirm-and-dlq-roundtrip` 合入（发布确认作用域与死信真往返，提案⑦，2026-09-22）+ `spec/changes/archive/fix-flaky-integration-baselines` 合入（集成测试偶发失败的可复现处置、跨实例取号确定性护栏，2026-09-26）。
> 实施注记：超限返回 `ResponseCode.TOO_MANY_REQUESTS`（1008 → HTTP 429），由 `GlobalExceptionHandler` 统一转换。
> 实施注记：限流依赖（Redis）异常时默认 **fail-open 放行**（`exam.ratelimit.fail-open`，默认 true），并打 ERROR 日志 + 递增 `exam.ratelimit.degraded` 计数器；置 false 则异常上抛（fail-close）。
> 实施注记（阶段 13）：定时扫描正确性靠下游幂等（CAS + 唯一索引 + INSERT IGNORE + 消费端 casFillAnswers），**刻意不加分布式调度锁**；重复扫描指标 `exam.sweep.duplicate_detected`（tag `task`=`sweep`/`state-advance`，含消费者 `filled==0`）；交卷锁按 token 解锁（`RedisLockHelper` Lua compare-and-delete，`exam.taking.submit.lock-ttl-seconds` 默认 30）。
> 实施注记（阶段 14）：重投是加速手段，正确性仍靠消费端幂等 + 补发对账；`RabbitTemplate#receive` autoAck 取出与落档之间的毫秒级崩溃窗口可接受；**禁止常驻 DLQ 消费者**（会死循环或丢消息）。指标 `exam.mq.dlq.depth`（不可用返回 -1 哨兵）/ `exam.mq.retry`（tag outcome）/ `exam.mq.dlq.entered`；管理端点 `POST /api/admin/mq/dlq/replay`（ADMIN）。
> 实施注记（harden-auth-interceptor-redis-degradation，2026-10-17）：鉴权拦截器（AuthenticationInterceptor 黑名单/会话版本检查）对 Redis 异常的降级与限流 fail-open **同源同哲学**——降级放行 + ERROR 日志 + 递增 `exam.auth.degraded`（tag `check`=`blacklist`/`session_version`，告警 `AuthDegraded`）；降级半径由黑名单 TTL（=Access Token 剩余寿命 ≤30min）自限。本域既有 Requirement 文字不动，行为 Requirement 落 authentication 域。
> 实施注记（harden-login-guard-redis-degradation，2026-10-17）：登录守卫（LoginGuardService 登录限流/锁定检查/失败计数/锁定写入/计数清理 + 验证码限流六触点）对 Redis 异常的降级与拦截器/限流先例**同源同哲学**——降级放行/跳过该步 + ERROR 日志 + 复用 `exam.auth.degraded`（tag `check` 追加六个登录守卫侧代码常量，告警 `AuthDegraded` 全 tag 增量自动覆盖）；凭据校验（DB）是登录正确性底座不降级，锁定/计数保护 Redis 恢复后自愈。本域既有 Requirement 文字不动，行为 Requirement 落 authentication 域。
> 实施注记（harden-submit-lock-redis-degradation，2026-10-17）：交卷 SETNX 锁对 Redis 异常降级放行（ERROR 日志 + `exam.submit.lock.degraded` 计数）——「幂等是正确性的唯一保证」在本域交卷链路的同源推广：防重表唯一索引 + 状态机 CAS + 消费端 casFillAnswers 三层 DB 幂等是唯一正确性后盾，锁仅是把三路竞态收敛为单飞的防抖器，降级只影响防抖半径；**降级口只认不可用不认竞争**（锁被真占 false 走既有等待/冲突路径，不递增降级计数）。本域既有 Requirement 文字不动，行为 Requirement 落 exam-taking 域。

## Requirements

### Requirement: 核心接口限流

WHEN 访问交卷、拉卷、抽题等核心接口,

系统 SHALL 按接口维度限流（Redis 令牌桶），超限 SHALL 返回 429。

#### Scenario: 限流生效

GIVEN 接口令牌桶已配置容量与速率

WHEN 请求速率超过令牌桶放行能力

THEN 超出请求返回 429

AND 放行请求正常处理

#### Scenario: 峰值可抗

GIVEN 5000 人同时拉卷或交卷

WHEN 系统限流

THEN 后端以配置速率平滑放行

AND 数据库与 MQ 不被瞬时峰值打垮

---

### Requirement: 分布式一致性

WHEN 多实例部署,

系统 SHALL 使限流桶在实例间共享（令牌桶计数存 Redis，Lua 脚本原子操作）。

#### Scenario: 多实例共享桶

GIVEN 两个应用实例服务同一接口

WHEN 两者限流

THEN 共享同一 Redis 令牌桶

AND 总放行速率等于配置值而非翻倍

#### Scenario: 原子操作

GIVEN 并发请求同时扣减令牌

WHEN Lua 脚本执行

THEN 令牌扣减原子完成

AND 不出现超发

---

### Requirement: 限流粒度

WHEN 配置限流,

系统 SHALL 按接口粒度（非用户粒度），各核心接口 SHALL 可独立设定容量与速率。

#### Scenario: 接口独立限流

GIVEN 交卷、拉卷、抽题三个接口

WHEN 配置限流

THEN 每个接口有独立的桶容量与放行速率

AND 互不影响

---

### Requirement: 限流器降级

WHEN 限流依赖（Redis）不可用,

系统 SHALL 以可配置的降级策略保持核心接口可用（默认 fail-open 放行），且 SHALL 区分「业务超限」与「限流器降级」两种情形。

#### Scenario: Redis 异常时放行

GIVEN 令牌桶因 Redis 异常无法判定

WHEN 请求命中带 @RateLimit 的核心接口

THEN 请求被放行

AND 记录降级 ERROR 日志

#### Scenario: 业务超限不降级

GIVEN 令牌桶判定桶已空

WHEN 请求命中核心接口

THEN 抛 TOO_MANY_REQUESTS（429）

AND 不记为降级

#### Scenario: 可切回 fail-close

GIVEN 配置为 fail-close

WHEN 令牌桶因 Redis 异常无法判定

THEN 异常上抛，请求失败

AND 行为与降级前一致

---

### Requirement: 降级可观测

WHEN 限流器发生降级,

系统 SHALL 记录降级计数指标，使降级状态可被外部告警发现。

#### Scenario: 降级指标可采集

GIVEN 限流器发生一次降级

WHEN 采集指标

THEN 降级计数器递增

AND 可按接口维度区分

---

### Requirement: 定时任务的并发与多实例安全

WHEN 同一套服务部署多个实例,

系统 SHALL 使定时扫描（考试状态推进、超时交卷兜底与答案补发对账）**在多实例并发执行下仍只产生一次业务效果**，且 SHALL 不依赖外部调度中间件来保证这一点。

#### Scenario: 并发扫描同一份超时答卷只交卷一次

GIVEN 一份已超时的进行中答卷

WHEN 两个实例的扫描同时处理它

THEN 只有一个实例完成状态迁移

AND 强制交卷只成功一次（答卷唯一、`submissionId` 唯一）

AND 同轮对账补发允许再投递，消费端幂等跳过

AND 答卷仍是唯一一条

#### Scenario: 并发扫描不重复落库

GIVEN 一条已交卷但答案未落库的答卷

WHEN 两个实例的扫描同时重新投递

THEN 答案只被写入一次

AND 重复投递被消费端幂等跳过

#### Scenario: 并发结束只标记一次缺考

GIVEN 一场考试在并发路径下被迁到已结束

WHEN 两条路径同时触发缺考标记

THEN 每个应考且无答卷的学生恰好一条缺考记录

AND 不产生重复缺考

#### Scenario: 并发推进同一场考试只迁移一次

GIVEN 一场到期未推进的考试

WHEN 两个实例同时推进

THEN 状态只迁移一次

AND 版本号只递增一次

#### Scenario: 重复扫描可被观测

GIVEN 多实例在重复处理同一批数据

WHEN 下游以幂等方式跳过这些重复处理

THEN 系统产生可采集的重复处理计数

AND 据此可判断是否需要引入调度锁，而非凭感觉决策

---

### Requirement: 不引入调度中间件与锁的取舍

WHEN 选择是否用分布式锁协调定时扫描,

系统 SHALL 以「正确性由幂等保证、锁仅用于省资源」为准则，且 SHALL NOT 让定时兜底能力依赖 Redis 等外部组件的可用性。

#### Scenario: 定时兜底不因锁而停摆

GIVEN 分布式协调组件（如 Redis）不可用

WHEN 定时扫描触发

THEN 扫描照常执行

AND 不因抢不到锁而整体跳过

#### Scenario: 幂等是正确性的唯一保证

GIVEN 调度锁失效或未启用

WHEN 多个实例并发扫描

THEN 业务效果仍只发生一次

AND 该性质由自动化测试证明

---

### Requirement: 死信消息的可见性

WHEN 一条消息因重试耗尽进入死信队列,

系统 SHALL 使其可被观测与追溯，且 SHALL NOT 让死信队列的堆积在主队列指标上表现为"链路变好"。

#### Scenario: 死信深度可被采集

GIVEN 死信队列中有消息堆积

WHEN 指标采集端抓取

THEN 系统暴露死信队列当前深度

AND 在该队列不可查询时暴露"不可用"语义而非伪装成 0 积压

#### Scenario: 进入死信是可计数事件

GIVEN 一条消息重试耗尽进入死信

WHEN 该消息被投递到死信队列

THEN 系统递增"进入死信"计数

AND 该计数不因日志轮转而丢失

#### Scenario: 死信非空即告警

GIVEN 死信队列稳态深度应为 0

WHEN 死信队列出现消息

THEN 系统产生告警

AND 告警不等待"堆积到一定量"才触发

#### Scenario: 重试过程本身可观测

GIVEN 消息持续重试

WHEN 重试成功或重试耗尽

THEN 系统按结果分别计数

AND 可据此区分"健康"与"持续重试但侥幸成功"的链路劣化

---

### Requirement: 死信消息的有界重投

WHEN 运维需要重新投递死信消息,

系统 SHALL 提供有界、可审计、幂等安全的重投能力，且 SHALL NOT 以牺牲消息不丢为代价简化重投实现。

#### Scenario: 重投先留档

GIVEN 一条死信消息被取出

WHEN 系统准备重新投递

THEN 其原始报文与消息头先被持久化留档

AND 留档先于任何投递动作

#### Scenario: 重投是幂等安全的

GIVEN 一条死信消息被重复投递

WHEN 消费端处理它

THEN 业务效果只发生一次

AND 该性质由消费端幂等（仅在答案未落库时写入、状态机 CAS）保证

#### Scenario: 重投次数有上限

GIVEN 一条消息已多次重投仍失败

WHEN 其上界被达到

THEN 系统不再自动重投

AND 将其标记为待人工处置

AND 不形成"重投→再失败→再重投"的无限循环

#### Scenario: 单次重投有界

GIVEN 运维触发一次重投

WHEN 请求的批量上限超过系统允许值

THEN 实际处理量被收敛到系统上限

AND 不允许一次抽干整个死信队列

---

### Requirement: 死信队列不设过期与容量上限

WHEN 配置死信队列的保留策略,

系统 SHALL NOT 为其设置消息过期或最大长度，且 SHALL 以可见性与人工处置替代自动清理。

#### Scenario: 不以过期代替兜底

GIVEN 死信队列承载"最终兜底"职责

WHEN 配置其保留策略

THEN 不设置消息过期时间

AND 不设置最大长度丢弃策略

#### Scenario: 稳态深度为 0 的判据

GIVEN 死信队列的稳态深度应为 0

WHEN 其深度持续大于 0

THEN 视为交卷链路存在真实缺陷

AND 处理方式是查明原因而非清空队列

---

### Requirement: 发布确认作用域与死信真往返

WHEN 交卷消息经 RabbitMQ 发布与消费,

系统 SHALL 在真 broker 下保证发布确认调用合法、启动期补发对账可完成、死信可往返，且 SHALL NOT 仅凭 mock 证据声称上述能力已验证。

#### Scenario: 真 broker 启动对账无异常

GIVEN 真 dev 实例（MySQL、RabbitMQ、Redis）启动

WHEN 启动期答案补发对账执行

THEN 无 IllegalStateException

AND 待补发消息实际送达消费侧

#### Scenario: confirm 调用作用域合法

GIVEN 任意需要 publisher confirm 的发布路径

WHEN 调用 waitForConfirmsOrDie 类 API

THEN 该调用处于 RabbitTemplate.invoke() 作用域内

AND 该性质有可在无真 broker 的 CI 中运行的护栏测试守住

#### Scenario: 死信真往返

GIVEN 真 broker 且发出一条必进死信的消息

WHEN 死信与重投流程走完

THEN 消息进入 DLQ 且指标可见

AND 重投后被消费或留档

AND 全程无 mock 替身

#### Scenario: mock 证据不得冒充实测

GIVEN 仅有 mock RabbitTemplate 的测试通过

WHEN 声称发布确认或死信链路能力

THEN 不得声称端到端已验证

AND 端到端结论只以真 broker 实测记录为准

---

### Requirement: 分页入参上限

WHEN 请求分页列表接口,

系统 SHALL 拒绝超出上限的页码与页大小并返回 400，不因参数越界退化成 500 或放行超大查询。

#### Scenario: 超限页大小被拒

GIVEN 列表接口声明页大小上限为 100

WHEN 客户端请求 `size=1000`

THEN 返回 400 与字段级提示

AND 不触发对数据库的大页扫描

#### Scenario: 入参约束必须真的生效

GIVEN 控制器方法参数上写了 `@Min`/`@Max`

WHEN 校验该接口是否具备约束力

THEN 控制器需带类级 `@Validated`（否则注解只是装饰，请求照常以 200 放行）

AND 参数约束抛出的异常类型与 `@Valid @RequestBody` 不同，须有对应异常处理映射到 400

---

### Requirement: 集成测试的偶发失败必须可复现或明确保持观察

WHEN 集成测试在一次全量或定向运行中失败、而单独重跑又通过,
系统 SHALL 要么给出可重复的失败与对应修复，要么把该失败保持为未复现的观察项，且 SHALL NOT 把重跑通过写成缺陷已消除。

#### Scenario: 失败可以重复

GIVEN 某个集成测试在固定命令下再次失败
WHEN 修复该失败
THEN 同一失败路径先失败、后通过
AND 修复不使用 `@Sql` 自建表，也不把断言改成接受多种行数

#### Scenario: 失败不能重复

GIVEN 按记录的命令重复运行后不再失败
WHEN 更新遗留说明
THEN 写明命令、次数和当时 revision
AND 遗留保持开放，不宣称测试已经稳定

#### Scenario: 护栏已修而历史失败未复现

GIVEN 跨实例护栏已经先红后绿且全量门禁通过
AND 两条历史偶发测试按记录次数未能复现
WHEN 归档本可靠性变更
THEN 历史遗留项仍标为开放观察，附命令与 revision
AND 归档结论只声称护栏已修和观察方法收口，不声称历史失败被消除

#### Scenario: 前端批改面板测试的负载稳定性 (fix-subjective-grading-panel-flaky)

GIVEN 全量 vitest 并行执行（同 CI 负载形态）
WHEN 运行 SubjectiveGradingPanel 测试套件（含「Enter 提交复用 createGradingFlow」用例）
THEN 连续多轮全量执行 SHALL 零该用例超时（等待条件外化于测试侧——断言经 vi.waitFor 兜底等待真实 mock 被调用，不依赖组件内部异步链在 5s 默认窗口内返回）
AND 孤立运行 SHALL 保持通过；治理 SHALL 仅落测试侧（组件源码、vitest 全局配置、其余测试用例零改动）
AND 若还原治理形态后超时无法按需稳定复现，SHALL 按等价情形如实登记（留机制证据），SHALL NOT 强杀或虚称已稳定复现

---

### Requirement: 跨实例取号护栏必须为确定性反例

WHEN 回归测试声明能防止测试实例各自取号而撞名的退化,
系统 SHALL 用不依赖墙钟分辨率、执行速度或偶然同毫秒构造的断言证明实例共享取号状态。

#### Scenario: 跨实例取号护栏不依赖时间巧合

GIVEN 旧取号器为每个测试实例单独持有计数器，新取号器为实例共享计数器
WHEN 运行跨实例回归护栏
THEN 旧取号器下的同一护栏以确定性断言失败，新取号器下通过
AND 护栏不依赖一千次构造恰好落在同一毫秒，也不重置共享静态计数以制造测试间干扰

---

> 合入注记（2026-09-20，`add-api-rate-limiting`）：只合入「分页入参上限」一条。
> 该提案原拟的**全局 `/api/**` 限流器已实现后撤回**——它与既有 `@RateLimit` 体系重叠且更危险：
> 一刀切 100 QPS 会把交卷接口压到其自身 500 预算之下（恰是 5000 人交卷场景），
> 且绕开"先鉴权再限流"的既定顺序。429 的产生方仍是上文的 `@RateLimit` 令牌桶，未新增第二条路径。

> 合入注记（2026-09-26，`fix-flaky-integration-baselines`）：合入「集成测试的偶发失败必须可复现或明确保持观察」「跨实例取号护栏必须为确定性反例」两条 Requirement。
> 护栏返修（UniqueSeqGuardTest 反射比较两实例取号器对象身份）在 ed06f42 实例级基座上确定性失败、在共享 static 计数实现上通过（命令、四计数、revision 见归档 tasks.json 证据字段）。
> **该返修只证明护栏本身**：`DataRetentionIntegrationTest` 与 `ExamTakingIntegrationTest` 两条历史偶发各 3 次逐次复核未复现（41f8298），
> 遗留 #14/#15 继续开放——本注记与归档记录不声称两条历史失败已被消除。

> 合入注记（2026-10-12，`add-backup-restore-drill`）：ADDED「数据库备份与恢复演练」Requirement（生产化 P5）。
> 六 Scenario 名称与 `spec/changes/archive/add-backup-restore-drill/spec-delta.md` 逐字一致；纯脚本域（docker/mysql/backup/）零业务代码，
> 用例数零变化、gate-baseline.json 零改动合法持平；既有 Requirement 零改动。

> 合入注记（2026-10-16，`fix-subjective-grading-panel-flaky`）：合入「前端批改面板测试的负载稳定性」一条 Scenario（挂于「集成测试的偶发失败必须可复现或明确保持观察」Requirement 下）。
> 治理仅落测试侧：SubjectiveGradingPanel.spec.ts 该用例 `await vm.onScoreEnter` 改 `void` + `vi.waitFor` 外化断言（5+/3−），组件源码与 vitest 全局配置零改动；
> 孤立时序 1875ms→1083ms，全量并行连跑多轮 119 files/908 零该用例超时，frontendTests 908 不增不减（gate-baseline 零改动合法持平）。
> 还原变异 ×3 轮全绿无法按需复现超时——按等价情形如实登记不强杀（机制证据与日志见归档 evidence/）；
> 同批 paperBlueprintDraw / questionImportExport 曾超时后自行转绿，观察名单登记不冒领修复。

---

### Requirement: 数据库备份与恢复演练

WHEN 业务数据库需要备份或恢复,

系统 SHALL 提供参数化双环境（dev 主库 / prod 编排库）的备份与恢复脚本，SHALL 以人工执行台账登记每次备份，AND SHALL 提供经真实演练验证的恢复步骤。

#### Scenario: 双环境全量备份

GIVEN dev 主库或 prod 编排库任一环境

WHEN 以环境参数执行备份脚本

THEN 产出含 GTID 一致性（--single-transaction --source-data）的全量 dump、gzip 压缩并附 sha256

AND 落于不入库的产物目录，凭据经环境变量注入、脚本零硬编码口令

#### Scenario: 隔离恢复演练

GIVEN 一次真实备份产物

WHEN 在一次性隔离 MySQL 容器内执行恢复脚本

THEN 恢复后表数与 dump 表数一致且关键行抽样逐值一致

AND 演练不向任何共享环境写入

#### Scenario: 恢复防护

WHEN 执行恢复脚本

THEN 目标容器与库名须显式指定且恢复前打印 dump 元信息并要求显式确认

AND 缺确认即拒绝执行（防误恢复生产库）

#### Scenario: 轮转与幂等

GIVEN 备份产物目录内已存在超过保留份数的旧 dump

WHEN 再次执行备份

THEN 过期文件按保留份数删除且脚本可被宿主 cron 幂等调用

AND 仓库不含任何自动执行者（定时属宿主 crontab 文档化示例）

#### Scenario: 人工执行台账

WHEN 每次真实备份或恢复演练完成

THEN 于 BACKUPS.md 台账登记日期、环境、文件名、sha256 与演练结论

AND 该登记为人工义务（对齐 migrations APPLIED.md 纪律，脚本入库不等于备份已运行）

#### Scenario: 丢失边界文档化

WHEN 维护者查看备份 README

THEN Redis 草稿、RabbitMQ 持久化消息、binlog 三者的丢失边界与既有事实（appendonly/durable/log-bin）如实陈述并对齐 data-consistency 域既有结论

AND 不新造任何新的持久化承诺、异地容灾与 PITR 显式排除

---

### Requirement: Redis 依赖口径与故障 runbook (add-redis-dependency-runbook)

WHEN 运维或开发需要回答「Redis 故障时系统各面如何表现、如何恢复」,

系统 SHALL 以 canonical runbook 文档承载裁决口径、键族降级矩阵、告警映射与恢复流程, AND SHALL 使每个降级计数指标都有对应告警规则。

#### Scenario: 键族降级矩阵文档化且与代码对齐 (add-redis-dependency-runbook)

GIVEN 仓库存在多族 Redis 键（会话黑名单/版本、登录守卫、限流、交卷锁、行为计数、在线状态、错题判定缓存、草稿、令牌签发续期、找回码、Spring Cache 快照与详情）

WHEN 查阅 docs/redis-dependency-runbook.md 的键族矩阵

THEN 每族故障行为（降级放行/fail-open/既有降级/硬依赖失败）与代码实态一致

AND 硬依赖面（草稿暂存、令牌签发与续期、Spring Cache 快照与详情、找回验证码）如实标注为硬依赖，不得写成已降级

#### Scenario: 降级计数告警映射完整 (add-redis-dependency-runbook)

GIVEN 仓库已有 exam.ratelimit.degraded / exam.auth.degraded 降级计数告警先例

WHEN 出现新的降级计数指标（如 D1 的 exam.submit.lock.degraded）

THEN 同族收口卡 SHALL 补齐对应告警规则（SubmitLockDegraded 照抄 AuthDegraded 形态）

AND AlertAssetsTest 以 EXPECTED_ALERTS 集合断言与 known 指标名断言守护规则与代码不漂移

#### Scenario: 恢复流程与学生端指引文档化 (add-redis-dependency-runbook)

GIVEN Redis 故障恢复后各降级面按键族 TTL 自愈、交卷对账扫描自动补发

WHEN 查阅 runbook 恢复流程节

THEN 恢复流程按「确认告警 → 恢复实例（不清数据）→ 观察自愈 → 复核降级半径申诉」给出可执行步骤

AND 故障期学生端行为指引（不刷新以防草稿丢失、交卷口径、新登录失败预期、找回密码不可用）与矩阵口径一致

#### Scenario: 口径变更须走变更链 (add-redis-dependency-runbook)

GIVEN runbook 是 Redis 依赖口径的 canonical 事实源

WHEN 任何触碰 Redis 调用点的变更（新增键族/降级/容错）

THEN 先读 runbook 并在其矩阵中登记新键族行为

AND 新降级计数指标必须有对应告警规则并过 AlertAssetsTest 守护

---

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

THEN base 必须声明 spring.task.scheduling.pool.size 且缺省值 >=2（词法护栏测试）

AND dev/prod 若未来出现同名覆盖，其值同样必须 >=2

#### Scenario: 变异击红 (harden-scheduler-pool-config)

GIVEN 把声明值改回 1 或删除声明

WHEN 运行调度池护栏测试

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）
