# 阶段 2 证据留证：行为上报与监考面加固（harden-behavior-report-and-monitor）

本目录记录阶段 2 的完整证据链：后端单测**先红后绿**、五项变异 **m1/m2/m3/m4/m5 击红-复原-回绿**、
全量后端门禁实测，以及关键红线核对。

- 分支：`feature/harden-behavior-report-and-monitor`
- 基线 revision：`2c88cea`（阶段 0&1 立项笔）
- 受控白名单：仅 `src/main` 与 `src/test` 的 `com/exam/taking/**`、`com/exam/anticheat/**`、
  `com/exam/monitoring/**`；**零 DDL**（`schema.sql` 零 diff）、`docker/`、`openapi.yaml`、
  `pom.xml`、`frontend/` 均未触碰。
- 命令均在仓库根执行，留证日志为完整控制台输出的裁剪版（仅保留判定性行与结果汇总）。

---

## 一、后端单测先红后绿

新增 / 改写的用例：
- 新增 `src/test/java/com/exam/anticheat/service/BehaviorEventCollectServiceTest.java`（12 例）：
  明细体积边界 4096/4097、UTF-8 字节口径（中文明细字符不超但字节超）、超限先于策略分派
  （不落库不打点）、null 明细放行、未注册类型兜底照常落库；时钟锚定 ±30 分、
  10 分 1 秒（锚定）/ 9 分 59 秒、9 分钟（保留）/ null 缺省；
- 新增 `src/test/java/com/exam/anticheat/service/BehaviorLogQueryServiceTest.java`（2 例）：
  1005 行 → items 截到 1000、统计全量不失真（窄投影 SQL 仅 severity 列、无 LIMIT、
  不载 event_data，Executor 层取数护栏断言）、明细 LIMIT+event_time/id 升序、
  存量少行全量返回；
- 新增 `src/test/java/com/exam/taking/controller/ExamTakingControllerRateLimitGuardTest.java`（3 例）：
  reportBehavior 三参数定档（200/1000/"behavior-report"）、qps 量级介于 enter/submit 之间、
  @PostMapping 写端点无裸端点；
- 改写 `src/test/java/com/exam/monitoring/metrics/BusinessMetricsTest.java`（+3 例）：
  未注册类型归并 unknown、七注册常量各占 tag 不落 unknown、注册集与 BehaviorEventTypes 反射对齐；
- 适配构造签名变更：`BehaviorEventCollectorTest`、`DlqObservabilityTest`。

### 红（实现前）

4 个新测试类先于实现运行：
`mvnw.cmd clean test -Dtest=BehaviorEventCollectServiceTest,BusinessMetricsTest,BehaviorLogQueryServiceTest,ExamTakingControllerRateLimitGuardTest`
→ `Tests run: 29, Failures: 13` **BUILD FAILURE**，逐项击红（摘录）：
- 限流缺失：`reportBehavior 必须声明 @RateLimit（裸写端点＝可无限刷写）`×3；
- 体积闸门缺失：超限明细未抛 400「事件明细过大」×3（含未注册类型先于分派拒绝、UTF-8 字节口径）；
- 时钟锚定缺失：偏差超阈值未按服务端 now 落库 ×3；
- 指标归并缺失：未注册类型未归并 tag "unknown"；
- 时间线拆查缺失：统计窄投影查询不存在（执行次数=0）×2。
- 日志：`red-before-impl.log`
- 注：该轮红同时暴露了部分新用例自身的夹具口径问题（精确字节夹具、秒级边界），
  属测试开发期修正，修正后全部纳入最终绿集——最终强度以变异击红为准。

### 绿（实现后）

实现后重跑 5 个相关测试类 → `Tests run: 35, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。
- 日志：`green-after-impl.log`

---

## 二、变异击红留证（m1 / m2 / m3 / m4 / m5）

每项均执行：注入违规 → 跑判定用例子集（35 例）确认变红 → 快照复原（逐字节还原，非 git checkout）→ 确认回绿。
判定子集：`-Dtest=BehaviorEventCollectServiceTest,BehaviorLogQueryServiceTest,BusinessMetricsTest,ExamTakingControllerRateLimitGuardTest,BehaviorEventCollectorTest`

### m1 —— 上报端点去限流

- 注入点：摘掉 `ExamTakingController.reportBehavior` 的 `@RateLimit` 注解。
- 击红：`Tests run: 3, Failures: 3`（`ExamTakingControllerRateLimitGuardTest` 三例：注解缺失、
  量级排序失据、裸写端点回归）。日志：`m1-ratelimit-red.log`
- 复原 + 回绿：`Tests run: 35, Failures: 0` **BUILD SUCCESS**。日志：`m1-ratelimit-green.log`

### m2 —— eventData 体积上限失效

- 注入点：移除 `BehaviorEventCollectService.collect` 入口的 `requireEventDataWithinLimit(eventData)`。
- 击红：`Tests run: 12, Failures: 3`（超限整笔拒绝 / 先于分派 / UTF-8 字节口径，均
  `Expected BusinessException to be thrown`）。日志：`m2-sizegate-red.log`
- 复原 + 回绿：`Tests run: 35, Failures: 0` **BUILD SUCCESS**。日志：`m2-sizegate-green.log`

### m3 —— occurredTime 服务端锚定失效

- 注入点：`Duration.between(now, occurredTime).abs()` → `Duration.ZERO`（锚定分支永不触发）。
- 击红：`Tests run: 12, Failures: 3`（过去/未来 30 分钟、10 分 1 秒均未锚定，
  落库时间=客户端声称时间）。日志：`m3-skew-red.log`
- 复原 + 回绿：`Tests run: 35, Failures: 0` **BUILD SUCCESS**。日志：`m3-skew-green.log`

### m4 —— 时间线统计窄投影删除（统计口径与 items 同源截断）

- 注入点：删除 `selectSeverities` 窄投影统计块（total 恒 0，统计只来自明细行集）。
- 击红：`Tests run: 2, Failures: 2`（统计查询恰一次=0、total 1005→1000——截断污染统计口径）。
  日志：`m4-projection-red.log`
- 复原 + 回绿：`Tests run: 35, Failures: 0` **BUILD SUCCESS**。日志：`m4-projection-green.log`

### m5 —— 统计改由截断明细重算

- 注入点：`setItems(...)` 后追加 `setTotal(logs.size())` 并重算三档计数（与 m4 互补，
  锁统计即使窄投影存在也被截断明细覆盖）。
- 击红：`Tests run: 2, Failures: 1`（total 1005→1000——统计口径保全断言击中）。
  日志：`m5-stats-red.log`
- 复原 + 回绿：`Tests run: 35, Failures: 0` **BUILD SUCCESS**。日志：`m5-stats-green.log`

### 等价变异体登记（不强杀）

`mutants.md` 文末登记 eq1（`return now;` → `return LocalDateTime.now();` 纳秒级等价）、
eq2（上限 +1，边界对可杀但非必要）、eq3（tag 改名，spec 定档口径）、
eq4（阈值 +1ns，断言口径 1 秒级无法区分）——如实登记，不投击红成本。

---

## 三、全量后端门禁实测

- 命令：`mvnw.cmd clean test`（仓库根）
- 结果（原始输出）：`Tests run: 587, Failures: 0, Errors: 0, Skipped: 1`（Skipped 为存量用例），`BUILD SUCCESS`。
- 日志：`full-gate.log`
- 本阶段按分工只实施 + 留证，`gate-baseline.json` 上调属阶段 3 收口笔（backendTests 预期 +29）。

---

## 四、红线核对

| 红线 | 落实 | 证据 |
|---|---|---|
| 上报限流 200/1000/"behavior-report" 按既有三端点风格论证 | `reportBehavior` 补 `@RateLimit`，注释论证量级介于 enter/submit、独立 key 隔离 | `ExamTakingControllerRateLimitGuardTest`（三例）/ m1 击红 |
| eventData ≤4096 字节，校验先于策略判定、不落库不打点 | `MAX_EVENT_DATA_BYTES=4096`，`collect` 入口最先校验，超限抛 400「事件明细过大」 | `BehaviorEventCollectServiceTest` 体积组 / m2 击红 |
| occurredTime 偏差 >10 分钟按服务端 now 落库 | `MAX_CLOCK_SKEW=10min`，只锚定不拒绝 | 锚定组用例 / m3 击红 |
| 未注册类型归并 tag "unknown"，落库/判定语义不变 | `countAntiCheatEvent` 以 `BehaviorEventRegistry.registeredTypes()` 判别；兜底策略照常落库 | `BusinessMetricsTest` 归并组；`BehaviorEventCollectorTest` 6 例绿 |
| timeline 拆两查：统计窄投影保全 total/三档计数，明细 LIMIT 1000 升序，items 如实 | `selectSeverities`（仅 severity 全量）+ `selectList ... .last("LIMIT 1000")` | `BehaviorLogQueryServiceTest`（Executor 护栏）/ m4、m5 击红 |
| 切屏只警告不强制交卷 | 策略判定与响应形状未动 | 既有集成用例绿（full-gate.log） |
| 时间线教师可见性/权限校验不变 | `timeline` 首行 `requireOwnedExam` 未动 | 源码 diff；`BehaviorLogQueryServiceTest` 以教师身份调用 |
| 测试严禁真实 `@Sql` 注解 | 新增/改写用例无 `@Sql` | `grep -rn '@Sql' src/test` 唯一命中为既有类注释 |

### 未触碰面

- `schema.sql`：零 diff（零 DDL）；`docker/`、`openapi.yaml`、`pom.xml`、`frontend/`：零 diff。
- 未注册事件类型照常落库（兜底策略）、切屏警告语义、时间线权限双层控制：零改动。
- 构造签名变更（`BusinessMetrics` +`BehaviorEventRegistry`、`BehaviorEventCollectService` +`ObjectMapper`）仅涉及白名单内包；`BehaviorEventCollectorTest`、`DlqObservabilityTest` 已同步适配。
