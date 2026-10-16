# 变更提案：polish-student-taking-and-review-markers

## 为什么（Why）

学生作答侧两处高频体验缺口，均属成熟在线考试产品的标配防漏答机制：

1. **疑难标记（Mark for Review）缺失**：
   - 学生考试中遇到拿不准的题，目前没有任何机制记录「这题我要回头看」——只能靠草稿纸或硬记；
   - 答题导航格（`QuestionNavPanel.vue`）仅有已答/未答/当前三态（`NavItemState`），无法区分「已答但拿不准」与「已答且确定」；
   - **既有基建已预留**：后端草稿保存契约 `SaveDraftRequest` 已含 `marked` 字段（`useAutoSaveDraft.flush()` 当前恒传 `[]`，[useAutoSaveDraft.ts L132]），本地 `DraftRecord` 持久化链路（`persistLocal`/`storage.save`）亦在案——疑难标记的端到端通道早已铺好，只差前端交互接入。

2. **交卷防漏答缺快捷定位**：
   - 交卷确认弹窗（`SubmitExamPanel.vue`）在 `unansweredCount > 0` 时已提示「还有 N 道题未作答」，但只有「确认交卷/再检查」两按钮；
   - 学生想补答必须自己关弹窗、逐格扫导航格找未答题——大题量考试下漏答风险高，定位成本高。

本案在**严格零 DDL、零后端改动、零契约变更**前提下（`marked` 字段已存在于草稿契约），纯前端补齐两处学生侧安全防呆。

---

## 改什么（What）

### 1. 答题卡疑难标记（Mark for Review 🚩）
- **落点**：`frontend/src/components/student/TakingBoard.vue`、`QuestionNavPanel.vue`、`frontend/src/utils/studentTaking.ts`、`frontend/src/hooks/useAutoSaveDraft.ts`、`draftMerge`（恢复链路）；
- **标记交互**：作答区提供「标记此题 🚩」切换按钮（当前题已标记则呈「取消标记」态）；标记是**独立于答案的状态**——标记/取消不触发草稿 dirty 的作答语义变化之外不干扰答案提交；
- **导航格渲染**：`NavItemState` 扩展 `marked` 字段（`navStatesOf` 纯函数加参），导航格右上角呈 🚩 角标并以橙色样式区分（与已答/未答色系叠加不冲突）；「已答未标记 / 已答已标记 / 未答已标记」三态均可视区分；
- **持久化（双层）**：
  - **本地**：`DraftRecord` 扩展 `marked`（题目标识集合），`persistLocal` 随 answers 一并写入——断线重进/刷新恢复标记不丢；
  - **上送**：`flush()` 的 `saveDraft` payload 将恒 `[]` 替换为真实标记集合（契约字段既有，零契约变更）；**边界**：后端对 `marked` 的存储语义（原样存储回放 or 忽略）由实施侧以集成用例核实——若后端拒绝非空 marked 则回退为仅本地持久化并在 evidence 如实注记；
  - **恢复**：草稿恢复链路（`draftMerge` 语义）同步恢复标记集。
- **提交语义**：标记**不进入**交卷请求（answers 语义不变），标记与否不影响判分——仅是学生侧导航辅助。

### 2. 交卷一键跳转首道未答题
- **落点**：`frontend/src/components/student/SubmitExamPanel.vue` + 作答页接线；
- **交互**：确认弹窗在 `unansweredCount > 0` 时于「确认交卷」旁提供「跳转首道未答题」按钮；点击后关闭弹窗并 `goTo(首道未答 index)`——首道未答以既有 `navStatesOf` 数组的第一个 `answered === false` 项为准（复用同一份状态源，不另算一套口径）；
- **既有语义不变**：确认交卷的 `useSubmitExam` 防重/loading/失败保留答案语义零改动；`unansweredCount === 0` 时不出现跳转按钮。

---

## 影响面（Impact）
- **数据库与后端**：**严格零 DDL**（schema.sql 与 docker/mysql/migrations 零 diff）、零后端改动（src/main、src/test、pom.xml 零 diff）；
- **契约与 SDK**：零变更（`SaveDraftRequest.marked` 字段既有；openapi paths 不变，SDK 不再生成）；
- **门禁基线**：仅前端 vitest 用例数增长（收口笔上调 frontendTests）；
- **既有不变式保全**：`isAnswered` 与后端「未作答」口径对齐不漂移；草稿版本冲突（多端）时 marked 与 answers 同策略不静默覆盖；倒计时/交卷结果如实呈现不变；
- **分级**：M 级（触及草稿持久化链与交卷确认链两处安全相关路径），双岗隔离。

---

## 时间线与任务
- **阶段 0 & 1**：立项三件套编制与在途总账登记；
- **阶段 2**：实施两特性（标记交互/导航格三态/双层持久化/恢复链路、交卷跳转）、前端单测先红后绿与变异击红留证；
- **阶段 3**：全量双端门禁实测、gate-baseline 上调（仅 frontendTests）、规范合入与收口归档、`--no-ff` 合并入 main。
