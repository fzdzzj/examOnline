# 提案：倒计时 Hook 监听依赖窄化并移除深层遍历（前端候选 6）

> 状态：实施中；代码与规范静态核对基于 `015397d`（2026-10-02）。`docs/frontend-optimization-candidates.md` 是未跟踪的候选线索台账，只作线索，不作为证据；执行时已现场重新核对 HEAD、工作树与门禁基线。

## Why

`frontend/src/hooks/useServerCountdown.ts` 的组件级 watch 以 `() => toValue(source)` 整卷快照为源并开启深层遍历。`source` 的实参是 `EnterExamResponse`（或其时间子集），除 `serverTime` / `deadlineTime` / `remainingSeconds` 三个时间字段外还挂着题目列表等大体积字段：

1. 深层遍历意味着每次依赖收集都要把整卷快照逐字段走一遍——响应式追踪开销随整卷体积而非时间字段体积增长；
2. 题目列表等非时间字段的任何原地更新都会触发一次无谓的引擎重锚定（语义上虽无害，但属纯浪费）。

重新锚定的真实语义边界只有一条：**时间三字段任一变化（换新引用或原地更新）时才需要以最新服务端值为锚**。

**已验证**：现有 17 个倒计时用例（剩余时长来源、篡改本地时钟不变、归零锁定、重锚定、作用域停表）与硬约定 2 的既有断言；watch 行为在 `useServerCountdown.spec.ts` 有直接覆盖。**未知**：真实整卷响应的字节占比与该深层遍历在端到端耗时中的份额——本提案不声称可测得该份额，只主张「响应边界应收窄到真实语义」，并以确定性词法/行为护栏验收，不做性能数字结论。

## What Changes

1. 把 watch 源从整卷快照改为时间三字段投影 `[s?.remainingSeconds, s?.deadlineTime, s?.serverTime]`，移除深层遍历选项；回调内仍以 `toValue(source)` 全量快照重建引擎（引擎口径不变）。
2. 不变式 100% 不动：单调秒表 `monotonicNow()` 测时长、服务端时间权威、本地不推算、`Date.now()` 绝不介入；hook 签名与外部调用方式向后兼容（时间子集与整卷响应都能传）。
3. 测试防线（先红后绿）：
   - 行为：非时间字段原地更新不重锚定（剩余秒数按秒表平滑递减、不跳跃不重置）；时间字段原地更新即时重锚定。
   - 词法：静态断言源码不含深层遍历标记（`deep` + `true` 组合）、含时间三字段投影。
4. 不改后端、不改 schema、不改 API 契约、不改其他前端文件。

## Impact

- **规范**：`spec/specs/frontend/spec.md` 的「倒计时以服务端时间为准」Requirement 下追加 Scenario「倒计时响应窄化不深层监听快照」；既有四个场景原文不动。
- **代码**：仅 `frontend/src/hooks/useServerCountdown.ts` 与 `frontend/src/hooks/__tests__/useServerCountdown.spec.ts`。
- **用户/API**：无任何可见变化；倒计时展示、归零锁定、超时口径全部照旧。
- **数据与部署**：无。

## 验收与停止条件

- 词法与行为护栏在旧实现下先红（红灯原因必须是「深层遍历仍存在」与「非时间字段更新触发重锚定」，不得是桩或环境失败），实施后转绿。
- 前端三门禁（`lint:check`、`type-check:check`、`test`）全绿，用例数只增不减（379 → 381+）；后端唯一门禁 `mvnw.cmd clean test` 保持 350 / 0 / 0 / 1 且 BUILD SUCCESS。
- 若实施中发现窄化投影无法覆盖既有重锚定语义（换新引用不触发等），停止实施并回退，不为变绿放宽断言。
