# 子 agent 提示词 —— `add-multi-instance-sweep-safety`（阶段 13）

> 用法：整份复制发给子 agent。它是自包含的，不依赖任何先前对话。

---

## 任务

在 `D:\code\examOnline` 这个 Java 17 + Spring Boot 3.5.5 单体项目上，实施已审批的变更提案 `spec/changes/add-multi-instance-sweep-safety/`。

**先读这两个文件，它们是本任务的唯一权威需求来源：**
- `spec/changes/add-multi-instance-sweep-safety/proposal.md`（为什么做、改什么、风险）
- `spec/changes/add-multi-instance-sweep-safety/tasks.json`（4 个阶段、17 个 step，逐个做）

**提案的核心命题（一句话）**：项目有两个 `@Scheduled` 扫描（`ExamStateMachineService.scheduledAdvance`、`ExamSweepService.scheduledSweep`）没有任何分布式协调，"多实例会不会重复执行"这件事目前**只有注释声称、没有测试证明**；同时这两个扫描依赖的那把 SETNX 锁（`ExamSubmitService.doSubmit`）**解锁时没有校验持有者**（token 生成了却从没用过），而同一仓库的 `CacheMutexLoader` 是**做对了的**。本任务 = 修掉锁、给出并发证据、把取舍写下来、让重复扫描有数字。

---

## 必须遵守的项目硬约定（违反任何一条都算未完成）

1. **集成测试禁止用 `@Sql` 自建表**。测试库建表只以 `src/main/resources/schema.sql` 为唯一来源（`application-test.yml` 已配 `mode: always` + `continue-on-error: false`）。这是项目硬约定，写进了 `spec/README.md` 工作流第 5 条。**本任务结束后 `grep -rn '@Sql' src/test` 必须为空。**
2. **不新增任何依赖**。`pom.xml` 不得出现 shedlock / quartz / redisson / curator。本提案明确"刻意不加分布式调度锁"，引入调度中间件等于推翻提案。
3. **不要用 `Thread.sleep` 凑时序**。并发用例用 `CountDownLatch` 让两线程同时起跑，照抄既有 `ExamTakingIntegrationTest.threeWayRaceSubmitsOnlyOnce` 的起跑模式。
4. **断言不要用全局计数**。H2 数据跨用例保留，`ArgumentCaptor` 捕获消息后必须按 `(examId, studentId)` 过滤，只用本用例新建的数据做断言。
5. **`ExamSubmitService` 只改 `finally` 一处**（锁释放），不改幂等主流程、不改 `doSubmit` 的 CAS/MQ 顺序。
6. **`CacheMutexLoader` 的行为必须逐字不变**（它是缓存击穿保护，属性能路径）。只把内联 Lua 换成共享助手。
7. **`@Transactional(rollbackFor = Exception.class)`** 是本项目统一写法，新增的事务方法照此写。
8. 包/表命名坑：`class` 是关键字 → 包 `com.exam.clazz`、实体 `ClassEntity`、表 `classes`。
9. 强一致读（答卷详情/成绩）**不加 `@DS("slave")`**，走主库。本任务不涉及，但别顺手加。

---

## 写入边界（严格遵守）

**允许新增**：
- `src/main/java/com/exam/common/cache/RedisLockHelper.java`
- `src/test/java/com/exam/taking/MultiInstanceSweepSafetyTest.java`

**允许修改**：
- `src/main/java/com/exam/common/cache/CacheMutexLoader.java`（改调共享助手，行为不变）
- `src/main/java/com/exam/taking/service/ExamSubmitService.java`（仅 `finally` 的锁释放）
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（新增重复扫描计数）
- `src/main/java/com/exam/taking/service/ExamSweepService.java`（仅类注释 + 埋点）
- `src/main/java/com/exam/exam/service/ExamStateMachineService.java`（仅类注释 + 埋点）
- `src/test/java/com/exam/monitoring/metrics/BusinessMetricsTest.java`（若既有断言依赖指标集合，需同步）

**绝对不要触碰**：`schema.sql`、`pom.xml`、`docker/` 下任何文件、`application.yml` 的既有键、`ExamSubmitService` 的幂等主流程、MQ 拓扑（`RabbitMqConfig`）、以及仓库里其他任何文件。

如果发现必须越界才能完成，**停下来在回报里说明，不要擅自改**。

---

## 构建与测试命令（本机环境特殊，必须用这条）

本机 `JAVA_HOME` 未设置，PATH 里 `java` 是 1.8 而 `javac` 是 21，Git Bash 的 `mvn` 脚本跑不起来（Plexus classworlds Launcher 路径错）。**必须直调 launcher**：

```bash
cd /d/code/examOnline
'D:\develop\jdk177\bin\java.exe' -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

单类跑：把结尾 `-o test` 换成 `-o test -Dtest=MultiInstanceSweepSafetyTest -DfailIfNoTests=false`。
`-o`（离线）必须带，`.mvn/maven.config` 会自动附加 `-s maven-settings.xml`。

**开工第一件事**：先跑一次全量测试，**记下基线数字**（tests run / failures / errors）。收尾时的数字要与基线对齐（只增不减、全绿）。

---

## 实施要点（提案里已论证，这里给出可执行口径）

**阶段 1 — 锁**
- 从 `CacheMutexLoader` 抽出那段 Lua：`if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end`，放进 `RedisLockHelper.unlock(key, token)`；可再加 `tryLock(key, token, ttl)` 统一加锁写法。
- `CacheMutexLoader` 改为调用助手，删掉内联 Lua 与私有 unlock 方法。
- `ExamSubmitService.doSubmit` 的 `finally`：`redisTemplate.delete(lockKey)` → 按 token 解锁（token 在 `setIfAbsent` 时已生成，此前从未被使用）。TTL = `exam.taking.submit.lock-ttl-seconds`（默认 30）。
- **必须写明注释**：TTL 到期后锁可能已易主，无条件 `del` 会删掉别人的锁，单飞语义直接被破坏。
- 定性要诚实：这**不构成数据错误**（CAS + 唯一索引仍保证一次交卷），修它是为了让"三路竞态收敛为一个执行流"这层语义名副其实、并避免 TTL 过期后多线程同时进锁区打 DB/MQ。

**阶段 2 — 并发证据（4 个用例）**
`MultiInstanceSweepSafetyTest` 继承 `IntegrationTestBase`（`@SpringBootTest` + MockMvc + H2 + 真实 Redis db15），`@MockitoBean RabbitTemplate`，用两个线程同构模拟双实例：
- `concurrentSweepForcesSubmitOnce`：造一份超时答卷 → 两线程同时 `sweepService.sweep()` → 该 `(examId, studentId)` 只有一条已交卷、`submit_type` 唯一、MQ 只发 1 条属于它的消息。
- `concurrentSweepRepublishesWithoutDuplicating`：造"已交卷但 `answers` 为 NULL"的答卷 → 两线程同时 `sweep()` → 消息可被重复投递，但驱动消费者落库后 `answers` 只写一次（消费端 `casFillAnswers` 幂等），最终与草稿一致。
- `concurrentEndMarksAbsenceOnce`：同一场已结束考试，两线程分别走 `force-end` 与 `autoAdvance()` → `exam_absence` 该考试下每个 student 恰好一行（`uk_absence_exam_student` + `INSERT IGNORE`）。
  - 注意：本用例依赖"`force-end` 也会标记缺考"。**若阶段 12 的 `add-post-exam-closure-e2e` 尚未落地，这条路径就是当前缺陷**——此时请把该用例改成"两条都会标记的路径并发"（例如两个线程都走 `autoAdvance`），并在回报里说明你做了这个替代，不要假装 `force-end` 已修。
- `concurrentAutoAdvanceAdvancesOnce`：同一场到期考试，两线程同时 `autoAdvance()` → 状态只迁一次（另一路 CAS 0 行静默跳过）、`version` 只 +1。

**阶段 3 — 策略显式化 + 可观测**
- 两个 Service 的类注释写明：**刻意不加分布式调度锁**；正确性由下游幂等（CAS + 唯一索引 + `INSERT IGNORE`）保证**并有测试证明**；代价是 N 实例重复扫描；换来的是**定时兜底不依赖 Redis 可用性**（若加锁，Redis 故障会让兜底扫描整体停摆，那比重复扫描严重得多）。
- 注释同时写明"将来若要省资源的可选路径"：Redis 调度锁**必须 fail-open**（抢不到锁或 Redis 异常时照常执行，退化回重复扫但幂等），**禁止**加 fail-close 的锁把兜底能力锁死。
- `BusinessMetrics` 新增 `exam.sweep.duplicate_detected`（Counter，tag `task` = `sweep` / `state-advance`），仿既有 `countAntiCheatEvent` 的 `ConcurrentHashMap` + `computeIfAbsent` 写法。埋点位置："扫描命中但下游已处理"（`forceSubmitByBackend` 因业务竞态跳过、消费者 `filled == 0`）。
- **不新增告警规则**（诊断用指标，不是故障信号）。

**阶段 4 — 回归**
- 全量测试须全绿，与开工基线对齐。
- 确认 `CacheThreeDefensesTest`、`CacheWiringIntegrationTest` 全绿（证明抽出助手没改缓存三防行为），且 `ExamTakingIntegrationTest.threeWayRaceSubmitsOnlyOnce` 仍全绿。

---

## 回报格式（请严格按此回报）

1. **基线**：开工前 `tests run / failures / errors` 三个数字。
2. **改动清单**：每个文件一行，说明改了什么（新增/修改，关键方法名）。
3. **新增用例清单**：用例名 + 它证明了什么（一句话）。
4. **收尾数字**：全量 `tests run / failures / errors` + 新增用例数。
5. **逐条对照 `tasks.json`**：每个 step 是"已完成"还是"未完成/替代做法"，未完成的**必须说明原因**，不要勾满。
6. **意外发现**：任何与提案描述不符的事实（例如某个你以为存在的索引/方法其实不存在），如实写。
7. **不要自称"已验证"**：只能报告你实际跑过的命令与输出数字。

**禁止**：为了让测试变绿而放宽断言、注释掉用例、加 `@Disabled`、或修改 `schema.sql` 去迁就测试。
