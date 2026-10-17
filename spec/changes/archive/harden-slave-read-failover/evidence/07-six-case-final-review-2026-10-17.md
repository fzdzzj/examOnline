# 六案批次最终复核签字（终核）

- 复核对象：main 最新提交 `4b9bfc1`（`docs(evidence): 补正 harden-slave-read-failover 变异击红与回绿真实证据`）
- 复核角色：复核子 Agent（终核，独立核验，不采信执行侧回报）
- 复核日期：2026-10-17
- 判据出处：`spec/README.md`「验收记录三要素」；`docs/主Agent执行指南.md`「审阅与升级边界」；本卡派工三条

## 0 终局裁决

| 项 | 结论 |
|---|---|
| 案 4 `harden-slave-read-failover` 证据链 P1 瑕疵（03 编译红冒充行为红 / 04 零用例假绿） | **已闭环** |
| 六案证据链（红-绿真实性） | **全部 PASS** |
| 批次终局 | **放行（SIGN-OFF）** |

## 1 范围核对（零源码 diff）

```
git show --stat 4b9bfc1
git show --name-only --format="" 4b9bfc1
git show --numstat --format="" 4b9bfc1
git diff --stat be715cc 4b9bfc1 -- src pom.xml src/main/resources/schema.sql openapi.yaml frontend docker
```

- 恰 4 文件、243+/31−，全部位于 `spec/changes/archive/harden-slave-read-failover/`：
  `evidence/03-red-mutation-no-failover.log`（+137/−11）、`evidence/04-green-restored.log`（+92/−8）、
  `evidence/README.md`（+11/−9）、`tasks.json`（+3/−3）。
- `src/`（main + test）、`pom.xml`、`schema.sql`、`openapi.yaml`、`frontend/`、`docker/` 相对 `be715cc` 的
  diff 行数为 **0**（上列命令输出为空）。
- 仓库态：`git rev-parse --abbrev-ref HEAD` = `main`，`git rev-parse --short HEAD` = `4b9bfc1`，
  `git status --porcelain` **空**（工作树无残留，03/04 即提交内容）。

## 2 证据断言核实（案 4）

### 2.1 03 = 断言红（非编译红）

`evidence/03-red-mutation-no-failover.log`：

- L137 `[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 4.032 s <<< FAILURE! -- in com.exam.config.SlaveReadFailoverTest`
- L139 `org.opentest4j.AssertionFailedError: 重试必须压栈 master（与 ReadYourWriteRouter 同机制） ==> expected: <master> but was: <null>`
- L145 栈顶归因 `at com.exam.config.SlaveReadFailoverTest.failoverPushesMasterAndReturnsRetryValue(SlaveReadFailoverTest.java:108)`
- L154 surefire 失败行同文案同 `:108`；L159 `[INFO] BUILD FAILURE`。

交叉核验（源码直读）：

- `src/test/java/com/exam/config/SlaveReadFailoverTest.java:108` 即该断言，消息逐字一致；
  测试 L97–108 的读供应商在每次调用时记录 `peek()`，首次 null、重试期必须 `master`。
- 变异靶点 `src/main/java/com/exam/config/SlaveReadFallbackExecutor.java:62`
  `DynamicDataSourceContextHolder.push(ReadYourWriteRouter.MASTER);` 当前工作树已复原（未被注释）。
- 03 全日志 `COMPILATION ERROR` / `找不到符号` / `无法访问` 命中数 **0**（与 README §03「注释单行、代码仍可编译」一致）。

结论：红来自断言（重试期路由栈顶 `null`），**不是**编译红。

### 2.2 04 = 真实回绿（非 0 用例空转）

`evidence/04-green-restored.log`：

- L52 `[INFO] Running com.exam.config.SlaveReadFailoverTest`（surefire 真实进入该测试类）
- L58 `18:57:26.108 [main] ERROR com.exam.config.SlaveReadFallbackExecutor -- 从库读降级：数据访问异常，降级回主库重试一次, endpoint=exam_snapshot, 异常=slave down, requestId=null`
  —— 即执行器在 03 同一链路上被触发的**预期降级 ERROR**（带 `slave down` 模拟异常栈，L59–61）
- L137 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.207 s`；L141 结果行同一计数；L148 `BUILD SUCCESS`。
- 与 03 是两次独立运行（失败/通过、4.032s/4.207s、不同日志体），非同一份复制。

结论：`Tests run: 1` + 0 失败 + BUILD SUCCESS，**不是** `Tests run: 0` 空转。

### 2.3 补正前状态（证明瑕疵真实存在，现已覆盖）

对 `be715cc`（本笔父提交）取同一路径旧文件：

| 文件 | 旧内容实测 | 定性 |
|---|---|---|
| 03（旧） | `[ERROR] COMPILATION ERROR`，无任何 `Tests run` | 编译红，行为红未被证明（P1 所指） |
| 04（旧） | `[INFO] Tests run: 0, Failures: 0` + `BUILD SUCCESS` | 零用例假绿（`-Dtest=...#failoverPushesMasterAndReturns` 漏 `RetryValue`） |

新 03/04 已整份覆盖为上述真实运行；`README.md` §03/§04/「复核 P1 更正登记」与 `tasks.json` 任务 3 证据字段的描述
与两份日志逐项对得上（命令、断言文案、行号、`Tests run` 计数、BUILD 结论）。

### 2.4 时序自洽

文件 mtime：03 = 18:56:37 → 04 = 18:57:29 → README = 18:57:48 → 提交 = 18:58:02；
04 日志内时间戳 18:57:26 落在 04 文件 mtime 前数秒。与「先跑 03 击红 → `git checkout --` 复原 → 同命令回绿 → 写 README → 提交」一致。

## 3 六案证据链机械核验（同一判据扫全批）

判据：03 = 至少 1 个真实用例执行且 `Failures>0` 或 `Errors>0` + BUILD FAILURE + 无 `COMPILATION ERROR`；
04 = `Tests run≥1` + `Failures: 0, Errors: 0` + BUILD SUCCESS + 无 `Tests run: 0`。

| # | 案 | 03 红（surefire 计数） | 04 绿（surefire 计数） | 0 用例 / 编译红 |
|---|---|---|---|---|
| 1 | `harden-token-store-redis-degradation` | run 1, F0, E1, BUILD FAILURE | run 1, F0, E0, BUILD SUCCESS | 无 |
| 2 | `harden-spring-cache-resilience` | run 1, F0, E1, BUILD FAILURE | run 1, F0, E0, BUILD SUCCESS | 无 |
| 3 | `harden-scheduler-pool-config` | run 2, F1, E0, BUILD FAILURE | run 2, F0, E0, BUILD SUCCESS | 无 |
| 4 | `harden-slave-read-failover`（本卡） | run 1, F1, E0, BUILD FAILURE | run 1, F0, E0, BUILD SUCCESS | 无（见 §2） |
| 5 | `harden-actuator-exposure` | run 2, F1, E0, BUILD FAILURE | run 2, F0, E0, BUILD SUCCESS | 无 |
| 6 | `add-prod-profile-enforcements` | run 1, F1, E0, BUILD FAILURE | run 1, F0, E0, BUILD SUCCESS | 无 |

名录判据（写明以免口径歧义）：本批六案 = 2026-10-17 六笔立项笔，按立案/合并先后：
①`d0ba916` token-store ②`17ddaff` spring-cache ③`14a7817` scheduler ④`2d1dc12` slave ⑤`25ecda8` actuator ⑥`1855768` prod-profile。
「最近六笔 merge」按时间升序亦为同一名录（14:00 → 14:30 → 14:48 → 15:12 → 15:35 → 18:18）。

加验（同法，非本批名录）：`harden-login-guard-redis-degradation`（16/6F→16/0）、
`harden-submit-lock-redis-degradation`（9/1E→9/0）、`add-redis-dependency-runbook`（7/1F→7/0）
三案 03/04 亦为真实红-绿；名录口径若发生替换，PASS 结论不因此改变。

## 4 声明与局限（如实）

- 本轮**未复跑全量门禁**：本笔为纯 archive 文本改动（§1 源码零 diff），复跑不改变本签字结论；
  六案各自全量门禁数字以其 `evidence/06-*` 与 `tasks.json` 为准，本签字不重述数值。
- 日志真伪判定为**结构一致性核验**（Maven/surefire 输出结构、源码行号与断言文案逐字对照、执行器预期降级日志与时间戳、两次运行时长差异），
  **不是**位级重放证明；如需更强证据，须在干净工作树重做一次变异五步并留存。
- 等价变异登记（重试次数 1→2、tag 常量改名）本轮未复核，按 README「不强杀」口径维持；不构成放行阻碍。
- 本文件为本轮复核新增，**尚未提交**；是否入库及是否并入 `spec/README.md` 归档行复核口径，由主 Agent 决定（本文件不写该共享文件，避免并发写冲突）。

签字：复核子 Agent（终核）　日期：2026-10-17
