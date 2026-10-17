# harden-submit-lock-redis-degradation

## 一、背景与问题（B.2 横切首扫 H2 残留面 · Redis 依赖裁决 D1 卡，2026-10-17 立案）

**裁决背景（用户已拍板，2026-10-17）**：Redis 故障口径统一为「**正确性由 DB 幂等保证，Redis 降级保可用性，草稿为已声明的硬依赖面**」。A1 卡（harden-login-guard-redis-degradation，已闭环）落定了登录守卫侧；本卡（D1）落定交卷 SETNX 锁侧。

`ExamSubmitService.doSubmit`（交卷三路竞态唯一入口）的第一重 SETNX 一次性锁（`redisTemplate.opsForValue().setIfAbsent`，key `exam:submit:lock:{examId}:{studentId}`）对 Redis 异常零容错——Redis 挂时异常从 doSubmit 裸穿，**交卷全停摆**（学生已作答完成却无法交卷）。同仓库已有同源降级先例：限流 fail-open、拦截器降级放行、登录守卫降级放行（A1），交卷锁是与降级哲学不一致的最后一处非草稿 Redis 故障半径。

## 二、方案（两类失败严格区分：降级口只认不可用，不认竞争）

- **Redis 连接异常（setIfAbsent 抛异常）→ 降级放行**：包 try-catch（try 只包 Redis 调用本身）→ ERROR 日志（exam/student/异常/requestId 对齐先例格式）+ 新降级计数指标 `exam.submit.lock.degraded`（BusinessMetrics.countSubmitLockDegraded，无 tag 单 Counter，命名对齐 exam.ratelimit.degraded / exam.auth.degraded 既有风格；AlertAssetsTest known 集合同笔守护登记）→ 直接继续锁后链路（防重表先查后插 + 状态机 CAS + MQ 削峰）；不记锁等待/锁竞争指标、不执行按 token 解锁（从未持锁，token 未写入 Redis，unlock 语义上无对象）；
- **锁被真占（setIfAbsent 返回 false）→ 现有路径语义不变**：recordLockContention + waitForConcurrentSubmit 短轮询幂等等待——竞争是正常业务形态，不是故障，降级口不吞；setIfAbsent 返回 null 的防御分支与既有 `!Boolean.TRUE.equals(locked)` 口径合并保持不变；
- **正确性论证（proposal 落档，reliability spec「不引入调度中间件与锁的取舍」Scenario「幂等是正确性的唯一保证」同源推广）**：交卷只发生一次由三层 DB 幂等保证——①防重表先查后插 + uk_submit_dedup 唯一索引兜底并发插入；②状态机 CAS（casSubmitToSubmitted，进行中→已交卷仅一次，唯一赢家才发 MQ）；③消费端 casFillAnswers（仅 answers IS NULL 时写入，重复投递业务只执行一次）。SETNX 锁只是把三路竞态收敛为单飞、省 DB/MQ 压力的**防抖器**——锁降级只影响防抖半径（故障期间并发交卷各自走完整幂等链，输家被 CAS/唯一索引裁决后幂等返回），不构成数据错误；
- **草稿边界（如实登记，不动）**：降级放行的是锁；草稿（ExamDraftService）仍是裁决声明的**硬依赖面**——学生端带答案载荷的手动/归零交卷不触草稿读，降级后可正常交卷；后端兜底 forceSubmitByBackend 需读 Redis 草稿，Redis 故障期该路径在草稿读取处失败（属草稿硬依赖语义，非本卡范围；runbook 卡 B2+C2 如实登记该边界）。resolveAnswers 的既有「载荷→草稿→空答案」优先级零改动；
- 不动：锁 TTL 配置、锁内二次确认、防重表/CAS/MQ/草稿暂存兜底、waitForConcurrentSubmit、按 token 解锁（lockHelper）。

## 三、白名单

- `src/main/java/com/exam/taking/service/ExamSubmitService.java`（SETNX 容错 + 降级放行）
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（countSubmitLockDegraded 新指标）
- `src/test/java/com/exam/observability/AlertAssetsTest.java`（known 集合守护登记 exam_submit_lock_degraded_total）
- `src/test/java/com/exam/taking/service/ExamSubmitServiceTest.java`（容错单测）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-submit-lock-redis-degradation/**`、
  `spec/README.md`、`docs/功能待办清单.md`
- **禁触（范围外，本卡不动）**：`src/main/java/com/exam/taking/service/ExamDraftService.java`（草稿 Redis 存储——裁决为硬依赖面，无 DB 兜底）、
  `src/main/java/com/exam/config/CacheConfig.java`（RedisCacheManager）、
  `src/main/java/com/exam/common/cache/RedisLockHelper.java`、`CacheMutexLoader.java`（解锁原语零改动）、
  `src/main/java/com/exam/submission/mq/**`（MQ 发布路径零改动）、
  `docker/observability/**`（告警规则零改动——新告警规则由 runbook 卡 B2+C2 告警盘点统一处置）；另禁触 schema.sql、docker/mysql/migrations/、openapi.yaml、pom.xml、frontend/。

## 四、验收要点

- 容错单测三断言（对齐拦截器/A1 先例形态）：setIfAbsent 抛异常时交卷照常完成（防重表 + CAS + MQ 走通、返回已交卷）+ ERROR 日志落（ListAppender）+ countSubmitLockDegraded 递增；负向：降级路径不记锁竞争/锁等待、不执行 unlock；回归：锁竞争（false）路径语义不变且零降级计数（降级口只认不可用不认竞争）
- 变异击红：还原「锁异常即失败」旧形态（摘除 try-catch 异常上抛）→ 容错用例确定性失败 → 复原回绿，留证；等价变异如实登记不强杀
- 范围外零改动核对（ExamDraftService / CacheConfig / RedisLockHelper / CacheMutexLoader / submission/mq + schema.sql + docker/mysql + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 649 上只增不减，收口笔同笔上调
- M 级理由：交卷链路既有测试密集（7 用例）需零回归；新指标 + 告警守护登记 + 正确性论证需落档；两类失败区分的语义边界（降级口不认竞争）需测试钉住
