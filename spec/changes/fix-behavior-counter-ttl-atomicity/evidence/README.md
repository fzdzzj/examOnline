# fix-behavior-counter-ttl-atomicity · 阶段 2 证据

基线 commit：`8a8110f`（feature/fix-behavior-counter-ttl-atomicity 立项笔）
事实源：`spec/changes/fix-behavior-counter-ttl-atomicity/`（proposal.md + tasks.json 任务 2）

## 一、单测先红后绿（BehaviorCounterServiceTest，4 用例）

- `red-two-step-baseline.txt`：对**两步旧实现**运行 → `Tests run: 4, Failures: 1`，
  唯一失败 `atomicSingleScriptCallNoTwoStepGap`：`verify(opsForValue(), never())` 命中旧实现
  调用了 `opsForValue()`（INCR）+ 独立 `expire`。先红成立。
- `green-lua-atomic.txt`：Lua 单脚本原子化改造后 → `Tests run: 4, Failures: 0`。后绿成立。

四用例语义：
1. 首增置 TTL（真 Lua，连测试 Redis db 15）——首次返回 1 且键已带 TTL。
2. 续增不重置 TTL——手动缩 TTL 到 30s 后续增仍在秒级（≤5 分钟），不弹回满额 12h。
3. 异常降级保留——`redis.execute` 抛异常时按首次事件返回 1、不抛出。
4. 崩溃间隙红线——mock 锁「单次 execute + 零独立 opsForValue/expire + 脚本文本含 INCR/EXPIRE/c==1 守卫」。

## 二、变异击红（退回两步 INCR/EXPIRE 形态）

- `red-mutation-two-step.txt`：increment 临时改回「INCR + 判 count==1 再 EXPIRE」→
  `atomicSingleScriptCallNoTwoStepGap:136` 击红（`NeverWantedButInvoked: opsForValue()；invoked`）。
- 复原（恢复 Lua execute 单脚本）→ 增量复跑 BUILD SUCCESS、F=0。变异击红→还原回绿成立。

## 三、全量门禁 mvnw.cmd clean test（仓库根、带 clean、禁 -o）

- `full-gate-fixed.log`：`Tests run: 630, Failures: 0, Errors: 0, Skipped: 1` → **BUILD SUCCESS**。
  - Skipped: 1 为既有 `OpenApiContractTest` 基线跳过（`Skipped: 1`）与本变更无关。
  - backendTests 626 → 630（新增 BehaviorCounterServiceTest 4 用例；SwitchScreenEventCollectorTest 保持 6 用例
    未增减，仅把既有降级用例内部桩从两步 shape 改为 Lua execute shape）。**gate-baseline.json 上调归阶段 3 收口笔。**

## 四、白名单 / 语义红线

- 变更文件全部在 `src/main/java/com/exam/anticheat/**`、`src/test/java/com/exam/anticheat/**` 内；
  auth 包仅只读参照（LoginGuardService.incrementWithTtl），**零触碰**；schema/migrations/openapi/pom/frontend 零 diff。
- 语义零变化逐字保留：KEY_PREFIX=`exam:behavior:count:`、key 维度 examId:studentId:eventType、
  TTL 配置 `@Value("${exam.anticheat.counter-ttl-hours:12}")`、返回口径 null→1L、异常降级 log.error+返回 1 不抛出。
- Lua 脚本常量本地复制于 anticheat 包（行为与先例语义一致），不引用 auth 包内部常量。