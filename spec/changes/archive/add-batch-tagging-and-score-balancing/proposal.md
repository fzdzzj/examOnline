# 变更提案：add-batch-tagging-and-score-balancing

## 为什么（Why）

当前教师在题库建设与试卷编排过程中存在两个显著的效率痛点：

1. **题库标签批量维护缺失**：
   - 题库列表目前仅支持单选编辑或批量删除（`onBatchDelete`）；
   - 当教师批量导入大批试题（如 Excel 导入数十道题）或进行学期知识点归类时，若要为这批题目打上特定标签（如“高一期末复习”、“导数综合”），必须逐题点击「编辑」弹层勾选并保存；若要移除某个标签也必须逐题清理，操作繁琐低效。

2. **试卷分值配平耗时且缺乏保和算法**：
   - 试卷总分与各题分值之和存在强一致性校验（`papers.total_score == sum(paper_questions.score)`）；
   - 从题库组卷或抽题入卷后，题目携带默认分（如 5 分），导致总和经常与申报总分不一致（例如 23 题默认分总计 115 分，试卷申报为 100 分），界面抛出红色警告且无法锁定生成考试快照；
   - 教师目前必须手动在表格每一行点开输入框逐题改分并回车，还需掏出计算器凑整，一旦差 0.5 分便无法锁定，极大增加组卷心理负担。

3. **试卷多题型混杂缺乏一键规整重排**：
   - 多次选题或抽题后，各题型穿插错乱；目前仅有单步「上移/下移」按钮，将一道题移动几十格需要重复点击数十次，缺乏按标准题型（单选→多选→判断→填空→简答）一键排布的能力。

本案在**严格零 DDL** 前提下，构建题库批量标签管理、离散最大余数试卷分值智能配平与标准题序一键规整，彻底解除组卷与分类痛点。

---

## 改什么（What）

### 1. 题库批量标签管理（Batch Tagging & Untagging）
- **落点**：`com.exam.question.controller.QuestionController` 与 `QuestionService`；
- **接口**：`POST /api/questions/batch-tags`；
- **参数**：
  - `questionIds`：目标题目 ID 列表；
  - `tagIds`：目标标签 ID 列表；
  - `action`：`BIND`（追加打标）或 `UNBIND`（解绑剥离）。
- **逻辑**：
  - 严格校验当前教师对题目的所有权（`OwnershipGuard.assertOwner`），越权题目计入 `failed`，ADMIN 自动放行；
  - `BIND` 模式：先查出既有关联，通过内存差集仅插入缺失的 `(questionId, tagId)` 对，天然幂等且保留原题其余标签；
  - `UNBIND` 模式：物理批量删除指定的 `(questionId, tagId)` 关联行；
  - 响应遵循标准批量操作信封，返回 `succeeded` 与 `failed`。
- **前端交互**：在 `questions/index.page.vue` 多选题目后提供「批量标签」按钮，弹出 `BatchTagModal`，支持按分类多选标签并切换「追加/移除」模式。

### 2. 试卷分值智能配平（Paper Score Balancing）
- **落点**：`com.exam.paper.controller.PaperController` 与 `PaperService`；
- **算法核心**：**离散最大余数保和算法**：
  - 以 0.5 分为步长整数化：$T = \text{round}(S_{target} / 0.5)$；
  - 基础步长数 $Q = \lfloor T / N \rfloor$，余数步长数 $R = T \pmod N$；
  - 前 $R$ 题分配分值 $(Q + 1) \times 0.5$，后 $N - R$ 题分配 $Q \times 0.5$；
  - 数学上严格恒等满足 $\sum = S_{target}$，单题下限 $\ge 0.5$ 分；
  - 支持**按题型配比**：部分题型固定单题分值（如单选 2 分、多选 4 分），其余题型一键均分补齐到目标总分。
- **接口**：`POST /api/papers/{id}/questions/scores/rebalance`，事务内批量更新 `paper_questions.score`；严格校验试卷可编辑态（`examPaperLockService.assertPaperEditable`）；
- **前端交互**：在 `papers/[id].page.vue` 题目表格上方增加「⚡ 分值智能配平」按钮，弹出弹窗提供「一键均分」与「按题型配比」双 Tab 实时预览与一键应用。

### 3. 试卷按标准题型一键重排
- 前端在试卷详情页提供「按标准题序重排」按钮，将试卷所含题目按 `单选 (1) → 多选 (2) → 判断 (3) → 填空 (5) → 简答 (4)` 重新排布 ID 数组，复用现有 `PUT /api/papers/{id}/questions/order` 端点一次性完成重排。

---

## 影响面（Impact）
- **数据库**：**严格零 DDL**，`question_tags` 现有表及唯一索引已完全支持，分值与题序更新全部使用现有 `paper_questions` 字段；
- **契约与 SDK**：`openapi.yaml` 新增 2 个端点（paths 95 → 97），前端 SDK 同步生成；
- **安全性与事务**：单题所有权与试卷锁定态严格校验，批量更新置于单事务内。

---

## 时间线与任务
- **阶段 0 & 1**：立项三件套编制与在途总账登记；
- **阶段 2**：实施题库批量打标/解绑端点与前端弹层、试卷配平算法/端点与前端弹层、题序重排交互、契约同步、先红后绿与变异击红留证；
- **阶段 3**：全量门禁实测、基线上调（paths 95→97）、规范合入与收口归档。
