# 提案：前端装饰性断言普查与修复（测试资产质量 C2）

> 状态：已立项待执行；阶段 0 查证基于封板基线 `ef14867`（2026-10-10）。全仓 `.exists()` 128 处 / 31 文件摸底已完成：绝大多数为合法直断（`expect(...exists()).toBe(...)`），守卫形态与双通形态各坐实 1 处（见 Why）。数值为该 revision 现场实测，执行时以开工核对为准。

## Why

离线重入案（`add-offline-exam-reentry`，2026-10-10）首轮复核抓实「变异 B 不击红」——selector 失配 + `exists()` 守卫形成装饰性断言（selector 失配时断言被静默跳过，测试恒绿）；该案修复笔 `4944851` 修了同文件倒计时用例的 selector 失配，却漏了交卷用例——**同文件同型事故当场重演**。本提案把该纪律从 memory 教训升格为四件套：全仓普查 → 修复坐实缺陷 → 词法护栏 → spec 基线落位。C1（ScoreService 拆分）的验收依赖可信测试资产，本案先行清障。

阶段 0 坐实的两处：

1. **活缺陷**（`frontend/src/pages/(dashboard)/student/exams/__tests__/offlineExamReentry.spec.ts` 用例⑦，L244-263）：`[data-test="manual-submit-button"]` 全仓仅测试自引用——生产代码不存在该属性（grep 坐实）；`if (submitBtn.exists())` 守卫使「交卷成功清理快照」这一交付断言**从未执行、恒绿**。真实交卷链路在 `SubmitExamPanel`：`[data-test="submit-button"]`（点击开确认 Modal）→ Modal `[data-test="submit-confirm"]`（ok 按钮文本「确认交卷」）→ `emit('submit')` → 页面 `onManualSubmit`；`submitEnabled` 在用例⑦场景（在线、快照有值、未封闭、未提交）必为真，面板必然渲染。
2. **弱化**（`frontend/src/components/exam/__tests__/ExamMonitorPanel.spec.ts` L101）：`exists() || html().includes()` 双通——`.abnormal-row` class 绑定（被测行为）失效时，字符串包含兜底仍真，断言照绿。

**已验证**：两处缺陷的形态、行号、全仓引用计数；真实交卷交互链（`SubmitExamPanel.vue` L26-52）；`submitEnabled` 渲染条件（`[id].page.vue` L480-482）。**未知**：85 文件全量普查是否还有抽查未覆盖的形态变体——故阶段 2 全量普查本身是交付物，不是走场。

## What Changes

1. **全量普查**：对 frontend 全部测试文件逐文件按六判据扫描——① `if (...exists())` 守卫包断言；② `exists() ||` 双通；③ `?.` 可选链吞断言；④ try-catch 吞断言；⑤ 动态集合 forEach / for-of 空集吞断言；⑥ `if (length)` 守卫。普查矩阵（文件 × 判据 + 命中行号 + 形态摘录）入 tasks.json evidence。预期仅坐实上述 2 处；新发现超出预期时逐处登记并上报裁决，不得静默扩改。
2. **修复坐实缺陷**：每处附三步证据链——先证装饰性（原样跑绿 + 引用计数自证）→ 修复为直接断言 → 变异可红（临时破坏被测行为演示击红后复原，先例 `fix-frontend-exam-page-error-states` 的变异校验）。用例⑦按真实交互链重写（见 Why）；ExamMonitorPanel L101 直断 `expect(wrapper.find('.abnormal-row').exists()).toBe(true)`。
3. **词法护栏**：新增护栏 spec（`readFileSync` + 源码扫描断言，先例 `questionBatchDelete.spec.ts` 词法护栏段）：断言 frontend 测试源码不含守卫与双通两禁形态；护栏文件自身从扫描集排除或以拆分构造避免模式自命中。动态集合循环写纪律文字、不做词法护栏（常量字面量驱动的循环是合法形态，防误伤）。护栏有效性以变异注入演示（注入守卫形态 → 击红 → 撤销 → 转绿）。
4. **spec 基线**：`spec/specs/frontend/spec.md` ADDED「测试断言有效性纪律」Requirement（五 Scenario：守卫禁用 / 双通禁用 / 词法护栏 / 修复先证装饰性 / 合法直断不受影响）。

## Impact

- **规范**：frontend 能力域 ADDED 1 个 Requirement（5 Scenario）；既有 Requirement 原文零改动。
- **代码**：仅 `frontend/src/**/__tests__/` 下 2 个既有 spec 修复 + 1 个新增护栏 spec；**零生产代码改动**（组件 / page / hooks / api / constants 一律不碰，变异校验的临时注入须全部复原后才准提交）。
- **用户/API**：无任何可见变化。
- **数据与部署**：无。
- **顺带收口**（仅归档动作，并入本案收口笔）：r2 巡查卡尾巴——`spec/changes/survey-frontend-candidates-r2/` 移入 `archive/`（其 evidence 已于 `b424c91` 入库，本次仅目录移动，内容零改动）+ `spec/README.md` 补登记行。

## 验收与停止条件

- 前端三门禁（`npm.cmd --prefix frontend run lint:check / type-check:check / test`）全绿；vitest 只增不减：85 → 86+ 文件、608 → 610+ 例（护栏新增文件与用例；用例⑦修复本身不增减计数、由恒绿转真验，若重写中拆分为多用例计入只增不减）。
- 后端零改动；仓库根 `mvnw.cmd clean test` 持平（398 / 0 / 0 / 1、BUILD SUCCESS）。
- 两处修复的变异校验均可红、护栏变异注入可红（执行侧自报仅供参考，以复核子 agent 独立复现击红为准）。
- 护栏 spec 对修复后的测试源码全绿，且对合法直断形态零误伤。
- **停止条件**：① 若修复用例⑦须改动生产组件才能走通交互链（如 Modal 在测试环境不可达需组件加测试钩子），停止实施并上报裁决——不得为变绿改组件迁就测试；② 若普查新发现超出 2 处且形态复杂（涉及跨文件测试基建或需重构 mock 体系），同样停止上报；③ 任何「删除断言换取变绿」的路径都是违规，红灯只许靠修对断言消掉。
