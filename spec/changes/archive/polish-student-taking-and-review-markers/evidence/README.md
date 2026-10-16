# 阶段 2 单测先红后绿与变异击红留证

本目录记录了 Polish-Student-Taking-And-Review-Markers 阶段 2 的测试证据：
单测先红后绿的执行过程、三项变异（m1/m2/m3）击红留证、以及后端 `marked` 语义核实的结论。

执行人：coding-agent（DSH harness）
分支：`feature/polish-student-taking-and-review-markers`
执行范围：frontend/src/**（受控白名单，未触碰 src/main、src/test、pom.xml、schema.sql、openapi.yaml、docker/）

---

## 一、单测先红后绿

### 红（先写测试后跑，确认新用例确实探测到未实现的行为）

- 新增 `studentTaking.spec.ts`：`navStatesOf` 加入 marked 参数、`toggleMarked` 纯函数（6 例）。
- 新增 `draftMerge.spec.ts`：marked 并入保守合并、resolveSeed 带出 marked、serverDraftOf 窄化 marked（6 例）。
- 新增 `useAutoSaveDraft.spec.ts`：flush 上送真实 marked、notifyMarked 本地持久化、仅标记也触发保存（3 例）。
- 新增 `SubmitExamPanel.spec.ts`：未答题>0 呈现跳转按钮、=0 不出现、点击关闭弹窗并回抛 jump-to-unanswered、不触发 submit（4 例）。
- 新增 `TakingBoard.spec.ts`：marked 传入导航态、标记按钮「标记此题/取消标记」与回抛 toggle-mark（4 例）。
- 新增 `QuestionNavPanel.spec.ts`：🚩 角标 + 橙色 nav-marked class + 图例「已标记」（3 例）。
- 新增 `studentTakingJump.spec.ts`：整页接线——点跳转落在 navStatesOf 第一个未答项（1 例）。

首次运行结果（红）：
- `studentTaking.spec.ts` / `draftMerge.spec.ts` / `useAutoSaveDraft.spec.ts`：`Tests 15 failed | 47 passed`（红，全部为新用例）。
- `SubmitExamPanel / TakingBoard / QuestionNavPanel.spec.ts`：`Tests 9 failed | 12 passed`（红）。其中 SubmitExamPanel 首版因未处理 antd Modal teleport 到 body 导致断言方式错误，已按仓库既有 Modal 用例口径改用 `document.body` 断言，语义判据不变。

### 绿（实现后跑全量）

- 全量 `pnpm vitest run`：`Test Files 116 passed (116)；Tests 874 passed (874)`。
- 三项门禁中前端两项先验：`lint:check` 通过、`type-check:check` 通过（详见 evidence/ 与收口阶段 3 门禁记录）。

---

## 二、变异击红留证（m1 / m2 / m3）

以下每一项均执行：应用变异 → 跑相关用例 → 确认变红 → 撤销变异 → 确认变绿。

### m1：标记状态丢失 / 不持久化

- 变异点：`useAutoSaveDraft.ts` `persistLocal()` 中删除 `marked: currentMarked()`（标记不再随草稿本地写入）。
- 表征期望击红用例：`useAutoSaveDraft.spec.ts > 标记切换（notifyMarked）后本地持久化写入 marked`。
- 结果（红）：
  ```
  AssertionError: expected undefined to deeply equal [ 7 ]
  - Expected: [ 7 ]
  + Received: undefined
  ❯ useAutoSaveDraft.spec.ts:316  expect(lastLocal.marked).toEqual([7])
  ```
- 撤销变异后该用例复绿。结论：标记不随草稿本地持久化会被本批次用例击中。

### m2：跳转口径另算 / 跳错题

- 变异点：`[id].page.vue` `onJumpToUnanswered()` 把「第一个未答项」改为「最后一个未答项」（`.at(-1)`）。
- 表征期望击红用例：`studentTakingJump.spec.ts > 点「跳转首道未答题」落在 navStatesOf 的第一个未答项`。
- 结果（红）：
  ```
  AssertionError: expected '第三题未答' to contain '第一题未答'
  - Expected: "第一题未答"
  + Received: "第三题未答"
  ```
- 撤销变异后复绿。结论：跳转口径若另算（不取 navStatesOf 第一个未答项）会被击中——印证「复用 navStatesOf 首个未答项、禁另算口径」的判据。

### m3：标记混入交卷 / 破坏确认语义

- 变异点：`SubmitExamPanel.vue` `onJumpToUnanswered()` 中误触发 `emit('submit')`（破坏「跳转不代表交卷」语义）。
- 表征期望击红用例：`SubmitExamPanel.spec.ts > 跳转按钮不触发确认交卷`。
- 结果（红）：
  ```
  AssertionError: expected [ [] ] to be undefined
  - Expected: undefined
  + Received: [ [], ]
  ❯ SubmitExamPanel.spec.ts:79  expect(wrapper.emitted('submit')).toBeUndefined()
  ```
- 撤销变异后复绿。结论：标记/跳转与交卷确认语义必须隔离，混入交卷路径会被击中。

补充说明：m3 变体「标记混入交卷 answers」在后端/前端契约层面本就零风险——`useSubmitExam` 交卷 body 只含 `{ answers, submitType }`，未引入 marked 字段（零改动不动该 hook）。上述 m3 改为破坏「确认语义」同属 proposal 红线「useSubmitExam 防重/失败保留答案语义零改动」的守卫面，故采用该变体击红留证。

---

## 三、后端 non-empty `marked` 语义核实（决定是否回退仅本地）

受控白名单限定 frontend/src/**，禁止触碰 src/test，因此「集成用例核实后端非空 marked 语义」改为
**阅读后端权威源码做静态核实**，结论以源码行号为准：

- `src/main/java/com/exam/taking/dto/AutoSaveRequest.java`：`marked` 为 `List<Long>`，
  除 `version` 需 `@NotNull` 外**无任何校验约束**——不存在「非空 marked 被拒绝」的入口。
- `src/main/java/com/exam/taking/service/ExamDraftService.save()`（L97-100）：
  `ArrayNode marked = root.putArray("marked"); request.getMarked().forEach(marked::add);`
  ——**原样存储**前端送来的任意 marked 列表（含非空），无拒绝/丢弃分支。
- `ExamDraftService.parseDraft()`（L153-157）读取 `marked` 数组；`EnterExamResponse.marked`（dto L49）
  由 `ExamTakingService.saveDraft`（L374 `draft == null ? List.of() : draft.marked()`）下发回前端——
  **标记在进入考试时能随服务端草稿回放**（刷新/断线重进恢复的种子来源）。

**结论：后端接受且回放非空 marked，端到端通道完整，无需回退仅本地持久化。**
前端 `useAutoSaveDraft.flush()` 上送真实标记集合即走既有契约字段 `marked`，零契约变更；
恢复链路以 `navStatesOf`/`resolveSeed`/`serverDraftOf` 一并取回后端与本地两源 marked。

---

## 四、关键红线核对

- **标记独立于 answers**：`useSubmitExam` 交卷 body 仅 `{ answers, submitType }`（零改动）；
  标记集合走 `useAutoSaveDraft.markedSource` 与 `DraftRecord.marked` 独立轨道，未进入交卷请求。
- **标记随 DraftRecord 本地持久化 + flush 上送真实 marked**：见 m1 击红与本文件第三节后端核实。
- **跳转复用 navStatesOf 首个未答项**：见 m2 击红。
- **isAnswered 与后端口径零漂移**：`studentTaking.isAnswered` 未改动（判断题 T/F、非空判定保持）。
- **useSubmitExam 防重/失败保留答案语义零改动**：`useSubmitExam.ts` 未改动（m3 击红的守卫面确认）。