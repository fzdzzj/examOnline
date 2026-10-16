# fix-subjective-grading-panel-flaky —— 阶段 2 治理记录

基线分支：feature/fix-subjective-grading-panel-flaky@b7bf816
事实源：spec/changes/fix-subjective-grading-panel-flaky/（proposal.md 白名单与方案、tasks.json 任务 2）
组件：frontend/src/components/postexam/SubjectiveGradingPanel.vue（零改动）
测试：frontend/src/components/postexam/__tests__/SubjectiveGradingPanel.spec.ts（唯一改动，5+/3-）

## 一、首任务归因

读取 onScoreEnter 实现与 useGradingFlow/flow.submit 链：
- onScoreEnter → flow.submit(createGradingFlow) → unwrap（mock）→ saveSubjectiveScore（mock）→ message.success（不被 await）。
- 该链不含真实定时器/动画/未 mock 的服务端异步等待；全部是 mock 微任务。

真正的测试形态缺陷：用例以 `await vm.onScoreEnter(...)` 等待**内部整条链**（含链尾 Vue 重渲染）在 5s 墙钟窗口内完成。antd Table mountPanel 渲染本身是秒级同步开销，全量 `vitest run`（CI 形态并行负载）下墙钟膨胀会挤爆 5s 窗口。

孤立运行实测（原始形态）：L96 该用例自身 1875ms，距 5s 余量仅 ~3.1s——并行放大即触发超时的机制成立。

## 二、治理（等待条件外化，方案的①）

仅改写用例内部，组件源码零改动：
- 不再 `await onScoreEnter(...)`，改为 `void vm.onScoreEnter(...)`（eslint 无 no-void/floating-promises 限制，合规）。
- 断言外化到同步段即已发生的**外部可观测点** saveSubjectiveScore 调用，用 `vi.waitFor`（vitest 4.0.15 内置，仓库有先例）兜底确定性等待，替代内部链完成墙钟。
- 断言内容不变（submissionId/expectedVersion/score 等既有期望），不删减用例、不增删用例。

孤立运行治理版：L96 墙钟 1875ms → 1083ms（切掉 mock 调用后的重渲尾巴），未掩盖任何真实性能回归——每个批改仍真实调用并断言 saveSubjectiveScore 一次。

## 三、验证留证（均落 evidence/）

| 证据文件 | 形态 | 结果 |
|---|---|---|
| baseline-isolated-original.log | 原始形态孤立 7/7 | 通过 |
| treated-isolated.log | 治理版孤立 7/7 | 通过 |
| treated-full-round1.log | 全量并行（CI 形态） | 119 文件 / 908 通过 |
| treated-full-round2.log | 全量并行 | 908 通过 |
| treated-full-round3.log | 全量并行 | 908 通过 |
| mutation-original-full-attempt1.log | 还原原始 await 形态全量 | 908 通过 |
| mutation-original-full-attempt2.log | 还原原始 await 形态全量 | 908 通过 |
| mutation-original-full-attempt3.log | 还原原始 await 形态全量 | 908 通过 |

全量 ≥3 轮（治理版 3 轮 + 收尾复跑）零该用例超时，frontendTests=908 不增不减（无新增/删除用例）。
还原变异复现红：3 轮原始形态全绿，超时无法按需稳定复现；按提案"equivalence 情形如实登记不强杀"处理，
机制证据以孤立运行时序差（1875ms→1083ms）为准，未强杀。

## 四、收尾

- restore 治理版与 treated-spec-backup.ts 字节一致（git diff --no-index → lastexitcode=0）。
- `pnpm run type-check:check` exit 0；`pnpm run lint:check` exit 0；目标文件 eslint 零问题。
- 白名单外零 diff（工作树仅 SubjectiveGradingPanel.spec.ts 一个文件变更）。
- 分支提交后停待复核。