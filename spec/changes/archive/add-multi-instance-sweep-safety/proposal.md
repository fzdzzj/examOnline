# 提案：定时扫描的多实例安全（阶段 13）

## Why

项目有 **两个 `@Scheduled` 扫描**，它们没有任何分布式协调，而"多实例部署会不会重复执行"是这类设计必被追问的一层：

| 定时任务 | 周期 | 作用 |
|---|---|---|
| `ExamStateMachineService.scheduledAdvance` | 10s | 未开始→进行中、进行中→已结束（并触发缺考标记） |
| `ExamSweepService.scheduledSweep` | 10s | 超时强制交卷 + 已交卷未落库的答案补发对账 |

### 已核实的事实（逐条可复算）

**1）没有任何分布式协调设施，且是有意为之。**
`pom.xml` 里 `grep 'shedlock|quartz|redisson|curator|zookeeper'` **零命中**；`ExamStateMachineService` 的类注释写着「定时推进不依赖外部调度中间件」。所以多实例下**每个实例都会各扫一遍**。

**2）两条扫描 SQL 都没有实例分片，两个实例必然取到同一批行。**

```sql
-- ExamSubmissionMapper
SELECT * FROM exam_submissions WHERE status = 2 AND answers IS NULL LIMIT #{limit}   -- 补发对账
```
`selectForceSubmitCandidates(now, limit)` 同理——**无 `ORDER BY`、无实例维度条件**。N 个实例 → 同一批行被 N 次处理。

**3）正确性靠下游幂等兜住，但没有任何测试证明这件事。**
- 强制交卷走 `forceSubmitByBackend` → 共享 SETNX 锁 + 状态机 CAS（`进行中→已交卷`），CAS 0 行即幂等返回、不重复发消息；
- 答案落库走 `casFillAnswers`（仅在 `answers IS NULL` 时写）。
现有 `ExamTakingIntegrationTest.threeWayRaceSubmitsOnlyOnce` 证明的是「手动 / 前端归零 / 后端兜底**三路**并发只提交一次」——**不是**「两个 `sweep()` 并发只生效一次」。`ExamSweepService` 的并发行为**零覆盖**，也没有任何文档说明这个取舍。

**4）它依赖的那把 SETNX 锁，"解锁"这一步有洞——而同一仓库里就有做对的实现。**

```java
// ExamSubmitService.doSubmit:119  把 token 写进了锁值
.setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofSeconds(lockTtlSeconds));
// :168-171  finally 只按 key 删，那个 token 完全没用
} finally {
    redisTemplate.delete(lockKey);
}
```

TTL = `exam.taking.submit.lock-ttl-seconds`（默认 30）。持锁一旦超过 TTL，线程 A 的 `finally` 会**删掉线程 B 的锁** → B 的等待者提前退出、第三个线程也能进锁区。对照 `common/cache/CacheMutexLoader:49`：

```java
"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
```
外加 `unlock(lockKey, token)` 在 `finally` 里调用（:96、:159）——**做对了**。同一仓库两种写法，说明是遗漏而非取舍。

> **诚实定性（重要）**：这一条**不构成数据错误**——CAS 与唯一索引仍然保证「一次交卷、一次落库」。它的实际后果是**「三路竞态收敛为一个执行流」这层设计语义被削弱**，以及 TTL 过期后多个线程同时进锁区打 DB/MQ（在 5000 并发场景下会放大压力）。把它修掉是为了让"单飞"名副其实，而不是因为数据会错。

### 期望状态

1. 「并发扫描只生效一次」**有测试证明**，而不是靠注释声称；
2. 那把 SETNX 锁的解锁与 `CacheMutexLoader` 对齐（带持有者校验）；
3. **多实例策略被显式写下来**：为什么选择"不锁、靠幂等"，代价是什么，什么情况下才需要加锁；
4. 重复扫描这件事**可观测**（能看出"同一份答卷被多个实例处理过"），否则将来排障无从下手。

## What Changes

### 1. 修复 `ExamSubmitService` 的锁解锁（带 token 的 compare-and-delete）

- 把 `CacheMutexLoader` 里那段 Lua（`get == token` 才 `del`）抽成**共享助手**（例如 `common/cache/RedisLockHelper` 的 `unlock(key, token)`），两处复用同一份实现——**不允许复制粘贴两份 Lua**，否则下次又只改一处。
- `ExamSubmitService.doSubmit` 的 `finally` 改为按 token 解锁；锁值那个 `UUID` 从此真正被使用。
- `CacheMutexLoader` 改为调用同一助手（行为不变，只是去重）。
- 注释写明为什么必须校验 token：TTL 到期后锁可能已易主，无条件 `del` 会删掉别人的锁，单飞语义直接被破坏。

### 2. 新增并发证据：`MultiInstanceSweepSafetyTest`

用「两个线程同时执行同一轮扫描」来模拟双实例（同构、无需真起两个进程），断言：

- **并发 `sweep()` 只产生一次状态迁移**：同一份超时答卷并发扫描后只有一条 `已交卷`，`submit_type` 唯一；
- **只发一次交卷消息**：按 `(examId, studentId)` 过滤 `ArgumentCaptor` 捕获的消息（照抄既有用例的过滤手法——H2 数据跨用例保留，不过滤会被其他用例的遗留答卷污染）；
- **并发结束只标记一次缺考**：两个线程同时触发同一场考试的结束（一个走 `force-end`、一个走 `autoAdvance`），断言 `exam_absence` 该考试下每人恰好一行；
- **并发 `autoAdvance()` 只推进一次**：同一场考试状态只迁一次（CAS 0 行的那一路静默跳过）。

### 3. 多实例策略显式化（不加分布式调度锁）

- 在 `ExamSweepService` / `ExamStateMachineService` 类注释里写明：**本任务刻意不加分布式锁**，正确性由下游幂等（CAS + 唯一索引 + `INSERT IGNORE`）保证，并有测试证明；代价是 N 实例重复扫描（重复投递、日志噪音），换来的是**定时兜底不依赖 Redis 可用性**（若加锁，Redis 故障会让兜底扫描整体停摆——那比重复扫描严重得多）。
- 把"若将来要省资源"的可选路径也写进注释（Redis 调度锁 + **fail-open**：抢不到锁或 Redis 异常时**照常执行**，退化回"重复扫但幂等"），避免后人误加一把 fail-close 的锁把兜底能力锁死。

### 4. 重复扫描可观测

- `BusinessMetrics` 新增计数 `exam.sweep.duplicate_detected`（或等价命名），在"扫描命中但下游已处理"（CAS 0 行 / `filled == 0`）时递增，tag 用 `task` 区分 `sweep` / `state-advance`。
- 作用：**多实例重复扫描从"看不见"变成"有数字"**。单实例下该值应≈0；若持续增长，说明多实例在重复扫，可据此判断要不要加调度锁——**用数据而不是感觉决定**。
- 不新增告警规则（该指标是诊断用，不是故障信号）。

## Impact

### 受影响的规范
- `spec/specs/reliability/spec.md` — 新增（`ADDED`）：定时任务的并发与多实例安全（幂等保证、重复扫描可观测、刻意不加调度锁的取舍）。

### 受影响的文件（写入边界）
- 新增 `src/main/java/com/exam/common/cache/RedisLockHelper.java`（共享 Lua 解锁助手）
- 修改 `src/main/java/com/exam/taking/service/ExamSubmitService.java`（`finally` 改按 token 解锁）
- 修改 `src/main/java/com/exam/common/cache/CacheMutexLoader.java`（改调共享助手，行为不变）
- 修改 `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（新增重复扫描计数）
- 修改 `src/main/java/com/exam/taking/service/ExamSweepService.java` + `src/main/java/com/exam/exam/service/ExamStateMachineService.java`（仅补类注释 + 埋点）
- 新增 `src/test/java/com/exam/taking/MultiInstanceSweepSafetyTest.java`
- 修改 `src/test/java/com/exam/monitoring/metrics/BusinessMetricsTest.java`（若既有用例断言指标集合，需同步）

**不要触碰**：`schema.sql`、`pom.xml`（**本提案不引入任何新依赖**，ShedLock/Redisson 明确不引）、`application.yml` 的既有键、`ExamSubmitService` 的幂等主流程（只改 `finally` 一处）、MQ 拓扑。

### 需要迁移
- [ ] 数据库迁移（无）
- [ ] 配置变更（无新增开关；不提供"是否加锁"的配置，因为它已按取舍定为不加）
- [x] 文档更新（本提案 + 规范 + 两个 Service 的类注释）

## 时间线评估

小到中：约 1 天（W14）。

## 风险

- **改 `CacheMutexLoader` 有回归风险**（缓存击穿保护是性能路径）。缓解：只把内联 Lua 换成共享助手、逻辑逐字不变；`CacheThreeDefensesTest` 与 `CacheWiringIntegrationTest` 必须保持全绿；如风险不可控，可先只改 `ExamSubmitService`、把 `CacheMutexLoader` 的合并留到后续（**允许这样收敛**，但要在回报里说明）。
- **并发测试可能偶发不稳**。缓解：不用 `Thread.sleep` 凑时序，用 `CountDownLatch` 让两线程同时起跑（照抄 `threeWayRaceSubmitsOnlyOnce` 的模式）；断言只针对本用例新建的 `(examId, studentId)`，不依赖全局计数。
- **"不加锁"这个结论可能被质疑**。这正是提案的价值：把取舍写清并给出**可观测的判据**（重复扫描计数），而不是拍脑袋。若重复扫描计数在生产持续偏高，再引入带 fail-open 的调度锁，那时候是数据驱动而非猜测。
- **`exam_absence` 并发标记依赖唯一索引**。`uk_absence_exam_student` + `INSERT IGNORE` 已在阶段 12 被首个真实用例执行过（若阶段 12 已归档）；若未归档，本提案的并发断言会与阶段 12 的用例**同时**覆盖这条 SQL——两处都保留，不冲突。
