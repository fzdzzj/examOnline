# fix-subjective-grading-panel-flaky

## 一、背景与问题（B.2 欠账登记，2026-10-16 立案）

`frontend/src/components/postexam/__tests__/SubjectiveGradingPanel.spec.ts:96` 用例
「Enter 提交复用 createGradingFlow」在全量 vitest 并行负载下 Test timed out in 5000ms
（add-pre-exam-device-readiness-check 复核时基线复现同失败 + 孤立运行 7/7 通过，归因
「全量并行负载既有 flaky」成立）。同批 paperBlueprintDraw / questionImportExport 曾
超时后自行转绿——同形态观察名单，不冒领修复，台账登记。

超时用例形态（亲验）：直接 `await vm.onScoreEnter(ROWS[0])` 并断言 mock 调用——挂起点
在组件提交流内部异步链（并行负载下 5s 窗口内不 resolve），等待条件未外化。

## 二、方案（测试治理，不改产品行为）

主方向（按优先级，执行侧阶段 2 深挖后定案，可组合）：
1. **等待条件外化**：用例不依赖 onScoreEnter 内部 await 链返回，改确定性等待
   （flushPromises / waitFor 断言 mock 调用）——消除负载敏感挂起
2. **异步链归因**：读组件 onScoreEnter 实现，定位真实定时器/动画/message 异步等待在
   测试中未 mock 的点，测试侧补 mock（fakeTimers / 组件桩）而非改组件
3. **最后手段**：仅该用例 testTimeout 上调（须论证默认 5s 对该用例的正当等待链
   不构成「掩盖真实性能回归」；全局 timeout 禁调）

红线：**组件源码零改动**（若深挖确证组件侧真实缺陷——如未清理定时器——则停下回报，
白名单扩组件文件须回写本提案留痕，另案处理）；其余 postexam 测试用例零改动。

## 三、白名单

- `frontend/src/components/postexam/__tests__/SubjectiveGradingPanel.spec.ts`
- 禁触：组件源码 SubjectiveGradingPanel.vue、其余测试文件、后端全部、vitest 全局配置
  （vitest.config 不动——治理点在用例，不在全局超时）

## 四、验收要点

- 全量 vitest 连跑 N≥3 次（并行负载同 CI 形态）零该用例超时；孤立运行保持 7/7
- frontendTests 908 只增不减（用例数不变则计数不变；治理不增删用例）
- 先红后绿适配变异验证纪律：去治理点（还原 await 形态）→ 全量并行下复现超时概率形态
  留证；等价情形（无法稳定复现红）如实登记不强杀
